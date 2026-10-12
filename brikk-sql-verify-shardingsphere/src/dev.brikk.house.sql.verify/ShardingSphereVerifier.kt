package dev.brikk.house.sql.verify

import java.util.concurrent.CancellationException
import org.apache.shardingsphere.sql.parser.api.CacheOption
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine

/**
 * A portable, pure-JVM **grammar oracle** backed by Apache ShardingSphere's re-implemented SQL
 * parser (5.5.2). It answers "is this SQL syntactically legal for the dialect?" without booting
 * a real engine or a native library, which makes it cheap enough to ship and run in an IDE.
 *
 * This is the **advisory tier** ([advisory] is true): the parser is a port of the dialect's
 * grammar, not the engine's own parser, so it can diverge from engine-exact behaviour. It is a
 * syntax check only — there is no catalog, no name/arity resolution, and no semantic analysis.
 * Consumers may treat an advisory [VerifyResult.Rejected] as a non-blocking warning. It reports
 * no error positions.
 *
 * Fidelity notes per dialect (from offline validation; these are intentional and NOT bugs to
 * "fix" here):
 *
 * - **PostgreSQL / MySQL:** grammars are excellent.
 * - **Hive:** decent, but rejects valid `LATERAL VIEW`. Requires `shardingsphere-infra-database-hive`
 *   on the classpath to load its DB-type SPI (declared in module.yaml).
 * - **ClickHouse:** imperfect — accepts invalid `SELECT FROM WHERE`, rejects valid `numbers(3)`,
 *   `PREWHERE`/`SETTINGS`, `{param:UInt32}`, and `CAST(x AS Nullable(String))`. For engine-exact
 *   ClickHouse verification use the chDB-backed `ClickhouseVerifier` in `brikk-sql-oracle`.
 *
 * ShardingSphere resolves its dialect parsers through `ServiceLoader`; in a plugin class loader,
 * call it with the thread context class loader set to this library's loader.
 *
 * The [SQLParserEngine] is the expensive part; hold one instance per dialect and reuse it.
 */
public class ShardingSphereVerifier(
    override val engine: String,
    databaseType: String,
) : SqlVerifier {
    override val advisory: Boolean get() = true

    private val parser: SQLParserEngine =
        SQLParserEngine(databaseType, CacheOption(CACHE_INITIAL_CAPACITY, CACHE_MAXIMUM_SIZE))

    override fun verify(sql: String): VerifyResult =
        try {
            parser.parse(sql, false)
            VerifyResult.Accepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ShardingSphere reports grammar rejections through several exception types.
            VerifyResult.Rejected(VerifyError(e.message ?: e::class.java.name))
        }

    /** ShardingSphere has no expression-only entry point, so the fragment is wrapped in `SELECT`. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    public companion object {
        private const val EXPRESSION_PREFIX = "SELECT "
        // ShardingSphere parse-tree cache sizing; matches its own default CacheOption.
        private const val CACHE_INITIAL_CAPACITY = 128
        private const val CACHE_MAXIMUM_SIZE = 1024L

        /** The advisory verifier for a brikk engine name, or null if unsupported. */
        public fun forEngine(engine: String): ShardingSphereVerifier? = when (engine.lowercase()) {
            "postgres" -> ShardingSphereVerifier("postgres", "PostgreSQL")
            "mysql" -> ShardingSphereVerifier("mysql", "MySQL")
            "hive" -> ShardingSphereVerifier("hive", "Hive")
            "clickhouse" -> ShardingSphereVerifier("clickhouse", "ClickHouse")
            else -> null
        }
    }
}
