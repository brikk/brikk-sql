package dev.brikk.house.sql.metadata

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableFunctionInfoTest {

    private val duckdb = DUCKDB_METADATA.default.functions
    private val doris = DORIS_METADATA.default
    private val dorisTableFunctions = checkNotNull(doris.tableFunctions)

    private fun tableOverloads(name: String) =
        duckdb.functions.single { it.name == name && it.kind == FunctionKind.TABLE_VALUED }.overloads

    @Test
    fun duckdbReadersTakeOnePositionalArgumentThenNamedParameters() {
        for (name in listOf("read_csv", "read_parquet", "read_json")) {
            val overloads = tableOverloads(name)
            assertTrue(overloads.isNotEmpty(), name)
            assertTrue(overloads.all { it.positionalCount == 1 && it.argNames.orEmpty().size > 1 }, name)
        }
    }

    @Test
    fun allPositionalOverloadsHaveNoPositionalCount() {
        assertTrue(tableOverloads("range").all { it.positionalCount == null })
        assertTrue(duckdb["round"]!!.overloads.all { it.positionalCount == null })
    }

    @Test
    fun dorisTableFunctionsAreFoundIgnoringCase() {
        val s3 = doris.tableFunction("S3")!!

        assertEquals(OutputSchema.Open, s3.schema)
        assertTrue(s3.properties.single { it.name == "format" }.values.containsAll(listOf("csv", "parquet")))
    }

    @Test
    fun dorisSchemasCanDependOnAPropertyValue() {
        val jobs = assertIs<OutputSchema.ByProperty>(doris.tableFunction("jobs")!!.schema)
        assertEquals("type", jobs.key)
        assertEquals(setOf("insert", "mv"), jobs.variants.keys)
        assertNull(jobs.default)

        val parquet = assertIs<OutputSchema.ByProperty>(doris.tableFunction("parquet_meta")!!.schema)
        assertEquals("parquet_metadata", parquet.default)
    }

    @Test
    fun ingestFunctionsAreNotQueryable() {
        assertFalse(doris.tableFunction("group_commit")!!.queryable)
        assertTrue(doris.tableFunction("numbers")!!.queryable)
    }

    @Test
    fun everyDorisEntryIsARegisteredTableFunction() {
        for (info in dorisTableFunctions) assertTrue(doris.functions.isTableFunction(info.name), info.name)
        assertNull(doris.tableFunction("iceberg_meta"))
        assertNull(doris.tableFunction("binlog"))
    }

    @Test
    fun doris415SearchFunctionsKeepDynamicAndFixedSchemasSeparate() {
        assertEquals("4.1.5", doris.engineVersion)
        val fullText = doris.tableFunction("FULL_TEXT_SEARCH")!!
        assertEquals(OutputSchema.Open, fullText.schema)
        assertEquals(listOf("match", "phrase"), fullText.properties.definitionOf("query_type")?.values)
        assertEquals(listOf("0"), fullText.properties.definitionOf("max_fuzzy_distance")?.values)
        for (name in listOf("vector_search", "full_text_search")) {
            val info = doris.tableFunction(name)!!
            assertEquals(OutputSchema.Open, info.schema)
            for (key in listOf("prefilter", "version", "timestamp", "tag", "branch")) {
                assertEquals("4.1.5", info.properties.definitionOf(key)?.since, "$name.$key")
            }
        }
        val vector = doris.tableFunction("vector_search")!!
        for (key in listOf("query_parallelism", "distance_lower_bound", "distance_upper_bound")) {
            assertEquals("4.1.5", vector.properties.definitionOf(key)?.since, key)
        }
        val entries = doris.tableFunction("lance_index_entries")!!
        assertEquals(listOf("table"), entries.properties.map { it.name })
        assertEquals(listOf(
            ColumnDef("CatalogName", "text"), ColumnDef("DatabaseName", "text"), ColumnDef("TableName", "text"),
            ColumnDef("IndexName", "text"), ColumnDef("IndexUuid", "text"), ColumnDef("DatasetVersion", "bigint"),
        ), assertIs<OutputSchema.Fixed>(entries.schema).columns)
    }

    @Test
    fun tableFunctionInfoRoundTripsThroughJson() {
        val serializer = ListSerializer(TableFunctionInfo.serializer())
        val json = Json.encodeToString(serializer, dorisTableFunctions)

        assertEquals(dorisTableFunctions, Json.decodeFromString(serializer, json))
    }
}
