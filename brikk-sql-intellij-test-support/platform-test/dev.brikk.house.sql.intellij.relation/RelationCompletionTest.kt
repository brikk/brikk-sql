package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.completion.SqlLookupKind

/** Create-or-decorate completion of the analysis's columns, labeled through the presentation policy. */
class RelationCompletionTest : RelationTestCase() {
    override fun setUp() {
        super.setUp()
        registerPgRelations(testRootDisposable)
    }

    /** The items named [names] offered at the caret in [text], as name, tail and type. */
    private fun offered(text: String, vararg names: String): List<Triple<String, String?, String?>> {
        open(text)
        return items(*names).map { val p = LookupElementPresentation.renderElement(it); Triple(it.lookupString, p.tailText, p.typeText) }
    }

    private fun items(vararg names: String): List<LookupElement> =
        myFixture.completeBasic().orEmpty().filter { it.lookupString in names }

    fun testColumnsOfAQualifierAreOfferedOnceWithTheirLabels() {
        assertSameElements(offered("SELECT e.<caret> FROM events() e", "id", "kind"),
            Triple("id", "  declared column (e)", "Long"), Triple("kind", "  declared column (e)", "Kind?"))
    }

    fun testSameNamedColumnsOfDifferentRelationsStayDistinct() {
        val ids = offered("SELECT i<caret> FROM events() a CROSS JOIN events() b", "id").map { it.second }
        assertSameElements(ids, "  declared column (a)", "  declared column (b)")
    }

    fun testAcceptingInsertsTheColumn() {
        open("SELECT e.i<caret> FROM events() e")
        val item = items("id").single()
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT e.id FROM events() e", myFixture.editor.document.text)
    }

    fun testPipeStagesGetTheirInputAndComputedColumns() {
        assertSameElements(offered("FROM events() |> EXTEND id + 1 AS k |> SELECT <caret>", "id", "kind", "k").map { it.first to it.second },
            "id" to "  declared column (events)", "kind" to "  declared column (events)", "k" to "  computed column (EXTEND)")
    }

    fun testAProvidersLabelsAndKindChooseThePresentation() {
        pgRelations.fake.labels = RelationColumnLabels(declared = "brikk column", declaredKind = SqlLookupKind.BRIKK_COLUMN)
        open("FROM events() |> AS e |> SELECT e.<caret>")
        val item = items("id").single()
        val presentation = LookupElementPresentation.renderElement(item)
        assertEquals("  brikk column (e)", presentation.tailText)
        assertEquals(SqlLookupIcons.BrikkColumn, presentation.icon)
        // Through a rename the column keeps its origin.
        assertEquals(listOf("  brikk column (events)"), offered("FROM events() |> RENAME id AS key |> SELECT <caret>", "key").map { it.second })
    }

    fun testAPlatformItemForAVisibleColumnIsDecoratedNotDuplicated() {
        open("WITH c AS (SELECT 1 AS total) SELECT tot<caret> FROM c")
        val total = items("total").single()
        assertEquals("  computed column (c)", LookupElementPresentation.renderElement(total).tailText)
    }

    fun testASlotIsOfferedWhereARelationStandsAndInsertedAsACall() {
        open("SELECT * FROM ev<caret>")
        val item = items("events").single()
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT * FROM events()", myFixture.editor.document.text)
    }

    fun testCreatedColumnsLeadAtUnqualifiedPositions() {
        for (text in listOf("SELECT <caret> FROM events() e", "SELECT * FROM events() e WHERE <caret>", "SELECT * FROM events() e ORDER BY <caret>")) {
            open(text)
            val all = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue("$text: ${all.take(10)}", all.take(5).containsAll(listOf("id", "kind")))
        }
    }

    fun testNothingIsOfferedWhileFactsArePending() {
        pgRelations.fake.pending = true
        assertEmpty(offered("FROM events() |> SELECT <caret>", "id", "kind"))
    }
}
