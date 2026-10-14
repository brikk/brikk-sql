package dev.brikk.house.sql.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.SqlLookupStyle
import java.util.UUID

/** A platform column item: its name and the relation it was resolved from. */
data class PlatformColumn(val name: String, val source: String)

private var platformColumns: List<PlatformColumn> = emptyList()
private var brikkColumns: List<SqlColumnFacts> = emptyList()

class PlatformColumnCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        for (column in platformColumns) result.addElement(LookupElementBuilder.create(column, column.name).withIcon(AllIcons.Nodes.Field))
    }
}

class BrikkColumnDecoration : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        SqlColumnCompletion.createOrDecorate(parameters, result, brikkColumns) { candidate ->
            (candidate.`object` as? PlatformColumn)?.let { platform ->
                brikkColumns.firstOrNull { it.name == platform.name && it.source == platform.source }
            }
        }
    }
}

class SqlColumnCompletionTest : BasePlatformTestCase() {
    private val idOfA = SqlColumnFacts("id", "Long", false, "a", source = "a")
    private val idOfB = SqlColumnFacts("id", "String", null, "b", source = "b")

    private fun complete(platform: List<PlatformColumn>, brikk: List<SqlColumnFacts>): List<LookupElement> {
        platformColumns = platform
        brikkColumns = brikk
        myFixture.configureByText("columns-${UUID.randomUUID()}.sql", "SELECT <caret>")
        val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
        val language = myFixture.file.language.id
        val point = CompletionContributor.EP.point
        point.registerExtension(CompletionContributorEP(language, BrikkColumnDecoration::class.java.name, plugin), LoadingOrder.FIRST, testRootDisposable)
        point.registerExtension(CompletionContributorEP(language, PlatformColumnCompletion::class.java.name, plugin), LoadingOrder.LAST, testRootDisposable)
        return myFixture.completeBasic().orEmpty().filter { it.lookupString == "id" || it.lookupString == "other" }
    }

    private fun render(item: LookupElement) = LookupElementPresentation.renderElement(item)

    fun testAPlatformCandidateIsDecoratedNotDuplicated() {
        val items = complete(listOf(PlatformColumn("id", "a")), listOf(idOfA))
        val id = items.single { it.lookupString == "id" }
        assertEquals(PlatformColumn("id", "a"), id.`object`)
        val presentation = render(id)
        assertEquals("Long", presentation.typeText)
        assertTrue(presentation.tailText.orEmpty().contains("(a)"))
        assertEquals(AllIcons.Nodes.Field, presentation.icon)
    }

    fun testAColumnWithoutACandidateIsCreated() {
        val id = complete(emptyList(), listOf(idOfA)).single { it.lookupString == "id" }
        assertEquals("Long", render(id).typeText)
    }

    fun testSameNamedColumnsOfTwoRelationsStayDistinct() {
        val items = complete(listOf(PlatformColumn("id", "a")), listOf(idOfA, idOfB)).filter { it.lookupString == "id" }
        // Nullability that cannot be proved is never shown, in words or otherwise.
        assertSameElements(items.map { render(it).typeText }, "Long", "String")
    }

    fun testACandidateMatchingNoColumnIsLeftAsIs() {
        val other = complete(listOf(PlatformColumn("other", "a")), listOf(idOfA)).single { it.lookupString == "other" }
        assertNull(render(other).typeText)
    }

    fun testCreatedItemsOfTwoAliasesKeepDistinctIdentities() {
        val a = SqlColumnCompletion.create("id", "Long", false, "a")
        val b = SqlColumnCompletion.create("id", "Long", false, "b")
        assertFalse(a == b)
        assertFalse(a.`object` == b.`object`)
    }

    fun testAPipeInputColumnShowsItsTypeOrTheStylesLabel() {
        val style = SqlLookupStyle(typeText = "input")
        assertEquals("input", render(SqlColumnCompletion.pipeInput("c", style = style)).typeText)
        assertEquals("BIGINT", render(SqlColumnCompletion.pipeInput("c", "BIGINT", style)).typeText)
    }

    fun testSeveralCandidatesForOneColumnYieldOneItem() {
        val items = complete(listOf(PlatformColumn("id", "a"), PlatformColumn("id", "a")), listOf(idOfA))
        assertEquals(1, items.count { it.lookupString == "id" })
    }

    fun testUnaliasedRelationsKeepTheirSameNamedColumnsApart() {
        val first = SqlColumnFacts("id", "Long", false, source = "first relation")
        val second = first.copy(source = "second relation")
        assertEquals(2, complete(emptyList(), listOf(first, second)).count { it.lookupString == "id" })
        val decorated = complete(listOf(PlatformColumn("id", "first relation"), PlatformColumn("id", "second relation")), listOf(first, second))
        assertEquals(2, decorated.count { it.lookupString == "id" })
    }

    fun testPipeInputColumnsOfDifferentSourcesStayDistinct() {
        val style = SqlLookupStyle()
        val left = SqlColumnCompletion.pipeInput("id", style = style, source = "left")
        val right = SqlColumnCompletion.pipeInput("id", style = style, source = "right")
        assertFalse(left.`object` == right.`object`)
        assertEquals(left.`object`, SqlColumnCompletion.pipeInput("id", style = style, source = "left").`object`)
    }
}
