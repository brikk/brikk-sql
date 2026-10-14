package dev.brikk.house.sql.scope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableFunctionColumnsTest {
    private fun columns(sql: String) =
        RelationScope.complete(sql.replace("<caret>", ""), "clickhouse", RelationFacts(), sql.indexOf("<caret>")).let { completion ->
            completion.knowledge to completion.items.map { it.name to it.column?.sqlType }
        }

    @Test
    fun aStructureStringReadsAsTypedColumns() {
        assertEquals(listOf("a" to "UInt32", "b" to "Nullable(String)", "c" to "Array(Tuple(Int8, String))"),
            TableFunctionStructure.parse("a UInt32, b Nullable(String), c Array(Tuple(Int8, String))", "clickhouse")?.map { it.name to it.sqlType })
        for (text in listOf("CSV", "x.csv", "https://example.invalid/object", "1\tsample", "")) assertNull(TableFunctionStructure.parse(text, "clickhouse"), text)
    }

    @Test
    fun aCallsDeclaredStructureIsItsColumnsAndAnyOtherCallIsOpen() {
        val declared = Knowledge.FULL to listOf("field_a" to "UInt64", "field_b" to "String")
        for (call in listOf(
            "input('field_a UInt64, field_b String')", "null('field_a UInt64, field_b String')",
            "generateRandom('field_a UInt64, field_b String')", "format('TSV', 'field_a UInt64, field_b String', '1\\tsample')",
            "values('field_a UInt64, field_b String', (1, 'sample'))",
            "s3('https://example.invalid/object', 'TSV', 'field_a UInt64, field_b String')",
            "s3(collection_demo, structure = 'field_a UInt64, field_b String')",
            "file('x.csv', 'CSV', 'field_a UInt64, field_b String')",
        )) assertEquals(declared, columns("SELECT t.<caret> FROM $call AS t"), call)
        // view(query) has the query's columns, with the types the analysis infers for them.
        val view = columns("SELECT t.<caret> FROM view(SELECT 1 AS field_a, 'sample' AS field_b) AS t")
        assertEquals(Knowledge.FULL to listOf("field_a", "field_b"), view.first to view.second.map { it.first })
        assertEquals(Knowledge.UNKNOWN to emptyList(), columns("SELECT t.<caret> FROM s3('https://example.invalid/object', 'TSV') AS t"))
        // A named argument is no column, and a column a declared structure lacks is reported.
        val scope = RelationScope.analyze("SELECT a, nope FROM url('https://e/x', 'CSV', structure = 'a Int64')", "clickhouse", RelationFacts())
        assertEquals(listOf("Unknown column 'nope'"), scope.diagnostics.map { it.message })
    }
}
