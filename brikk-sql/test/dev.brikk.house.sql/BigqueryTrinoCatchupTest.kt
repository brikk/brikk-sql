package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class BigqueryTrinoCatchupTest {
    @Test fun datePartsUseExtractAndKeepWeekdayContracts() {
        for ((fn, part) in mapOf("YEAR" to "YEAR", "MONTH" to "MONTH", "QUARTER" to "QUARTER", "HOUR" to "HOUR",
            "DAY" to "DAY", "DAY_OF_MONTH" to "DAY", "DAY_OF_YEAR" to "DAYOFYEAR", "WEEK_OF_YEAR" to "ISOWEEK")) {
            assertEquals("EXTRACT($part FROM x)", parseOne("$fn(x)", "trino").sql("bigquery"))
        }
        assertEquals("(MOD(EXTRACT(DAYOFWEEK FROM x) + 5, 7) + 1) * 2", parseOne("DAY_OF_WEEK(x) * 2", "trino").sql("bigquery"))
        assertEquals("EXTRACT(DAYOFWEEK FROM CAST(x AS DATE))", parseOne("DAYOFWEEK(x)", "mysql").sql("bigquery"))
        // Independent ISO calendar control, including a year boundary and all weekdays.
        for (day in 0..6) {
            val date = java.time.LocalDate.of(2021, 1, 3).plusDays(day.toLong())
            val sundayBased = date.dayOfWeek.value % 7 + 1
            assertEquals(date.dayOfWeek.value, (sundayBased + 5) % 7 + 1)
        }
    }
    @Test fun weightedTrinoIsNotPrestoAccuracy() {
        val trino = assertIs<ApproxQuantile>(parseOne("APPROX_PERCENTILE(x, w, 0.5)", "trino"))
        assertEquals("w", (trino.args["weight"] as Expression).name)
        assertNull(trino.args["accuracy"])
        assertEquals(trino, Serde.loadExpression(Serde.dump(trino)))
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT APPROX_PERCENTILE(x, w, 0.5) FROM t", "trino").toExecutable("duckdb") }
        val presto = parseOne("APPROX_PERCENTILE(x, 0.5, 0.01)", "presto")
        assertNull(presto.args["weight"])
        assertNotNull(presto.args["accuracy"])
        assertContains(parseOne("APPROX_PERCENTILE(x, 1, 0.95)", "trino").sql("bigquery"), "APPROX_QUANTILES(x, 20)[OFFSET(19)]")
        for (q in listOf("q", "-0.5", "1.5", "0.000001", "1e-1000000", "1e1000000000", "0.50000000000000000000000000001")) {
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT APPROX_PERCENTILE(x, $q) FROM t", "trino").toExecutable("bigquery") }
        }
        for (sql in listOf("APPROX_PERCENTILE(x, 0.5)", "APPROX_PERCENTILE(x, 2, 0.5)", "APPROX_PERCENTILE(x, 0.5) OVER ()")) {
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT $sql FROM t", "trino").toExecutable("bigquery") }
        }
    }
    @Test fun decimalWideningAndPreviewGenerationDoNotMutateAst() {
        for ((type, expected) in mapOf("DECIMAL(30,0)" to "BIGNUMERIC", "DECIMAL(29,0)" to "NUMERIC", "DECIMAL(20,10)" to "BIGNUMERIC", "DECIMAL(38)" to "BIGNUMERIC")) {
            val tree = parseOne("CAST(x AS $type)", "trino")
            val before = Serde.dump(tree)
            assertEquals("CAST(x AS $expected)", Dialects.BIGQUERY.generator().generate(tree, copy = false))
            assertEquals(before, Serde.dump(tree))
        }
        assertContains(parseOne("FROM_BASE('-ff', 16)", "trino").sql("bigquery"), "REGEXP_REPLACE")
        for (expr in listOf("FROM_BASE('-ff',16)", "FROM_BASE('10',2)", "ARRAY_SORT(ARRAY_AGG(x))", "ARRAY_DISTINCT(ARRAY_AGG(x))", "ARRAY_SORT(x)")) {
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT $expr FROM t", "trino").toExecutable("bigquery") }
        }
    }
}
