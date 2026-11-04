import dev.brikk.house.sql.ast.Expression;
import dev.brikk.house.sql.dialects.DialectKt;
import dev.brikk.house.sql.shape.SqlFragment;
import dev.brikk.house.sql.generator.UnsupportedError;
import dev.brikk.house.sql.parser.ParserKt;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Test-only, line-oriented bridge through the pre-existing public JVM facade. */
public final class SqlglotPrecedenceProbe {
    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String shape(Object value) {
        if (value instanceof Expression e) {
            return e.getClass().getSimpleName() + e.getArgs().entrySet().stream()
                .map(x -> x.getKey() + "=" + shape(x.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "(", ")"));
        }
        if (value instanceof java.util.List<?> list) return list.stream()
            .map(SqlglotPrecedenceProbe::shape).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return String.valueOf(value);
    }
    public static void main(String[] args) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\t", 3);
                String read = fields[0], write = fields[1];
                String sql = new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8);
                try {
                    var ast = ParserKt.parseOne(sql, read);
                    String output = DialectKt.transpile(sql, read, write, false);
                    String strict;
                    String executable = "";
                    try {
                        executable = new SqlFragment(sql, read).toExecutable(write, false, false).getSql();
                        strict = "accepted";
                    } catch (UnsupportedError unsupported) {
                        strict = "unsupported: " + unsupported.getMessage();
                    }
                    System.out.println("OK\t" + encoded(shape(ast)) + "\t" + encoded(output) + "\t" + encoded(strict) + "\t" + encoded(executable));
                } catch (Throwable error) {
                    System.out.println("ERROR\t" + encoded(error.getClass().getName() + ": " + error.getMessage()));
                }
            }
        }
    }
}
