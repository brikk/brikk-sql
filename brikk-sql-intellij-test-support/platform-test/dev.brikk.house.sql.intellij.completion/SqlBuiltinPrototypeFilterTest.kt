package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.util.Disposer
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.psi.SqlLanguage
import java.util.UUID

class DuckdbBuiltinFilter : SqlBuiltinPrototypeFilter(duckdbProfile)

class SameNamedColumnCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        result.addElement(LookupElementBuilder.create(SAME_NAMED_COLUMN))
    }
}

private const val SAME_NAMED_COLUMN = "pg_sleep"

class SqlBuiltinPrototypeFilterTest : CompletionTestCase() {
    /** Builtin items the PostgreSQL base dialect offers for `pg_sl`, and every item's name. */
    private fun offered(filter: ((String) -> Boolean)?): Pair<List<String>, List<String>> {
        duckdbProfile.builtinFilter = filter
        val file = myFixture.configureByText("builtins-${UUID.randomUUID()}.sql", "SELECT pg_sl<caret>").virtualFile
        val mappings = SqlDialectMappings.getInstance(project)
        val postgres = Language.findLanguageByID("PostgreSQL") as SqlLanguageDialect
        mappings.setMapping(file, postgres)
        Disposer.register(testRootDisposable) { mappings.setMapping(file, null) }
        register(DuckdbBuiltinFilter::class.java, LoadingOrder.FIRST, SqlLanguage.INSTANCE.id)
        register(SameNamedColumnCompletion::class.java, LoadingOrder.LAST, SqlLanguage.INSTANCE.id)
        val items = complete()
        return items.filter { it.`object` is BuiltinFunction.Prototype }.map { it.lookupString } to items.map { it.lookupString }
    }

    fun testWithoutAFilterEveryBuiltinStays() {
        assertNotEmpty(offered(null).first)
    }

    fun testTheFilterDropsBuiltinsItDoesNotKeep() {
        val builtins = offered(null).first.distinct()
        assertTrue(builtins.size > 1)
        val kept = builtins.first()
        assertEquals(listOf(kept), offered { it == kept }.first.distinct())
    }

    fun testAnItemThatIsNotABuiltinStaysWhateverItsName() {
        val (builtins, all) = offered { false }
        assertEmpty(builtins)
        assertContainsElements(all, SAME_NAMED_COLUMN)
    }
}
