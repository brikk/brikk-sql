package dev.brikk.house.sql.intellij.relation

import com.intellij.database.symbols.DasColumnSymbol

/** Light column symbols for provider columns, and resolve-inspection suppression only where the analysis decides. */
class RelationResolveTest : RelationTestCase() {
    private fun resolved(text: String, name: String): Any? {
        open(text)
        return myFixture.file.findReferenceAt(myFixture.file.text.indexOf(name))?.resolve()
    }

    fun testAProviderColumnResolvesToALightSymbol() {
        val sql = "SELECT e.id FROM events() e"
        assertNull(resolved(sql, "id"))
        registerPgRelations(testRootDisposable)
        val symbol = resolved(sql, "id") as DasColumnSymbol
        assertEquals("id", symbol.name)
        assertEquals(symbol, myFixture.file.findReferenceAt(sql.indexOf("id"))?.resolve())
    }

    fun testPendingAndPlatformResolvedFactsGiveNoSymbols() {
        registerPgRelations(testRootDisposable)
        pgRelations.fake.pending = true
        assertNull(resolved("SELECT e.id FROM events() e", "id"))
        pgRelations.fake.pending = false
        pgRelations.fake.platformResolves = true
        assertNull(resolved("SELECT e.id FROM events() e", "id"))
    }

    fun testOnlyReferencesTheAnalysisDecidesLoseTheResolveInspection() {
        enableResolveInspection()
        val sql = "SELECT e.id, e.nope, x.y FROM events() e, nowhere x;"
        open(sql)
        val platform = reports().filter { it.second?.startsWith("Un") == true }.map { it.first }
        assertSameElements(platform, "id", "nope", "y", "events", "nowhere")
        registerPgRelations(testRootDisposable)
        open(sql)
        // The slot, its resolved column and the missing column the analysis reports are decided; the rest stays.
        val reported = reports()
        assertSameElements(reported.map { it.first }, "y", "nowhere", "nope")
        assertEquals("only the analysis reports the missing column", 1, reported.count { it.first == "nope" })
    }
}
