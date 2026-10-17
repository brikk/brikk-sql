package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the ClickHouse dialect wiring, each verified against the Python
 * oracle (reference/sqlglot v30.12.0-44-g93d16591): Nullable type wrapping, {name: Type}
 * query parameters, aggregate-function combinators with parameters, the
 * FINAL/PREWHERE/SETTINGS/FORMAT clauses, the `?:` ternary, TRY_CAST-to-Nullable
 * transpilation and ARRAY JOIN.
 */
class ClickhouseDialectTest {

    private fun roundTrip(sqlText: String): String =
        parseOne(sqlText, "clickhouse").sql("clickhouse")

    @Test
    fun textBecomesNullableString() {
        // datatype_sql wraps unmarked types in Nullable(...) and maps TEXT -> String
        assertEquals(
            "SELECT CAST(x AS Nullable(String))",
            transpile("SELECT CAST(x AS TEXT)", read = "", write = "clickhouse"),
        )
    }

    @Test
    fun queryParametersRoundTrip() {
        // PLACEHOLDER_PARSERS[L_BRACE] -> _parse_query_parameter / placeholder_sql
        assertEquals(
            "SELECT {abc: UInt32}, {b: String}",
            roundTrip("SELECT {abc: UInt32}, {b: String}"),
        )
    }

    @Test
    fun aggregateCombinatorsWithParams() {
        // _resolve_clickhouse_agg + _parse_func_params -> CombinedParameterizedAgg
        assertEquals(
            "SELECT quantileIf(0.5)(a, b > 0) FROM t",
            roundTrip("SELECT quantileIf(0.5)(a, b > 0) FROM t"),
        )
    }

    @Test
    fun finalPrewhereSettingsFormatRoundTrip() {
        assertEquals(
            "SELECT * FROM t FINAL PREWHERE x > 1 WHERE y = 2 SETTINGS max_threads = 1 FORMAT JSON",
            roundTrip(
                "SELECT * FROM t FINAL PREWHERE x > 1 WHERE y = 2 SETTINGS max_threads = 1 FORMAT JSON"
            ),
        )
    }

    @Test
    fun ternaryBecomesCase() {
        // _parse_assignment: `cond ? a : b` -> exp.If -> CASE WHEN
        assertEquals(
            "SELECT CASE WHEN cond THEN a ELSE b END FROM t",
            roundTrip("SELECT cond ? a : b FROM t"),
        )
    }

    @Test
    fun tryCastBecomesNullableCast() {
        // trycast_sql: casting into Nullable(T) behaves like TRY_CAST(x AS T)
        assertEquals(
            "SELECT CAST(x AS Nullable(Int32))",
            transpile("SELECT TRY_CAST(x AS Int32)", read = "duckdb", write = "clickhouse"),
        )
    }

    @Test
    fun arrayJoinRoundTrips() {
        // _parse_join: ARRAY JOIN operands become Column references, not Tables
        assertEquals(
            "SELECT x FROM t ARRAY JOIN arr AS a",
            roundTrip("SELECT x FROM t ARRAY JOIN arr AS a"),
        )
    }

    @Test
    fun viewTableFunctionRoundTrips() {
        assertEquals("SELECT x FROM view(SELECT 1 AS x)", roundTrip("SELECT x FROM view(SELECT 1 AS x)"))
    }

    @Test
    fun refreshableMaterializedViews() {
        // sqlglot #7990: AutoRefreshProperty (REFRESH EVERY/AFTER/OFFSET/RANDOMIZE/DEPENDS/
        // SETTINGS/APPEND) with bare (keyword-less) schedule intervals. Verified against the
        // Python oracle — each round-trips identically.
        for (sql in listOf(
            "CREATE MATERIALIZED VIEW v REFRESH EVERY 30 SECOND AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH AFTER 5 MINUTE AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH DEPENDS ON db.x, db.y APPEND TO db.t AS SELECT 1",
            "CREATE MATERIALIZED VIEW v ON CLUSTER '{cluster}' REFRESH EVERY 1 MONTH OFFSET 5 DAY 2 HOUR " +
                "RANDOMIZE FOR 1 HOUR DEPENDS ON db.x, db.y SETTINGS refresh_retries = 5 APPEND TO db.t AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH EVERY 1 HOUR (id UInt64) AS SELECT 1 AS id",
            "CREATE MATERIALIZED VIEW v REFRESH DEPENDS ON dep (id UInt64) AS SELECT 1",
        )) {
            assertEquals(sql, roundTrip(sql))
        }
    }

