package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DorisFullTextCatchupTest {
    @Test fun allNativeKindsPreserveOperandsAndQuotedAnalyzers() {
        for (op in listOf("MATCH", "MATCH_ANY", "MATCH_ALL", "MATCH_PHRASE", "MATCH_PHRASE_PREFIX", "MATCH_PHRASE_EDGE", "MATCH_REGEXP")) {
            val sql = "SELECT * FROM t WHERE c $op ('word1 word2') USING ANALYZER 'standard'"
            val tree = parseOne(sql, "doris")
            val match = tree.findAll<Match>().single()
            assertEquals(op.removePrefix("MATCH").removePrefix("_").ifEmpty { null }, match.args["kind"])
            assertIs<Column>(match.thisArg); assertIs<Paren>(match.expressionArg)
            assertIs<Literal>(match.args["analyzer"])
            assertEquals(sql, tree.sql("doris"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }
    @Test fun infixAndPrefixNegationKeepBooleanPrecedence() {
        val sql = "SELECT * FROM t WHERE c NOT MATCH_ALL ('x') USING ANALYZER 'standard' AND d MATCH_ANY 'y' OR e = 1"
        val tree = parseOne(sql, "doris")
        assertEquals(2, tree.findAll<Match>().count())
        assertIs<Match>(tree.findAll<Not>().single().thisArg)
        val output = tree.sql("doris")
        assertContains(output, "NOT c MATCH_ALL ('x') USING ANALYZER 'standard'")
        assertEquals(output, parseOne(output, "doris").sql("doris"))
    }
    @Test fun operatorNamesRemainAvailableAsImplicitAndExplicitAliases() {
        for (sql in listOf("SELECT c match_any FROM t", "SELECT c match_any", "SELECT c AS match_all FROM t")) {
            val tree = parseOne(sql, "doris")
            assertTrue(tree.findAll<Match>().none(), sql)
            assertIs<Alias>(tree.expressionsArg.single())
            val output = tree.sql("doris")
            assertEquals(output, parseOne(output, "doris").sql("doris"))
        }
        parseOne("SELECT * FROM t WHERE c NOT LIKE 'x%'", "doris")
        assertFailsWith<ParseError> { parseOne("SELECT * FROM t WHERE c MATCH_ANY 'x' USING ANALYZER", "doris") }
    }
    @Test fun dynamicAndParenthesizedOperandsRemainStructuredNotCommands() {
        for (rhs in listOf("terms", "CONCAT('a', 'b')", "('word')")) {
            val sql = "SELECT c MATCH_ALL $rhs AS found FROM t"
            val tree = parseOne(sql, "doris")
            assertTrue(tree.findAll<Command>().none())
            assertEquals(1, tree.findAll<Match>().count())
            val before = Serde.dump(tree); val parents = tree.walk().map { it to it.parent }.toList()
            Dialects.DORIS.generator().generate(tree, copy = false)
            assertEquals(before, Serde.dump(tree))
            for ((node, parent) in parents) assertSame(parent, node.parent)
        }
    }
    @Test fun unsupportedTargetsAndSerdeCannotLoseKindAnalyzerOrOrigin() {
        for (op in listOf("MATCH", "MATCH_ALL")) for (target in listOf("mysql", "starrocks", "postgres", "duckdb", "trino", "bigquery", "clickhouse", "spark")) {
            val source = "SELECT * FROM t WHERE c $op 'x' USING ANALYZER 'standard'"
            assertFailsWith<UnsupportedError> { SqlFragment(source, "doris").toExecutable(target) }
            val loaded = Serde.loadExpression(Serde.dump(parseOne(source, "doris")))
            assertFailsWith<UnsupportedError> { Dialects.forName(target).generator().apply { forExecution = true }.generate(loaded) }
        }
        SqlFragment("SELECT * FROM t WHERE c MATCH_ALL 'x' USING ANALYZER 'standard'", "doris").toExecutable("doris")
    }
    @Test fun booleanTypeAndLegacyConstructorsRemainAvailable() {
        Match::class.java.getConstructor(java.util.Map::class.java)
        Match::class.java.getConstructor()
        assertEquals("BOOLEAN", SqlFragment("SELECT c MATCH_ANY 'x' AS found FROM t", "doris").outputShape().columns.single().type)
    }
}
