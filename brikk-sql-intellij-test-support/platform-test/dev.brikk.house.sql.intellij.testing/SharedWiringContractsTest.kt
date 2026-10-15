package dev.brikk.house.sql.intellij.testing

import com.intellij.lang.Language
import com.intellij.lang.LanguageDocumentation
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.docs.SqlEngineDocumentationProvider

/** The shared wiring contracts on the kit's own lane: the zero-code docs provider. */
class SharedWiringContractsTest : BasePlatformTestCase() {
    private val clickhouse: Language get() = checkNotNull(Language.findLanguageByID("ClickHouse"))

    fun testTheNoArgDocsProviderFindsTheDocsOfTheFilesLanguage() {
        LanguageDocumentation.INSTANCE.addExplicitExtension(clickhouse, SqlEngineDocumentationProvider(), testRootDisposable)
        DocumentationProviderContract(myFixture, clickhouse as SqlLanguageDialect, testRootDisposable)
            .check("CREATE <caret>TABLE t (x UInt8) ENGINE = Memory", "clickhouse.com", table = "orders")
    }

    fun testTheDocsContractFailsWithoutTheProvider() {
        val failure = runCatching {
            DocumentationProviderContract(myFixture, clickhouse as SqlLanguageDialect, testRootDisposable)
                .check("CREATE <caret>TABLE t (x UInt8) ENGINE = Memory", "clickhouse.com", table = "orders")
        }.exceptionOrNull()
        assertTrue("$failure", failure is AssertionError && "not a page on clickhouse.com" in failure.message.orEmpty())
    }
}
