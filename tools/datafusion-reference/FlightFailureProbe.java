import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.driver.jdbc.ArrowFlightConnection;
import org.apache.arrow.driver.jdbc.client.ArrowFlightSqlClientHandler;
import org.apache.arrow.driver.jdbc.utils.AvaticaParameterBinder;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.flight.*;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.memory.*;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.vector.*;
import org.apache.arrow.driver.jdbc.shaded.org.apache.arrow.vector.types.pojo.*;
import org.apache.arrow.driver.jdbc.shaded.org.apache.calcite.avatica.Meta;
import org.apache.arrow.driver.jdbc.shaded.org.apache.calcite.avatica.remote.TypedValue;
import org.apache.arrow.driver.jdbc.shaded.org.apache.calcite.avatica.ColumnMetaData.Rep;
import org.apache.arrow.driver.jdbc.shaded.com.google.protobuf.Any;

/** Investigation only. Pinned driver, disposable loopback Spice, no Brikk or IDE classes. */
public class FlightFailureProbe {
    interface Op { Object run() throws Exception; }
    static Map<String,Object> attempt(Op op) {
        Map<String,Object> out = new LinkedHashMap<>();
        try { out.put("status", "returned"); out.put("value", op.run()); }
        catch (Exception e) {
            out.put("status", "threw"); out.put("class", e.getClass().getName()); out.put("message", e.getMessage());
            List<String> stack = new ArrayList<>();
            for (Throwable t=e; t!=null && stack.size()<70; t=t.getCause()) {
                stack.add(t.toString());
                for (StackTraceElement f:t.getStackTrace()) { if (stack.size()>=70) break; stack.add(f.toString()); }
            }
            out.put("stack",stack);
        }
        return out;
    }
    static List<Object> fields(Schema schema) {
        List<Object> out = new ArrayList<>();
        for (Field f:schema.getFields()) out.add(Map.of("name",f.getName(),"type",f.getType().toString(),"nullable",f.isNullable()));
        return out;
    }
    static Object jdbc(Connection c, String sql, String mode) throws Exception {
        try (Statement s=c.createStatement()) {
            s.setQueryTimeout(5);
            if (mode.equals("query")) return FlightCatalogProbe.rows(s.executeQuery(sql));
            if (mode.equals("update")) return Map.of("update_count",s.executeUpdate(sql));
            boolean rows=s.execute(sql);
            return rows ? Map.of("result",FlightCatalogProbe.rows(s.getResultSet())) : Map.of("update_count",s.getUpdateCount());
        }
    }
    static Object nullCase(String url, String sql, String kind, int repetitions) throws Exception {
        Connection c=DriverManager.getConnection(url);
        // Investigation-only access to allocation counters, not a consumer integration API.
        java.lang.reflect.Method allocatorGetter=ArrowFlightConnection.class.getDeclaredMethod("getBufferAllocator");
        allocatorGetter.setAccessible(true);
        BufferAllocator allocator=(BufferAllocator)allocatorGetter.invoke(c.unwrap(ArrowFlightConnection.class));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("sql",sql); out.put("kind",kind); out.put("initial_bytes",allocator.getAllocatedMemory());
        List<Object> rounds=new ArrayList<>();
        try {
            for (int i=0;i<repetitions;i++) {
                Map<String,Object> r=new LinkedHashMap<>();
                PreparedStatement s=c.prepareStatement(sql);
                ParameterMetaData md=s.getParameterMetaData();
                List<Object> parameters=new ArrayList<>();
                for (int p=1;p<=md.getParameterCount();p++) parameters.add(Map.of("index",p,"jdbc_type",md.getParameterType(p),"nullable",md.isNullable(p)));
                r.put("metadata",parameters);
                r.put("setters",attempt(()->{
                    if (kind.equals("string_then_null")) { s.setString(1,"O'Reilly 😀"); s.setNull(2,Types.BIGINT); }
                    else if (kind.equals("null_then_string")) { s.setNull(1,Types.BIGINT); s.setString(2,"x"); }
                    else if (kind.equals("valid")) { s.setString(1,"x"); s.setLong(2,9); }
                    else if (kind.equals("wrong_type")) { s.setString(1,"x"); s.setString(2,"not-a-number"); }
                    else if (kind.equals("object_null")) s.setObject(1,null);
                    else s.setNull(1,Types.BIGINT);
                    return true;
                }));
                r.put("after_setters_bytes",allocator.getAllocatedMemory());
                r.put("execute",attempt(()->FlightCatalogProbe.rows(s.executeQuery())));
                r.put("after_execute_bytes",allocator.getAllocatedMemory());
                r.put("clear_parameters",attempt(()->{s.clearParameters();return true;}));
                r.put("statement_close",attempt(()->{s.close();return true;}));
                r.put("after_statement_close_bytes",allocator.getAllocatedMemory());
                rounds.add(r);
            }
            out.put("rounds",rounds);
            out.put("subsequent_query",attempt(()->jdbc(c,"SELECT 1 AS n","query")));
            out.put("before_connection_close_bytes",allocator.getAllocatedMemory());
        } finally { out.put("connection_close",attempt(()->{c.close();return true;})); }
        return out;
    }

