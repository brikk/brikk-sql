package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class Clickhouse2026SemanticsTest {
    private fun roundTrip(sql: kotlin.String): Expression {
        val ast = parseOne(sql, "clickhouse")
        val before = ast.copy()
        assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
        assertEquals(ast, Serde.load(Serde.dump(ast)), sql)
        assertEquals(before, ast)
        return ast
    }

    @Test
    fun nativeTryCastKeepsAccurateOverflowAndCompositeFailureSemantics() {
        for (sql in listOf("SELECT TRY_CAST(256 AS UInt8)", "SELECT TRY_CAST('[1,2]' AS Array(Int32))",
            "SELECT TRY_CAST('abc' AS INTEGER)")) {
            val ast = roundTrip(sql)
            assertNotNull(ast.find<TryCast>())
            assertTrue("TRY_CAST(" in ast.sql("clickhouse"))
            assertTrue("TRY_CAST(" in SqlFragment(sql, "clickhouse").toExecutable("clickhouse").sql)
        }
        assertNotNull(roundTrip("SELECT year()").find<ClickhouseYear>())
        assertNotNull(roundTrip("SELECT year(toDate('2026-01-01'))").find<Year>())
    }

    @Test
    fun integerDivisionWarningsDoNotInventUnknownSessionState() {
        val native = SqlFragment("SELECT CAST(-7 AS Int64) / CAST(2 AS Int64)", "clickhouse")
        assertTrue(native.toExecutable("clickhouse").unsupportedMessages.isEmpty())
        assertTrue(native.transpileTo("duckdb").unsupportedMessages.any { "sql_compatible_division_for_integers" in it && "unknown" in it })
        assertFalse(native.certify("duckdb").ok)
        val target = SqlFragment("SELECT 7 / 2", "duckdb").transpileTo("clickhouse")
        assertTrue(target.unsupportedMessages.any { "target" in it && "unknown" in it })
        val enabled = "SELECT CAST(-7 AS Int64) / CAST(2 AS Int64) SETTINGS sql_compatible_division_for_integers=1"
        assertFailsWith<UnsupportedError> { SqlFragment(enabled, "clickhouse").toExecutable("duckdb") }
        assertTrue(SqlFragment(enabled, "clickhouse").toExecutable("clickhouse").unsupportedMessages.isEmpty())
        val disabled = enabled.replace("integers=1", "integers=0")
        assertTrue(SqlFragment(disabled, "clickhouse").transpileTo("duckdb").unsupportedMessages.none { "sql_compatible_division" in it })
        assertTrue(SqlFragment("SELECT CAST(7 AS DOUBLE) / 2", "duckdb").transpileTo("clickhouse").unsupportedMessages.none { "sql_compatible_division" in it })
    }

    @Test
    fun settingEnabledDivisionDoesNotClaimTheHistoricalFloatType() {
        val sql = "SELECT CAST(-7 AS Int64) / CAST(2 AS Int64) SETTINGS sql_compatible_division_for_integers=1"
        val enabled = annotateTypes(roundTrip(sql), dialect = Dialects.CLICKHOUSE).find<Div>()!!
        assertEquals(DType.UNKNOWN, enabled.type?.thisArg)
        val disabled = annotateTypes(roundTrip(sql.replace("integers=1", "integers=0")), dialect = Dialects.CLICKHOUSE).find<Div>()!!
        assertEquals(DType.DOUBLE, disabled.type?.thisArg)
        val nested = "SELECT v FROM (SELECT 7 / 2 v SETTINGS sql_compatible_division_for_integers=0) SETTINGS sql_compatible_division_for_integers=1"
        assertEquals(DType.DOUBLE, annotateTypes(roundTrip(nested), dialect = Dialects.CLICKHOUSE).find<Div>()?.type?.thisArg)
    }

    @Test
    fun nullableTupleFieldsKeepTheirContainersNullability() {
        val ast = roundTrip("SELECT CAST(NULL AS Nullable(Tuple(x Int32))).x")
        val typed = annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
        val field = typed.find<Dot>()!!
        assertEquals(true, field.type?.args?.get("nullable"))
        assertEquals(false, typed.find<Cast>()?.let { it.args["to"] as DataType }?.expressionsArg
            ?.filterIsInstance<ColumnDef>()?.single()?.let { it.args["kind"] as DataType }?.args?.get("nullable"))
    }

    @Test
    fun postgresCardinalityCountsAllDimensionsWithoutChangingOtherArrayLengthMappings() {
        val postgres = SqlFragment("SELECT cardinality(ARRAY[[1,2],[3,4]])", "postgres").transpileTo("clickhouse")
        assertTrue("arrayFlattenedLength(" in postgres.sql, postgres.sql)
        assertTrue("arrayFlattenedLength(" in SqlFragment("SELECT cardinality(ARRAY[[1,2],[3,4]])", "PostgreSQL").transpileTo("clickhouse").sql)
        assertFalse("arrayFlattenedLength" in SqlFragment("SELECT custom.cardinality(ARRAY[1,2])", "postgres").transpileTo("clickhouse").sql)
        assertFalse(SqlFragment("SELECT cardinality(ARRAY[[1,2],[3,4]])", "postgres").certify("clickhouse").ok)
        val trino = SqlFragment("SELECT cardinality(ARRAY[ARRAY[1,2],ARRAY[3,4]])", "trino").transpileTo("clickhouse")
        assertFalse("arrayFlattenedLength" in trino.sql)
        assertTrue("LENGTH(" in trino.sql, trino.sql)
        val native = SqlFragment("SELECT cardinality([[1,2],[3,4]]),length([[1,2],[3,4]])", "clickhouse").transpileTo("clickhouse")
        assertFalse("arrayFlattenedLength" in native.sql)
    }

    @Test
    fun experimentalLateralRequiresExplicitEnablementDuringExecutableGeneration() {
        val sql = "SELECT a.number,b.n FROM numbers(2) a LEFT JOIN LATERAL (SELECT a.number+10 n) b ON true"
        assertFailsWith<UnsupportedError> { SqlFragment(sql, "clickhouse").toExecutable("clickhouse") }
        val enabled = sql + " SETTINGS allow_experimental_lateral_join=1"
        assertTrue("LATERAL" in SqlFragment(enabled, "clickhouse").toExecutable("clickhouse").sql)
        assertTrue(SqlFragment(sql, "clickhouse").transpileTo("clickhouse").unsupportedMessages.any { "experimental_lateral" in it })
    }

    @Test
    fun measuredNaturalJoinAndNativePipesKeepTheLoweringContract() {
        val sql = "FROM numbers(2) |> AS a |> NATURAL LEFT JOIN (SELECT 0 number,'R' r) b |> SELECT number,r |> ORDER BY number"
        assertTrue("NATURAL JOIN" in Dialects.CLICKHOUSE.pipeRunnableStages.orEmpty())
        val result = SqlFragment(sql, "clickhouse").toExecutable("clickhouse")
        assertFalse("|>" in result.sql)
        assertTrue("NATURAL LEFT JOIN" in result.sql)
        assertTrue(result.unsupportedMessages.isEmpty())
        assertTrue(SqlFragment(sql, "clickhouse").toSourcePreservingExecutable().unsupportedMessages.isEmpty())
    }

    @Test
    fun newRegressionAndMapFormsStayStructuredWithoutUnprovedReturnTypes() {
        for (sql in listOf("SELECT regr_slope(y,x),regr_count(y,x) FROM t",
            "SELECT sumMap(value,key),avgMap(value,key) FROM t", "SELECT sumMap(map('a',number)) FROM numbers(3)")) {
            val ast = roundTrip(sql)
            assertTrue(ast.walk().any { it is AggFunc })
            val typed = annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
            assertTrue(typed.walk().filter { it is AnonymousAggFunc || it is CombinedAggFunc }.all { it.type?.thisArg == DType.UNKNOWN })
        }
    }
}
