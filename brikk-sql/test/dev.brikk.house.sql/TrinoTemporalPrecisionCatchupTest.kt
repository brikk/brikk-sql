package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class TrinoTemporalPrecisionCatchupTest {
    @Test
    fun literalTypesPreserveZeroThroughPicosecondPrecisionAndZones() {
        for ((kind, value) in listOf("TIMESTAMP" to "2020-01-01 00:00:00", "TIME" to "12:00:00")) {
            for (zone in listOf("", " +02:00")) for (precision in 0..12) {
                val fraction = if (precision == 0) "" else "." + "123456789012".take(precision)
                val source = "SELECT $kind '$value$fraction$zone'"
                val suffix = if (zone.isEmpty()) "" else " WITH TIME ZONE"
                val tree = Dialects.TRINO.parseOne(source)
                assertEquals("SELECT CAST('$value$fraction$zone' AS $kind($precision)$suffix)", tree.sql("trino"))
                assertEquals(tree, Serde.load(Serde.dump(tree)))
                annotateTypes(tree, dialect = Dialects.TRINO)
                assertEquals("$kind($precision)$suffix", tree.selects.single().type?.sql("trino"))
            }
        }
    }

    @Test
    fun explicitCastsAndTypedZoneLiteralsKeepTheirDeclaredPrecision() {
        for (kind in listOf("TIMESTAMP", "TIME")) for (precision in listOf("", "(0)", "(2)", "(6)")) {
            val value = if (kind == "TIME") "12:00:00.123" else "2020-01-01 00:00:00.123"
            val source = "SELECT CAST('$value' AS $kind$precision)"
            assertEquals(source, Dialects.TRINO.parseOne(source).sql("trino"))
        }
        assertEquals("SELECT CAST('12:00:00.1234 +02:00' AS TIME(6) WITH TIME ZONE)",
            Dialects.TRINO.parseOne("SELECT TIME(6) '12:00:00.1234 +02:00'").sql("trino"))
        assertEquals("SELECT CAST('2020-01-01' AS TIMESTAMP(0))",
            Dialects.TRINO.parseOne("SELECT TIMESTAMP '2020-01-01'").sql("trino"))
    }

    @Test
    fun prestoDropsOnlySupportedPrecisionAndDiagnosesLossWithoutMutatingSource() {
        for (kind in listOf("TIMESTAMP", "TIME")) for (zone in listOf("", " WITH TIME ZONE")) {
            for (precision in listOf("", "(0)", "(1)", "(2)", "(3)", "(6)")) {
                val tree = Dialects.TRINO.parseOne("CAST(x AS $kind$precision$zone)")
                val before = tree.copy()
                val generator = Dialects.PRESTO.generator()
                assertEquals("CAST(x AS $kind$zone)", generator.generate(tree))
                assertEquals(if (precision in listOf("", "(3)")) emptyList() else
                    listOf("Presto only supports millisecond precision for temporal types"), generator.unsupportedMessages)
                assertEquals(before, tree)
            }
        }
    }
}