    static CallOption[] options(String session) {
        FlightCallHeaders headers=new FlightCallHeaders(); headers.insert("x-session-id",session);
        return new CallOption[]{new HeaderCallOption(headers),CallOptions.timeout(5,TimeUnit.SECONDS)};
    }
    static Map<String,Object> drain(FlightClient client, FlightInfo info, CallOption[] opts) throws Exception {
        List<Object> rows=new ArrayList<>(); List<Object> schema=new ArrayList<>();
        for (FlightEndpoint endpoint:info.getEndpoints()) {
            try (FlightStream stream=client.getStream(endpoint.getTicket(),opts)) {
                schema=fields(stream.getSchema());
                while(stream.next()) {
                    VectorSchemaRoot root=stream.getRoot();
                    for(int r=0;r<root.getRowCount();r++) {
                        List<Object> row=new ArrayList<>();
                        for(FieldVector v:root.getFieldVectors()) { Object value=v.getObject(r); row.add(value==null?null:value.toString()); }
                        rows.add(row);
                        if(rows.size()>100) throw new IllegalStateException("Probe row bound exceeded");
                    }
                }
            }
        }
        return Map.of("fields",schema,"rows",rows);
    }
    static Object rawQuery(FlightSqlClient sql, FlightClient client, String query, String session) throws Exception {
        CallOption[] opts=options(session);
        FlightInfo info=sql.execute(query,opts);
        Map<String,Object> out=new LinkedHashMap<>(drain(client,info,opts));
        out.put("advertised_fields",fields(info.getSchema()));
        return out;
    }
    static Object rawPrepared(FlightSqlClient sql, FlightClient client, String query, String session) throws Exception {
        CallOption[] opts=options(session); Map<String,Object> out=new LinkedHashMap<>();
        try(FlightSqlClient.PreparedStatement p=sql.prepare(query,opts)) {
            out.put("dataset_fields",fields(p.getResultSetSchema()));
            out.put("parameter_fields",fields(p.getParameterSchema()));
            out.put("force_query_path",attempt(()->drain(client,p.execute(opts),opts)));
        }
        return out;
    }
    static Object rawNull(FlightSqlClient sql, FlightClient client, RootAllocator allocator, String query) throws Exception {
        CallOption[] opts=options("null-test"); Map<String,Object> out=new LinkedHashMap<>();
        try(FlightSqlClient.PreparedStatement p=sql.prepare(query,opts)) {
            Schema declared=p.getParameterSchema(); out.put("advertised_parameters",fields(declared));
            List<Field> nullable=new ArrayList<>();
            for(Field f:declared.getFields()) nullable.add(new Field(f.getName(),new FieldType(true,f.getType(),f.getDictionary(),f.getMetadata()),f.getChildren()));
            VectorSchemaRoot values=VectorSchemaRoot.create(new Schema(nullable),allocator);
            values.allocateNew(); for(FieldVector v:values.getFieldVectors()) v.setNull(0); values.setRowCount(1);
            out.put("sent_parameters",fields(values.getSchema()));
            p.setParameters(values); // PreparedStatement owns and closes this root.
            out.put("execute",attempt(()->drain(client,p.execute(opts),opts)));
        }
        return out;
    }
    static Object wrongUpdateReply(FlightClient client, String query, String session) throws Exception {
        CallOption[] opts=options(session); Map<String,Object> out=new LinkedHashMap<>();
        FlightSql.ActionCreatePreparedStatementRequest request=FlightSql.ActionCreatePreparedStatementRequest.newBuilder().setQuery(query).build();
        Iterator<Result> result=client.doAction(new Action("CreatePreparedStatement",Any.pack(request).toByteArray()),opts);
        FlightSql.ActionCreatePreparedStatementResult prepared=Any.parseFrom(result.next().getBody()).unpack(FlightSql.ActionCreatePreparedStatementResult.class);
        FlightDescriptor descriptor=FlightDescriptor.command(Any.pack(FlightSql.CommandPreparedStatementUpdate.newBuilder().setPreparedStatementHandle(prepared.getPreparedStatementHandle()).build()).toByteArray());
        try(VectorSchemaRoot empty=VectorSchemaRoot.of(); SyncPutListener listener=new SyncPutListener()) {
            FlightClient.ClientStreamListener put=client.startPut(descriptor,empty,listener,opts);
            put.putNext();put.completed();put.getResult();
            try(PutResult reply=listener.read()) {
                ArrowBuf metadata=reply.getApplicationMetadata(); byte[] bytes=new byte[(int)metadata.readableBytes()];metadata.getBytes(0,bytes);
                FlightSql.DoPutUpdateResult asUpdate=FlightSql.DoPutUpdateResult.parseFrom(bytes);
                FlightSql.DoPutPreparedStatementResult asBinding=FlightSql.DoPutPreparedStatementResult.parseFrom(bytes);
                out.put("metadata_hex",HexFormat.of().formatHex(bytes));
                out.put("decoded_update_count",asUpdate.getRecordCount());
                out.put("update_unknown_fields",asUpdate.getUnknownFields().toString());
                out.put("returned_binding_handle_bytes",asBinding.getPreparedStatementHandle().size());
                out.put("record_count_field_type",FlightSql.DoPutUpdateResult.getDescriptor().findFieldByName("record_count").getType().toString());
                out.put("binding_handle_field_type",FlightSql.DoPutPreparedStatementResult.getDescriptor().findFieldByName("prepared_statement_handle").getType().toString());
            }
        } finally {
            FlightSql.ActionClosePreparedStatementRequest close=FlightSql.ActionClosePreparedStatementRequest.newBuilder().setPreparedStatementHandle(prepared.getPreparedStatementHandle()).build();
            client.doAction(new Action("ClosePreparedStatement",Any.pack(close).toByteArray()),opts).forEachRemaining(r->{});
        }
        return out;
    }

