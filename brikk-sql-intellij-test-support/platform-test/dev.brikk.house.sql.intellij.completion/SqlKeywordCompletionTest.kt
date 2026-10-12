package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.lookup.Lookup
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.TRINO_METADATA

class SqlKeywordCompletionTest : CompletionTestCase() {
    private val trinoKeywords = TRINO_METADATA.default.keywords!!.keywords
    private val reserved = trinoKeywords.first { it.category == KeywordCategory.RESERVED }
    private val unreserved = trinoKeywords.first { it.category == KeywordCategory.UNRESERVED }

    private fun offered(scope: KeywordScope, keyword: KeywordDef): Boolean {
        trinoProfile.keywords = scope
        configure("SELECT 1; ${keyword.word.take(2).lowercase()}<caret>", TrinoCompletion::class.java)
        return complete().any { it.`object` == keyword }
    }

    fun testReservedScopeOffersOnlyReservedKeywords() {
        assertTrue(offered(KeywordScope.RESERVED, reserved))
        assertFalse(offered(KeywordScope.RESERVED, unreserved))
    }

    fun testAllScopeOffersEveryKeyword() {
        assertTrue(offered(KeywordScope.ALL, reserved))
        assertTrue(offered(KeywordScope.ALL, unreserved))
    }

    fun testNoneScopeOffersNoKeywords() {
        assertFalse(offered(KeywordScope.NONE, reserved))
    }

    fun testAKeywordIsInsertedInTheRowsCase() {
        val keyword = DUCKDB_METADATA.default.keywords!!.keywords.first { it.word == it.word.lowercase() && it.word.length > 3 }
        duckdbProfile.keywords = KeywordScope.ALL
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(SqlLookupKind.KEYWORD to SqlLookupStyle(case = LookupCase.UPPER))
        configure("SELECT 1; ${keyword.word}<caret>", DuckdbCompletion::class.java)
        val item = complete().single { it.`object` == keyword }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT 1; ${keyword.word.uppercase()}<caret>", resultWithCaret())
    }
}
