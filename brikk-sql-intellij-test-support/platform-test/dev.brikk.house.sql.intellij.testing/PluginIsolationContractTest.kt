package dev.brikk.house.sql.intellij.testing

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim
import java.util.concurrent.ExecutionException

/** Declines every execution, as a plugin's wrapper does outside its own consoles. */
class DecliningExecuteConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = ExecuteInterceptor { _, _ -> ExecutionClaim.DECLINED }
}

/** A broken wrapper: it runs each Execute action it wraps twice. */
class DoublingExecuteConfiguration : DynamicActionConfigurationCustomizer {
    private val previous = LinkedHashMap<String, AnAction>()

    override fun registerActions(actionManager: ActionManager) {
        for (id in CooperativeExecuteConfiguration.IDS) {
            val wrapped = actionManager.getAction(id) ?: continue
            previous[id] = wrapped
            actionManager.replaceAction(id, object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    wrapped.actionPerformed(e)
                    wrapped.actionPerformed(e)
                }
            })
        }
    }

    override fun unregisterActions(actionManager: ActionManager) {
        previous.forEach { (id, action) -> actionManager.replaceAction(id, action) }
        previous.clear()
    }
}

/**
 * The isolation checks pass on what holds and fail on what does not. This sandbox installs no
 * sibling dialect plugins, so they run on what it has: the platform's Java IDE customization plugin,
 * the one with a plugin class loader of its own, and this library's own Execute configurations.
 */
class PluginIsolationContractTest : BasePlatformTestCase() {
    private val plugin = "com.intellij.java.ide"
    private val pluginPackage = "com.intellij.ide.customization.java"

    private fun fails(expected: String, check: () -> Unit) {
        val error = runCatching(check).exceptionOrNull()
        assertTrue("expected a failure mentioning '$expected', got $error", error is AssertionError && expected in error.message.orEmpty())
    }

    fun testInstalledPluginsNeedClassLoadersOfTheirOwn() {
        val loaders = PluginIsolation.assertInstalledApart(listOf(plugin))
        assertEquals(PluginIsolation.PLUGIN_CLASS_LOADER, loaders.getValue(plugin).javaClass.name)
        fails("is not installed") { PluginIsolation.assertInstalledApart(listOf(plugin, "dev.brikk.no-such-plugin")) }
        fails("class loader") { PluginIsolation.assertInstalledApart(listOf("com.intellij.database")) }
        fails("one class loader per plugin") { PluginIsolation.assertInstalledApart(listOf(plugin, plugin)) }
    }

    fun testOwnedPackagesLoadFromThePluginsJars() {
        PluginIsolation.assertOwnsPackages(plugin, listOf(pluginPackage))
        fails("bundles no class of") { PluginIsolation.assertOwnsPackages(plugin, listOf("dev.brikk.house.sql")) }
        val itself = PluginIsolation.loaderOf(plugin)
        fails("shares plugin") { PluginIsolation.assertOwnsPackages(plugin, listOf(pluginPackage), mapOf("a sibling sharing its loader" to itself)) }
    }

    fun testLanguagesAreTheirPluginsAndParse() {
        val postgres = checkNotNull(Language.findLanguageByID("PostgreSQL"))
        PluginIsolation.assertLanguages(project, postgres.javaClass.classLoader, listOf("PostgreSQL"))
        fails("class loader") { PluginIsolation.assertLanguages(project, PluginIsolation.loaderOf(plugin), listOf("PostgreSQL")) }
        fails("is not registered") { PluginIsolation.assertLanguages(project, postgres.javaClass.classLoader, listOf("NoSuchSQL")) }
    }

    fun testExecuteChainsHoldEachWrapper() {
        val loader = DecliningExecuteConfiguration::class.java.classLoader
        fails("is missing from Console.Jdbc.Execute") { PluginIsolation.assertExecuteChains(listOf(loader)) }
        val configuration = DecliningExecuteConfiguration()
        val manager = ActionManager.getInstance()
        configuration.registerActions(manager)
        try {
            PluginIsolation.assertExecuteChains(listOf(loader))
            fails("ends in") { PluginIsolation.assertExecuteChains(listOf(loader), stock = "com.example.NoSuchAction") }
        } finally {
            configuration.unregisterActions(manager)
        }
    }

    fun testEveryRegistrationOrderReachesEachActionOnce() {
        val before = CooperativeExecuteConfiguration.IDS.map { ActionManager.getInstance().getAction(it) }
        PluginIsolation.assertEveryRegistrationOrder(project, List(3) { { DecliningExecuteConfiguration() } })
        fails("each action reached once") {
            PluginIsolation.assertEveryRegistrationOrder(project, listOf({ DecliningExecuteConfiguration() }, { DoublingExecuteConfiguration() }))
        }
        assertEquals("the registered actions are restored", before, CooperativeExecuteConfiguration.IDS.map { ActionManager.getInstance().getAction(it) })
    }

    fun testThePermutationsAreEveryOrder() {
        assertEquals(24, PluginIsolation.permutations(listOf(1, 2, 3, 4)).toSet().size)
        assertEquals(listOf(emptyList<Int>()), PluginIsolation.permutations(emptyList<Int>()))
    }

    fun testLoggedErrorsAreCollectedOnEveryThread() {
        val contract = PluginIsolationContract(plugin, emptyList(), emptySet(), listOf(pluginPackage), wrapsExecuteActions = false)
        val misuse = runCatching { contract.anErrorLoggedOnAPooledThreadIsSeen() }.exceptionOrNull()
        assertTrue("a check of the collection before collectErrors() is a misuse: $misuse", misuse is IllegalStateException)
        contract.collectErrors()
        contract.anErrorLoggedOnAPooledThreadIsSeen()
        contract.thePluginAndItsSiblingsAreInstalledApart()
        contract.ownedPackagesLoadFromThePlugin()
        contract.everyExecuteChainKeepsEachPluginsWrapper()
        contract.assertNoErrors()

        contract.collectErrors()
        val logged = AssertionError("logged by background work")
        try {
            ApplicationManager.getApplication().executeOnPooledThread { Logger.getInstance(javaClass).error(logged) }.get()
        } catch (_: ExecutionException) {
            // The platform logger rethrows on the logging thread.
        }
        fails("logged; the first: java.lang.AssertionError: logged by background work") { contract.assertNoErrors() }
    }

    fun testTheKotlinPluginsStateIsCheckedWhenGiven() {
        val enabled = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("org.jetbrains.kotlin"))?.isEnabled == true
        PluginIsolationContract(plugin, emptyList(), emptySet(), emptyList(), kotlinPluginEnabled = enabled).thePluginAndItsSiblingsAreInstalledApart()
        fails("the Kotlin plugin is") {
            PluginIsolationContract(plugin, emptyList(), emptySet(), emptyList(), kotlinPluginEnabled = !enabled).thePluginAndItsSiblingsAreInstalledApart()
        }
    }
}
