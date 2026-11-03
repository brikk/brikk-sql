import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import dev.brikk.house.sql.shape.PipeExecutionKt;
import dev.brikk.house.sql.shape.PipeLowering;

/** Bounded execution qualification on disposable fixtures, never on a user's connection. */
public class FlightExecutionProbe {
    interface Read { Object get() throws Exception; }
    static Map<String,Object> attempt(Read read) {
        Map<String,Object> result=new LinkedHashMap<>();
        try { result.put("status","accepted"); result.put("value",read.get()); }
        catch (Exception e) {
            result.put("status","failed"); result.put("class",e.getClass().getName()); result.put("message",e.getMessage());
            List<String> causes=new ArrayList<>();
            for (Throwable t=e.getCause();t!=null && causes.size()<8;t=t.getCause()) causes.add(t.toString());
            result.put("causes",causes);
        }
        return result;
    }
    static Object query(Connection c,String sql) throws SQLException {
        try(Statement s=c.createStatement()) { s.setQueryTimeout(5); return FlightCatalogProbe.rows(s.executeQuery(sql)); }
    }
    static Object update(Connection c,String sql) throws SQLException {
        try(Statement s=c.createStatement()) { s.setQueryTimeout(5); return s.executeUpdate(sql); }
    }
    static Object execute(Connection c,String sql) throws SQLException {
        try(Statement s=c.createStatement()) {
            s.setQueryTimeout(5);
            return s.execute(sql) ? Map.of("result",FlightCatalogProbe.rows(s.getResultSet())) : Map.of("update_count",s.getUpdateCount());
        }
    }
    static Object bound(Connection c,String sql,boolean repeated) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setQueryTimeout(5);
            int count=s.getParameterMetaData().getParameterCount();
            s.setLong(1,7);
            return Map.of("parameters",count,"result",FlightCatalogProbe.rows(s.executeQuery()));
        }
    }
    static Object boundValues(Connection c) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT CAST($1 AS VARCHAR) AS txt, CAST($2 AS BIGINT) AS nil")) {
            s.setQueryTimeout(5);s.setString(1,"O'Reilly 😀");s.setLong(2,9);
            return Map.of("parameters",s.getParameterMetaData().getParameterCount(),"result",FlightCatalogProbe.rows(s.executeQuery()));
        }
    }
    static Object boundNull(Connection c) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT CAST($1 AS BIGINT) AS n")) {
            s.setQueryTimeout(5);s.setNull(1,Types.BIGINT);return FlightCatalogProbe.rows(s.executeQuery());
        }
    }
    static Object partiallyBoundNull(Connection c) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT CAST($1 AS VARCHAR) AS txt, CAST($2 AS BIGINT) AS nil")) {
            s.setQueryTimeout(5);s.setString(1,"O'Reilly 😀");s.setNull(2,Types.BIGINT);
            return FlightCatalogProbe.rows(s.executeQuery());
        }
    }
    static Object rebind(Connection c) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT CAST($1 AS BIGINT) + 1 AS n")) {
            s.setQueryTimeout(5);s.setLong(1,7);Object first=FlightCatalogProbe.rows(s.executeQuery());
            s.setLong(1,9);return List.of(first,FlightCatalogProbe.rows(s.executeQuery()));
        }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver");
        String product=args[1];
        Properties props=new Properties();
        if(product.equals("datafusion")) { props.setProperty("user","fixture");props.setProperty("password","fixture"); }
        Map<String,Object> out=new LinkedHashMap<>() {
            @Override public Object put(String key,Object value) {
                Object previous=super.put(key,value);
                try { Files.writeString(Path.of(args[2]),FlightCatalogProbe.json(this)); }
                catch(Exception e) { throw new RuntimeException(e); }
                System.err.println("captured "+key);
                return previous;
            }
        };
        Connection c=DriverManager.getConnection(args[0],props);
        Connection other=DriverManager.getConnection(args[0],props);
        try {
            out.put("jdbc_question",attempt(()->bound(c,"SELECT CAST(? AS BIGINT) + 1 AS n",false)));
            out.put("jdbc_numbered",attempt(()->bound(c,"SELECT CAST($1 AS BIGINT) + 1 AS n",false)));
            out.put("jdbc_repeated",attempt(()->bound(c,"SELECT CAST($1 AS BIGINT) + CAST($1 AS BIGINT) AS n",true)));
            out.put("jdbc_values",attempt(()->boundValues(c)));
            out.put("jdbc_null",attempt(()->boundNull(c)));
            out.put("jdbc_rebind",attempt(()->rebind(c)));
            Connection partial=DriverManager.getConnection(args[0],props);
            try { out.put("jdbc_partial_null",attempt(()->partiallyBoundNull(partial))); }
            finally { out.put("partial_binding_close",attempt(()->{partial.close();return true;})); }
            out.put("prepare",attempt(()->update(c,"PREPARE brikk_named(BIGINT) AS SELECT $1 + 1 AS n")));
            out.put("execute_same",attempt(()->query(c,"EXECUTE brikk_named(7)")));
            out.put("execute_same_generic",attempt(()->execute(c,"EXECUTE brikk_named(7)")));
            out.put("execute_other",attempt(()->query(other,"EXECUTE brikk_named(7)")));
            out.put("deallocate",attempt(()->update(c,"DEALLOCATE brikk_named")));
            out.put("execute_after_deallocate",attempt(()->query(c,"EXECUTE brikk_named(7)")));
            out.put("parse_error",attempt(()->query(c,"SELECT '😀' AS x,\n  FROM")));
            out.put("parse_error_position",attempt(()->query(c,"SELECT '😀' AS x |> BOGUS 1")));
            out.put("plan_error",attempt(()->query(c,"SELECT missing_column FROM sample")));
            out.put("plan_error_generic",attempt(()->execute(c,"SELECT missing_column FROM sample")));
            out.put("explain",attempt(()->query(c,"EXPLAIN SELECT 1 AS n")));
            out.put("explain_analyze",attempt(()->query(c,"EXPLAIN ANALYZE SELECT 1 AS n")));
            Map<String,String> stages=new LinkedHashMap<>();
            stages.put("WHERE","WHERE id > 1"); stages.put("SELECT","SELECT id + 1 AS n");
            stages.put("EXTEND","EXTEND id + 1 AS n"); stages.put("AS","AS q |> SELECT q.id");
            stages.put("AGGREGATE","AGGREGATE count(*) AS n"); stages.put("DISTINCT","DISTINCT");
            stages.put("ORDER BY","ORDER BY id DESC"); stages.put("LIMIT","LIMIT 2");
            stages.put("OFFSET","OFFSET 1"); stages.put("WINDOW","WINDOW row_number() OVER (ORDER BY id) AS rn");
            List<Object> pipes=new ArrayList<>();
            for(var stage:stages.entrySet()) {
                String source="FROM (SELECT 1 AS id UNION ALL SELECT 2 AS id UNION ALL SELECT 2 AS id) t |> ORDER BY id |> "+stage.getValue();
                PipeLowering lowered=PipeExecutionKt.lowerForExecution(source,product,false);
                if(!(lowered instanceof PipeLowering.Ok ok) || !ok.getBlockers().isEmpty()) throw new AssertionError("lowering: "+lowered);
                String nativeSql="SELECT * "+source;
                pipes.add(Map.of("stage",stage.getKey(),"source",source,"sql",ok.getSql(),"native_sql",nativeSql,
                    "native",attempt(()->query(c,nativeSql)),"lowered",attempt(()->query(c,ok.getSql()))));
            }
            out.put("pipes",pipes);
            out.put("set",attempt(()->update(c,"SET datafusion.sql_parser.enable_ident_normalization = "+(product.equals("spice") ? "true" : "false"))));
            String setting="SELECT value FROM information_schema.df_settings WHERE name = 'datafusion.sql_parser.enable_ident_normalization'";
            out.put("own_setting",attempt(()->query(c,setting)));
            out.put("other_setting",attempt(()->query(other,setting)));
            out.put("cancellation",cancel(c, progress->out.put("cancellation",progress)));
        } finally {
            out.put("other_close",attempt(()->{other.close();return true;}));
            out.put("connection_close",attempt(()->{c.close();return true;}));
        }
        System.out.println(FlightCatalogProbe.json(out));
    }
    static Object cancel(Connection c,java.util.function.Consumer<Map<String,Object>> checkpoint) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();
        Statement s=c.createStatement(); s.setQueryTimeout(3);
        ExecutorService pool=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"bounded-flight-query");t.setDaemon(true);return t;});
        CountDownLatch entered=new CountDownLatch(1);
        Future<Object> query=pool.submit(()->{entered.countDown();return attempt(()->FlightCatalogProbe.rows(s.executeQuery(
            "SELECT sum(sin(CAST(value AS DOUBLE)) * cos(CAST(value AS DOUBLE))) FROM generate_series(1, 10000000)")));});
        try {
            if(!entered.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("query worker did not start");
            // Let the bounded fixture enter execution; this is a cancellation probe, not a polling loop.
            Thread.sleep(50);
            out.put("running_before_cancel",!query.isDone());
            out.put("phase","calling_cancel"); checkpoint.accept(out);
            out.put("cancel",attempt(()->{s.cancel();return true;}));
            out.put("phase","awaiting_completion"); checkpoint.accept(out);
            out.put("completion",attempt(()->query.get(8,TimeUnit.SECONDS)));
            out.put("phase","checking_connection"); checkpoint.accept(out);
            if(query.isDone()) out.put("connection_after_cancel",attempt(()->query(c,"SELECT 1 AS n")));
        } finally { query.cancel(true);pool.shutdownNow();out.put("phase","closing_statement");checkpoint.accept(out);s.close(); }
        out.put("phase","finished");
        return out;
    }
}
