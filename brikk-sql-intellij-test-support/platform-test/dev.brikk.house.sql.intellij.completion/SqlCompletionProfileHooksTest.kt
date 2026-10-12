package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.KeywordDef

class SqlCompletionProfileHooksTest : CompletionTestCase() {
    fun testKeywordsComeFromTheProfilesCatalog() {
        trinoProfile.keywords = KeywordScope.ALL
        trinoProfile.keywordCatalog = KeywordCatalog(listOf(KeywordDef("HARVESTEDWORD", KeywordCategory.RESERVED)))
        configure("SELECT 1; harv<caret>", TrinoCompletion::class.java)
        assertContainsElements(complete().map { it.lookupString.uppercase() }, "HARVESTEDWORD")
    }

    fun testEachHookIsCalledOncePerCompletion() {
        trinoProfile.keywords = KeywordScope.ALL
        configure("SELECT a<caret> FROM t", TrinoCompletion::class.java)
        trinoProfile.calls.clear()
        complete()
        assertEquals(mapOf("snapshot" to 1, "functions" to 1, "serverOnlyFunctions" to 1, "keywordCatalog" to 1), trinoProfile.calls)
    }
}
