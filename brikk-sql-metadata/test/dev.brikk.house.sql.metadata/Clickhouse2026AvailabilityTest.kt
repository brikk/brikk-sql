package dev.brikk.house.sql.metadata

import kotlin.test.*

class Clickhouse2026AvailabilityTest {
    @Test
    fun currentAdvertisingRetiresRemovedNamesButRetainsHistoricalKnowledge() {
        val old = CLICKHOUSE_METADATA.forServerVersion("26.8.34.13").snapshot
        val current = CLICKHOUSE_METADATA.default
        for (name in listOf("catboostEvaluate", "windowID")) {
            assertTrue(old.functions.isKnown(name))
            assertFalse(current.functions.isKnown(name))
        }
        for (name in listOf("tumble", "hop")) assertTrue(current.functions.isKnown(name))
        assertFalse(CLICKHOUSE_DOCS.statements.any { "WINDOW" in it.pattern || "WATCH" in it.pattern })
        assertTrue(CLICKHOUSE_HISTORICAL_DOCS.statements.any { "|WINDOW" in it.pattern })
    }

    @Test
    fun tryCastIsANativeGrammarFormOnlyForTheMeasuredStableSnapshot() {
        val old = CLICKHOUSE_METADATA.forServerVersion("26.8.34.13").snapshot
        val current = CLICKHOUSE_METADATA.default
        assertFalse(old.functions.isKnown("TRY_CAST"))
        assertTrue(current.functions.isKnown("TRY_CAST"))
        assertFalse(current.functions.contains("TRY_CAST")) // not a registered function or fabricated overload
        val form = current.grammarForms!!.single { it.name == "TRY_CAST" }
        assertEquals(setOf(InvocationSyntax.SPECIAL_FORM), form.syntaxes)
        assertEquals("TRY_CAST(expression AS type)", form.template)
        assertEquals(CLICKHOUSE_DOCS, CLICKHOUSE_METADATA.docs)
    }

    @Test
    fun registryAliasesDoNotInventZeroArgumentYearDeterminism() {
        for (snapshot in CLICKHOUSE_METADATA.snapshots) {
            assertEquals(snapshot.functions["toYear"], snapshot.functions["YEAR"])
            assertTrue(snapshot.functions["YEAR"]!!.overloads.isEmpty())
            assertNull(snapshot.functions["YEAR"]!!.profile)
        }
    }
}
