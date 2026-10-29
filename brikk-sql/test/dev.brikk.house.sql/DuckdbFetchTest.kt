package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Fetch
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DuckdbFetchTest {
    @Test
    fun firstNextRowRowsCountlessAndBothOffsetOrdersCanonicalizeWithoutChangingTheAst() {
        for (direction in listOf("FIRST", "NEXT")) for (rows in listOf("ROW", "ROWS")) for ((count, limit) in listOf("" to "1", "2 " to "2")) {
            for ((suffix, expected) in listOf(
                "FETCH $direction $count$rows ONLY" to "LIMIT $limit",
                "OFFSET 1 $rows FETCH $direction $count$rows ONLY" to "LIMIT $limit OFFSET 1",
                "FETCH $direction $count$rows ONLY OFFSET 1 $rows" to "LIMIT $limit OFFSET 1",
            )) {
                val tree = Dialects.DUCKDB.parseOne("SELECT a FROM t ORDER BY a $suffix")
                assertIs<Fetch>(tree.args["limit"])
                val before = Serde.dump(tree)
                assertEquals(tree, Serde.load(before))
                assertEquals("SELECT a FROM t ORDER BY a $expected", tree.sql("duckdb"))
                assertEquals(before, Serde.dump(tree))
                val rendered = tree.sql("duckdb")
                assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            }
        }
    }

    @Test
    fun nativeExpressionsParametersAndSubqueriesRemainCountExpressions() {
        val arithmetic = Dialects.DUCKDB.parseOne("SELECT a FROM t FETCH FIRST 1 + 1 ROWS ONLY")
        assertIs<Add>((arithmetic.args["limit"] as Fetch).args["count"])
        for (value in listOf("(1+1)", "1+1", "NULL", "0", "1.5", "?", "\$1", "(SELECT 2)", "CASE WHEN true THEN 2 ELSE 1 END")) {
            val sql = "SELECT a FROM t ORDER BY a OFFSET 1 ROW FETCH NEXT $value ROWS ONLY"
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
        }
    }

    @Test
    fun incompleteUnsupportedAndDuplicateFetchClausesAreNotSilentlyRepaired() {
        for (suffix in listOf("FETCH 2 ROWS ONLY", "FETCH FIRST 2", "FETCH FIRST 2 ONLY", "FETCH FIRST 2 ROWS",
            "FETCH FIRST 2 ROWS WITH TIES", "FETCH FIRST 50 PERCENT ROWS ONLY", "FETCH FIRST ROW",
            "LIMIT 2 FETCH FIRST ROW ONLY", "FETCH FIRST ROW ONLY LIMIT 2", "FETCH FIRST ROW ONLY FETCH NEXT ROW ONLY",
            "OFFSET 1 ROW OFFSET 2 ROW FETCH FIRST ROW ONLY")) {
            assertFailsWith<ParseError>(suffix) { Dialects.DUCKDB.parseOne("SELECT a FROM t ORDER BY a $suffix") }
        }
        // This is DuckDB-specific strictness, not removal of another dialect's WITH TIES.
        Dialects.TRINO.parseOne("SELECT a FROM t ORDER BY a FETCH FIRST 2 ROWS WITH TIES")
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT a FROM t ORDER BY a FETCH FIRST 2 ROWS WITH TIES", "trino").toExecutable("duckdb")
        }
    }

    @Test
    fun paginationLeavesShapesAndNestedSetBoundariesIntact() {
        val catalog = ShapeCatalog(mapOf("t" to Shape(listOf(ColumnShape("a", "INT", false)))))
        val base = "SELECT a FROM t ORDER BY a"
        assertEquals(SqlFragment(base, "duckdb").outputShape(catalog),
            SqlFragment("$base OFFSET 1 ROW FETCH NEXT ROW ONLY", "duckdb").outputShape(catalog))
        for (sql in listOf(
            "SELECT a FROM t UNION ALL SELECT a FROM t ORDER BY a OFFSET 1 ROW FETCH NEXT 2 ROWS ONLY",
            "WITH q AS (SELECT a FROM t ORDER BY a FETCH FIRST 2 ROWS ONLY) SELECT a FROM q ORDER BY a DESC FETCH NEXT ROW ONLY",
            "(SELECT a FROM t ORDER BY a DESC FETCH FIRST 2 ROWS ONLY) UNION ALL SELECT a FROM t ORDER BY a FETCH NEXT 3 ROWS ONLY",
        )) {
            val generated = SqlFragment(sql, "duckdb").toExecutable("duckdb").sql
            assertTrue("LIMIT" in generated)
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
        }
    }

    @Test
    fun anonymousPaginationBindOrderSurvivesCanonicalizationCopySerdeAndReparse() {
        for ((suffix, expected) in listOf(
            "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" to "OFFSET ? LIMIT ?",
            "FETCH NEXT ? ROWS ONLY OFFSET ? ROWS" to "LIMIT ? OFFSET ?",
        )) {
            val tree = Dialects.DUCKDB.parseOne("SELECT a FROM t ORDER BY a $suffix")
            for (copy in listOf(tree, tree.copy(), Serde.load(Serde.dump(tree)) as Expression)) {
                val generated = copy.sql("duckdb")
                assertEquals("SELECT a FROM t ORDER BY a $expected", generated)
                assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            }
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT a FROM t OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", "duckdb").toExecutable("doris")
        }
    }
}
