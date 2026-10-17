package dev.brikk.house.sql.verify

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TrinoPipeRenameResultTest {
    private val complete = ShapeCatalog(tables = mapOf("t" to
        Shape.of("label" to "VARCHAR", "id" to "INT", "payload" to "BIGINT")))
    private val cases = listOf(
        Triple("id AS x", "SELECT label, id AS x, payload FROM t", listOf("label", "x", "payload")),
        Triple("id AS payload, payload AS id", "SELECT label, id AS payload, payload AS id FROM t",
            listOf("label", "payload", "id")),
    )

    private fun result(statement: Statement, sql: String): Pair<List<Pair<String, String>>, List<List<String?>>> =
        statement.executeQuery(sql).use { rows ->
            val columns = (1..rows.metaData.columnCount).map {
                rows.metaData.getColumnLabel(it) to rows.metaData.getColumnTypeName(it)
            }
            val values = buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
            }
            columns to values
        }

    @Test
    fun nativeTrinoGrammarRejectsThePreviouslyUnflaggedOutputAndGenerationNowRefuses() {
        val old = "WITH __tmp1 AS (SELECT * FROM t), " +
            "__tmp2 AS (SELECT * RENAME (id AS x) FROM __tmp1) SELECT * FROM __tmp2"
        assertFalse(TrinoVerifier().verify(old) is VerifyResult.Accepted, old)
        assertFailsWith<UnsupportedError> { SqlFragment("FROM t |> RENAME id AS x", "trino").toExecutable("trino") }
    }

    @Test
    fun expandedRenameHasNativeGrammarAndPreservesOrderedNamesTypesNullsAndDuplicateRows() {
        val verifier = TrinoVerifier()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(label VARCHAR, id INTEGER, payload BIGINT)")
                statement.execute("INSERT INTO t VALUES ('a',1,100000000000),('a',1,100000000000),('b',NULL,NULL)")
                for (target in listOf("presto", "trino")) {
                    for ((rename, reference, names) in cases) {
                        val fragment = SqlFragment("FROM t |> RENAME $rename", target)
                        val original = fragment.ast.copy()
                        val sql = fragment.toStandardSql(target, complete, expandStars = true)
                        assertIs<VerifyResult.Accepted>(verifier.verify(sql), sql)
                        assertEquals(names, result(statement, reference).first.map { it.first })
                        assertEquals(result(statement, reference), result(statement, sql), sql)
                        assertEquals(original, fragment.ast)
                    }
                }
            }
        }
    }

    @Test
    fun liveTrinoExpandedRenameKeepsRowsWithoutAppendingOrDroppingColumns() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val client = listOf("trino", "--output-format=TSV_HEADER", "--execute")
        val prefix = "WITH t(label,id,payload) AS (VALUES " +
            "('a',1,CAST(100000000000 AS BIGINT)),('a',1,CAST(100000000000 AS BIGINT)),('b',NULL,NULL)) "
        for ((rename, reference, _) in cases) {
            val sql = SqlFragment("FROM t |> RENAME $rename", "trino")
                .toStandardSql("trino", complete, expandStars = true)
            val suffix = " AS renamed ORDER BY label, 2 NULLS FIRST, 3 NULLS FIRST"
            val expected = runCatchupEngineSql(container!!, client, "$prefix SELECT * FROM ($reference)$suffix")
            assertEquals(expected, runCatchupEngineSql(container, client, "$prefix SELECT * FROM ($sql)$suffix"), sql)
        }
    }
}
