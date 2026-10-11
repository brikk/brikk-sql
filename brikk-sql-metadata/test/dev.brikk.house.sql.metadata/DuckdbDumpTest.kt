package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuckdbDumpTest {

    private fun catalogOf(vararg rows: DuckdbFunctionRow) = DuckdbDump("2.0.0", rows.toList()).toSnapshot().functions

    @Test
    fun windowRowsMapToTheWindowKind() {
        val catalog = catalogOf(DuckdbFunctionRow("row_number", "window", "BIGINT"))

        assertEquals(FunctionKind.WINDOW, catalog["row_number"]?.kind)
    }

    @Test
    fun anUnknownFunctionTypeFailsNamingTheTypeAndFunction() {
        val failure = assertFailsWith<IllegalArgumentException> {
            catalogOf(DuckdbFunctionRow("f", "window_macro"))
        }

        val message = failure.message.orEmpty()
        assertTrue("'window_macro'" in message && "f" in message, message)
    }
}
