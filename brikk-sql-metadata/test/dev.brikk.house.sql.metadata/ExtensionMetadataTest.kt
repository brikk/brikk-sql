package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionMetadataTest {

    private val duckdb = DUCKDB_METADATA.default

    @Test
    fun extensionFunctionsStayOutOfTheBaseCatalog() {
        val area = duckdb.extensionFunctions.filter { it.name == "ST_Area" }

        assertTrue(area.isNotEmpty())
        assertTrue(area.all { it.extension == "spatial" && it.overloads.isNotEmpty() })
        assertFalse("ST_Area" in duckdb.functions)
        assertTrue(duckdb.functions.functions.all { it.extension == null })
    }

    @Test
    fun extensionFunctionsKeepEveryKind() {
        val kinds = duckdb.extensionFunctions.filter { it.extension == "spatial" }.map { it.kind }.toSet()

        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.AGGREGATE, FunctionKind.TABLE_VALUED), kinds)
    }

    @Test
    fun extensionPragmasNameTheirExtension() {
        val pragma = checkNotNull(duckdb.pragmas).single { it.name == "create_fts_index" }

        assertEquals("fts", pragma.extension)
    }

    @Test
    fun staticallyLinkedExtensionsAreMarked() {
        val extensions = checkNotNull(duckdb.extensions).associateBy { it.name }

        assertEquals(true, extensions["icu"]?.staticallyLinked)
        assertEquals(false, extensions["spatial"]?.staticallyLinked)
        assertEquals(listOf("http", "https", "s3"), extensions["httpfs"]?.aliases)
    }
}
