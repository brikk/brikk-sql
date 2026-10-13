package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts

/**
 * The shared features beside the native table-type adapter, as Doris wires them: completion decorates the
 * platform's items for the adapter's columns and hides those the analysis does not see; navigation follows
 * the analysis.
 */
class RelationNativeAdapterFeaturesTest : RelationTestCase() {
    private val slots = RelationFacts(
        slots = mapOf(
            "src" to RelationFact(listOf(
                ColumnFact("id", displayType = "Long", nullability = Nullability.NON_NULL, provenance = "src.id"),
                ColumnFact("kind", displayType = "Kind", provenance = "src.kind"),
            ), provenance = "src"),
            "rhs" to RelationFact(listOf(
                ColumnFact("id", displayType = "Long", nullability = Nullability.NON_NULL, provenance = "rhs.id"),
                ColumnFact("code", displayType = "String", provenance = "rhs.code"),
            ), provenance = "rhs"),
        ),
        tables = mapOf("physical" to RelationFact(listOf(
            ColumnFact("id", "INTEGER", nullability = Nullability.NON_NULL, origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "physical.id"),
            ColumnFact("label", "VARCHAR", origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "physical.label"),
        ))),
    )

    private lateinit var declarations: Map<String, PsiFile>

    override fun setUp() {
        super.setUp()
        installNativeSlotTyping(testRootDisposable)
        registerPgRelations(testRootDisposable)
        pgRelations.fake.facts = slots
        declarations = listOf("src.id", "rhs.id").associateWith { myFixture.addFileToProject("$it.txt", it) }
        pgRelations.fake.targets = { _, provenance -> declarations[provenance] }
    }

    /** Every item named [names] offered at the caret in [text], as name and tail. */
    private fun offered(text: String, vararg names: String): List<Pair<String, String?>> {
        open(text)
        return myFixture.completeBasic().orEmpty().filter { it.lookupString in names }.map { it.lookupString to LookupElementPresentation.renderElement(it).tailText }
    }

    fun testASelfJoinsColumnsAreOfferedOncePerAlias() {
        assertSameElements(offered("SELECT i<caret> FROM src() e JOIN src() f ON e.id = f.id", "id"),
            "id" to "  declared column (e)", "id" to "  declared column (f)")
    }

    fun testAShadowedAliasOffersOnlyTheInnerRelationsColumns() {
        val items = offered("SELECT id FROM src() e WHERE EXISTS (SELECT 1 FROM physical e WHERE e.<caret>label IS NULL)", "id", "kind", "label")
        assertSameElements(items, "id" to "  column (physical)", "label" to "  column (physical)")
    }

    fun testColumnsASetOperationOrUsingMergesAreOfferedAsTheirOwn() {
        assertEquals(listOf("id" to "  computed column (UNION ALL)"), offered("SELECT id FROM src() UNION ALL SELECT id FROM rhs() ORDER BY i<caret>", "id"))
        assertEquals(listOf("id" to "  computed column (USING)"), offered("SELECT q.<caret> FROM (SELECT id FROM src() FULL JOIN rhs() USING (id)) q", "id"))
        assertEmpty(offered("SELECT id FROM src() UNION ALL SELECT id, code FROM rhs() ORDER BY i<caret>", "id"))
    }
}
