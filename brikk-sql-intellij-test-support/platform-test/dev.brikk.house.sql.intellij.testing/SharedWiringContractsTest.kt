package dev.brikk.house.sql.intellij.testing

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.lang.Language
import com.intellij.lang.LanguageDocumentation
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.JDOMUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.docs.SqlEngineDocumentationProvider

class ContractCompletion : CompletionContributor()

@Service(Service.Level.PROJECT)
class FinalLightService

@Service(Service.Level.PROJECT)
open class OpenLightService

class DeclaredAppService

class DeclaredProjectService

/** Declared in XML but registered in no container, as when the declaration's class was renamed. */
class UnregisteredDeclaredService

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

    private val completion = ContractCompletion::class.java.name

    /** The jar documents of a plugin as Doris, DuckDB and Trino write them: the PIPE descriptor included as `xi:include`. */
    private fun documents(root: String, included: String) = mapOf(
        "META-INF/plugin.xml" to JDOMUtil.load(
            """<idea-plugin xmlns:xi="http://www.w3.org/2001/XInclude"><id>$id</id>
            |<xi:include href="/META-INF/pipes.xml" xpointer="xpointer(/idea-plugin/*)"/>$root</idea-plugin>""".trimMargin(),
        ),
        "META-INF/pipes.xml" to JDOMUtil.load("<idea-plugin>$included</idea-plugin>"),
    )

    private fun completionXml(language: String = "PostgreSQL") =
        """<extensions defaultExtensionNs="com.intellij"><completion.contributor language="$language" implementationClass="$completion"/></extensions>"""

    fun testRegistrationsAndServicesThatWorkPassIncludedDescriptorsToo() {
        registerCompletion()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(DeclaredAppService::class.java, DeclaredAppService(), testRootDisposable)
        project.registerOrReplaceServiceInstance(DeclaredProjectService::class.java, DeclaredProjectService(), testRootDisposable)
        val services = """<extensions defaultExtensionNs="com.intellij">
            |<applicationService serviceImplementation="${DeclaredAppService::class.java.name}"/>
            |<projectService serviceImplementation="${DeclaredProjectService::class.java.name}"/></extensions>""".trimMargin()
        assertEquals(3 to 1, PluginRegistrationContract(id, "unused").check(project, documents(services, completionXml()), listOf(FinalLightService::class.java)))
    }

    fun testABadRegistrationInAnIncludedDescriptorFails() {
        registerCompletion()
        val failure = runCatching {
            PluginRegistrationContract(id, "unused").check(project, documents("", completionXml() + completionXml("MySQL")), emptyList())
        }.exceptionOrNull()
        assertTrue("$failure", failure is AssertionError && "2 registration(s), 1 adapter(s)" in failure.message.orEmpty())
    }

    fun testADeclaredServiceThatIsMissingOrFailsFails() {
        val services = """<extensions defaultExtensionNs="com.intellij">
            |<projectService serviceImplementation="dev.sort.missing.NoSuchService"/>
            |<projectService serviceImplementation="${UnregisteredDeclaredService::class.java.name}"/></extensions>""".trimMargin()
        val failure = runCatching { PluginRegistrationContract(id, "unused").check(project, documents(services, ""), emptyList()) }.exceptionOrNull()
        val message = failure?.message.orEmpty()
        assertTrue(message, failure is AssertionError)
        assertTrue(message, "projectService dev.sort.missing.NoSuchService does not load" in message)
        assertTrue(message, "projectService ${UnregisteredDeclaredService::class.java.name} does not instantiate" in message)
    }

    /** Doris's descriptor: Kotlin's plugin-mode marker in plugin.xml, Kotlin an optional dependency not loaded here. */
    fun testAPointOfAnUnloadedOptionalDependencyIsSkippedAndAWrongNamespaceStillFails() {
        val kotlin = """<depends optional="true" config-file="kotlin.xml">org.jetbrains.kotlin</depends>
            |<extensions defaultExtensionNs="org.jetbrains.kotlin"><supportsKotlinPluginMode supportsK1="false" supportsK2="true"/></extensions>""".trimMargin()
        assertNull(PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))?.takeIf { it.isEnabled })
        assertEquals(1 to 0, PluginRegistrationContract(id, "unused").check(project, documents(kotlin, ""), emptyList()))
        val wrong = kotlin + """<extensions defaultExtensionNs="org.jetbrains.kotlinx"><supportsKotlinPluginMode supportsK2="true"/></extensions>"""
        val failure = runCatching { PluginRegistrationContract(id, "unused").check(project, documents(wrong, ""), emptyList()) }.exceptionOrNull()
        val message = failure?.message.orEmpty()
        assertTrue(message, failure is AssertionError && "no extension point org.jetbrains.kotlinx.supportsKotlinPluginMode" in message)
        assertFalse(message, "org.jetbrains.kotlin.supportsKotlinPluginMode" in message)
    }

    fun testAWrongNamespaceAMissingAdapterAndAnOpenLightServiceFail() {
        registerCompletion()
        val xml = """${completionXml()}${completionXml("MySQL")}
            |<extensions defaultExtensionNs="dev.sort.wrong"><completion.contributor implementationClass="x.Y"/></extensions>""".trimMargin()
        val failure = runCatching {
            PluginRegistrationContract(id, "unused").check(project, documents(xml, ""), listOf(OpenLightService::class.java))
        }.exceptionOrNull()
        val message = failure?.message.orEmpty()
        assertTrue(message, failure is AssertionError)
        assertTrue(message, "no extension point dev.sort.wrong.completion.contributor" in message)
        assertTrue(message, "2 registration(s), 1 adapter(s)" in message)
        assertTrue(message, "OpenLightService is not final" in message)
        assertFalse(message, "1 registration(s)" in message)
    }
}
