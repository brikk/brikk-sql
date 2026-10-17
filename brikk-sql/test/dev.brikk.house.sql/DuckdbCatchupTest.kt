package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.TimestampFromParts
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckdbCatchupTest {
    @Test
    fun timezoneTypesDropUnsupportedPrecisionWithoutLosingTheirTypeMapping() {
        for ((type, expected) in listOf(DType.TIMESTAMPLTZ to "TIMESTAMPTZ", DType.TIMESTAMPTZ to "TIMESTAMPTZ",
            DType.TIMETZ to "TIMETZ", DType.TIME to "TIME")) {
            for (precision in listOf<Int?>(null, 0, 3, 6, 9)) {
                val node = DataType(args("this" to type,
                    "expressions" to precision?.let { listOf(Literal.number(it)) }))
                val original = node.copy()
                assertEquals(expected, node.sql("duckdb"), "$type/$precision")
                assertEquals(original, node)
            }
        }
    }

    @Test
    fun zonedTimestampPartsKeepTheZoneAndFractionalComponents() {
        val timestamp = timestampParts()
        val original = timestamp.copy()
        assertEquals("MAKE_TIMESTAMP(2019, 7, 10, 2, 3, 4 + 123456000 / 1000000000.0) AT TIME ZONE 'America/Los_Angeles'",
            timestamp.sql("duckdb"))
        assertEquals(original, timestamp)
        timestamp.set("zone", null)
        assertTrue("AT TIME ZONE" !in timestamp.sql("duckdb"))
    }

    @Test
    fun samplePrecedesOrderingAndPaginationWithoutMovingTableLevelSampling() {
        val source = "SELECT a, COUNT(*) OVER w AS c FROM tbl WHERE a > 0 GROUP BY a HAVING COUNT(*) > 0 " +
            "WINDOW w AS (ORDER BY a) QUALIFY c > 0 USING SAMPLE SYSTEM (50 PERCENT) ORDER BY a LIMIT 3 OFFSET 1"
        assertEquals(source, Dialects.DUCKDB.parseOne(source).sql("duckdb"))
        val native = "SELECT * FROM t TABLESAMPLE RESERVOIR (2 ROWS) REPEATABLE (42) ORDER BY id LIMIT 1"
        val output = Dialects.DUCKDB.parseOne(native).sql("duckdb")
        assertTrue(output.indexOf("TABLESAMPLE") < output.indexOf("ORDER BY"), output)
        assertTrue(output.indexOf("ORDER BY") < output.indexOf("LIMIT"), output)
        val pipe = SqlFragment("FROM t |> TABLESAMPLE SYSTEM (100 PERCENT) |> ORDER BY id |> LIMIT 2", "duckdb")
            .toExecutable("duckdb", trackSourceMap = false)
        assertTrue(pipe.sql.indexOf("USING SAMPLE") < pipe.sql.indexOf("ORDER BY"), pipe.sql)
    }

    companion object {
        fun timestampParts(): TimestampFromParts = TimestampFromParts(args(
            "year" to Literal.number(2019), "month" to Literal.number(7), "day" to Literal.number(10),
            "hour" to Literal.number(2), "min" to Literal.number(3), "sec" to Literal.number(4),
            "nano" to Literal.number(123456000), "zone" to Literal.string("America/Los_Angeles"),
        ))
    }
}
