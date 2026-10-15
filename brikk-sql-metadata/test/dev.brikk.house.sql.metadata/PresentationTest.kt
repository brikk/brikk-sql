package dev.brikk.house.sql.metadata

import dev.brikk.house.sql.metadata.InvocationSyntax.BARE
import dev.brikk.house.sql.metadata.InvocationSyntax.CALL
import dev.brikk.house.sql.metadata.InvocationSyntax.SPECIAL_FORM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresentationTest {

    private val duckdb = DUCKDB_METADATA.default.completionEntries()
    private val trino = TRINO_METADATA.default.completionEntries()

    private fun List<FunctionPresentation>.named(name: String) = filter { it.name.equals(name, ignoreCase = true) }

    @Test
    fun aNameRegisteredUnderTwoKindsYieldsTwoEntries() {
        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED), duckdb.named("range").map { it.kind }.toSet())
        assertEquals(2, trino.named("sequence").size)
    }

    @Test
    fun scalarAndMacroRowsOfOneNameAreOneEntry() {
        val entry = duckdb.named("current_database").single()

        assertEquals(FunctionKind.SCALAR, entry.kind)
        assertNull(entry.nativeKind)
    }

    @Test
    fun eachAliasIsItsOwnEntry() {
        val entries = DORIS_METADATA.default.completionEntries()
        val substring = DORIS_FUNCTION_CATALOG["substring"]!!

        for (name in listOf(substring.name) + substring.aliases) {
            val entry = entries.named(name).single()
            assertEquals(name != substring.name, entry.isAlias, name)
        }
    }

    @Test
    fun clickhouseEntriesCarryNoSignature() {
        assertTrue(CLICKHOUSE_METADATA.default.completionEntries().all { it.signature == null && it.returnType == null })
    }

    @Test
    fun theFirstOverloadGivesSignatureAndReturnType() {
        val concat = duckdb.named("concat").single()

        assertEquals("ANY, ...", concat.signature)
        assertEquals("ANY", concat.returnType)
        assertEquals(1, concat.overloadCount)
    }

    @Test
    fun namedParametersAreThoseAfterTheOverloadsPositionalOnes() {
        val def = FunctionDef(
            "reader", FunctionKind.TABLE_VALUED,
            overloads = listOf(
                FunctionOverload(listOf("VARCHAR", "BOOLEAN"), "TABLE", argNames = listOf("col0", "header"), positionalCount = 1),
                FunctionOverload(listOf("VARCHAR[]", "BOOLEAN", "VARCHAR"), "TABLE", argNames = listOf("col0", "header", "sep"), positionalCount = 1),
                FunctionOverload(listOf("INTEGER"), "TABLE", argNames = listOf("x")),
            ),
        )
        assertEquals(listOf("header", "sep"), def.presentation().namedParameters)
        assertTrue("filename" in duckdb.single { it.name == "read_parquet" && it.kind == FunctionKind.TABLE_VALUED }.namedParameters)
        assertEquals(emptyList(), duckdb.named("abs").single().namedParameters)
    }

    @Test
    fun aZeroArgumentRegisteredFunctionIsACall() {
        assertEquals(setOf(CALL), duckdb.named("version").single().syntaxes)
    }

    @Test
    fun grammarFormsDecideBareAndSpecialSyntax() {
        assertEquals(setOf(BARE, CALL), duckdb.named("current_date").single().syntaxes)
        assertEquals(setOf(BARE), duckdb.named("current_timestamp").single().syntaxes)
        assertEquals(setOf(BARE), trino.named("current_date").single().syntaxes)
        assertEquals(setOf(BARE, CALL), trino.named("current_timestamp").single().syntaxes)

        val cast = duckdb.named("cast").single()
        assertEquals(setOf(SPECIAL_FORM), cast.syntaxes)
        assertEquals("CAST(expression AS type)", cast.template)
    }

    @Test
    fun extensionFunctionsAreOfferedWithTheirExtension() {
        assertTrue(duckdb.named("ST_Area").all { it.extension == "spatial" })
        assertTrue(duckdb.named("ST_Area").isNotEmpty())
        assertNull(duckdb.named("abs").single().extension)
    }

    @Test
    fun everyGrammarBuiltinHasAGrammarForm() {
        for (metadata in listOf(DUCKDB_METADATA, TRINO_METADATA, DORIS_METADATA, STARROCKS_METADATA)) {
            val snapshot = metadata.default
            val forms = checkNotNull(snapshot.grammarForms).map { it.name }.toSet()
            assertTrue(forms.containsAll(snapshot.functions.grammarBuiltins), metadata.dialect)
        }
    }

    @Test
    fun getAllReturnsEveryKindWhileGetStaysLastWins() {
        val catalog = DUCKDB_METADATA.default.functions

        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED), catalog.getAll("RANGE").map { it.kind }.toSet())
        assertEquals(FunctionKind.TABLE_VALUED, catalog["range"]?.kind)
    }

    private fun caseCatalog(caseSensitive: Boolean) = FunctionCatalog(
        listOf(FunctionDef("dateDiff", FunctionKind.SCALAR), FunctionDef("DATEDIFF", FunctionKind.SCALAR)),
        caseSensitive = caseSensitive,
    )

    @Test
    fun caseSensitiveNamesAreNeverMerged() {
        val snapshot = MetadataSnapshot(
            "1",
            caseCatalog(caseSensitive = true),
            tableFunctions = listOf(TableFunctionInfo("numbers")),
        )

        assertEquals(listOf("dateDiff", "DATEDIFF"), snapshot.completionEntries().map { it.name })
        assertEquals(listOf("dateDiff"), snapshot.functions.getAll("dateDiff").map { it.name })
        assertTrue(snapshot.functions.getAll("datediff").isEmpty())
        assertNull(snapshot.tableFunction("Numbers"))
    }

    @Test
    fun caseInsensitiveNamesMergeToTheFirstSpelling() {
        val snapshot = MetadataSnapshot("1", caseCatalog(caseSensitive = false))

        assertEquals(listOf("dateDiff"), snapshot.completionEntries().map { it.name })
        assertEquals(2, snapshot.functions.getAll("datediff").size)
    }

    @Test
    fun clickhouseFollowsItsCaseSensitivePolicy() {
        val catalog = CLICKHOUSE_METADATA.default.functions

        assertTrue(catalog.caseSensitive)
        assertEquals(listOf("groupBitAnd"), catalog.getAll("BIT_AND").map { it.name })
        assertTrue(catalog.getAll("GROUPBITAND").isEmpty())
    }
}
