package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.StatementEffect
import dev.brikk.house.sql.shape.statementEffect
import dev.brikk.house.sql.scope.*
import kotlin.test.*

class SparkHiveCatchupTest {
    @Test fun ddlIsNotAQueryScopeOrAnAppliedCatalogMigration() {
        val relation = RelationFact(listOf(ColumnFact("old", "INT")))
        val facts = RelationFacts(tables = mapOf("t" to relation))
        for (sql in listOf("ALTER TABLE t REPLACE COLUMNS (fresh STRING)", "ALTER TABLE t DROP COLUMNS (old)")) {
            val scope = RelationScope.analyze(sql, "spark", facts)
            assertTrue(scope.parsed)
            assertNull(scope.output)
            assertTrue(scope.references.isEmpty(), "DDL field names must not resolve as query references")
            assertEquals(StatementEffect.MODIFIES_STATE, SqlFragment(sql, "spark").statementEffect)
            assertEquals(relation, facts.tables["t"], "Declarative analysis must not mutate catalog facts")
        }
        assertEquals(listOf("old"), RelationScope.analyze("SELECT * FROM t", "spark", facts).output!!.columns.map { it.name })
        val aggregate = RelationScope.analyze("SELECT LISTAGG(DISTINCT old, '-') WITHIN GROUP (ORDER BY old) AS v FROM t", "spark", facts)
        assertTrue(aggregate.parsed)
        assertEquals(listOf("v"), aggregate.output!!.columns.map { it.name })
        assertTrue(aggregate.references.isNotEmpty())
    }
    @Test fun listaggQuantifiersOrderingAndSeparators() {
        for (dialect in listOf("spark")) {
            for (quantifier in listOf("", "ALL ", "DISTINCT ")) {
                for (tail in listOf("", " WITHIN GROUP (ORDER BY x DESC)")) {
                    val sql = "LISTAGG(${quantifier}x, '-')$tail"
                    val tree = assertIs<GroupConcat>(parseOne(sql, dialect))
                    assertEquals("LISTAGG(${if (quantifier == "DISTINCT ") quantifier else ""}x, '-')$tail", tree.sql(dialect))
                    assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
                    val before = Serde.dump(tree)
                    Dialects.forName(dialect).generator().generate(tree, copy = false)
                    assertEquals(before, Serde.dump(tree))
                    if (quantifier == "DISTINCT ") assertNotNull(tree.find<Distinct>())
                    if (tail.isNotEmpty()) assertNotNull(tree.find<Order>())
                }
            }
            assertEquals("LISTAGG(x, '')", parseOne("LISTAGG(x)", dialect).sql(dialect))
            assertEquals("LISTAGG(x, NULL)", parseOne("LISTAGG(x, NULL)", dialect).sql(dialect))
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT LISTAGG(x, ',' ON OVERFLOW ERROR) WITHIN GROUP (ORDER BY x)", "trino").toExecutable("spark")
        }
        for (target in listOf("hive", "spark2")) assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT LISTAGG(DISTINCT x, '-') WITHIN GROUP (ORDER BY x) FROM t", "spark").toExecutable(target)
        }
        for (dialect in listOf("postgres", "bigquery")) {
            assertIs<GroupConcat>(parseOne("STRING_AGG(ALL x, '-')", dialect))
        }
    }

    @Test fun dropColumnFormsAreStructured() {
        for (dialect in listOf("spark", "spark2")) {
            for (suffix in listOf("COLUMN a", "COLUMN IF EXISTS a.b", "COLUMNS (a, b.c)",
                "COLUMNS (from)", "COLUMNS IF EXISTS (a, b.c)", "COLUMNS a, b")) {
                val source = "ALTER TABLE t DROP $suffix"
                val tree = assertIs<Alter>(parseOne(source, dialect), source)
                val drop = assertIs<Drop>((tree.args["actions"] as List<*>).single())
                assertEquals(suffix.contains("IF EXISTS"), drop.args["exists"] == true)
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
                val rendered = tree.sql(dialect)
                assertIs<Alter>(parseOne(rendered, dialect))
                assertContains(rendered, if (suffix.startsWith("COLUMNS")) "DROP COLUMNS" else "DROP COLUMN")
                if (suffix.contains("b.c")) assertContains(rendered, "b.c")
                if (suffix.contains("a.b")) assertContains(rendered, "a.b")
            }
        }
        assertFailsWith<UnsupportedError> { SqlFragment("ALTER TABLE t DROP COLUMNS (a,b)", "spark").toExecutable("postgres") }
        assertFailsWith<UnsupportedError> { SqlFragment("ALTER TABLE t DROP COLUMN IF EXISTS a.b", "spark").toExecutable("postgres") }
    }

    @Test fun replaceColumnsHasItsOwnActionAndRetainsCommentsAndCascade() {
        for (dialect in listOf("hive", "spark", "spark2")) {
            for (suffix in listOf("(a INT COMMENT 'c', b STRING)", "(from INT)", "a INT, b STRING")) {
                val source = "ALTER TABLE t REPLACE COLUMNS $suffix"
                val tree = assertIs<Alter>(parseOne(source, dialect), source)
                val action = assertIs<ReplaceColumns>((tree.args["actions"] as List<*>).single())
                assertTrue(action.expressionsArg.all { it is ColumnDef })
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
                assertIs<ReplaceColumns>(parseOne(tree.sql(dialect), dialect).find<ReplaceColumns>())
                if (suffix.contains("COMMENT")) assertContains(tree.sql(dialect), "COMMENT 'c'")
                assertFailsWith<UnsupportedError> { SqlFragment(source, dialect).toExecutable("postgres") }
            }
            for (suffix in listOf("", " ()", " (a UNIONTYPE<INT, STRING>)", " (a TIMESTAMPLOCALTZ)", " (a INT COMMENT 'it''s')")) {
                val sql = "ALTER TABLE t REPLACE COLUMNS$suffix"
                assertEquals(sql, assertIs<Command>(parseOne(sql, dialect)).sql(dialect))
            }
        }
        val cascade = parseOne("ALTER TABLE t REPLACE COLUMNS (a INT) CASCADE", "hive")
        assertEquals(true, cascade.args["cascade"])
        assertContains(cascade.sql("hive"), "CASCADE")
        assertFailsWith<UnsupportedError> { SqlFragment("ALTER TABLE t REPLACE COLUMNS (a INT) CASCADE", "hive").toExecutable("spark") }
    }
}
