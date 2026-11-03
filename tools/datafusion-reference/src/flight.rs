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
// Adapted from Apache DataFusion's Flight SQL example at 500744d5:
// datafusion-examples/examples/flight/sql_server.rs. See ATTRIBUTIONS.md.
//! Disposable loopback fixture. Each handshake owns a SessionContext; no production authentication.
use std::{collections::HashMap, pin::Pin, result::Result, sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}}};
use datafusion::{arrow::{datatypes::SchemaRef, record_batch::RecordBatch, ipc::writer::IpcWriteOptions},
    logical_expr::LogicalPlan, prelude::{SessionConfig, SessionContext}};
use arrow_flight::{encode::FlightDataEncoderBuilder, flight_service_server::{FlightService, FlightServiceServer},
    sql::{server::{FlightSqlService, PeekableFlightDataStream}, metadata::SqlInfoDataBuilder, *}, *};
use futures::{Stream, TryStreamExt};
use prost::Message;
use tonic::{Request, Response, Status, Streaming};

fn error(e: impl std::fmt::Display) -> Status { Status::internal(e.to_string()) }
type ResultData = (String, SchemaRef, Vec<RecordBatch>);
#[derive(Default)]
struct Fixture {
    next: AtomicU64,
    contexts: Mutex<HashMap<String, Arc<SessionContext>>>,
    plans: Mutex<HashMap<String, (String, LogicalPlan)>>,
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
    fn result(&self, owner: String, schema: SchemaRef, batches: Vec<RecordBatch>) -> Result<Response<FlightInfo>, Status> {
        let id = self.id();
        self.results.lock().unwrap().insert(id.clone(), (owner, schema.clone(), batches));
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
        for sql in [
            "CREATE VIEW sample AS SELECT 1 AS id, 'Ada' AS name",
            "CREATE VIEW \"MixedRows\" AS SELECT 7 AS \"MixedColumn\"",
            "CREATE VIEW \"a.b\" AS SELECT 9 AS id",
            "CREATE VIEW typed AS SELECT arrow_cast(NULL, 'Decimal128(18, 3)') AS amount, arrow_cast(NULL, 'List(Int64)') AS items",
        ] { ctx.sql(sql).await.map_err(error)?.collect().await.map_err(error)?; }
        let token = self.id();
        self.contexts.lock().unwrap().insert(token.clone(), ctx);
        let mut response: Response<Pin<Box<dyn Stream<Item=Result<HandshakeResponse, Status>> + Send>>> = Response::new(Box::pin(futures::stream::iter(vec![Ok(HandshakeResponse { protocol_version: 0, payload: token.clone().into() })])));
        response.metadata_mut().insert("authorization", format!("Bearer {token}").parse().map_err(error)?);
        Ok(response)
    }
    async fn do_action_create_prepared_statement(&self, query: ActionCreatePreparedStatementRequest, request: Request<Action>) -> Result<ActionCreatePreparedStatementResult, Status> {
        let (owner, ctx) = self.context(&request)?;
        let plan = ctx.state().create_logical_plan(&query.query).await.map_err(error)?;
        let IpcMessage(schema) = SchemaAsIpc::new(plan.schema().as_arrow(), &IpcWriteOptions::default()).try_into().map_err(error)?;
        let id = self.id();
        self.plans.lock().unwrap().insert(id.clone(), (owner, plan));
        Ok(ActionCreatePreparedStatementResult { prepared_statement_handle: id.into(), dataset_schema: schema, parameter_schema: Default::default() })
    }
    async fn get_flight_info_prepared_statement(&self, cmd: CommandPreparedStatementQuery, request: Request<FlightDescriptor>) -> Result<Response<FlightInfo>, Status> {
        let (owner, ctx) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        let (plan_owner, plan) = self.plans.lock().unwrap().get(id).cloned().ok_or_else(|| Status::not_found("plan"))?;
        if owner != plan_owner { return Err(Status::permission_denied("foreign plan")); }
        let df = ctx.execute_logical_plan(plan).await.map_err(error)?;
        let schema = Arc::new(df.schema().as_arrow().clone());
        let batches = df.collect().await.map_err(error)?;
        self.result(owner, schema, batches)
    }
    async fn do_get_fallback(&self, request: Request<Ticket>, message: Any) -> Result<Response<<Self as FlightService>::DoGetStream>, Status> {
        let (owner, _) = self.context(&request)?;
        let fetch: Fetch = message.unpack().map_err(error)?.ok_or_else(|| Status::invalid_argument("ticket"))?;
        let (result_owner, schema, batches) = self.results.lock().unwrap().remove(&fetch.handle).ok_or_else(|| Status::not_found("result"))?;
        if owner != result_owner { return Err(Status::permission_denied("foreign result")); }
        let stream = FlightDataEncoderBuilder::new().with_schema(schema).build(futures::stream::iter(batches.into_iter().map(Ok))).map_err(Status::from);
        Ok(Response::new(Box::pin(stream)))
    }
    async fn do_action_close_prepared_statement(&self, cmd: ActionClosePreparedStatementRequest, request: Request<Action>) -> Result<(), Status> {
        let (owner, _) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        let mut plans = self.plans.lock().unwrap();
        if plans.get(id).is_some_and(|(p,_)| p == &owner) { plans.remove(id); }
        Ok(())
    }
    async fn do_put_prepared_statement_update(&self, cmd: CommandPreparedStatementUpdate, request: Request<PeekableFlightDataStream>) -> Result<i64, Status> {
        let (owner, ctx) = self.context(&request)?;
        let id = std::str::from_utf8(&cmd.prepared_statement_handle).map_err(error)?;
        let (plan_owner, plan) = self.plans.lock().unwrap().get(id).cloned().ok_or_else(|| Status::not_found("plan"))?;
        if owner != plan_owner { return Err(Status::permission_denied("foreign plan")); }
        ctx.execute_logical_plan(plan).await.map_err(error)?.collect().await.map_err(error)?;
        Ok(-1)
    }
    async fn get_flight_info_sql_info(&self, cmd: CommandGetSqlInfo, request: Request<FlightDescriptor>) -> Result<Response<FlightInfo>, Status> {
        let (owner, _) = self.context(&request)?;
        let mut info = SqlInfoDataBuilder::new();
        info.append(SqlInfo::FlightSqlServerName, "Brikk DataFusion reference fixture");
        info.append(SqlInfo::FlightSqlServerVersion, "55.2.0");
        info.append(SqlInfo::SqlIdentifierQuoteChar, "\"");
        info.append(SqlInfo::FlightSqlServerTransaction, 0i32);
        let batch = cmd.into_builder(&info.build().map_err(error)?).build().map_err(error)?;
        self.result(owner, batch.schema(), vec![batch])
    }
    async fn register_sql_info(&self, _: i32, _: &SqlInfo) {}
}
#[derive(Clone, PartialEq, prost::Message)]
struct Fetch { #[prost(string, tag="1")] handle: String }
impl ProstMessageExt for Fetch {
    fn type_url() -> &'static str { "type.googleapis.com/brikk.fixture.Fetch" }
    fn as_any(&self) -> Any { Any { type_url: Self::type_url().into(), value: self.encode_to_vec().into() } }
}
#[tokio::main(flavor="current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = std::env::args().nth(1).ok_or("port required")?.parse()?;
    tonic::transport::Server::builder().add_service(FlightServiceServer::new(Fixture::default()))
        .serve(([127,0,0,1], port).into()).await?;
    Ok(())
}
