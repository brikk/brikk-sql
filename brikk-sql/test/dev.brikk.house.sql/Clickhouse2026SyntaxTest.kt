package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Clickhouse2026SyntaxTest {
    private fun roundTrip(sql: String): Expression {
        val ast = parseOne(sql, "clickhouse")
        val original = ast.copy()
        assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
        assertEquals(ast, Serde.load(Serde.dump(ast)), sql)
        assertEquals(original, ast)
        return ast
    }

    @Test
    fun limitBoundariesKeepConditionsCountsAndAllWithoutBecomingAliases() {
        for (clause in listOf(
            "LIMIT 3 AFTER number >= 5", "LIMIT UNTIL number >= 3",
            "LIMIT AFTER number >= 2 UNTIL number >= 6", "LIMIT 2 AFTER number IN (2, 6) ALL",
            "LIMIT 4 AFTER number > 1 ALL UNTIL number > 8", "LIMIT 3 UNTIL number > 5",
            "LIMIT 3 OFFSET 1 AFTER number > 2", "LIMIT 1, 3 AFTER number > 2",
            "LIMIT -2 AFTER number > 1", "LIMIT -2 WITH TIES AFTER number > 1",
            "LIMIT 2 WITH TIES AFTER number > 1", "LIMIT 2 BY number % 2 LIMIT 3 AFTER number > 1",
            "LIMIT 1 BY number % 2 LIMIT UNTIL number > 4",
            "LIMIT 1 BY number % 2 LIMIT 2 OFFSET 1", "LIMIT 3 AFTER (number >= 2 AND number < 8)",
        )) {
            val sql = "SELECT number FROM numbers(10) ORDER BY number $clause"
            val ast = roundTrip(sql)
            if ("AFTER" in clause || "UNTIL" in clause) assertNotNull(ast.find<ClickhouseLimitRange>(), sql)
        }
    }

    @Test
    fun limitBoundaryWordsRemainContextualCountNames() {
        for (word in listOf("after", "until", "`after`", "`until`")) {
            for (tail in listOf("", " BY number", " OFFSET 1")) {
                val sql = "WITH 2 AS $word SELECT number FROM numbers(4) LIMIT $word$tail"
                assertNull(roundTrip(sql).find<ClickhouseLimitRange>(), sql)
            }
        }
        assertIs<ClickhouseLimitRange>(roundTrip("SELECT 1 LIMIT after(2)").args["limit"])
        for (sql in listOf("SELECT 1 LIMIT 2 AFTER", "SELECT 1 LIMIT 2 AFTER x UNTIL", "SELECT 1 LIMIT 2 UNTIL")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
        }
    }

    @Test
    fun newLimitNodesAreNotTreatedAsComposableIntegerSlices() {
        for (sql in listOf(
            "FROM numbers(10) |> LIMIT 3 AFTER number > 2",
            "SELECT number FROM numbers(10) LIMIT 3 AFTER number > 2 |> LIMIT 1",
        )) assertFailsWith<UnsupportedError>(sql) { SqlFragment(sql, "clickhouse").toExecutable("clickhouse") }
    }

    @Test
    fun naturalJoinsKeepTheMethodInsteadOfBecomingGlobalOrLosingSources() {
        for (kind in listOf("", "INNER", "LEFT", "RIGHT", "FULL", "LEFT OUTER", "RIGHT OUTER", "FULL OUTER")) {
            val ast = roundTrip("SELECT * FROM a NATURAL $kind JOIN b")
            val join = ast.find<Join>()!!
            assertEquals("NATURAL", join.method)
            assertNull(join.args["global_"])
            assertEquals(listOf("a", "b"), buildScope(ast)?.sources?.keys?.toList())
        }
        assertTrue("GLOBAL NATURAL LEFT JOIN" in roundTrip("SELECT * FROM a GLOBAL NATURAL LEFT JOIN b").sql("clickhouse"))
        for (sql in listOf("SELECT * FROM a NATURAL ALL LEFT JOIN b", "SELECT * FROM a NATURAL CROSS JOIN b",
            "SELECT * FROM a NATURAL OUTER JOIN b", "SELECT * FROM a NATURAL LEFT INNER JOIN b",
            "SELECT * FROM a NATURAL JOIN b ON a.x = b.x", "SELECT * FROM a NATURAL JOIN b USING (x)")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
        }
    }

    @Test
    fun naturalJoinScopeDeduplicatesSharedKeysAndRetainsPhysicalDeclarations() {
        val facts = RelationFacts(tables = mapOf(
            "a" to RelationFact(listOf(ColumnFact("id", "UInt32"), ColumnFact("left_value", "String"))),
            "b" to RelationFact(listOf(ColumnFact("id", "UInt32"), ColumnFact("right_value", "String"))),
        ), catalog = CatalogKnowledge.CLOSED)
        for (kind in listOf("INNER", "LEFT", "RIGHT", "FULL")) {
            val sql = "SELECT * FROM a AS l NATURAL $kind JOIN b AS r"
            val scope = RelationScope.analyze(sql, "clickhouse", facts)
            assertTrue(scope.parsed)
            assertEquals(emptyList(), scope.diagnostics)
            assertEquals(listOf("id", "left_value", "right_value"), scope.output?.columns?.map { it.name })
        }
    }

    @Test
    fun localTimeAndCompoundIntervalsStayNativeAndInspectable() {
        assertNotNull(roundTrip("SELECT dt AT LOCAL AT TIME ZONE 'UTC' FROM t").find<ClickhouseAtLocal>())
        for ((value, qualifier) in listOf("1-2" to "YEAR TO MONTH", "1 02:03:04" to "DAY TO SECOND",
            "1:30" to "HOUR TO MINUTE", "1:30:02.5" to "HOUR TO SECOND", "30:02" to "MINUTE TO SECOND")) {
            val ast = roundTrip("SELECT INTERVAL '$value' $qualifier")
            assertNotNull(ast.find<ClickhouseCompoundInterval>())
        }
        for (sql in listOf("SELECT INTERVAL '1' SECOND TO HOUR", "SELECT INTERVAL 1 HOUR TO MINUTE", "SELECT INTERVAL '1' HOUR TO bogus")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
        }
        assertIs<Alias>(roundTrip("SELECT INTERVAL '1' HOUR TO").expressionsArg.single())
        assertNull(roundTrip("SELECT x FROM t ORDER BY x WITH FILL FROM 0 + INTERVAL '1' HOUR TO 10 STEP 1")
            .find<ClickhouseCompoundInterval>())
        assertNull(roundTrip("CREATE TABLE t (d DateTime) ENGINE = MergeTree ORDER BY d TTL d + INTERVAL 1 WEEK TO VOLUME 'cold'")
            .find<ClickhouseCompoundInterval>())
    }

    @Test
    fun tableFunctionSettingsStayInsideTheCallAndKeepParameterRanges() {
        for (name in listOf("remote", "remoteSecure", "cluster", "clusterAllReplicas")) {
            val sql = "SELECT * FROM $name('target', db.t, SETTINGS skip_unavailable_shards = 1, max_threads = {n:UInt64}) SETTINGS max_threads = 2"
            val ast = roundTrip(sql)
            val call = ast.find<Anonymous>()!!
            assertEquals(name, call.name)
            assertIs<ClickhouseFunctionSettings>(call.expressionsArg.last())
            assertNotNull(ast.args["settings"])
            val params = SqlFragment(sql, "clickhouse").scalarParams
            assertTrue(params.any { it.name == "n" })
            assertEquals(sql, SqlFragment(sql, "clickhouse").toSourcePreservingExecutable().sql)
        }
        roundTrip("SELECT * FROM remote('x', db.t, SETTINGS)") // a positional identifier, not an empty clause
        roundTrip("SELECT * FROM remote('x', db.t, SETTINGS skip_unavailable_shards)")
        for (sql in listOf("SELECT * FROM remote('x', db.t, SETTINGS max_threads =)",
            "SELECT * FROM remote('x', db.t, SETTINGS max_threads = 1", "SELECT * FROM remote('x', db.t,)")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
        }
    }

    @Test
    fun qbitAndMultipointDoNotBecomeGenericArraysOrUserDefinedText() {
        for (element in listOf("Int8", "BFloat16", "Float32", "Float64")) {
            for (tail in listOf("128", "128, 32")) {
                val sql = "CREATE TABLE t (v QBit($element, $tail), g MultiPoint, a Array(MultiPoint)) ENGINE = Memory"
                val ast = roundTrip(sql)
                assertEquals(3, ast.findAll<ClickhouseNativeType>().count())
            }
        }
        roundTrip("SELECT CAST([1, 2] AS QBit(Float32, 2))")
        roundTrip("SELECT {v:QBit(Float32,128,32)}")
        for (type in listOf("QBit", "QBit(Float32)", "QBit(String, 8)", "QBit(Float32, 0)",
            "QBit(Float32, 8.5)", "QBit(Float32, 16, 3)", "MultiPoint(Int32)")) {
            assertFailsWith<ParseError>(type) { parseOne("CREATE TABLE t (v $type) ENGINE = Memory", "clickhouse") }
        }
    }

    @Test
    fun zeroArgumentYearIsOnlySyntaxSupportOnThePinnedEngines() {
        assertIs<ClickhouseYear>(roundTrip("SELECT year()").expressionsArg.single())
        assertTrue("year()" in SqlFragment("SELECT year()", "clickhouse").toExecutable("clickhouse").sql)
        assertNotNull(roundTrip("SELECT year(toDate('2026-01-01'))").find<Year>())
        assertEquals("SELECT year(toDateTime('2026-01-01 00:30:00', 'UTC'), 'America/New_York')",
            roundTrip("SELECT year(toDateTime('2026-01-01 00:30:00', 'UTC'), 'America/New_York')").sql("clickhouse"))
        assertFailsWith<ParseError> { parseOne("SELECT year(1, 2, 3)", "clickhouse") }
        assertEquals(Dialects.CLICKHOUSE.functionCatalog, dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA.default.functions)
    }

    @Test
    fun freshlyCapturedAggregatesResolveTheirParameterListsAndCombinators() {
        assertNotNull(roundTrip("SELECT groupFormat('JSONEachRow')(x) FROM t").find<ParameterizedAgg>())
        assertNotNull(roundTrip("SELECT giniIf(x, x > 0) FROM t").find<CombinedAggFunc>())
        assertNotNull(roundTrip("SELECT mergedJSONPatch(v, ts) FROM t").find<AnonymousAggFunc>())
        // The registry's aggregate flag also includes window-only implementations.
        // Refreshing the catalog must not reclassify the existing window ASTs.
        assertIs<Anonymous>(roundTrip("SELECT lagInFrame(x, 1, 0) OVER (ORDER BY x) FROM t").find<Window>()?.thisArg)
        assertIs<Anonymous>(roundTrip("SELECT leadInFrame(x, 1, 0) OVER (ORDER BY x) FROM t").find<Window>()?.thisArg)
    }

    @Test
    fun dialectSpecificFormsCannotSilentlyLowerToOtherEngines() {
        for (sql in listOf("SELECT x FROM t LIMIT 2 AFTER x > 1", "SELECT dt AT LOCAL FROM t",
            "SELECT INTERVAL '1:30' HOUR TO MINUTE", "SELECT CAST(x AS QBit(Float32, 8))",
            "SELECT * FROM remote('x', db.t, SETTINGS max_threads = 1)", "SELECT year()")) {
            for (target in listOf("duckdb", "trino", "doris", "postgres")) {
                assertFailsWith<UnsupportedError>("$target: $sql") { SqlFragment(sql, "clickhouse").toExecutable(target) }
            }
        }
    }
}
