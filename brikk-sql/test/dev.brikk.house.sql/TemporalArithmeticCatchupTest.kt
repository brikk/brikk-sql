package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

/** SG29-009: #8815/#8809. Rendering grouping is separate from native type acceptance. */
class TemporalArithmeticCatchupTest {
    @Test fun timestampaddAndSqlTsiUnitsAreCanonicalWithoutChangingTimestampdiff() {
        for (dialect in listOf("mysql", "doris", "starrocks")) {
            for (unit in listOf("SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR")) {
                val normal = parseOne("TIMESTAMPADD($unit, 1, '2024-01-01')", dialect)
                val prefixed = parseOne("TIMESTAMPADD(sql_tsi_${unit.lowercase()}, 1, '2024-01-01')", dialect)
                assertIs<TimestampAdd>(prefixed)
                // The aliases have different authored source offsets, not different meaning.
                assertEquals(normal, prefixed, "$dialect/$unit")
                assertEquals(unit, (prefixed.args["unit"] as Expression).name)
                assertEquals(prefixed, Serde.loadExpression(Serde.dump(prefixed)))
            }
        }
        assertIs<TimestampDiff>(parseOne("TIMESTAMPDIFF(DAY, a, b)", "mysql"))
    }

    @Test fun loweredRightOperandsAndUnaryOrZoneParentsRetainTheirGroups() {
        for (target in listOf("postgres", "duckdb")) {
            val delta = "DATE_ADD(x, INTERVAL 1 DAY)"
            val subtraction = transpile("SELECT t - $delta", "mysql", target)
            val root = (parseOne(subtraction, target).expressionsArg.single() as Expression)
            assertIs<Sub>(root)
            assertIs<Paren>(root.expressionArg, subtraction)
            assertIs<Add>((root.expressionArg as Expression).unnest())
            for (sql in listOf("SELECT -$delta", "SELECT $delta AT TIME ZONE 'UTC'", "SELECT t + $delta", "SELECT $delta * 2")) {
                val output = transpile(sql, "mysql", target)
                assertContains(output, "(", message = output)
                parseOne(output, target)
            }
            val explicit = transpile("SELECT t - ($delta)", "mysql", target)
            assertFalse("((x +" in explicit, explicit)
        }
    }

    @Test fun dynamicCountsAndNestedDateArgumentsAreGroupedAndNotMutated() {
        for (target in listOf("postgres", "duckdb")) {
            for (sql in listOf("SELECT t - DATE_ADD(x, INTERVAL n + 1 DAY)",
                "SELECT DATE_ADD(DATE_SUB(x, INTERVAL n DAY), INTERVAL m DAY)",
                "SELECT TIMESTAMPADD(SQL_TSI_DAY, n + 1, x)")) {
                val tree = parseOne(sql, "mysql")
                val before = Serde.dump(tree)
                val parents = tree.walk().map { it to it.parent }.toList()
                val output = Dialects.forName(target).generator(sourceDialect = "mysql").generate(tree, copy = false)
                assertEquals(before, Serde.dump(tree), output)
                for ((node, parent) in parents) assertSame(parent, node.parent, output)
                assertEquals(output, Serde.loadExpression(before).sql(target))
                parseOne(output, target)
            }
        }
        assertContains(transpile("SELECT DATE_ADD(x, INTERVAL n + 1 DAY)", "mysql", "postgres"), "(n + 1)")
    }

    @Test fun postgresSupportsAllCanonicalDateDatetimeAndTimestampDeltaClasses() {
        for (factory in listOf<(Args) -> Expression>(::DateAdd, ::DateSub, ::DatetimeAdd, ::DatetimeSub, ::TimestampAdd, ::TimestampSub)) {
            val tree = factory(args("this" to Column(args("this" to Identifier(args("this" to "x")))),
                "expression" to Neg(args("this" to Literal.number("2"))), "unit" to Var(args("this" to "SECOND"))))
            val before = Serde.dump(tree)
            val output = Dialects.POSTGRES.generator().generate(tree, copy = false)
            assertContains(output, "INTERVAL '-2 SECOND'")
            assertEquals(before, Serde.dump(tree))
        }
        val literal = transpile("SELECT TIMESTAMPADD(SQL_TSI_SECOND, 30, '2024-01-01 12:00:00')", "mysql", "duckdb")
        assertContains(literal, "AS TIMESTAMP")
        assertFalse("AS DATE)" in literal, literal)
    }

