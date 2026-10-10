package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetOperationPaginationCatchupTest {
    @Test
    fun sharedTopParserKeepsTheBranchLimitSeparateFromOuterPagination() {
        // Exercise the shared parser hook, not a new unsupported T-SQL dialect.
        val config = TokenizerConfig.BASE.withKeywords(mapOf("TOP" to TokenType.TOP))
        val source = "SELECT 1 AS x UNION ALL SELECT TOP 2 2 AS x LIMIT 1"
        val tree = Parser(tokenizerConfig = config).parse(Tokenizer(config).tokenize(source), source).filterNotNull().single()
        val right = tree.args["expression"] as Expression
        assertEquals(true, (right.args["limit"] as Expression).meta["top"])
        assertEquals(null, (tree.args["limit"] as Expression).metaOrNull?.get("top"))
        assertEquals("SELECT 1 AS x UNION ALL (SELECT 2 AS x LIMIT 2) LIMIT 1", tree.sql("duckdb"))
    }

    @Test
    fun explicitBranchLimitsRemainInsideBothSetOperandsForPortableTargets() {
        val left = Dialects.DUCKDB.parseOne("SELECT 1 AS x LIMIT 1")
        val right = Dialects.DUCKDB.parseOne("SELECT 2 AS x LIMIT 1")
        val tree = Union(args("this" to left, "expression" to right, "distinct" to false))
        val original = tree.copy()
        for (dialect in listOf("duckdb", "postgres", "doris", "trino")) {
            val output = tree.sql(dialect)
            assertEquals("(SELECT 1 AS x LIMIT 1) UNION ALL (SELECT 2 AS x LIMIT 1)", output, dialect)
            val parsed = Dialects.forName(dialect).parseOne(output)
            assertEquals(null, parsed.args["limit"], output)
            assertTrue((parsed.args["this"] as Subquery).thisArg.let { (it as Expression).args["limit"] != null })
            assertEquals(original, tree)
        }
    }

    @Test
    fun nestedSetPaginationAndAnOuterLimitAreRenderedAtSeparateScopes() {
        val nested = Dialects.DUCKDB.parseOne("SELECT 1 AS x UNION ALL SELECT 2 AS x ORDER BY x LIMIT 1")
        val root = Union(args("this" to nested, "expression" to Dialects.DUCKDB.parseOne("SELECT 3 AS x"), "distinct" to false))
        val output = root.sql("duckdb")
        assertEquals("(SELECT 1 AS x UNION ALL SELECT 2 AS x ORDER BY x LIMIT 1) UNION ALL SELECT 3 AS x", output)
        val parsed = Dialects.DUCKDB.parseOne(output)
        assertTrue((parsed.args["this"] as Subquery).thisArg is SetOperation)
        assertEquals(null, parsed.args["limit"])
    }

    @Test
    fun clickhouseOuterPaginationUsesASelectWrapperInsteadOfRestrictingTheLastArm() {
        val source = "SELECT 1 AS x UNION ALL SELECT 2 AS x ORDER BY x LIMIT 1 OFFSET 1"
        val result = SqlFragment(source, "postgres").transpileTo("clickhouse")
        assertEquals("SELECT * FROM (SELECT 1 AS x UNION ALL SELECT 2 AS x) AS _l_0 ORDER BY x LIMIT 1 OFFSET 1", result.sql)
        assertTrue(result.unsupportedMessages.isEmpty(), result.toString())
        val offsetOnly = SqlFragment("SELECT 1 AS x UNION ALL SELECT 2 AS x OFFSET 1", "postgres").transpileTo("clickhouse")
        assertTrue(offsetOnly.sql.startsWith("SELECT * FROM ("), offsetOnly.sql)
        assertTrue(offsetOnly.sql.endsWith("OFFSET 1"), offsetOnly.sql)
        val nativeBranch = "SELECT 1 AS x LIMIT 1 UNION ALL SELECT 2 AS x LIMIT 1"
        assertEquals(nativeBranch, Dialects.CLICKHOUSE.parseOne(nativeBranch).sql("clickhouse"))
    }
}
