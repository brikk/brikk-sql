package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatafusionSpiceMetadataTest {
    @Test
    fun profilesHaveSeparateInventoriesAndVersionSelection() {
        assertEquals(234, DATAFUSION_FUNCTION_CATALOG.size)
        assertEquals(447, SPICE_FUNCTION_CATALOG.size)
        assertFalse("vector_search" in DATAFUSION_FUNCTION_CATALOG)
        assertTrue("vector_search" in SPICE_FUNCTION_CATALOG)
        assertEquals("55.2.0", DATAFUSION_METADATA.default.engineVersion)
        assertEquals("2.4.0", SPICE_METADATA.default.engineVersion)
        assertTrue(SPICE_METADATA.forServerVersion("2.4.0").exact)
        assertFalse(SPICE_METADATA.forServerVersion("2.3.0").exact)
        assertFalse(DATAFUSION_METADATA.forServerVersion(null).exact)
        assertEquals(DATAFUSION_METADATA, bundledMetadata("datafusion"))
        assertEquals(SPICE_METADATA, bundledMetadata("spice"))
    }

    @Test
    fun scalarAndTableRegistrationsRemainDistinct() {
        val kinds = setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED)
        assertEquals(kinds, DATAFUSION_FUNCTION_CATALOG.getAll("generate_series").map { it.kind }.toSet())
        assertEquals(kinds, SPICE_FUNCTION_CATALOG.getAll("vector_search").map { it.kind }.toSet())
        assertEquals("SCALAR_STUB", SPICE_FUNCTION_CATALOG.getAll("vector_search").single { it.kind == FunctionKind.SCALAR }.nativeKind)
        assertEquals(kinds, SPICE_METADATA.default.completionEntries().filter { it.name == "vector_search" }.map { it.kind }.toSet())
        assertEquals(OutputSchema.Open, SPICE_METADATA.default.tableFunction("vector_search")!!.schema)
        assertEquals(FunctionKind.AGGREGATE, SPICE_FUNCTION_CATALOG["sum"]!!.kind)
        assertEquals(FunctionKind.WINDOW, DATAFUSION_FUNCTION_CATALOG["row_number"]!!.kind)
    }

    @Test
    fun representativeSamplesNeverBecomeExhaustiveOverloads() {
        val sqrt = DATAFUSION_FUNCTION_CATALOG["sqrt"]!!
        assertEquals(setOf(listOf("Float32"), listOf("Float64")), sqrt.overloads.map { it.argTypes }.toSet())
        assertTrue(DATAFUSION_FUNCTION_CATALOG["abs"]!!.overloads.isEmpty())
        assertTrue(DATAFUSION_PROFILE_EVIDENCE.functions("abs").single().samples.isNotEmpty())
        assertTrue(SPICE_FUNCTION_CATALOG["sqrt"]!!.overloads.isEmpty())
        assertTrue(SPICE_PROFILE_EVIDENCE.functions("sqrt").single().samples.isNotEmpty())
        val table = SPICE_PROFILE_EVIDENCE.functions("vector_search").single { it.kind == FunctionKind.TABLE_VALUED }
        assertTrue(table.samples.all { it.argumentTypes == null })
        assertNull(SPICE_PROFILE_EVIDENCE.functions("truncate").single().nativeSignature)
        assertEquals("Nullary", DATAFUSION_PROFILE_EVIDENCE.functions("random").single().nativeSignature)
    }

    @Test
    fun aliasesAreOnlyMergedFromRegistryIdentity() {
        assertEquals(DATAFUSION_FUNCTION_CATALOG["array_has"], DATAFUSION_FUNCTION_CATALOG["list_contains"])
        assertTrue(DATAFUSION_FUNCTION_CATALOG["array_has"]!!.aliases.contains("array_contains"))
        assertTrue(SPICE_FUNCTION_CATALOG["array_has"]!!.aliases.isEmpty())
        assertTrue("array_contains" in SPICE_FUNCTION_CATALOG)
    }

    @Test
    fun settingsTypesAndDocsRetainTheirEvidenceScope() {
        assertEquals("true", DATAFUSION_PROFILE_EVIDENCE.settingDefaults["datafusion.sql_parser.enable_ident_normalization"])
        assertEquals("false", SPICE_PROFILE_EVIDENCE.settingDefaults["datafusion.sql_parser.enable_ident_normalization"])
        assertEquals(KeywordCategory.UNKNOWN, SPICE_METADATA.default.keywords!!["SELECT"]!!.category)
        assertNotNull(DATAFUSION_METADATA.default.type("BIGINT"))
        assertNotNull(SPICE_METADATA.default.type("VARCHAR"))
        assertNull(SPICE_METADATA.default.type("a_type_we_did_not_probe"))
        val docs = assertNotNull(SPICE_METADATA.docs)
        assertEquals("2.4.0", docs.release)
        assertTrue(docs.url(docs.tableFunctions.getValue("rrf")).startsWith("https://spiceai.org/"))
    }
}
