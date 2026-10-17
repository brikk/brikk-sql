package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The relation corpus's coreExecution cases (brikk-sql/testResources@jvm/relation-corpus): each
 * slot is replaced by its declared engine table, the pipe is lowered by core's toExecutable, and
 * DuckDB must return the case's columns and rows (as a multiset).
 */
class RelationCorpusExecutionTest {
    private val directory = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "project.yaml").isFile }.resolve("brikk-sql/testResources@jvm/relation-corpus")
    private val facts = Json.parseToJsonElement(directory.resolve("facts.json").readText()).jsonObject
    private val cases = directory.resolve("cases.jsonl").readLines().filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject }.filter { "coreExecution" in it }

    @Test
    fun loweredPipesReturnTheWitnessedRows() {
        assertTrue(cases.size >= 7, "${cases.size} execution cases")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (setup in facts.getValue("engineSetup").jsonObject.getValue("duckdb").jsonArray) statement.execute(setup.jsonPrimitive.content)
                for (case in cases) {
                    val id = case.getValue("id").jsonPrimitive.content
                    val sql = case.getValue("engineRelations").jsonObject.entries.fold(case.getValue("sql").jsonPrimitive.content) { text, (slot, table) ->
                        text.replace(Regex("\\b${Regex.escape(slot)}\\(\\)"), table.jsonPrimitive.content)
                    }
                    val expected = case.getValue("coreExecution").jsonObject
                    val executable = SqlFragment(sql, "duckdb").toExecutable("duckdb").sql
                    statement.executeQuery(executable).use { rows ->
                        val columns = (1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel)
                        assertEquals(expected.getValue("columns").jsonArray.map { it.jsonPrimitive.content }, columns, "$id\n$executable")
                        val actual = ArrayList<List<String?>>()
                        while (rows.next()) actual += columns.indices.map { rows.getString(it + 1) }
                        val wanted = expected.getValue("rows").jsonArray.map { row -> row.jsonArray.map(::text) }
                        assertEquals(wanted.sortedBy { it.toString() }, actual.sortedBy { it.toString() }, "$id\n$executable")
                    }
                }
            }
        }
    }

    private fun text(value: JsonElement): String? = if (value == JsonNull) null else value.jsonPrimitive.content
}
