import java.sql.*;
import java.util.*;

/** Development-only Flight SQL JDBC qualification against disposable fixtures. */
public class FlightCatalogProbe {
    interface Read { Object get() throws Exception; }
    static Map<String,Object> attempt(Read read) {
        Map<String,Object> result = new LinkedHashMap<>();
        try { result.put("status", "accepted"); result.put("value", read.get()); }
        catch (Exception e) { result.put("status", "unsupported-or-failed"); result.put("class", e.getClass().getName()); result.put("message", e.getMessage()); e.printStackTrace(System.err); }
        return result;
    }
    static Object rows(ResultSet rows) throws SQLException {
        try (rows) {
            ResultSetMetaData meta = rows.getMetaData();
            List<Object> columns = new ArrayList<>();
            for (int i=1; i<=meta.getColumnCount(); i++) {
                Map<String,Object> c = new LinkedHashMap<>();
                c.put("name", meta.getColumnLabel(i)); c.put("type", meta.getColumnTypeName(i)); c.put("jdbc_type", meta.getColumnType(i));
                columns.add(c);
            }
            List<Object> values = new ArrayList<>();
            while (rows.next()) {
                List<Object> row = new ArrayList<>();
                for (int i=1; i<=meta.getColumnCount(); i++) row.add(rows.getString(i));
                values.add(row);
                if (values.size() > 100000) throw new SQLException("Probe row budget exceeded");
            }
            return Map.of("columns", columns, "rows", values);
        }
    }
    static Object query(Connection c, String sql) throws SQLException {
        try (Statement s=c.createStatement()) { s.setQueryTimeout(20); return rows(s.executeQuery(sql)); }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver");
        Properties props = new Properties();
        if (args.length > 1 && args[1].equals("fixture-auth")) { props.setProperty("user", "fixture"); props.setProperty("password", "fixture"); }
        DriverManager.setLoginTimeout(15);
        Map<String,Object> result = new LinkedHashMap<>();
        try (Connection c=DriverManager.getConnection(args[0], props)) {
            DatabaseMetaData m=c.getMetaData();
            result.put("driver", m.getDriverName()); result.put("driver_version", m.getDriverVersion());
            result.put("product", attempt(m::getDatabaseProductName)); result.put("version", attempt(m::getDatabaseProductVersion));
            result.put("quote", attempt(m::getIdentifierQuoteString)); result.put("auto_commit", attempt(c::getAutoCommit));
            result.put("catalogs", attempt(() -> rows(m.getCatalogs())));
            result.put("schemas", attempt(() -> rows(m.getSchemas())));
            result.put("tables", attempt(() -> rows(m.getTables(null,null,"%",null))));
            result.put("columns", attempt(() -> rows(m.getColumns(null,null,"%","%"))));
            result.put("functions", attempt(() -> rows(m.getFunctions(null,null,"%"))));
            result.put("primary_keys", attempt(() -> rows(m.getPrimaryKeys(null,null,"sample"))));
            result.put("type_info", attempt(() -> rows(m.getTypeInfo())));
            Map<String,Object> queries = new LinkedHashMap<>();
            for (String sql : List.of(
                "SELECT version() AS version",
                "SELECT name, value FROM information_schema.df_settings ORDER BY name",
                "SELECT catalog_name, schema_name FROM information_schema.schemata ORDER BY catalog_name, schema_name",
                "SELECT routine_catalog, routine_schema, routine_name, function_type FROM information_schema.routines ORDER BY routine_name, function_type",
                "SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables ORDER BY table_catalog, table_schema, table_name",
                "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, column_default, is_nullable, data_type FROM information_schema.columns ORDER BY table_catalog, table_schema, table_name, ordinal_position",
                "SELECT table_catalog, table_schema, table_name, definition FROM information_schema.views ORDER BY table_catalog, table_schema, table_name",
                "SELECT * FROM information_schema.df_settings ORDER BY name",
                "SELECT * FROM information_schema.routines ORDER BY routine_name, function_type",
                "SELECT * FROM information_schema.parameters ORDER BY specific_name, ordinal_position",
                "SHOW FUNCTIONS",
                "SELECT * FROM information_schema.tables ORDER BY table_catalog, table_schema, table_name",
                "SELECT * FROM information_schema.columns ORDER BY table_catalog, table_schema, table_name, ordinal_position",
                "SELECT * FROM information_schema.views ORDER BY table_catalog, table_schema, table_name",
                "SELECT * FROM information_schema.table_constraints",
                "SELECT * FROM information_schema.key_column_usage",
                "SELECT * FROM information_schema.schemata",
                "SELECT * FROM \"MixedRows\"",
                "SELECT * FROM \"mixedrows\"",
                "SELECT * FROM \"a.b\"",
                "SELECT arrow_cast(NULL, 'Decimal128(18, 3)') AS d, arrow_cast(NULL, 'List(Int64)') AS l")) {
                queries.put(sql, attempt(() -> query(c, sql)));
            }
            result.put("queries", queries);
            if (args.length > 2) result.put("production_harvest", attempt(() -> Class.forName("FlightHarvesterProbe")
                .getMethod("harvest", Connection.class, String.class).invoke(null, c, args[2])));
            if (args.length > 1 && args[1].equals("fixture-auth")) {
                Map<String,Object> sessions = new LinkedHashMap<>();
                try (Connection other=DriverManager.getConnection(args[0], props)) {
                    sessions.put("create", attempt(() -> { try (Statement s=c.createStatement()) { return s.executeUpdate("CREATE VIEW session_only AS SELECT 42 AS value"); } }));
                    sessions.put("own_view", attempt(() -> query(c, "SELECT * FROM session_only")));
                    sessions.put("other_view", attempt(() -> query(other, "SELECT * FROM session_only")));
                    sessions.put("set", attempt(() -> { try (Statement s=c.createStatement()) { return s.executeUpdate("SET datafusion.sql_parser.enable_ident_normalization = false"); } }));
                    String setting = "SELECT value FROM information_schema.df_settings WHERE name = 'datafusion.sql_parser.enable_ident_normalization'";
                    sessions.put("own_setting", attempt(() -> query(c, setting)));
                    sessions.put("other_setting", attempt(() -> query(other, setting)));
                }
                result.put("sessions", sessions);
            }
        }
        System.out.println(json(result));
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?,?> map) {
            List<String> parts=new ArrayList<>(); map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v))); return "{"+String.join(",",parts)+"}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> parts=new ArrayList<>(); list.forEach(v->parts.add(json(v))); return "["+String.join(",",parts)+"]";
        }
        StringBuilder s=new StringBuilder("\"");
        for (char ch:value.toString().toCharArray()) {
            switch(ch) { case '"' -> s.append("\\\""); case '\\' -> s.append("\\\\"); case '\n' -> s.append("\\n"); case '\r' -> s.append("\\r"); case '\t' -> s.append("\\t"); default -> { if(ch<32) s.append(String.format("\\u%04x",(int)ch)); else s.append(ch); } }
        }
        return s.append('"').toString();
    }
}
