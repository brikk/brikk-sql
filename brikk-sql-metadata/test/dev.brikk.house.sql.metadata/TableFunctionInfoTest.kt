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
        assertFalse(doris.tableFunction("binlog")!!.queryable)
        assertTrue(doris.tableFunction("numbers")!!.queryable)
    }

    @Test
    fun everyDorisEntryIsARegisteredTableFunction() {
        for (info in doris.tableFunctions) assertTrue(doris.functions.isTableFunction(info.name), info.name)
        assertNull(doris.tableFunction("iceberg_meta"))
    }

    @Test
    fun tableFunctionInfoRoundTripsThroughJson() {
        val serializer = ListSerializer(TableFunctionInfo.serializer())
        val json = Json.encodeToString(serializer, doris.tableFunctions)

        assertEquals(doris.tableFunctions, Json.decodeFromString(serializer, json))
    }
}
