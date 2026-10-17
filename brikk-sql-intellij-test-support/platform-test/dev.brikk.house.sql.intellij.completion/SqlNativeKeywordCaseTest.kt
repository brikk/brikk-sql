package dev.brikk.house.sql.intellij.completion

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.util.Case
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.sql.formatter.settings.SqlCodeStyleSettings
import com.intellij.sql.formatter.settings.SqlCodeStyles
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.KeywordDef

/** Keywords other contributors offer take the profile's keyword case, unless the SQL code style sets one. */
class SqlNativeKeywordCaseTest : CompletionTestCase() {
    private fun keywordCase(case: LookupCase) {
        trinoProfile.styles = SqlLookupStyles.DEFAULT.with(SqlLookupKind.KEYWORD to SqlLookupStyles.DEFAULT[SqlLookupKind.KEYWORD].copy(case = case))
    }

    /** Completes [text] and accepts the item [pick] chooses; returns the document with the caret. */
    private fun acceptIn(text: String, case: LookupCase, dialect: String? = null, pick: (LookupElement) -> Boolean): String {
        keywordCase(case)
        if (dialect == null) {
            configure(text, TrinoCompletion::class.java)
        } else {
            myFixture.configureDialect(dialect, text)
            register(TrinoCompletion::class.java, LoadingOrder.FIRST)
        }
        val items = complete()
        myFixture.lookup.currentItem = items.singleOrNull(pick) ?: error("not one match in ${items.map { it.lookupString }}")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return resultWithCaret()
    }

    private fun acceptKeyword(text: String, keyword: String, case: LookupCase) =
        acceptIn(text, case) { it.lookupString.equals(keyword, ignoreCase = true) && it.`object` !is FunctionPresentation }

    fun testADelegatedKeywordIsInsertedInTheProfilesCaseWithItsInsertion() {
        val native = acceptKeyword("SELECT 1; sel<caret>", "select", LookupCase.AS_GIVEN)
        assertEquals("SELECT 1; select <caret>", native)
        assertEquals("SELECT 1; SELECT <caret>", acceptKeyword("SELECT 1; sel<caret>", "select", LookupCase.UPPER))
    }

    fun testAMultiWordKeywordKeepsItsInsertion() {
        val text = "SELECT a FROM t gro<caret>"
        val native = acceptKeyword(text, "group by", LookupCase.AS_GIVEN)
        assertEquals("SELECT a FROM t group by <caret>", native)
        assertEquals("SELECT a FROM t GROUP BY <caret>", acceptKeyword(text, "group by", LookupCase.UPPER))
    }

    fun testAMixedCasePrefixTakesTheProfilesCase() {
        assertEquals("SELECT 1; select <caret>", acceptKeyword("SELECT 1; SeL<caret>", "select", LookupCase.LOWER))
        assertEquals("SELECT 1; SELECT <caret>", acceptKeyword("SELECT 1; sEl<caret>", "select", LookupCase.UPPER))
    }

    fun testADelegatedKeywordIsOfferedOnceInTheProfilesCase() {
        trinoProfile.keywords = KeywordScope.ALL
        keywordCase(LookupCase.UPPER)
        configure("SELECT 1; sel<caret>", TrinoCompletion::class.java)
        val select = complete().filter { it.lookupString.equals("select", ignoreCase = true) }
        assertEquals(listOf("SELECT"), select.map { it.lookupString })
        assertEquals("SELECT", LookupElementPresentation.renderElement(select.single()).itemText)
    }

    fun testAMetadataKeywordIsInsertedInTheProfilesCase() {
        trinoProfile.keywords = KeywordScope.ALL
        trinoProfile.keywordCatalog = KeywordCatalog(listOf(KeywordDef("ZORDERED")))
        assertEquals("SELECT 1; ZORDERED<caret>", acceptKeyword("SELECT 1; zor<caret>", "zordered", LookupCase.UPPER))
    }

    fun testTheCodeStylesKeywordCaseWins() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCustomSettings(SqlCodeStyleSettings::class.java).KEYWORD_CASE = SqlCodeStyleSettings.TO_LOWER
            configure("SELECT 1;", TrinoCompletion::class.java)
            assertEquals(Case.LOWER, SqlCodeStyles.getSqlSettings(myFixture.file).keywordCase)
            trinoProfile.keywords = KeywordScope.ALL
            trinoProfile.keywordCatalog = KeywordCatalog(listOf(KeywordDef("SELECT"), KeywordDef("ZORDERED")))
            assertEquals("SELECT 1; select <caret>", acceptKeyword("SELECT 1; SEL<caret>", "select", LookupCase.UPPER))
            assertEquals("SELECT 1; zordered<caret>", acceptKeyword("SELECT 1; ZOR<caret>", "zordered", LookupCase.UPPER))
        }
    }

    /** A column and a platform function named like keywords keep their names and insertion. */
    fun testNonKeywordItemsAreUnchanged() {
        trinoProfile.keywordCatalog = KeywordCatalog(listOf(KeywordDef("SELECT"), KeywordDef("AMOUNT"), KeywordDef("BIN")))
        val column = "WITH t AS (SELECT 1 AS amount) SELECT amo<caret> FROM t"
        val native = acceptIn(column, LookupCase.AS_GIVEN) { it.lookupString == "amount" }
        assertEquals("WITH t AS (SELECT 1 AS amount) SELECT amount<caret> FROM t", native)
        assertEquals(native, acceptIn(column, LookupCase.UPPER) { it.lookupString == "amount" })
        // A base-dialect function metadata lacks; one it has is offered once, from metadata.
        val platformFunction = { item: LookupElement -> item.lookupString.equals("bin", ignoreCase = true) && item.`object` !is FunctionPresentation }
        val nativeFunction = acceptIn("SELECT bi<caret>", LookupCase.AS_GIVEN, "MySQL", platformFunction)
        assertEquals("SELECT bin(<caret>)", nativeFunction)
        assertEquals(nativeFunction, acceptIn("SELECT bi<caret>", LookupCase.UPPER, "MySQL", platformFunction))
    }
}
