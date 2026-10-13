package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.psi.PsiElement
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts

/** Go to declaration: a provider's target for its columns and relations, the SQL's declaration where the platform has no reference. */
class RelationNavigationTest : RelationTestCase() {
    override fun setUp() {
        super.setUp()
        registerPgRelations(testRootDisposable)
        // One file per declaration: a plain text file is a single element.
        val declarations = listOf("events", "id", "kind").associateWith { myFixture.addFileToProject("$it.txt", it) }
        pgRelations.fake.targets = { _, provenance -> declarations[provenance.removePrefix("decl:")] }
    }

    /** Where go to declaration leads from the [nth] occurrence of [word] in [text], as the text there. */
    private fun targets(text: String, word: String, nth: Int = 0): List<String> {
        open(text)
        val offset = Regex("\\b$word\\b").findAll(text).elementAt(nth).range.first
        val handler = GotoDeclarationHandler.EP_NAME.extensionList.single { it is PgRelationGoto }
        return handler.getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor).orEmpty().map(::describe)
    }

    /** A declaration file's name, or where in the SQL a target is. */
    private fun describe(element: PsiElement): String =
        if (element is com.intellij.psi.PsiFile) element.name else "sql@" + element.textRange.startOffset

    fun testAProviderColumnLeadsToItsDeclaration() {
        assertEquals(listOf("id.txt"), targets("SELECT e.id FROM events() e", "id"))
        assertEquals(listOf("kind.txt"), targets("FROM events() |> SELECT kind", "kind"))
    }

    fun testARenamedColumnStillLeadsToItsProvider() {
        assertEquals(listOf("id.txt"), targets("FROM events() |> RENAME id AS key |> SELECT key", "key", 1))
    }

    fun testAComputedPipeColumnLeadsToItsDeclarationInTheSql() {
        val text = "FROM events() |> EXTEND id + 1 AS k |> SELECT k"
        assertEquals(listOf("sql@${text.indexOf("AS k") + 3}"), targets(text, "k", 1))
    }

    fun testASlotLeadsToItsRelation() {
        assertEquals(listOf("events.txt"), targets("SELECT 1 FROM events() e", "events"))
    }

    fun testNothingWhileFactsArePending() {
        pgRelations.fake.pending = true
        assertEmpty(targets("SELECT e.id FROM events() e", "id"))
    }

    fun testSameKeyedSlotAndTableLeadToTheirOwnProviders() {
        val slot = FakeFacts(RelationFacts(slots = mapOf("same" to RelationFact(listOf(ColumnFact("id", provenance = "decl:events"))))))
        val table = FakeFacts(RelationFacts(tables = mapOf("same" to RelationFact(listOf(ColumnFact("id", provenance = "decl:kind"))))))
        val targets = pgRelations.fake.targets
        // Each provider knows only its own declarations.
        slot.targets = { file, provenance -> if (provenance == "decl:events") targets(file, provenance) else null }
        table.targets = { file, provenance -> if (provenance == "decl:kind") targets(file, provenance) else null }
        pgRelations.providers = listOf(slot, table)
        assertEquals(listOf("kind.txt"), targets("SELECT t.id FROM same t", "id"))
        assertEquals(listOf("events.txt"), targets("SELECT t.id FROM same() t", "id"))
    }

    fun testUnqualifiedColumnsOverAnAliasedSlotOrThroughSelectStarLeadToTheirDeclarations() {
        assertEquals(listOf("id.txt"), targets("SELECT id FROM events() e", "id"))
        assertEquals(listOf("kind.txt"), targets("SELECT * FROM events() AS e WHERE kind IS NULL", "kind"))
        assertEquals(listOf("id.txt"), targets("WITH c AS (SELECT * FROM events()) SELECT c.id FROM c", "id"))
    }
}
