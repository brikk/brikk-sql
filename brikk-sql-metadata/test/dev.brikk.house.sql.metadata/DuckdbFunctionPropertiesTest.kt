package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbFunctionPropertiesTest {
    @Test fun observedFlagsArePreservedWithoutInventingNullDefaultOrArgumentRoleFacts() {
        val row=listOf("system","main","fn","scalar","INTEGER","[\"INTEGER\"]","[\"col0\"]",null,"alias","VOLATILE","true",null,"desc","[\"fn(1)\"]")
        val facts=DUCKDB_FUNCTION_PROPERTIES.decode(row)
        assertEquals(listOf("INTEGER"),facts.parameterTypes);assertEquals("VOLATILE",facts.stability)
        assertEquals(true,facts.hasSideEffects);assertEquals("alias",facts.aliasOf);assertEquals(listOf("fn(1)"),facts.examples)
        assertFalse(DUCKDB_FUNCTION_PROPERTIES.columns.any { it in setOf("null_handling","argument_defaults","positional_count","table_columns","macro_definition","comment","tags") })
        val catalog=DuckdbDump("2.0.0",listOf(DuckdbFunctionRow("fn","scalar","INTEGER",listOf("INTEGER"),listOf("col0")))).toSnapshot().functions
        assertNull(catalog["fn"]!!.profile)
    }
    @Test fun genericColNamesOrSameNamedUserFunctionsDoNotInventNamedParameters() {
        for(row in listOf(DuckdbFunctionRow("not_a_proved_reader","table","ANY",listOf("VARCHAR","BOOLEAN"),listOf("col0","flag")),
            DuckdbFunctionRow("read_json","table","ANY",listOf("VARCHAR","BOOLEAN"),listOf("col0","flag"),database="memory",internal=false))) {
            val dump=DuckdbDump("2.0.0",listOf(row))
            val def=if(row.internal)dump.toSnapshot().functions[row.name]!! else dump.userFunctions().single().def
            assertNull(def.overloads.single().positionalCount)
            assertEquals(row.parameters,def.overloads.single().argNames)
        }
        assertEquals(1,DUCKDB_METADATA.default.functions["read_json"]!!.overloads.first().positionalCount)
    }
    @Test fun existingCaptureSchemasStillExcludeConnectionValuesAndKeepTheirOldColumns() {
        assertEquals(13,DUCKDB_FUNCTIONS.columns.size)
        assertTrue(DUCKDB_SETTINGS.columns.none { it in setOf("value","typed_value","default_value") })
        assertTrue(DUCKDB_SETTINGS.sql.contains("input_type"))
    }
}