    /**
     * ClickHouse ParserInsertQuery: `INSERT INTO [TABLE] FUNCTION tf(...) [PARTITION BY expr [AS alias]]
     * [(columns)] [SETTINGS ...] VALUES | FORMAT | SELECT`; the column list follows the partition.
     */
    @Test
    fun insertIntoFunctionTakesAPartitionThenAColumnList() {
        for (sql in listOf(
            "INSERT INTO FUNCTION null('id UInt64') (id) SELECT 1",
            "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY toYYYYMM(ts) (id, ts) SELECT id, ts FROM events",
            "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY a % 10 (a, b) SELECT 1, 2",
            "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY cityHash64(a) AS p (a, b) SELECT 1, 2",
            "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY a % 10 (a) SETTINGS s3_truncate_on_insert = 1 SELECT 1",
            "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY a SELECT 1",
            "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY a % 10 SETTINGS s3_truncate_on_insert = 1 SELECT 1",
        )) assertEquals(sql, roundTrip(sql), sql)
        assertEquals("INSERT INTO FUNCTION remote('h', db.t) (a, b) SELECT 1, 2", roundTrip("INSERT INTO TABLE FUNCTION remote('h', db.t) (a, b) SELECT 1, 2"))
        // A subquery in parentheses is the data, not a column list.
        assertEquals(
            "INSERT INTO FUNCTION null('id UInt64') PARTITION BY a % 10 (SELECT 1)",
            roundTrip("INSERT INTO FUNCTION null('id UInt64') PARTITION BY a % 10 (SELECT 1)"),
        )
        // The plugin's IDE bind shape: :prefix is read as a parameter, and the columns stay columns.
        val bound = parseOne(
            "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY concat(:prefix, toString(ts)) (id, ts) SELECT id, ts FROM events",
            "clickhouse",
        )
        assertEquals(listOf("id", "ts"), (bound.args["this"] as dev.brikk.house.sql.ast.Schema).expressionsArg.map { (it as dev.brikk.house.sql.ast.Expression).sql("clickhouse") })
    }

    /** Table functions stand wherever a table does: joined with an alias, ON or USING, beside ARRAY JOIN, and dictionary(...) too. */
    @Test
    fun tableFunctionsAreJoinOperands() {
        for (sql in listOf(
            "SELECT * FROM db1.one_column AS a CROSS JOIN numbers(3) AS t",
            "SELECT * FROM db1.one_column AS a JOIN numbers(3) AS t ON a.x = t.number",
            "SELECT * FROM db1.one_column AS a JOIN numbers(3) AS t USING (number)",
            "SELECT * FROM db1.one_column AS a LEFT JOIN s3('https://x', 'TSV', 'a UInt64') AS t ON a.x = t.a",
            "SELECT * FROM db1.one_column AS a ARRAY JOIN arr AS e CROSS JOIN numbers(3) AS t",
            "SELECT * FROM dictionary('db1.dictionary_demo')",
            "SELECT * FROM dictionary('db1.dictionary_demo') AS t",
            "SELECT * FROM db1.one_column AS a CROSS JOIN dictionary('db1.d') AS t",
        )) assertEquals(sql, roundTrip(sql), sql)
    }

    /** A table function's argument may be a query: view(...), and view(...) inside remote(...) or cluster(...). */
    @Test
    fun tableFunctionsTakeQueryArguments() {
        for (sql in listOf(
            "SELECT * FROM view(SELECT 1 AS field_a, 'sample' AS field_b)",
            "SELECT * FROM view(SELECT 1 AS field_a) AS t",
            "SELECT * FROM remote('h', view(SELECT 1 AS a))",
            "SELECT * FROM cluster('c', view(SELECT 1 AS field_a))",
            "SELECT * FROM viewExplain('EXPLAIN', '', (SELECT 1))",
        )) assertEquals(sql, roundTrip(sql), sql)
    }

    /** DESCRIBE [TABLE] takes a table function, never a column list: numbers (3) is a call, not a table with columns. */
    @Test
    fun describeTakesTableFunctions() {
        for ((sql, expected) in listOf(
            "DESCRIBE TABLE numbers(3)" to "DESCRIBE numbers(3)",
            "DESCRIBE TABLE format('TSV', 'a UInt64, b String', '1\\tsample')" to "DESCRIBE format('TSV', 'a UInt64, b String', '1\\tsample')",
            "DESCRIBE TABLE merge('db1', 'one_column')" to "DESCRIBE merge('db1', 'one_column')",
            "DESCRIBE TABLE view(SELECT 1 AS a)" to "DESCRIBE view(SELECT 1 AS a)",
            "DESCRIBE TABLE dictionary('db1.d')" to "DESCRIBE dictionary('db1.d')",
            "DESCRIBE TABLE s3('https://x', 'TSV', 'a UInt64')" to "DESCRIBE s3('https://x', 'TSV', 'a UInt64')",
            "DESCRIBE TABLE db1.one_column" to "DESCRIBE db1.one_column",
        )) {
            val once = roundTrip(sql)
            assertEquals(expected, once, sql)
            assertEquals(once, roundTrip(once), "stable: $sql")
        }
    }
}
