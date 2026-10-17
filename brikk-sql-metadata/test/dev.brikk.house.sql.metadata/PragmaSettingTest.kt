package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PragmaSettingTest {

    private val duckdb = DUCKDB_METADATA.default
    private val pragmas = checkNotNull(duckdb.pragmas).associateBy { it.name }

    @Test
    fun pragmaRowsArePragmasNotFunctions() {
        assertTrue("show_tables" in pragmas)
        assertFalse("show_tables" in duckdb.functions)
    }

    @Test
    fun aPragmaKeepsItsPositionalArgumentsAndTableFunction() {
        val tableInfo = checkNotNull(pragmas["table_info"])

        assertEquals(listOf(FunctionOverload(listOf("VARCHAR"), "ANY", argNames = listOf("col0"))), tableInfo.overloads)
        assertEquals("pragma_table_info", tableInfo.tableFunction)
        assertNull(pragmas["show_tables"]?.tableFunction)
    }

    @Test
    fun aNameThatIsBothPragmaAndFunctionAppearsInBoth() {
        assertTrue("version" in pragmas)
        assertEquals(FunctionKind.SCALAR, duckdb.functions["version"]?.kind)
    }

    @Test
    fun settingsCarryTypeScopeAndAliases() {
        val threads = checkNotNull(duckdb.settings).single { it.name == "threads" }

        assertEquals("BIGINT", threads.valueType)
        assertEquals("GLOBAL", threads.scope)
        assertEquals(listOf("worker_threads"), threads.aliases)
    }

    @Test
    fun dialectsWithoutCapturedPragmasOrSettingsReportUnknown() {
        assertNull(TRINO_METADATA.default.pragmas)
        assertNull(TRINO_METADATA.default.settings)
        assertNull(TRINO_METADATA.default.tableFunctions)
        assertNull(CLICKHOUSE_METADATA.default.grammarForms)
    }
}
