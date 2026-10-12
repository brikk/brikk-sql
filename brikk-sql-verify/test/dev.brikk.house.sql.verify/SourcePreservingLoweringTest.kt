package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourcePreservingLoweringTest {
    private fun rows(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                }
            }
        }
    }

    private fun probe(source: String, expected: List<List<String?>>) {
        val fragment = SqlFragment(source, "duckdb")
        val preserved = fragment.toSourcePreservingExecutable()
        assertIs<VerifyResult.Accepted>(SqlVerifiers.forEngine("duckdb")!!.verify(preserved.sql), preserved.sql)
        assertEquals(expected, rows(preserved.sql), preserved.sql)
        assertEquals(rows(fragment.toExecutable("duckdb", trackSourceMap = false).sql), rows(preserved.sql), preserved.sql)
        assertTrue(preserved.unsupportedMessages.isEmpty(), preserved.unsupportedMessages.joinToString())
    }

    @Test
    fun nativeCastAndCommentSurviveBindingBeforeLowering() {
        val source = "FROM (select /* keep */ 2::integer as id) AS raw |> WHERE id = :n |> SELECT id"
        val parameter = SqlFragment(source, "duckdb").toSourcePreservingExecutable().parameterOccurrences.single()
        // Engine binds source before constructing the lowering fragment, as the handoff specifies.
        val bound = source.substring(0, parameter.range.start) + "2" + source.substring(parameter.range.end)
        val result = SqlFragment(bound, "duckdb").toSourcePreservingExecutable()
        assertTrue("select /* keep */ 2::integer as id" in result.sql, result.sql)
        probe(bound, listOf(listOf("2")))
    }

    @Test
    fun nestedPipeChangesOnlyItsNativeParentIntervalAndExecutes() {
        val source = "select /* outer */ id FROM (FROM (select 1::integer AS id) AS raw |> SELECT id) AS nested"
        probe(source, listOf(listOf("1")))
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertTrue(result.sql.startsWith("select /* outer */ id FROM ("), result.sql)
        assertTrue(result.sql.endsWith(") AS nested"), result.sql)
    }

    @Test
    fun recursiveAndCollidingCtesKeepScopesAndResults() {
        probe("with recursive seq(n) as (select 1 union all select n + 1 from seq where n < 3), " +
            "__tmp1 as (select /* keep */ n::integer as id from seq), " +
            "p as (FROM __tmp1 |> WHERE id > 1 |> SELECT id) select id from p order by id",
            listOf(listOf("2"), listOf("3")))
    }

    @Test
    fun repeatedFiltersProjectionsExtensionsDistinctAndPaginationKeepRowBoundaries() {
        val input = "FROM (select /* native */ * from (values (1), (2), (2), (3)) AS vals(id)) AS raw"
        probe("$input |> SELECT id + 10 AS adjusted |> WHERE adjusted > 11 |> SELECT adjusted |> DISTINCT |> ORDER BY adjusted",
            listOf(listOf("12"), listOf("13")))
        probe("$input |> EXTEND id + 10 AS adjusted |> WHERE adjusted > 11 |> SELECT adjusted |> DISTINCT |> ORDER BY adjusted",
            listOf(listOf("12"), listOf("13")))
        probe("$input |> ORDER BY id |> LIMIT 3 OFFSET 1 |> WHERE id > 1 |> SELECT id |> DISTINCT |> ORDER BY id",
            listOf(listOf("2"), listOf("3")))
        probe("$input |> SELECT id |> WHERE id = 1 OR id = 3 |> WHERE id > 1 |> SELECT id",
            listOf(listOf("3")))
        probe("$input |> SELECT DISTINCT id |> ORDER BY id |> LIMIT 2 |> WHERE id > 1 |> SELECT id",
            listOf(listOf("2")))
    }

    @Test
    fun quotedAliasesAndQualifiedJoinsRemainResolved() {
        probe("FROM (select 1::integer as id) AS \"Left Slot\" |> " +
            "JOIN (select 1 AS id) AS \"Right Slot\" ON \"Left Slot\".id = \"Right Slot\".id |> " +
            "SELECT \"Left Slot\".id AS \"Result\"",
            listOf(listOf("1")))
    }

    @Test
    fun embeddingProtectsTrailingCommentsAndLiteralPunctuation() {
        val source = "FROM (select '😀 |> ; :n src()' AS txt, 2::integer AS id) AS raw\n" +
            "|> WHERE id = 2 -- stage comment\n|> SELECT txt, id; -- trailing comment"
        val rendered = SqlFragment(source, "duckdb").toSourcePreservingExecutable(embedded = true)
        val embedded = "WITH stage AS (${rendered.sql}) SELECT * FROM stage"
        assertEquals(listOf(listOf("😀 |> ; :n src()", "2")), rows(embedded), embedded)
        assertTrue(rendered.sql.endsWith('\n'))
        assertFalse(rendered.parameterOccurrences.any())
    }

    @Test
    fun dorisNativeGrammarAcceptsRetainedHintsAndLoweredClauses() {
        for (source in listOf(
            "select /*+ SET_VAR(exec_mem_limit=1234) */ CAST(2 AS INT) as id",
            "FROM (select /* keep */ CAST(2 AS INT) as id) AS raw |> WHERE id = 2 |> SELECT id",
            "FROM (select /*+ SET_VAR(exec_mem_limit=1234) */ CAST(2 AS INT) as id) AS raw |> WHERE id = 2 |> SELECT id",
            "SELECT id FROM (FROM (select CAST(1 AS INT) AS id) AS raw |> SELECT id) AS nested",
            "FROM (select 2 AS id) AS raw where id = 2",
        )) {
            val result = SqlFragment(source, "doris").toSourcePreservingExecutable()
            assertIs<VerifyResult.Accepted>(SqlVerifiers.forEngine("doris")!!.verify(result.sql), result.sql)
        }
    }

    @Test
    fun preparedPositionalBindingAndRepeatedNativeSubqueriesExecute() {
        val source = "FROM (select /* native */ 2::integer AS id) AS raw |> WHERE id = ? |> SELECT id"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertEquals(listOf(null), result.parameterOccurrences.map { it.name })
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.prepareStatement(result.sql).use { statement ->
                statement.setInt(1, 2)
                statement.executeQuery().use { values ->
                    assertTrue(values.next())
                    assertEquals(2, values.getInt("id"))
                    assertFalse(values.next())
                }
            }
        }
        val nested = "FROM (select 1::integer AS id) AS raw |> SELECT id"
        probe("select /* parent */ a.id, b.id FROM ($nested) AS a CROSS JOIN ($nested) AS b", listOf(listOf("1", "1")))
    }
}
