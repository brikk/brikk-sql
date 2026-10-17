package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionOverload
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.GrammarForm
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.MetadataSnapshot
import junit.framework.TestCase

class LiveMergeTest : TestCase() {
    private val bundled = MetadataSnapshot(
        engineVersion = "1.0.0",
        functions = FunctionCatalog(
            listOf(
                FunctionDef("abs", FunctionKind.SCALAR, overloads = listOf(FunctionOverload(listOf("BIGINT"), "BIGINT"))),
                FunctionDef("lower", FunctionKind.SCALAR, overloads = listOf(FunctionOverload(listOf("VARCHAR"), "VARCHAR"))),
                FunctionDef("current_date", FunctionKind.SCALAR, overloads = listOf(FunctionOverload(emptyList(), "DATE"))),
            ),
            grammarBuiltins = setOf("CAST"),
        ),
        keywords = KeywordCatalog(listOf(KeywordDef("SELECT"))),
        extensionFunctions = listOf(FunctionDef("st_area", FunctionKind.SCALAR, overloads = listOf(FunctionOverload(listOf("GEOMETRY"), "DOUBLE")), extension = "spatial")),
        grammarForms = listOf(
            GrammarForm("current_date", setOf(InvocationSyntax.BARE)),
            GrammarForm("CAST", setOf(InvocationSyntax.SPECIAL_FORM), template = "CAST(expression AS type)"),
        ),
    )
    private val baseline = bundled.completableFunctions()
    private val newer = inventory(
        liveFunction("abs", "DOUBLE", returnType = "DOUBLE"),
        liveFunction("current_date", returnType = "DATE"),
        liveFunction("newfn", "INTEGER"),
        version = "2.0.0",
    )

    private fun LiveCompletion.named(name: String): FunctionPresentation? = functions.singleOrNull { it.name.equals(name, ignoreCase = true) }

    private fun live(inventory: LiveInventory, session: SessionFacts = SessionFacts.NONE) =
        LiveCatalogSelection(CatalogSource.Live(java.time.Instant.EPOCH), inventory, session)

    fun testNewerLiveSignaturesReplaceTheOlderBundledOnes() {
        val merged = LiveMergePolicy.REPLACE.merge(live(newer), bundled, baseline)
        assertEquals("DOUBLE", merged.named("abs")?.signature)
        assertEquals("a function the bundle lacks", "INTEGER", merged.named("newfn")?.signature)
        assertNull("a bundled function the server does not list", merged.named("lower"))
        assertEquals("live functions keep the grammar's forms", setOf(InvocationSyntax.BARE), merged.named("current_date")?.syntaxes)
        assertNotNull("grammar-only entries stay", merged.named("CAST"))
        assertEquals("an extension function not loaded yet stays", "spatial", merged.named("st_area")?.extension)
        assertTrue(merged.serverOnlyFunctions.isEmpty())
        assertNull(merged.builtinFunctionFilter)
    }

    fun testANamesOnlyInventoryProvesAvailabilityNotOverloads() {
        val namesOnly = inventory(*newer.functions.toTypedArray(), signatures = false)
        val merged = LiveMergePolicy.REPLACE.merge(live(namesOnly), bundled, baseline)
        assertEquals("the bundled overload stays", "BIGINT", merged.named("abs")?.signature)
        assertNotNull(merged.named("newfn"))
        assertNull(merged.named("lower"))
    }

    fun testFilterAndExtendKeepsBundledSignaturesForListedNames() {
        val merged = LiveMergePolicy.FILTER_AND_EXTEND.merge(live(inventory(*newer.functions.toTypedArray(), signatures = false)), bundled, baseline)
        assertEquals("BIGINT", merged.named("abs")?.signature)
        assertNull(merged.named("lower"))
        assertNotNull("entries no registry def backs stay", merged.named("CAST"))
        assertEquals(listOf("newfn"), merged.serverOnlyFunctions.map { it.name })
        val keeps = checkNotNull(merged.builtinFunctionFilter)
        assertTrue("known to the bundle", keeps("lower"))
        assertTrue("listed by the server", keeps("NEWFN"))
        assertFalse("known to neither", keeps("regexp_like"))
    }

    fun testExtendKeepsTheBundledFunctions() {
        val merged = LiveMergePolicy.EXTEND.merge(live(newer), bundled, baseline)
        assertEquals(baseline.map { it.name to it.kind }, merged.functions.map { it.name to it.kind })
        assertEquals(listOf("newfn"), merged.serverOnlyFunctions.map { it.name })
    }

    fun testWithoutAHarvestTheBundledFunctionsServeAndSessionFactsAdd() {
        val session = SessionFacts(listOf(liveFunction("my_macro")))
        for (policy in LiveMergePolicy.entries) {
            val merged = policy.merge(LiveCatalogSelection(CatalogSource.Bundled, null, session), bundled, baseline)
            assertEquals(policy.name, listOf("my_macro") + baseline.map { it.name }, merged.functions.map { it.name })
            assertSame(bundled.keywords, merged.keywords)
            assertNull(merged.builtinFunctionFilter)
        }
    }

    fun testSessionFunctionsAreOfferedWithALiveInventory() {
        val session = SessionFacts(listOf(liveFunction("my_macro")))
        for (policy in LiveMergePolicy.entries) {
            assertNotNull(policy.name, policy.merge(live(newer, session), bundled, baseline).named("my_macro"))
        }
    }

    fun testASessionFunctionShadowsTheServerAndBundledOnes() {
        val session = SessionFacts(listOf(liveFunction("abs", "ANY", "ANY", returnType = "ANY")))
        for (policy in LiveMergePolicy.entries) {
            val merged = policy.merge(live(newer, session), bundled, baseline)
            assertEquals(policy.name, "ANY, ANY", merged.named("abs")?.signature)
        }
        val bundledOnly = LiveMergePolicy.REPLACE.merge(LiveCatalogSelection(CatalogSource.Bundled, null, session), bundled, baseline)
        assertEquals("ANY, ANY", bundledOnly.named("abs")?.signature)
    }

    fun testFilterAndExtendPresentsLiveSignaturesWhenTheInventoryHasThem() {
        val merged = LiveMergePolicy.FILTER_AND_EXTEND.merge(live(newer), bundled, baseline)
        assertEquals("DOUBLE", merged.named("abs")?.signature)
        assertEquals("the grammar's form stays", setOf(InvocationSyntax.BARE), merged.named("current_date")?.syntaxes)
    }

    fun testLiveKeywordsReplaceTheBundledOnes() {
        val merged = LiveMergePolicy.REPLACE.merge(live(inventory(liveFunction("abs"), keywords = listOf(KeywordDef("QUALIFY")))), bundled, baseline)
        assertEquals(listOf("QUALIFY"), merged.keywords?.keywords?.map { it.word })
        assertSame("no harvested keywords", bundled.keywords, LiveMergePolicy.REPLACE.merge(live(newer), bundled, baseline).keywords)
    }

    fun testCaseSensitiveNamesStayDistinct() {
        val sensitive = MetadataSnapshot("1.0.0", FunctionCatalog(listOf(FunctionDef("Foo", FunctionKind.SCALAR, overloads = listOf(FunctionOverload(emptyList(), "INT")))), caseSensitive = true))
        val merged = LiveMergePolicy.REPLACE.merge(live(inventory(liveFunction("foo"), liveFunction("Foo"))), sensitive, sensitive.completableFunctions())
        assertEquals(setOf("foo", "Foo"), merged.functions.map { it.name }.toSet())
    }
}
