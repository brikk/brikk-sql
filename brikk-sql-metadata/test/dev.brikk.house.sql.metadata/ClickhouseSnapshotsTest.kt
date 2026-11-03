package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClickhouseSnapshotsTest {
    @Test
    fun snapshotsSelectExactAndNearestVersionsWithoutErasingTheEmbeddedBuild() {
        val metadata = CLICKHOUSE_METADATA
        assertEquals(listOf("26.5.1.1", "26.8.34.13", "26.10.2.103"), metadata.snapshots.map { it.engineVersion })
        assertSame(CLICKHOUSE_FUNCTION_CATALOG, metadata.default.functions)
        for (version in listOf("26.5.1.1", "26.8.34.13", "26.10.2.103")) {
            val selection = metadata.forServerVersion(version)
            assertTrue(selection.exact)
            assertEquals(version, selection.snapshot.engineVersion)
        }
        assertEquals("26.8.34.13", metadata.forServerVersion("26.8.1.1").snapshot.engineVersion)
        assertEquals("26.8.34.13", metadata.forServerVersion("26.9.25.15").snapshot.engineVersion)
        assertEquals("26.5.1.1", metadata.forServerVersion("25.8.1").snapshot.engineVersion)
        assertEquals("26.10.2.103", metadata.forServerVersion(null).snapshot.engineVersion)
        assertFalse(metadata.forServerVersion("26.10.2.103-beta").exact)
    }

    @Test
    fun everyCaptureHasItsOwnExactCatalogAndKeywords() {
        for ((version, counts) in listOf(
            "26.5.1.1" to listOf(1648, 87, 630), "26.8.34.13" to listOf(1735, 94, 658),
            "26.10.2.103" to listOf(1808, 99, 666),
        )) {
            val snapshot = CLICKHOUSE_METADATA.forServerVersion(version).snapshot
            assertEquals(counts[0], snapshot.functions.size)
            assertEquals(counts[1], snapshot.functions.functions.count { it.kind == FunctionKind.TABLE_VALUED })
            assertEquals(counts[2], snapshot.keywords?.keywords?.size)
            assertTrue(snapshot.functions.functions.all { it.overloads.isEmpty() && it.sinceVersion == null })
        }
        assertTrue(CLICKHOUSE_26_8_34_13_FUNCTION_CATALOG.isKnown("catboostEvaluate"))
        assertTrue(CLICKHOUSE_26_8_34_13_FUNCTION_CATALOG.isKnown("windowID"))
        assertFalse(CLICKHOUSE_FUNCTION_CATALOG.isKnown("catboostEvaluate"))
        assertFalse(CLICKHOUSE_FUNCTION_CATALOG.isKnown("windowID"))
    }

    @Test
    fun newFunctionKindsAliasesAndMixedCasePoliciesFollowTheEngine() {
        val catalog = CLICKHOUSE_FUNCTION_CATALOG
        for (name in listOf("gini", "groupFormat", "mergedJSONPatch", "regr_slope", "regr_count")) {
            assertEquals(FunctionKind.AGGREGATE, catalog[name]?.kind, name)
        }
        for (name in listOf("arrayTopK", "parseISO8601Duration", "arrayFlattenedLength", "arrayMinIndex", "mapEntries", "ipcryptEncrypt")) {
            assertEquals(FunctionKind.SCALAR, catalog[name]?.kind, name)
        }
        for (name in listOf("bigquery", "obfuscate", "elasticsearch")) {
            assertTrue(catalog.getAll(name).any { it.kind == FunctionKind.TABLE_VALUED }, name)
            assertNull(CLICKHOUSE_METADATA.default.tableFunction(name)) // argument-dependent schema remains unknown
        }
        assertEquals(listOf("sum"), catalog.getAll("SuM").map { it.name })
        assertEquals(listOf("regr_slope"), catalog.getAll("REGR_SLOPE").map { it.name })
        assertTrue(catalog.getAll("ARRAYTOPK").isEmpty())
        assertEquals(catalog["argMin"], catalog["min_by"])
        assertEquals(catalog["sparkbar"], catalog.getAll("sparkBar").single())
        assertTrue(CLICKHOUSE_METADATA.default.completionEntries().all { it.signature == null && it.returnType == null })
    }

    @Test
    fun typesAndRelevantSettingsAreVersionedAndKeepExperimentalEvidence() {
        val old = CLICKHOUSE_METADATA.forServerVersion("26.5.1.1").snapshot
        assertNull(old.types)
        assertNull(old.settings)
        for (snapshot in CLICKHOUSE_METADATA.snapshots.drop(1)) {
            assertEquals(141, snapshot.types?.size)
            assertEquals("QBit", snapshot.type("QBit")?.logicalType)
            assertNull(snapshot.type("qbit"))
            assertEquals("MultiPoint", snapshot.type("MultiPoint")?.logicalType)
            assertNotNull(snapshot.type("integer"))
            val streaming = snapshot.settings!!.single { it.name == "enable_streaming_queries" }
            assertTrue("Tier: Experimental" in streaming.description.orEmpty())
        }
        assertTrue(CLICKHOUSE_METADATA.default.settings!!.any { it.name == "sql_compatible_division_for_integers" })
        assertFalse(CLICKHOUSE_METADATA.forServerVersion("26.8.34.13").snapshot.settings!!.any { it.name == "sql_compatible_division_for_integers" })
    }
}
