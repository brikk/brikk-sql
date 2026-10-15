package dev.brikk.house.sql.intellij.testing

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.lang.Language
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.JDOMUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.docs.SqlEngineDocumentationProvider

class ContractCompletion : CompletionContributor()

@Service(Service.Level.PROJECT)
class FinalLightService

@Service(Service.Level.PROJECT)
open class OpenLightService

/** The shared wiring contracts on the kit's own lane: the zero-code docs provider and registration checks. */
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

    private val id = "dev.brikk.house.sql.contract-selftest"

    private fun registerCompletion() {
        val plugin = DefaultPluginDescriptor(PluginId.getId(id), ContractCompletion::class.java.classLoader)
        CompletionContributor.EP.point.registerExtension(CompletionContributorEP("PostgreSQL", ContractCompletion::class.java.name, plugin), plugin, testRootDisposable)
    }

    private fun descriptor(extensions: String) = JDOMUtil.load("<idea-plugin><id>$id</id>$extensions</idea-plugin>")

    fun testRegistrationsAndLightServicesThatWorkPass() {
        registerCompletion()
        val xml = """<extensions defaultExtensionNs="com.intellij"><completion.contributor language="PostgreSQL" implementationClass="${ContractCompletion::class.java.name}"/></extensions>"""
        assertEquals(1 to 1, PluginRegistrationContract(id, "unused").check(project, listOf(descriptor(xml)), listOf(FinalLightService::class.java)))
    }

    fun testAWrongNamespaceAMissingAdapterAndAnOpenLightServiceFail() {
        registerCompletion()
        val xml = """<extensions defaultExtensionNs="com.intellij">
            |<completion.contributor language="PostgreSQL" implementationClass="${ContractCompletion::class.java.name}"/>
            |<completion.contributor language="MySQL" implementationClass="${ContractCompletion::class.java.name}"/></extensions>
            |<extensions defaultExtensionNs="dev.sort.wrong"><completion.contributor implementationClass="x.Y"/></extensions>""".trimMargin()
        val failure = runCatching {
            PluginRegistrationContract(id, "unused").check(project, listOf(descriptor(xml)), listOf(OpenLightService::class.java))
        }.exceptionOrNull()
        val message = failure?.message.orEmpty()
        assertTrue(message, failure is AssertionError)
        assertTrue(message, "no extension point dev.sort.wrong.completion.contributor" in message)
        assertTrue(message, "2 registration(s), 1 adapter(s)" in message)
        assertTrue(message, "OpenLightService is not final" in message)
        assertFalse(message, "1 registration(s)" in message)
    }
}
