// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
//
// DFS-010 execution variant of flight.rs. The DFS-009 fixture stays immutable.
//! Loopback test embedding with typed parameter binding and lazy result streams.
use std::{collections::HashMap, pin::Pin, result::Result, sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}}};
use datafusion::{arrow::{datatypes::{Schema, SchemaRef, Field}, record_batch::RecordBatch, ipc::writer::IpcWriteOptions},
    common::{ScalarValue, tree_node::{TreeNode, TreeNodeRecursion}}, logical_expr::{Expr, LogicalPlan, Statement as SqlStatement}, prelude::{SessionConfig, SessionContext}};
use arrow_flight::{encode::FlightDataEncoderBuilder, decode::FlightRecordBatchStream, error::FlightError,
    flight_service_server::{FlightService, FlightServiceServer},
    sql::{server::{FlightSqlService, PeekableFlightDataStream}, metadata::SqlInfoDataBuilder, *}, *};
use futures::{Stream, TryStreamExt};
use prost::Message;
use tonic::{Request, Response, Status, Streaming};

fn error(e: impl std::fmt::Display) -> Status { Status::internal(e.to_string()) }
enum ResultData { Query(String, Arc<SessionContext>, LogicalPlan), Batch(String, RecordBatch) }
#[derive(Default)]
struct Fixture {
    next: AtomicU64,
    contexts: Mutex<HashMap<String, Arc<SessionContext>>>,
    plans: Mutex<HashMap<String, (String, LogicalPlan)>>,
    bindings: Mutex<HashMap<String, Vec<ScalarValue>>>,
    results: Mutex<HashMap<String, ResultData>>,
}
impl Fixture {
    fn id(&self) -> String { self.next.fetch_add(1, Ordering::SeqCst).to_string() }
    fn context<T>(&self, req: &Request<T>) -> Result<(String, Arc<SessionContext>), Status> {
        let token = req.metadata().get("authorization").and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer ")).ok_or_else(|| Status::unauthenticated("fixture handshake required"))?;
        let ctx = self.contexts.lock().unwrap().get(token).cloned().ok_or_else(|| Status::unauthenticated("unknown context"))?;
        Ok((token.to_owned(), ctx))
    }
    fn plan(&self, owner: &str, id: &str) -> Result<LogicalPlan, Status> {
        let (plan_owner, plan) = self.plans.lock().unwrap().get(id).cloned().ok_or_else(|| Status::not_found("plan"))?;
        if owner != plan_owner { return Err(Status::permission_denied("foreign plan")); }
        let bindings = self.bindings.lock().unwrap().get(id).cloned();
        match bindings { Some(values) => plan.with_param_values(values).map_err(error), None => Ok(plan) }
    }
    fn result(&self, schema: SchemaRef, result: ResultData) -> Result<Response<FlightInfo>, Status> {
        let id = self.id();
        self.results.lock().unwrap().insert(id.clone(), result);
        let ticket = Ticket { ticket: Fetch { handle: id }.as_any().encode_to_vec().into() };
        Ok(Response::new(FlightInfo::new().try_with_schema(&schema).map_err(error)?
            .with_descriptor(FlightDescriptor::new_cmd(Vec::<u8>::new()))
            .with_endpoint(FlightEndpoint::new().with_ticket(ticket))))
    }
}
#[tonic::async_trait]
impl FlightSqlService for Fixture {
    type FlightService = Fixture;
    async fn do_handshake(&self, _: Request<Streaming<HandshakeRequest>>) -> Result<Response<Pin<Box<dyn Stream<Item=Result<HandshakeResponse, Status>> + Send>>>, Status> {
        let ctx = Arc::new(SessionContext::new_with_config(SessionConfig::new().with_information_schema(true)));
        ctx.sql("CREATE VIEW sample AS SELECT 1 AS id, 'Ada' AS name").await.map_err(error)?.collect().await.map_err(error)?;
        let token = self.id();
        self.contexts.lock().unwrap().insert(token.clone(), ctx);
        let mut response: Response<Pin<Box<dyn Stream<Item=Result<HandshakeResponse, Status>> + Send>>> = Response::new(Box::pin(futures::stream::iter(vec![Ok(HandshakeResponse { protocol_version: 0, payload: token.clone().into() })])));
        response.metadata_mut().insert("authorization", format!("Bearer {token}").parse().map_err(error)?);
        Ok(response)
    }
    async fn do_action_create_prepared_statement(&self, query: ActionCreatePreparedStatementRequest, request: Request<Action>) -> Result<ActionCreatePreparedStatementResult, Status> {
        let (owner, ctx) = self.context(&request)?;
        let mut plan = ctx.state().create_logical_plan(&query.query).await.map_err(error)?;
        // Resolving SQL EXECUTE builds its bound query plan. It does not collect or stream rows.
        if matches!(plan, LogicalPlan::Statement(SqlStatement::Execute(_))) {
            plan = ctx.execute_logical_plan(plan).await.map_err(error)?.into_unoptimized_plan();
        }
        let command = matches!(plan, LogicalPlan::Statement(_) | LogicalPlan::Ddl(_) | LogicalPlan::Dml(_));
        if !command { plan = ctx.state().optimize(&plan).map_err(error)?; }
        let schema = if command { Schema::empty() } else { plan.schema().as_arrow().clone() };
        let mut parameters: Vec<_> = if command { vec![] } else { plan.get_parameter_types().map_err(error)?.into_iter().collect() };
        // Bounded fixture contract: direct CAST($n AS type) supplies a wire parameter type.
        let mut casts = HashMap::new();
        plan.apply(|node| {
            node.apply_expressions(|expr| expr.apply(|expr| {
                if let Expr::Cast(cast) = expr {
                    if let Expr::Placeholder(parameter) = cast.expr.as_ref() {
                        casts.insert(parameter.id.clone(),cast.field.data_type().clone());
                    }
                }
                Ok(TreeNodeRecursion::Continue)
            }))?;
            Ok(TreeNodeRecursion::Continue)
        }).map_err(error)?;
        parameters.sort_by_key(|(name,_)| name.trim_start_matches('$').parse::<usize>().unwrap_or(usize::MAX));
        let fields = parameters.into_iter().map(|(name,ty)| ty.or_else(|| casts.get(&name).cloned()).map(|ty| Field::new(name,ty,true))
            .ok_or_else(|| Status::invalid_argument("fixture requires typed parameters"))).collect::<Result<Vec<_>,_>>()?;
        let IpcMessage(dataset_schema) = SchemaAsIpc::new(&schema, &IpcWriteOptions::default()).try_into().map_err(error)?;
        let IpcMessage(parameter_schema) = SchemaAsIpc::new(&Schema::new(fields), &IpcWriteOptions::default()).try_into().map_err(error)?;
        let id = self.id();
        self.plans.lock().unwrap().insert(id.clone(), (owner, plan));
        Ok(ActionCreatePreparedStatementResult { prepared_statement_handle: id.into(), dataset_schema, parameter_schema })
    }
    async fn do_put_prepared_statement_query(&self, cmd: CommandPreparedStatementQuery, request: Request<PeekableFlightDataStream>) -> Result<DoPutPreparedStatementResult, Status> {
        let (owner, _) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?.to_owned();
        self.plan(&owner, &id)?;
        let batches: Vec<_> = FlightRecordBatchStream::new_from_flight_data(request.into_inner().map_err(FlightError::from))
            .try_collect().await.map_err(error)?;
        if batches.len() != 1 || batches[0].num_rows() != 1 { return Err(Status::invalid_argument("fixture binds one row")); }
        let values = batches[0].columns().iter().map(|col| ScalarValue::try_from_array(col,0)).collect::<Result<Vec<_>,_>>().map_err(error)?;
        self.bindings.lock().unwrap().insert(id, values);
        Ok(DoPutPreparedStatementResult { prepared_statement_handle: Some(cmd.prepared_statement_handle) })
    }
    async fn get_flight_info_prepared_statement(&self, cmd: CommandPreparedStatementQuery, request: Request<FlightDescriptor>) -> Result<Response<FlightInfo>, Status> {
        let (owner, ctx) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        let plan = self.plan(&owner,id)?;
        self.result(Arc::new(plan.schema().as_arrow().clone()), ResultData::Query(owner,ctx,plan))
    }
    async fn do_get_fallback(&self, request: Request<Ticket>, message: Any) -> Result<Response<<Self as FlightService>::DoGetStream>, Status> {
        let (owner, _) = self.context(&request)?;
        let fetch: Fetch = message.unpack().map_err(error)?.ok_or_else(|| Status::invalid_argument("ticket"))?;
        let result = self.results.lock().unwrap().remove(&fetch.handle).ok_or_else(|| Status::not_found("result"))?;
        match result {
            ResultData::Query(result_owner,ctx,plan) => {
                if owner != result_owner { return Err(Status::permission_denied("foreign result")); }
                let df = ctx.execute_logical_plan(plan).await.map_err(error)?;
                let schema = Arc::new(df.schema().as_arrow().clone());
                let batches = df.execute_stream().await.map_err(error)?.map_err(|e| FlightError::ExternalError(Box::new(e)));
                Ok(Response::new(Box::pin(FlightDataEncoderBuilder::new().with_schema(schema).build(batches).map_err(Status::from))))
            }
            ResultData::Batch(result_owner,batch) => {
                if owner != result_owner { return Err(Status::permission_denied("foreign result")); }
                Ok(Response::new(Box::pin(FlightDataEncoderBuilder::new().with_schema(batch.schema()).build(futures::stream::iter(vec![Ok(batch)])).map_err(Status::from))))
            }
        }
    }
    async fn do_action_close_prepared_statement(&self, cmd: ActionClosePreparedStatementRequest, request: Request<Action>) -> Result<(), Status> {
        let (owner, _) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        let mut plans = self.plans.lock().unwrap();
        if plans.get(id).is_some_and(|(p,_)| p == &owner) { plans.remove(id); self.bindings.lock().unwrap().remove(id); }
        Ok(())
    }
    async fn do_put_prepared_statement_update(&self, cmd: CommandPreparedStatementUpdate, request: Request<PeekableFlightDataStream>) -> Result<i64, Status> {
        let (owner, ctx) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        ctx.execute_logical_plan(self.plan(&owner,id)?).await.map_err(error)?.collect().await.map_err(error)?;
        Ok(0)
    }
    async fn get_flight_info_sql_info(&self, cmd: CommandGetSqlInfo, request: Request<FlightDescriptor>) -> Result<Response<FlightInfo>, Status> {
        let (owner, _) = self.context(&request)?;
        let mut info = SqlInfoDataBuilder::new();
        info.append(SqlInfo::FlightSqlServerName, "Brikk DataFusion execution fixture");
        info.append(SqlInfo::FlightSqlServerVersion, "55.2.0");
        info.append(SqlInfo::SqlIdentifierQuoteChar, "\"");
        info.append(SqlInfo::FlightSqlServerTransaction, 0i32);
        let batch = cmd.into_builder(&info.build().map_err(error)?).build().map_err(error)?;
        self.result(batch.schema(),ResultData::Batch(owner,batch))
    }
    async fn register_sql_info(&self, _: i32, _: &SqlInfo) {}
}
#[derive(Clone, PartialEq, prost::Message)]
struct Fetch { #[prost(string, tag="1")] handle: String }
impl ProstMessageExt for Fetch {
    fn type_url() -> &'static str { "type.googleapis.com/brikk.execution.fixture.Fetch" }
    fn as_any(&self) -> Any { Any { type_url: Self::type_url().into(), value: self.encode_to_vec().into() } }
}
#[tokio::main(flavor="multi_thread", worker_threads=2)]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = std::env::args().nth(1).ok_or("port required")?.parse()?;
    tonic::transport::Server::builder().add_service(FlightServiceServer::new(Fixture::default()))
        .serve(([127,0,0,1], port).into()).await?;
    Ok(())
}
