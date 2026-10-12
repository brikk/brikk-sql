package dev.brikk.house.sql.verify

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Registry of the native-grammar verifiers in the per-engine brikk-sql-verify artifacts.
 *
 * Verifier instances are created per call — callers that verify repeatedly (editor sessions,
 * corpus gates) should hold on to the returned instance to amortize cold-start costs.
 */
public object SqlVerifiers {
    /** System property naming the Doris `fe-sql-parser` jar when it is not on the classpath. */
    public const val DORIS_PARSER_JAR_PROPERTY: String = "brikk.doris.parser.jar"

    /**
     * Returns a verifier for [name] (case-insensitive), or null when the engine is unsupported.
     * A supported engine whose parser cannot load returns an [UnavailableVerifier].
     *
     * Supported engines: `"trino"`, `"duckdb"`, `"doris"`, `"postgres"`, `"mysql"`, `"hive"`,
     * `"clickhouse"`. Engines split into two tiers:
     *
     * - **Fidelity (native, non-advisory):** `"trino"`, `"duckdb"`, `"doris"` use each engine's
     *   own parser ([TrinoVerifier], the corpus-gate [DuckdbVerifier], [DorisVerifier]). Doris
     *   loads its parser from the [DORIS_PARSER_JAR_PROPERTY] jar when set, else the classpath.
     * - **Advisory (portable grammar oracle):** `"postgres"`, `"mysql"`, `"hive"`, `"clickhouse"`
     *   use the pure-JVM ShardingSphere SQL parser ([ShardingSphereVerifier]). Fidelity oracles
     *   for these engines live in the heavyweight `brikk-sql-oracle` module and are reached via
     *   its `SqlOracles.forEngine` registry: `PostgresVerifier` (real embedded PostgreSQL +
     *   SQLSTATE discrimination) for the postgres corpus gate, and `ClickhouseVerifier` (chDB
     *   `EXPLAIN AST`) for offline ClickHouse fidelity checks.
     *
     * Editors should construct the engine verifier they need directly rather than use this
     * registry.
     */
    public fun forEngine(name: String): SqlVerifier? = when (name.lowercase()) {
        "doris" -> dorisVerifier()
        "trino" -> TrinoVerifier()
        "duckdb" -> DuckdbVerifier()
        "postgres", "mysql", "hive", "clickhouse" -> ShardingSphereVerifier.forEngine(name)
        else -> null
    }

    private fun dorisVerifier(): SqlVerifier {
        val configured = System.getProperty(DORIS_PARSER_JAR_PROPERTY)
        return try {
            DorisVerifier.create(configured?.let { Path.of(it) })
        } catch (e: InvalidPathException) {
            UnavailableVerifier("doris", unavailableDoris("invalid -D$DORIS_PARSER_JAR_PROPERTY path '$configured'"))
        } catch (e: IllegalStateException) {
            UnavailableVerifier("doris", unavailableDoris(e.message ?: "parser unavailable"))
        }
    }

    private fun unavailableDoris(detail: String): String =
        "Doris verification was not performed: $detail; set -D$DORIS_PARSER_JAR_PROPERTY=/path/to/doris-fe-sql-parser.jar."
}
