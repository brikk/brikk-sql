package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.lookup.Lookup
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.TRINO_METADATA

class SqlKeywordCompletionTest : CompletionTestCase() {
    private val trinoKeywords = TRINO_METADATA.default.keywords!!.keywords

    private fun offered(scope: KeywordScope, keyword: KeywordDef): Boolean {
        trinoProfile.keywords = scope
        configure("SELECT 1; ${keyword.word.take(2).lowercase()}<caret>", TrinoCompletion::class.java)
        return complete().any { it.lookupString.equals(keyword.word, ignoreCase = true) }
    }

    /** A keyword of [category] that the platform does not offer by itself. */
    private fun keywordOnlyWeOffer(category: KeywordCategory) =
        trinoKeywords.filter { it.category == category }.first { !offered(KeywordScope.NONE, it) }

    fun testReservedScopeOffersOnlyReservedKeywords() {
        val reserved = keywordOnlyWeOffer(KeywordCategory.RESERVED)
        val unreserved = keywordOnlyWeOffer(KeywordCategory.UNRESERVED)
        assertTrue(offered(KeywordScope.RESERVED, reserved))
        assertFalse(offered(KeywordScope.RESERVED, unreserved))
    }

    fun testAllScopeOffersEveryKeyword() {
        assertTrue(offered(KeywordScope.ALL, keywordOnlyWeOffer(KeywordCategory.RESERVED)))
        assertTrue(offered(KeywordScope.ALL, keywordOnlyWeOffer(KeywordCategory.UNRESERVED)))
    }

    fun testAKeywordThePlatformAlsoOffersIsListedOnce() {
        val select = trinoKeywords.single { it.word == "SELECT" }
        assertTrue(offered(KeywordScope.NONE, select))
        trinoProfile.keywords = KeywordScope.RESERVED
        configure("SELECT 1; SE<caret>", TrinoCompletion::class.java)
        assertEquals(1, complete().count { it.lookupString.equals("SELECT", ignoreCase = true) })
    }

    fun testAKeywordIsInsertedInTheRowsCase() {
        val keyword = DUCKDB_METADATA.default.keywords!!.keywords.first { it.word == it.word.lowercase() && it.word.length > 3 }
        duckdbProfile.keywords = KeywordScope.ALL
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(SqlLookupKind.KEYWORD to SqlLookupStyle(case = LookupCase.UPPER))
        configure("SELECT 1; ${keyword.word}<caret>", DuckdbCompletion::class.java)
        val item = complete().single { it.lookupString == keyword.word.uppercase() && it.`object` == keyword.word.uppercase() }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT 1; ${keyword.word.uppercase()}<caret>", resultWithCaret())
    }

    /** [name], a Trino keyword and function, is offered once, as the syntax-aware function item. */
    private fun assertOfferedOnceAsFunction(name: String) {
        assertTrue("$name is a keyword", trinoKeywords.any { it.word.equals(name, ignoreCase = true) })
        val function = TRINO_METADATA.default.completableFunctions().single { it.name.equals(name, ignoreCase = true) }
        trinoProfile.keywords = KeywordScope.ALL
        configure("SELECT 1; ${name.lowercase()}<caret>", TrinoCompletion::class.java)
        assertEquals(listOf(function), complete().filter { it.lookupString.equals(name, ignoreCase = true) }.map { it.`object` })
    }

    fun testUnnestIsOfferedOnceAsATableFunction() = assertOfferedOnceAsFunction("UNNEST")

    fun testGroupingIsOfferedOnceAsAFunction() = assertOfferedOnceAsFunction("GROUPING")

    fun testCurrentPathIsOfferedOnceAsABareForm() {
        assertTrue(InvocationSyntax.BARE in TRINO_METADATA.default.completableFunctions().single { it.name == "CURRENT_PATH" }.syntaxes)
        assertOfferedOnceAsFunction("CURRENT_PATH")
    }
}
