package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the base-dialect Generator. Expected strings are Python oracle
 * outputs (reference/sqlglot parse_one(...).sql(...)), except deterministic reuse (#68).
 */
class GeneratorTest {

    private fun gen(sql: String, generator: Generator = Generator()): String =
        generator.generate(parseOne(sql))

    @Test
    fun roundTripsSimpleSelect() {
        assertEquals("SELECT a FROM b WHERE c = 1", gen("SELECT a FROM b WHERE c = 1"))
    }

    @Test
    fun identifyQuotesIdentifiers() {
        assertEquals("SELECT \"a\" FROM \"b\"", gen("SELECT a FROM b", Generator(identify = true)))
    }

    @Test
    fun preservesComments() {
        assertEquals("SELECT 1 /* one */", gen("SELECT 1 /* one */"))
    }

    @Test
    fun dropsCommentsWhenDisabled() {
        assertEquals("SELECT 1", gen("SELECT 1 /* one */", Generator(comments = false)))
    }

    @Test
    fun normalizeLowercasesUnquotedIdentifiers() {
        assertEquals("SELECT a FROM b", gen("SELECT A FROM B", Generator(normalize = true)))
    }

    @Test
    fun functionFallbackRendersVarLenArgs() {
        assertEquals("SELECT COALESCE(a, b, c)", gen("SELECT COALESCE(a, b, c)"))
    }

    @Test
    fun prettyPrintsWithIndentation() {
        assertEquals(
            "SELECT\n  a,\n  b\nFROM t\nWHERE\n  x = 1\nORDER BY\n  a",
            gen("SELECT a, b FROM t WHERE x = 1 ORDER BY a", Generator(pretty = true)),
        )
    }

    @Test
    fun reusedGeneratorGivesIdenticalSqlForTheSameUnnamedAlias() {
        val ast = Dialects.BIGQUERY.parseOne("SELECT * FROM UNNEST([1, 2]) AS x")
        val generator = Generator()
        val first = generator.generate(ast)
        assertEquals("SELECT * FROM UNNEST(ARRAY(1, 2)) AS _t0(x)", first)
        assertEquals(first, generator.generate(ast))
    }

    @Test
    fun reusedGeneratorStartsUnrelatedStatementsWithFreshAliases() {
        val generator = Generator()
        for (sql in listOf(
            "SELECT * FROM UNNEST([1, 2]) AS x",
            "SELECT item FROM UNNEST(['a', 'b']) AS item",
        )) {
            val ast = Dialects.BIGQUERY.parseOne(sql)
            assertEquals(Generator().generate(ast), generator.generate(ast), sql)
        }
    }

    @Test
    fun syntheticAliasesStayDistinctWithinOneStatement() {
        val ast = Dialects.BIGQUERY.parseOne(
            "SELECT x, y FROM UNNEST([1, 2]) AS x CROSS JOIN UNNEST([3, 4]) AS y",
        )
        assertEquals(
            "SELECT x, y FROM UNNEST(ARRAY(1, 2)) AS _t0(x) CROSS JOIN UNNEST(ARRAY(3, 4)) AS _t1(y)",
            Generator().generate(ast),
        )
    }
}