    /** Proves the binder cleanup fault without any server or JDBC connection. */
    static Object localBinder() throws Exception {
        RootAllocator allocator=new RootAllocator(); Map<String,Object> out=new LinkedHashMap<>();
        Schema schema=new Schema(List.of(new Field("text",FieldType.notNullable(new ArrowType.Utf8()),null),new Field("number",FieldType.notNullable(new ArrowType.Int(64,true)),null)));
        final VectorSchemaRoot[] attached={null};
        ArrowFlightSqlClientHandler.PreparedStatement stub=new ArrowFlightSqlClientHandler.PreparedStatement() {
            public FlightInfo executeQuery(){throw new AssertionError("No network");}
            public long executeUpdate(){throw new AssertionError("No network");}
            public Meta.StatementType getType(){return Meta.StatementType.SELECT;}
            public Schema getDataSetSchema(){return new Schema(List.of());}
            public Schema getParameterSchema(){return schema;}
            public void setParameters(VectorSchemaRoot root){attached[0]=root;}
            public void close(){if(attached[0]!=null)attached[0].close();}
        };
        AvaticaParameterBinder binder=new AvaticaParameterBinder(stub,allocator);
        out.put("before_bytes",allocator.getAllocatedMemory());
        out.put("bind",attempt(()->{binder.bind(Arrays.asList(TypedValue.ofLocal(Rep.STRING,"text"),null));return true;}));
        out.put("ownership_transferred",attached[0]!=null);
        out.put("after_failure_bytes",allocator.getAllocatedMemory());stub.close();
        out.put("after_owner_close_bytes",allocator.getAllocatedMemory());
        // Investigation-only reflection retrieves the orphan solely to release the probe's memory.
        java.lang.reflect.Field root=AvaticaParameterBinder.class.getDeclaredField("parameters");root.setAccessible(true);
        ((VectorSchemaRoot)root.get(binder)).close();
        out.put("after_manual_probe_cleanup_bytes",allocator.getAllocatedMemory());allocator.close();
        return out;
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver");
        Map<String,Object> out=new LinkedHashMap<>(); String url=args[0]; int port=Integer.parseInt(args[1]);
        try(Connection c=DriverManager.getConnection(url)) {
            List<Object> matrix=new ArrayList<>();
            for(String query:List.of("SELECT 1 AS n","SELECT missing_column FROM sample","SELECT no_such_column","SELECT * FROM no_such_table","SELECT no_such_function(1)","SELECT FROM","SELECT CAST('not-an-integer' AS BIGINT)")) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("sql",query);
                for(String mode:List.of("execute","query","update")) row.put(mode,attempt(()->jdbc(c,query,mode)));
                matrix.add(row);
            }
            out.put("jdbc_matrix",matrix);
            out.put("jdbc_prepare",attempt(()->jdbc(c,"PREPARE named_jdbc(BIGINT) AS SELECT $1 + 1 AS n","update")));
            out.put("jdbc_execute",attempt(()->jdbc(c,"EXECUTE named_jdbc(7)","execute")));
            out.put("jdbc_execute_unknown",attempt(()->jdbc(c,"EXECUTE never_prepared(7)","execute")));
        }
        String mixed="SELECT CAST($1 AS VARCHAR) AS txt, CAST($2 AS BIGINT) AS n";
        out.put("jdbc_null_only",nullCase(url,"SELECT CAST($1 AS BIGINT) AS n","null",1));
        out.put("jdbc_object_null",nullCase(url,"SELECT CAST($1 AS BIGINT) AS n","object_null",1));
        out.put("jdbc_null_first",nullCase(url,"SELECT CAST($1 AS BIGINT) AS n, CAST($2 AS VARCHAR) AS txt","null_then_string",1));
        out.put("jdbc_partial_null_repeated",nullCase(url,mixed,"string_then_null",3));
        out.put("jdbc_wrong_type",nullCase(url,mixed,"wrong_type",1));
        out.put("jdbc_valid_control",nullCase(url,mixed,"valid",2));
        out.put("server_free_binder",localBinder());
        try(RootAllocator allocator=new RootAllocator();FlightClient client=FlightClient.builder(allocator,Location.forGrpcInsecure("127.0.0.1",port)).build()) {
            FlightSqlClient sql=new FlightSqlClient(client);
            out.put("raw_valid",attempt(()->rawQuery(sql,client,"SELECT 1 AS n","direct")));
            out.put("raw_invalid_direct",attempt(()->rawQuery(sql,client,"SELECT missing_column FROM sample","direct")));
            out.put("raw_invalid_prepared",attempt(()->rawPrepared(sql,client,"SELECT missing_column FROM sample","direct")));
            out.put("raw_wrong_update_reply",attempt(()->wrongUpdateReply(client,"SELECT missing_column FROM sample","direct")));
            out.put("raw_parameter_schemas",attempt(()->{
                List<Object> schemas=new ArrayList<>();
                for(String query:List.of(mixed,"SELECT CAST($1 AS BIGINT) IS NULL AS is_null","SELECT COALESCE(CAST($1 AS BIGINT), 42) AS n")) {
                    try(FlightSqlClient.PreparedStatement p=sql.prepare(query,options("schema-test"))) {
                        schemas.add(Map.of("sql",query,"parameters",fields(p.getParameterSchema()),"result",fields(p.getResultSetSchema())));
                    }
                }
                return schemas;
            }));
            out.put("raw_named_prepare",attempt(()->rawQuery(sql,client,"PREPARE named_raw(BIGINT) AS SELECT $1 + 1 AS n","session-a")));
            out.put("raw_named_execute",attempt(()->rawQuery(sql,client,"EXECUTE named_raw(7)","session-a")));
            out.put("raw_named_other_session",attempt(()->rawQuery(sql,client,"EXECUTE named_raw(7)","session-b")));
            out.put("raw_named_deallocate",attempt(()->rawQuery(sql,client,"DEALLOCATE named_raw","session-a")));
            out.put("raw_named_after_deallocate",attempt(()->rawQuery(sql,client,"EXECUTE named_raw(7)","session-a")));
            out.put("jdbc_shared_session_control",attempt(()->{
                Map<String,Object> bridge=new LinkedHashMap<>();
                bridge.put("raw_prepare",rawQuery(sql,client,"PREPARE bridge_name(BIGINT) AS SELECT $1 + 1 AS n","bridge-session"));
                Properties props=new Properties();props.setProperty("x-session-id","bridge-session");
                try(Connection c=DriverManager.getConnection(url,props)) {
                    bridge.put("jdbc_execute_prepared_elsewhere",attempt(()->jdbc(c,"EXECUTE bridge_name(7)","execute")));
                    bridge.put("prepared_execute_query_control",attempt(()->rawPrepared(sql,client,"EXECUTE bridge_name(7)","bridge-session")));
                    bridge.put("jdbc_force_flight_query_control",attempt(()->{
                        try(PreparedStatement p=c.prepareStatement("EXECUTE bridge_name(7)")) {
                            FlightInfo info=p.unwrap(org.apache.arrow.driver.jdbc.ArrowFlightInfoStatement.class).executeFlightInfoQuery();
                            return drain(client,info,options("bridge-session"));
                        }
                    }));
                    bridge.put("jdbc_deallocate",attempt(()->jdbc(c,"DEALLOCATE bridge_name","update")));
                    bridge.put("raw_still_exists",attempt(()->rawQuery(sql,client,"EXECUTE bridge_name(7)","bridge-session")));
                    bridge.put("raw_deallocate",attempt(()->rawQuery(sql,client,"DEALLOCATE bridge_name","bridge-session")));
                    bridge.put("raw_now_missing",attempt(()->rawQuery(sql,client,"EXECUTE bridge_name(7)","bridge-session")));
                }
                return bridge;
            }));
            out.put("raw_prepare_update_reply",attempt(()->wrongUpdateReply(client,"PREPARE no_effect(BIGINT) AS SELECT $1 AS n","session-a")));
            out.put("raw_execute_after_update_prepare",attempt(()->rawQuery(sql,client,"EXECUTE no_effect(9)","session-a")));
            out.put("raw_nullable_override",attempt(()->rawNull(sql,client,allocator,"SELECT CAST($1 AS BIGINT) AS n")));
            out.put("raw_nullable_coalesce",attempt(()->rawNull(sql,client,allocator,"SELECT COALESCE(CAST($1 AS BIGINT), 42) AS n")));
        }
        Files.writeString(Path.of(args[2]),FlightCatalogProbe.json(out));
        System.out.println("Captured "+out.size()+" investigation groups");
    }
}
