package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax.BARE
import dev.brikk.house.sql.metadata.InvocationSyntax.CALL
import dev.brikk.house.sql.metadata.InvocationSyntax.SPECIAL_FORM
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.MetadataSnapshot

private val extensionProfile = TestCompletionProfile(
    DialectMetadata(
        "test",
        listOf(
            MetadataSnapshot(
                "1.0",
                FunctionCatalog(listOf(FunctionDef("st_area", FunctionKind.SCALAR))),
                extensionFunctions = listOf(
                    FunctionDef("ST_Area", FunctionKind.AGGREGATE, extension = "spatial"),
                    FunctionDef("ST_Length", FunctionKind.SCALAR, extension = "spatial"),
                ),
            ),
        ),
        "1.0",
    ),
)

class ExtensionCompletion : SqlCompletionContributor(extensionProfile)

class SqlFunctionCompletionTest : CompletionTestCase() {
    private val duckdb = DUCKDB_METADATA.default.completableFunctions()

    private fun duckdbEntry(predicate: (FunctionPresentation) -> Boolean) = duckdb.first(predicate)

    private fun insertion(entry: FunctionPresentation, before: String = "", after: String = ""): String {
        configure("SELECT $before${entry.name}<caret>$after", DuckdbCompletion::class.java)
        accept(entry)
        return resultWithCaret()
    }

    fun testACallWithArgumentsPutsTheCaretInsideNewParentheses() {
        val abs = duckdbEntry { it.name == "abs" && it.kind == FunctionKind.SCALAR }
        assertEquals("SELECT abs(<caret>)", insertion(abs))
    }

    fun testAZeroArgumentRegisteredFunctionIsStillACall() {
        val niladic = duckdbEntry { it.syntaxes == setOf(CALL) && it.overloadCount == 1 && it.signature == "" }
        assertEquals("SELECT ${niladic.name}()<caret>", insertion(niladic))
    }

    fun testAGrammarBareFormInsertsOnlyTheName() {
        val bare = duckdbEntry { it.syntaxes == setOf(BARE) }
        assertEquals("SELECT ${bare.name}<caret>", insertion(bare))
    }

    fun testANameAcceptedBareAndCalledInsertsTheBareForm() {
        val both = duckdbEntry { it.syntaxes == setOf(BARE, CALL) }
        assertEquals("SELECT ${both.name}<caret>", insertion(both))
    }

    fun testASpecialFormInsertsOnlyTheName() {
        val special = duckdbEntry { it.syntaxes == setOf(SPECIAL_FORM) }
        assertEquals("SELECT ${special.name}<caret>", insertion(special))
    }

    fun testAnExistingParenthesisIsReused() {
        val abs = duckdbEntry { it.name == "abs" && it.kind == FunctionKind.SCALAR }
        assertEquals("SELECT abs(<caret>-1)", insertion(abs, after = "(-1)"))
    }

    fun testANameUnderTwoKindsIsOfferedOncePerKind() {
        val multiKind = duckdb.groupBy { it.name }.values.first { entries -> entries.map { it.kind }.distinct().size > 1 }
        configure("SELECT ${multiKind.first().name}<caret>", DuckdbCompletion::class.java)
        assertContainsElements(functionItems(), multiKind)
    }

    fun testExtensionFunctionsAreOfferedOnlyWhenTheBaseEngineLacksTheName() {
        configure("SELECT <caret>", ExtensionCompletion::class.java)
        assertSameElements(functionItems().map { it.name to it.extension }, "st_area" to null, "ST_Length" to "spatial")
    }

    fun testTableFunctionsMetadataMarksNotQueryableAreNotOffered() {
        val snapshot = DORIS_METADATA.default
        val hidden = snapshot.tableFunctions.orEmpty().first { !it.queryable && snapshot.functions.getAll(it.name).isNotEmpty() }
        val shown = snapshot.tableFunctions.orEmpty().first { it.queryable && snapshot.functions.getAll(it.name).isNotEmpty() }
        fun offered(name: String): Boolean {
            configure("SELECT * FROM $name<caret>", DorisCompletion::class.java)
            return functionItems().any { it.name.equals(name, ignoreCase = true) }
        }
        assertTrue(offered(shown.name))
        assertFalse(offered(hidden.name))
    }

    fun testServerOnlyFunctionsAreOfferedAsCalls() {
        val extra = FunctionPresentation("server_only_fn", false, FunctionKind.SCALAR, null, null, null, 0, null, setOf(CALL))
        duckdbProfile.serverOnly = listOf(extra)
        configure("SELECT server_only<caret>", DuckdbCompletion::class.java)
        accept(extra)
        assertEquals("SELECT server_only_fn(<caret>)", resultWithCaret())
    }

    fun testFunctionsMatchIgnoringCase() {
        configure("SELECT ABS<caret>", DuckdbCompletion::class.java)
        assertTrue(functionItems().any { it.name == "abs" })
    }
}
