package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.generator.UnsupportedError
import java.io.File
import kotlin.test.*

/**
 * Pins the DuckDB executable-boundary guards that run at the start of `Generator.generate`:
 * exact message text, the order messages collect in a preview, which one throws first under
 * `forExecution`, and the target/source directions each guard covers. Every case runs for the
 * base, postgres and duckdb generators, across source dialects (including an unknown and a
 * differently cased DuckDB source) and the plain, native-preview and execution modes.
 *
 * Regenerate after an intended change with `BRIKK_UPDATE_GUARD_GOLDEN=1` and review the diff.
 */
class DuckdbExecutableGuardsTest {
    private val golden = "generator-guards/duckdb-executable-guards.tsv"

    private val sqls = listOf(
        "SELECT 7//2",
        "SELECT 1/0",
        "SELECT SAFE_DIVIDE(1,2)",
        "SELECT INTERVAL '1.5 days'",
        "SELECT json_extract('{}','\$.x','\$.y')",
        "SELECT date_diff('month',DATE '2026-01-31',DATE '2026-02-01')",
        "CREATE TABLE t AS SELECT NULL AS x",
        "SELECT TIMESTAMP '2024-01-01' AT TIME ZONE 'UTC'",
        "SELECT 'A' COLLATE nocase='a'",
        "CHECKPOINT main",
        "FORCE CHECKPOINT",
        "WITH RECURSIVE t(x) USING KEY(x) AS (SELECT 1 UNION SELECT x+1 FROM t WHERE x<2) SELECT * FROM t",
        "INSTALL AND LOAD x FROM community",
        "CREATE EXTERNAL RESOURCE 'fixture@local' AS r",
        "EXPLAIN(FORMAT JSON) SELECT 1",
        "SHOW DUCKDB_NAME",
        "SHOW threads",
        "SELECT 3!",
        "ATTACH 'never-opened.db' AS db (STORAGE_VERSION 'v1.5.0')",
        "SELECT * FROM t AT(VERSION=>3)",
        "SELECT * FROM read_parquet('never-read',schema:=MAP {1:{name:'x',type:'INTEGER',default_value:0}})",
        "COPY (SELECT 42 AS x) TO 'none.parquet' (FORMAT parquet,DATA_PAGE_SIZE_LIMIT 'not-bytes',SHREDDING 'auto')",
        "COPY t TO 'never-written.geojson' (FORMAT GEOJSON)",
        "SELECT regexp_extract('ABC','abc','i')",
        "SELECT regexp_matches('Ab','a','i')",
        "SELECT setval('s',4)",
        "COPY t TO 'never-written' (PARTITION_BY(x))",
        "SELECT path_join('a','b')",
        "SELECT overlay('abcdef','xy',2,3)",
        "SELECT overlay('abc','X')",
        "CREATE SECURE VIEW v AS SELECT 1",
        "CREATE RECURSIVE VIEW v(x) AS SELECT 1 UNION SELECT x+1 FROM v WHERE x<2",
        "CREATE TRIGGER tr AFTER INSERT ON t INSERT INTO log VALUES(1)",
        "CREATE TEMP TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(1,2)",
        "SELECT \$threshold",
        "SET VARIABLE x=1",
        "SELECT CAST(1 AS VARIANT)",
        "SELECT json_set('{}','\$.x','2')",
        "SELECT unnest([1,2]) AS x,count(*) FROM range(1) GROUP BY x",
        "SELECT 1 AS x",
        // Several guards in one statement: pins their relative order.
        "SELECT overlay('abc','X'), json_set('{}','\$.x','2'), CAST(1 AS VARIANT), 3!, setval('s',4), path_join('a','b') FROM t AT(VERSION=>3) GROUP BY unnest([1])",
        "CREATE SECURE RECURSIVE VIEW v(x) AS SELECT 1 UNION SELECT x+1 FROM v WHERE x<2",
    )

    private fun outcomes(): String = buildString {
        for (sql in sqls) for (target in listOf("postgres", "sqlglot", "duckdb"))
            for (source in listOf("duckdb", "DuckDB", null, "", "postgres")) for (mode in listOf("plain", "preview", "exec")) {
                val generator = Dialects.forName(target).generator(sourceDialect = source)
                if (mode == "preview") generator.duckdbNativeSemantics = true
                if (mode == "exec") generator.forExecution = true
                val outcome = try {
                    val out = generator.generate(parseDuckdbNativeOne(sql))
                    "ok ${generator.unsupportedMessages} $out"
                } catch (e: UnsupportedError) {
                    "throw ${e.message}"
                } catch (e: Exception) {
                    "error ${e::class.simpleName} ${e.message}"
                }
                append("$sql\t$target\t$source\t$mode\t$outcome\n")
            }
        for (sql in listOf("SELECT \$threshold", "SELECT 1 AS x")) for (defaults in listOf(true, false)) {
            val generator = Dialects.forName("postgres").generator(sourceDialect = "duckdb")
            generator.duckdbSessionDefaults = defaults
            generator.generate(parseDuckdbNativeOne(sql))
            append("$sql\tdefaults=$defaults\t${generator.unsupportedMessages}\n")
        }
    }

    @Test
    fun guardMessagesOrderAndDirectionsMatchTheGolden() {
        val actual = outcomes()
        val file = File(testResourcesRoot(), golden)
        if (System.getenv("BRIKK_UPDATE_GUARD_GOLDEN") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        val expected = file.readText()
        val diff = expected.lines().zip(actual.lines()).filter { (e, a) -> e != a }
            .take(10).joinToString("\n") { (e, a) -> "expected: $e\n  actual: $a" }
        assertEquals(expected.lines().size, actual.lines().size, "case count changed\n$diff")
        assertTrue(diff.isEmpty(), diff)
    }
}
