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
import kotlin.test.assertTrue

class PipeExecutionCapabilitiesResultTest {
    private val complete = ShapeCatalog(tables = mapOf("t" to
        Shape.of("label" to "VARCHAR", "id" to "INT", "payload" to "BIGINT")))
    private val cases = listOf(
        "DROP id" to "SELECT label,payload FROM t",
        "DROP id,payload" to "SELECT label FROM t",
        "SET id = 7" to "SELECT label,7 AS id,payload FROM t",
        "SET id = id + 1" to "SELECT label,id+1 AS id,payload FROM t",
        "SET payload = id, id = payload" to "SELECT label,payload AS id,id AS payload FROM t",
    )

    @Test
    fun trinoNativeGrammarRejectsUnexpandedExceptReplaceAndCoreRefusesThem() {
        val verifier = TrinoVerifier()
        for ((stage, modifier) in listOf("DROP id" to "EXCEPT (id)", "SET id = 7" to "REPLACE (7 AS id)")) {
            val old = "WITH __tmp1 AS (SELECT * FROM t) SELECT * $modifier FROM __tmp1"
            assertFalse(verifier.verify(old).accepted, old)
            assertFailsWith<UnsupportedError> { SqlFragment("FROM t |> $stage", "trino").toExecutable("trino") }
        }
    }

    private fun rows(statement: Statement, sql: String): Pair<List<Pair<String, String>>, List<List<String?>>> =
        statement.executeQuery(sql).use { result ->
            val columns = (1..result.metaData.columnCount).map {
                result.metaData.getColumnLabel(it) to result.metaData.getColumnTypeName(it)
            }
            val values = buildList {
                while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
            }
            columns to values
        }

    @Test
    fun completeDropAndSetProjectionsHaveNativeGrammarAndKeepPositionsTypesNullsAndRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(label VARCHAR,id INTEGER,payload BIGINT)")
                statement.execute("INSERT INTO t VALUES ('a',1,100000000000),('a',1,100000000000),('b',NULL,NULL)")
                for (target in listOf("presto", "trino")) {
                    for ((stage, reference) in cases) {
                        val sql = SqlFragment("FROM t |> $stage", target).toStandardSql(target, complete, expandStars = true)
                        assertTrue(TrinoVerifier().verify(sql).accepted, sql)
                        assertEquals(rows(statement, reference), rows(statement, sql), stage)
                    }
                }
            }
        }
    }

    @Test
    fun liveTrinoCompleteDropAndSetProjectionsKeepAllUntouchedColumnsAndSimultaneousBindings() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val client = listOf("trino", "--output-format=TSV_HEADER", "--execute")
        val prefix = "WITH t(label,id,payload) AS (VALUES " +
            "('a',1,CAST(100000000000 AS BIGINT)),('a',1,CAST(100000000000 AS BIGINT)),('b',NULL,NULL)) "
        for ((stage, reference) in cases) {
            val sql = SqlFragment("FROM t |> $stage", "trino").toStandardSql("trino", complete, expandStars = true)
            val order = when (stage) {
                "DROP id,payload" -> "ORDER BY 1"
                "DROP id" -> "ORDER BY 1,2"
                else -> "ORDER BY 1,2,3"
            }
            fun execute(query: String) = runCatchupEngineSql(container!!, client,
                "$prefix SELECT * FROM ($query) AS rendered $order")
            assertEquals(execute(reference), execute(sql), stage)
        }
    }
}
