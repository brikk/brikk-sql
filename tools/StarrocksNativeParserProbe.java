import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.analyzer.AstToSQLBuilder;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.RefreshMaterializedViewStatement;
import com.starrocks.sql.ast.HashDistributionDesc;

/** Test-only shipped FE parser. Never analyzes, plans, executes or mutates SQL. */
public final class StarrocksNativeParserProbe {
    private static String encoded(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String snapshot(StatementBase statement) {
        if (statement instanceof CreateTableStmt c) {
            String columns = c.getColumnDefs().stream().map(v -> v.getName() + ":" + v.getTypeDef().toSql()
                + ":" + v.getAggregateType() + ":nullable=" + v.isAllowNull() + ":default=" + v.getDefaultValue())
                .collect(java.util.stream.Collectors.joining(","));
            var keys = c.getKeysDesc();
            String distribution = c.getDistributionDesc() instanceof HashDistributionDesc h
                ? h.getDistributionColumnNames() + ":buckets=" + h.getBuckets() : String.valueOf(c.getDistributionDesc());
            return "CREATE|" + c.getDbName() + "." + c.getTableName() + "|keys=" + keys.getKeysType()
                + keys.getKeysColumnNames() + "|columns=" + columns + "|hash=" + distribution;
        }
        if (statement instanceof RefreshMaterializedViewStatement r) {
            var partition = r.getPartitionRangeDesc();
            return "REFRESH|" + r.getDbName() + "." + r.getMvName() + "|partition="
                + (partition == null ? "none" : partition.getPartitionStart() + ":" + partition.getPartitionEnd())
                + "|force=" + r.isForceRefresh() + "|sync=" + r.isSync() + "|priority=" + r.getPriority();
        }
        try { return AstToSQLBuilder.toSQL(statement); } catch (Throwable unavailableFormatter) { return ""; }
    }
    public static void main(String[] args) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String sql = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                try {
                    var statements = SqlParser.parse(sql, 0L);
                    String classes = statements.stream().map(s -> s.getClass().getSimpleName())
                        .collect(java.util.stream.Collectors.joining(","));
                    String canonical;
                    try {
                        canonical = statements.stream().map(StarrocksNativeParserProbe::snapshot)
                            .collect(java.util.stream.Collectors.joining("; "));
                    } catch (Throwable unsupportedFormatter) { canonical = ""; }
                    System.out.println("SG14\tOK\t" + encoded(classes) + "\t" + encoded(canonical));
                } catch (Throwable error) {
                    System.out.println("SG14\tERROR\t" + encoded(error.getClass().getName() + ": " + error.getMessage()));
                }
            }
        }
    }
}
