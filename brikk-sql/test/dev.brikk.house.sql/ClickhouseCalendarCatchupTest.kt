package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClickhouseCalendarCatchupTest {
    @Test
    fun isoFunctionsMapInBothDirectionsAndHaveCanonicalTypes() {
        for ((source, node, duckdb) in listOf(
            Triple("toDayOfWeek(d)", DayOfWeekIso::class, "ISODOW(d)"),
            Triple("toISOWeek(d)", WeekOfYear::class, "WEEKOFYEAR(d)"),
            Triple("toISOYear(d)", YearOfWeekIso::class, "EXTRACT(ISOYEAR FROM d)"),
        )) {
            val tree = Dialects.CLICKHOUSE.parseOne(source)
            assertEquals(node, tree::class)
            assertEquals(source, tree.sql("clickhouse"))
            assertEquals(duckdb, tree.sql("duckdb"))
            val input = if (node == YearOfWeekIso::class) Dialects.BASE.parseOne("YEAR_OF_WEEK_ISO(d)")
                else Dialects.DUCKDB.parseOne(duckdb)
            assertEquals(source, input.sql("clickhouse"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            annotateTypes(tree, dialect = Dialects.CLICKHOUSE)
            assertTrue(tree.isType(DType.TINYINT)) // Existing canonical metadata, not ClickHouse's native unsigned width.
        }
        for ((canonical, native) in listOf("DAY_OF_MONTH(d)" to "toDayOfMonth(d)", "DAY_OF_YEAR(d)" to "toDayOfYear(d)")) {
            assertEquals(native, Dialects.BASE.parseOne(canonical).sql("clickhouse"))
        }
    }

    @Test
    fun extraModeAndTimezoneArgumentsRemainNativeRatherThanBeingDiscarded() {
        for (source in listOf("toDayOfWeek(d, 3)", "toDayOfWeek(d, 0, 'UTC')", "toISOWeek(d, 'UTC')", "toISOYear(d, 'UTC')")) {
            val tree = Dialects.CLICKHOUSE.parseOne(source)
            assertIs<Anonymous>(tree)
            assertEquals(source, tree.sql("clickhouse"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
        }
    }
}
