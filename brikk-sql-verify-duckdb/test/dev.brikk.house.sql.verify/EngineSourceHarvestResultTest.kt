package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reads DuckDB's MIT-licensed SQLLogicTest files unchanged. See ATTRIBUTIONS.md and the manifest. */
internal class EngineSourceHarvestResultTest {
    private data class Record(val header: String, val sql: String, val expected: List<String>)

    private fun source(path: String): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "project.yaml").isFile }
        return File(root, "vendor/data/test-expansion/duckdb/$path")
    }

    // Deliberately supports only the directives in the two pinned files, failing on anything else.
    // This is not a permissive general-purpose SQLLogicTest reader.
    private fun records(path: String): List<Record> {
        val lines = source(path).readLines()
        val output = mutableListOf<Record>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i++].trim()
            if (header.isEmpty() || header.startsWith("#") || header == "require json") continue
            require(header in setOf("statement ok", "statement error", "query I", "query III")) { "$path: unknown directive $header" }
            val sql = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i] != "----") sql += lines[i++]
            require(sql.isNotEmpty()) { "$path: empty SQL after $header" }
            val expected = mutableListOf<String>()
            if (i < lines.size && lines[i] == "----") {
                i++
                while (i < lines.size && lines[i].isNotBlank()) expected += lines[i++]
            }
            output += Record(header, sql.joinToString("\n"), expected)
        }
        return output
    }

    private fun rows(connection: Connection, sql: String): List<String> = connection.createStatement().use { s ->
        s.executeQuery(sql).use { r ->
            buildList {
                while (r.next()) add((1..r.metaData.columnCount).joinToString("\t") { r.getString(it) ?: "NULL" })
            }
        }
    }

    private fun assertNativeError(connection: Connection, case: Record) {
        val expected = case.expected.single()
        assertTrue(expected.startsWith("<REGEX>:Parser Error"), case.sql)
        val failure = assertIs<SerializedSql.Failed>(connection.serializeSql(case.sql))
        assertEquals("parser", failure.type, case.sql)
        assertTrue(Regex(expected.removePrefix("<REGEX>:"), RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn("Parser Error: ${failure.message.orEmpty()}"), "${case.sql}: ${failure.message}")
    }

    private fun replay(path: String, queryCount: Int, errorCount: Int) {
        val cases = records(path)
        assertEquals(queryCount, cases.count { it.header.startsWith("query ") }, path)
        assertEquals(errorCount, cases.count { it.header == "statement error" }, path)
        assertEquals(1, cases.count { it.header == "statement ok" }, path)
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
        }).use { connection ->
            assertEquals(listOf("v2.0.0"), rows(connection, "SELECT version()"))
            for (case in cases) when (case.header) {
                "statement ok" -> connection.createStatement().use { it.execute(case.sql) }
                "statement error" -> {
                    // Parse only: malformed vendor SQL is never executed.
                    assertNativeError(connection, case)
                }
                else -> {
                    assertEquals(case.expected, rows(connection, case.sql), "native source: ${case.sql}")
                    val tree = parseDuckdbNativeOne(case.sql)
                    for (ast in listOf(tree, tree.copy(), Serde.loadExpression(Serde.dump(tree)))) {
                        val generated = ast.sql("duckdb")
                        assertEquals(case.expected, rows(connection, generated), "generated: $generated")
                    }
                }
            }
        }
    }

    @Test fun nativeJsonArrowSubqueryCasesPreserveRowsThroughGeneration() {
        replay("test/sql/json/scalar/test_json_arrow_expr.test", queryCount = 3, errorCount = 0)
    }

    @Test fun nativeNestedJoinCasesKeepAssociationAndRejectMissingQualifiers() {
        replay("test/sql/parser/nested_join_backtracking.test", queryCount = 1, errorCount = 2)
    }

    @Test fun malformedNestedJoinsAreRejectedEvenWhenThePositiveRenderingRegresses() {
        // Run negative controls independently so a failure of the positive case cannot hide them.
        val cases = records("test/sql/parser/nested_join_backtracking.test").filter { it.header == "statement error" }
        assertEquals(2, cases.size)
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
        }).use { connection ->
            assertEquals(listOf("v2.0.0"), rows(connection, "SELECT version()"))
            for (case in cases) assertNativeError(connection, case)
        }
    }
}