    @Test fun explicitIntervalsAndTsOrDsCastsDoNotReparentTheInput() {
        for (target in listOf("postgres", "duckdb")) {
            val delta = TsOrDsAdd(args("this" to Literal.string("2024-01-01 12:00:00"),
                "expression" to Literal.number("1"), "unit" to Var(args("this" to "DAY"))))
            val root = Sub(args("this" to Column(args("this" to Identifier(args("this" to "t")))), "expression" to delta))
            val before = Serde.dump(root)
            Dialects.forName(target).generator().generate(root, copy = false)
            assertEquals(before, Serde.dump(root))
            assertSame(root, delta.parent)
            val withInterval = TimestampAdd(args("this" to Column(args("this" to Identifier(args("this" to "x")))),
                "expression" to Interval(args("this" to Literal.number("1"), "unit" to Var(args("this" to "DAY"))))))
            val intervalBefore = Serde.dump(withInterval)
            Dialects.forName(target).generator().generate(withInterval, copy = false)
            assertEquals(intervalBefore, Serde.dump(withInterval))
        }
    }

    @Test fun existingDuckdbTemporalAndFractionalExecutionRefusalsRemain() {
        for (sql in listOf("SELECT DATE_DIFF('day', a, b)", "SELECT DATE_TRUNC('day', x)")) {
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("postgres") }
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT x + INTERVAL '1.5' DAY", "mysql").toExecutable("duckdb")
        }
    }

    @Test fun sparkSharedIntervalLoweringKeepsRightOperandGroupingWithoutAddingCasts() {
        for (factory in listOf<(Args) -> Expression>(::DatetimeAdd, ::DatetimeSub, ::TimestampSub)) {
            val delta = factory(args("this" to Column(args("this" to Identifier(args("this" to "x")))),
                "expression" to Literal.number("1"), "unit" to Var(args("this" to "DAY"))))
            val root = Sub(args("this" to Column(args("this" to Identifier(args("this" to "t")))), "expression" to delta))
            val before = Serde.dump(root)
            val output = Dialects.SPARK.generator().generate(root, copy = false)
            assertIs<Paren>(assertIs<Sub>(parseOne(output, "spark")).expressionArg, output)
            assertFalse("CAST" in output, output)
            assertEquals(before, Serde.dump(root))
        }
    }

    @Test fun typedFloatPreparationAndEmittedSourcePositionsLeaveTheInputIntact() {
        val count = Literal.number("1.5").also { it.typeSlot = DataType.build(DType.DOUBLE) }
        val delta = TimestampAdd(args("this" to Column(args("this" to Identifier(args("this" to "x")))),
            "expression" to count, "unit" to Var(args("this" to "DAY"))))
        val before = Serde.dump(delta)
        Dialects.DUCKDB.generator().generate(delta, copy = false)
        assertEquals(before, Serde.dump(delta))
        assertSame(delta, count.parent)
        val tree = parseOne("SELECT t - DATE_ADD(`😀x`, INTERVAL 1 DAY)", "mysql")
        val owned = Serde.dump(tree)
        val column = tree.findAll<Identifier>().first { it.name == "😀x" }
        val generator = Dialects.POSTGRES.generator(sourceDialect = "mysql").apply { trackSpans = true }
        val output = generator.generate(tree, copy = false)
        val position = output.indexOf("\"😀x\"")
        assertTrue(position >= 0, output)
        assertEquals(assertNotNull(SourceMap.sourcePosOf(column)).start,
            assertNotNull(assertNotNull(generator.lastSourceMap).sourcePosition(position)).start)
        assertEquals(owned, Serde.dump(tree))
    }
}
