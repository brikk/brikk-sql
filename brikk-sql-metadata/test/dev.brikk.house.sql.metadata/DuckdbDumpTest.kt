package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbDumpTest {

    private fun catalogOf(vararg rows: DuckdbFunctionRow) = DuckdbDump("2.0.0", rows.toList()).toSnapshot().functions

    @Test
    fun windowRowsMapToTheWindowKind() {
        val catalog = catalogOf(DuckdbFunctionRow("row_number", "window", "BIGINT"))

        assertEquals(FunctionKind.WINDOW, catalog["row_number"]?.kind)
    }

    @Test
    fun sessionFunctionsKeepTheirSchemaAndStayOutOfTheSnapshot() {
        val dump = DuckdbDump(
            "1.5.6",
            listOf(
                DuckdbFunctionRow("abs", "scalar", "INTEGER", listOf("INTEGER")),
                DuckdbFunctionRow("review_fn", "macro", null, listOf(null), listOf("x"), database = "memory", schema = "a", internal = false),
                DuckdbFunctionRow("review_fn", "macro", null, listOf(null, null), listOf("x", "y"), database = "memory", schema = "b", internal = false),
                DuckdbFunctionRow("t_fn", "macro", null, listOf(null), listOf("x"), database = "temp", internal = false),
            ),
        )

        assertEquals(listOf("abs"), dump.toSnapshot().functions.functions.map { it.name })
        assertEquals(
            listOf(
                Triple("memory", "a", listOf("x")),
                Triple("memory", "b", listOf("x", "y")),
                Triple("temp", "main", listOf("x")),
            ),
            dump.userFunctions().map { Triple(it.database, it.schema, it.def.overloads.single().argNames) },
        )
    }

    @Test
    fun partsThatWereNotCapturedAreUnknown() {
        val snapshot = DuckdbDump("1.5.6", listOf(DuckdbFunctionRow("abs", "scalar"))).toSnapshot()

        assertNull(snapshot.keywords)
        assertNull(snapshot.settings)
        assertNull(snapshot.extensions)
        assertNull(snapshot.extensionFunctions)
        assertEquals(emptyList(), DuckdbDump("1.5.6", emptyList(), extensionFunctions = emptyList()).toSnapshot().extensionFunctions)
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
