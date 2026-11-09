package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class PostgresMappingCatchupTest {
    @Test fun namedIntervalsUseTheirUnitsAndAnonymousCallsStayAnonymous() {
        val tree = assertIs<MakeInterval>(parseOne("MAKE_INTERVAL(days => 3)", "postgres"))
        assertNull(tree.args["year"]); assertIs<Kwarg>(tree.args["day"])
        assertEquals("INTERVAL '3 day'", tree.sql("duckdb"))
        val mixed = parseOne("MAKE_INTERVAL(1, hours => 2, mins => 30)", "postgres")
        assertEquals("INTERVAL '1 year 2 hour 30 minute'", mixed.sql("duckdb"))
        val before = Serde.dump(mixed)
        Dialects.DUCKDB.generator().generate(mixed, copy = false)
        assertEquals(before, Serde.dump(mixed))
        assertEquals(mixed, Serde.loadExpression(before))
        val anonymous = parseOne("MAKE_INTERVAL(days => 3) /* sqlglot.anonymous */", "postgres")
        assertIs<Anonymous>(anonymous); assertContains(anonymous.sql("duckdb"), "MAKE_INTERVAL")
    }
    @Test fun boundaryDiffFlagsExcludeWeeksAndCrossEngineRefusalsRemain() {
        for (unit in listOf("second", "hour", "day", "month", "quarter", "year")) {
            val sql = "DATE_DIFF('$unit', a, b)"
            val tree = assertIs<DateDiff>(parseOne(sql, "duckdb"))
            assertEquals(true, tree.args["date_part_boundary"])
            val output = tree.sql("postgres")
            val before = Serde.dump(tree)
            val parents = tree.walk().map { it to it.parent }.toList()
            Dialects.POSTGRES.generator().generate(tree, copy = false)
            assertEquals(before, Serde.dump(tree))
            for ((node, parent) in parents) assertSame(parent, node.parent)
            if (unit != "day") assertContains(output, "DATE_TRUNC")
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("postgres") }
        }
        for (unit in listOf("week", "weeks", "w")) {
            assertNull(parseOne("DATE_DIFF('$unit', a, b)", "duckdb").args["date_part_boundary"])
        }
    }
    @Test fun isoWeekAndYearAreNotCalendarYearOrNonIsoWeeks() {
        assertEquals("SELECT STRFTIME(x, '%G-%V')", transpile("SELECT TO_CHAR(x, 'iyyy-iw')", "postgres", "duckdb"))
        assertEquals("SELECT TO_CHAR(x, 'IYYY-IW')", transpile("SELECT STRFTIME(x, '%G-%V')", "duckdb", "postgres"))
    }
    @Test fun shaDigestAndHexHaveDifferentCanonicalKindsAndOutputs() {
        for (size in listOf(256, 384, 512)) assertIs<SHA2Digest>(parseOne("SHA$size(x)", "postgres"))
        assertEquals("ENCODE(SHA256(x), 'hex')", parseOne("SHA256(x)", "duckdb").sql("postgres"))
        assertEquals("UNHEX(SHA256(x))", parseOne("SHA256(x)", "postgres").sql("duckdb"))
        for (size in listOf(384, 512)) assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT SHA$size('abc')", "postgres").toExecutable("duckdb")
        }
    }
    @Test fun regexCaseFlagsUseLastCaseFlagAndRejectUnsupportedOrDynamicFlags() {
        for ((flag, op) in listOf("i" to "~*", "c" to "~", "ci" to "~*", "ic" to "~")) {
            val tree = RegexpLike(args("this" to Literal.string("ABC"), "expression" to Literal.string("abc"), "flag" to Literal.string(flag)))
            assertEquals("'ABC' $op 'abc'", tree.sql("postgres"))
        }
        for (flag in listOf<Expression>(Literal.string("m"), Column(args("this" to Identifier(args("this" to "flags")))))) {
            val tree = RegexpLike(args("this" to Literal.string("ABC"), "expression" to Literal.string("abc"), "flag" to flag))
            assertFailsWith<UnsupportedError> { Dialects.POSTGRES.generator().apply { forExecution = true }.generate(tree) }
        }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT REGEXP_MATCHES('ABC','abc','i')", "duckdb").toExecutable("postgres") }
    }
}
