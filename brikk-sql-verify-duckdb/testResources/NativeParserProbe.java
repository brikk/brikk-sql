import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Test-only source-launched child JVM. Never prepares, binds or executes input SQL. */
class NativeParserProbe {
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Properties settings = new Properties();
        settings.setProperty("enable_external_access", "false");
        settings.setProperty("autoload_known_extensions", "false");
        settings.setProperty("autoinstall_known_extensions", "false");
        settings.setProperty("threads", "1");
        settings.setProperty("memory_limit", "256MB");
        settings.setProperty("lock_configuration", "true");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:", settings);
             BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA version")) {
                r.next();
                out.write("ENGINE\t" + encode(r.getString(1)) + "\t" + encode(r.getString(2)) + "\t" + encode(c.getMetaData().getDriverVersion()) + "\n");
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT to_json(list(struct_pack(name := name, value := value) ORDER BY name)) FROM duckdb_settings()")) {
                r.next(); out.write("SETTINGS\t" + encode(r.getString(1)) + "\n");
            }
            for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t", -1);
                String sql = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                if (sql.indexOf(0) >= 0) {
                    out.write(fields[0] + "\tunverified\t" + encode("nul") + "\t\n");
                    continue;
                }
                String literal = "'" + sql.replace("'", "''") + "'";
                String query = "SELECT d->>'error',d->>'error_type',d->>'error_message' FROM " +
                    "(SELECT system.main.json_serialize_sql(" + literal + ") AS d)";
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(10);
                    try (ResultSet r = s.executeQuery(query)) {
                        if (!r.next()) throw new SQLException("missing parser response");
                        String error = r.getString(1), type = r.getString(2), message = r.getString(3);
                        String detail = message == null ? "" : message.toLowerCase(Locale.ROOT);
                        boolean limited = "out of memory".equals(type) || "interrupt".equals(type) ||
                            detail.contains("expression depth") || detail.contains("parser depth") ||
                            detail.contains("stack limit") || detail.contains("memory limit");
                        String status = "false".equals(error) ? "accepted" : !limited && "parser".equals(type) ? "rejected" : "unverified";
                        out.write(fields[0] + "\t" + status + "\t" + encode(type) + "\t" + encode(message) + "\n");
                    }
                } catch (SQLException e) {
                    out.write(fields[0] + "\tunavailable\t" + encode(e.getSQLState()) + "\t" + encode(e.getClass().getSimpleName()) + "\n");
                }
                out.flush();
            }
        }
    }
}
