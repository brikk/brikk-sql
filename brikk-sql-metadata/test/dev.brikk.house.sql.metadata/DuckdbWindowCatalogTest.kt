package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbWindowCatalogTest {
    @Test fun capturedWindowsAreDistinctFromScalarAndAggregateFunctions() {
        val functions=DUCKDB_METADATA.default.functions
        for(name in listOf("row_number","rank","dense_rank","lag","lead","first_value","last_value","nth_value","ntile","percent_rank","cume_dist"))
            assertEquals(FunctionKind.WINDOW,functions[name]?.kind,name)
        assertEquals(FunctionKind.AGGREGATE,functions["sum"]!!.kind)
        assertEquals(FunctionKind.SCALAR,functions["abs"]!!.kind)
    }
    @Test fun unitDecodePreservesThreeCoexistingKindsAndExtensionWindowsWithoutCallingThemLiveEvidence() {
        val kinds=listOf("scalar","aggregate","window")
        val rows=kinds.map { kind -> DuckdbFunctionRow("overlap",kind,"BIGINT") }
        val snapshot=DuckdbDump("2.0.0",rows,extensionFunctions=rows.map { DuckdbExtensionFunctionRow("fixture","test",it) }).toSnapshot()
        val expected=setOf(FunctionKind.SCALAR,FunctionKind.AGGREGATE,FunctionKind.WINDOW)
        assertEquals(expected,snapshot.functions.getAll("OVERLAP").map { it.kind }.toSet())
        assertEquals(expected,snapshot.extensionFunctions!!.map { it.kind }.toSet())
        assertEquals(expected,snapshot.completionEntries().filter { it.name=="overlap" }.map { it.kind }.toSet())
    }
}
