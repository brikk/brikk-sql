package dev.brikk.house.sql.intellij.testing

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LoggedErrorProcessor
import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/**
 * Checks that a dialect plugin built on this library stays apart from its siblings when they are
 * installed together, each with its own copy of the Brikk libraries. [PluginIsolationContract] runs
 * them for one plugin; each check takes what it reads, so a test can also call it directly. A check
 * fails with an `AssertionError`.
 */
public object PluginIsolation {
    public const val PLUGIN_CLASS_LOADER: String = "com.intellij.ide.plugins.cl.PluginClassLoader"

    /** The wrapper this library's `CooperativeExecuteConfiguration` puts around each Execute action. */
    public const val EXECUTE_WRAPPER: String = "dev.brikk.house.sql.intellij.CooperativeExecuteAction"

    /** The stock action every Execute chain ends in. */
    public const val STOCK_EXECUTE: String = "com.intellij.database.actions.RunQueryAction"

    public val EXECUTE_ACTION_IDS: List<String> = CooperativeExecuteConfiguration.IDS

    /** The class loader of the enabled plugin [id], which must be a plugin class loader of its own. */
    public fun loaderOf(id: String): ClassLoader {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(id)) ?: throw AssertionError("plugin $id is not installed")
        assertTrue("plugin $id is disabled", descriptor.isEnabled)
        val loader = descriptor.pluginClassLoader ?: throw AssertionError("plugin $id has no class loader")
        assertEquals("plugin $id's class loader", PLUGIN_CLASS_LOADER, loader.javaClass.name)
        return loader
    }

    /** [ids] are installed and enabled, each with its own plugin class loader; their loaders by id. */
    public fun assertInstalledApart(ids: List<String>): Map<String, ClassLoader> {
        val loaders = ids.associateWith(::loaderOf)
        val distinct = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>()).apply { addAll(loaders.values) }
        assertEquals("one class loader per plugin: $ids", ids.size, distinct.size)
        return loaders
    }

    /**
     * Every class file under [packages] (subpackages included) in the jars of plugin [id] is served
     * from those jars by the plugin's loader, and one class of each package is defined by it, so no
     * other plugin's or the platform's copy is used. A sibling in [siblings] that bundles that class
     * defines its own copy.
     */
    public fun assertOwnsPackages(id: String, packages: List<String>, siblings: Map<String, ClassLoader> = emptyMap()) {
        val loader = loaderOf(id)
        val home = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(id))).pluginPath.toRealPath()
        val entries = classEntries(home)
        for (pkg in packages) {
            val prefix = pkg.replace('.', '/') + "/"
            val owned = entries.filter { it.startsWith(prefix) }
            assertTrue("plugin $id bundles no class of $pkg", owned.isNotEmpty())
            val foreign = owned.filter { entry -> loader.getResource(entry)?.let(::origin)?.startsWith(home) != true }
            assertTrue("plugin $id's loader serves classes of $pkg from elsewhere: ${foreign.take(5)}", foreign.isEmpty())
            val sample = owned.firstOrNull { '$' !in it && !it.endsWith("-info.class") } ?: owned.first()
            val name = sample.removeSuffix(".class").replace('/', '.')
            val type = Class.forName(name, false, loader)
            assertSame("$name is defined by plugin $id", loader, type.classLoader)
            for ((sibling, siblingLoader) in siblings) {
                if (siblingLoader.getResource(sample) == null) continue
                val copy = Class.forName(name, false, siblingLoader)
                assertNotSame("$sibling shares plugin $id's $name", type, copy)
                assertSame("$sibling's $name is its own", siblingLoader, copy.classLoader)
            }
        }
    }

    /** Each of [languageIds] is registered by [loader]'s classes and parses a statement as written. */
    public fun assertLanguages(project: Project, loader: ClassLoader, languageIds: Collection<String>) {
        for (id in languageIds) {
            val language = Language.findLanguageByID(id) ?: throw AssertionError("language $id is not registered")
            assertSame("language $id's class loader", loader, language.javaClass.classLoader)
            val text = "SELECT 1;"
            val file = PsiFileFactory.getInstance(project).createFileFromText("isolation.sql", language, text)
            assertEquals("language $id keeps the text", text, file.node.text)
        }
    }

    /** The classes of the chain under action [id], from the registered action through every delegate. */
    public fun actionChain(id: String): List<Class<*>> {
        var action: AnAction = checkNotNull(ActionManager.getInstance().getAction(id)) { "no action $id" }
        val chain = ArrayList<Class<*>>()
        val visited = Collections.newSetFromMap(IdentityHashMap<AnAction, Boolean>())
        while (visited.add(action)) {
            chain += action.javaClass
            action = (action as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
        }
        return chain
    }

    /** Each of [actionIds] holds every one of [loaders]' own [wrapper] class in its chain, which ends in [stock]. */
    public fun assertExecuteChains(
        loaders: Collection<ClassLoader>,
        actionIds: List<String> = EXECUTE_ACTION_IDS,
        wrapper: String = EXECUTE_WRAPPER,
        stock: String = STOCK_EXECUTE,
    ) {
        for (id in actionIds) {
            val chain = actionChain(id)
            for (loader in loaders) {
                val own = Class.forName(wrapper, false, loader)
                assertTrue("$loader's $wrapper is missing from $id: $chain", chain.any { it === own })
            }
            assertTrue("$id ends in $stock: $chain", chain.last().name.startsWith(stock))
        }
    }

    /** The installed `dynamicActionConfigurationCustomizer`s of [loaders], one per loader, as factories of fresh instances. */
    public fun installedCustomizers(loaders: Collection<ClassLoader>): List<() -> DynamicActionConfigurationCustomizer> {
        val registered = ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DynamicActionConfigurationCustomizer>("com.intellij.dynamicActionConfigurationCustomizer").extensionList
        return loaders.map { loader ->
            val owned = registered.filter { it.javaClass.classLoader === loader }
            assertEquals("customizers registered by $loader: $owned", 1, owned.size)
            val type = owned.single().javaClass
            return@map { type.getConstructor().newInstance() }
        }
    }

    /**
     * Registering [customizers] over a stand-in for each of [actionIds], in every order, makes
     * performing each action reach the stand-in exactly once; so it does after the first registered
     * is removed, its wrappers left detached in the chain. The registered actions are restored.
     */
    public fun assertEveryRegistrationOrder(
        project: Project,
        customizers: List<() -> DynamicActionConfigurationCustomizer>,
        actionIds: List<String> = EXECUTE_ACTION_IDS,
    ) {
        val manager = ActionManager.getInstance()
        val originals = actionIds.associateWith { checkNotNull(manager.getAction(it)) { "no action $it" } }
        val event = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(project), Presentation(), "PluginIsolation", ActionUiKind.NONE, null)
        try {
            for (order in permutations(customizers.indices.toList())) {
                var reached = 0
                for (id in actionIds) manager.replaceAction(id, object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        assertSame("the event passed through", event, e)
                        reached++
                    }
                })
                val registered = order.map { customizers[it]() }
                try {
                    registered.forEach { it.registerActions(manager) }
                    actionIds.forEach { checkNotNull(manager.getAction(it)).actionPerformed(event) }
                    assertEquals("each action reached once, registered in order $order", actionIds.size, reached)
                    registered.first().unregisterActions(manager)
                    actionIds.forEach { checkNotNull(manager.getAction(it)).actionPerformed(event) }
                    assertEquals("each action reached once after removing the first of $order", actionIds.size * 2, reached)
                } finally {
                    registered.asReversed().forEach { it.unregisterActions(manager) }
                }
            }
        } finally {
            originals.forEach { (id, action) -> manager.replaceAction(id, action) }
        }
    }

    public fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList())
        else values.flatMapIndexed { index, value -> permutations(values.filterIndexed { other, _ -> other != index }).map { listOf(value) + it } }

    /** The class file paths in the jars and class directories under [home]. */
    private fun classEntries(home: Path): Set<String> {
        val entries = HashSet<String>()
        Files.walk(home).use { paths ->
            for (path in paths.toList()) {
                when {
                    path.toString().endsWith(".jar") -> ZipFile(path.toFile()).use { zip ->
                        zip.entries().asSequence().filter { it.name.endsWith(".class") }.mapTo(entries) { it.name }
                    }
                    path.toString().endsWith(".class") -> entries += home.relativize(path).toString().replace('\\', '/')
                }
            }
        }
        return entries
    }

    /** The jar or directory a class resource [url] comes from. */
    private fun origin(url: java.net.URL): Path? = runCatching {
        val text = url.toString()
        when {
            text.startsWith("jar:") -> Path.of(URI(text.removePrefix("jar:").substringBefore("!/"))).toRealPath()
            text.startsWith("file:") -> Path.of(url.toURI()).toRealPath()
            else -> null
        }
    }.getOrNull()
}

/**
 * The isolation contract of one dialect plugin installed beside its siblings, for the plugin's
 * isolation test run (the plugin and its siblings installed as distributions, without the Brikk jars
 * on the test classpath). Call [collectErrors] before the light test's `super.setUp()`, so errors the
 * project's startup logs count, and [assertNoErrors] after its `tearDown`; call each check from a test.
 *
 * [pluginId] is the plugin under test and [siblingPluginIds] the plugins that must be installed beside
 * it; [languageIds] are the languages it registers; [ownedPackages] are packages it bundles and must
 * load from its own jars (`dev.brikk.house.sql`). With [wrapsExecuteActions], it and every sibling
 * holding the wrapper class wrap the Execute actions with `CooperativeExecuteConfiguration`.
 * [kotlinPluginEnabled], when set, is whether the Kotlin plugin must be enabled in this run.
 */
public class PluginIsolationContract(
    public val pluginId: String,
    public val siblingPluginIds: List<String>,
    public val languageIds: Set<String>,
    public val ownedPackages: List<String>,
    public val wrapsExecuteActions: Boolean = true,
    public val kotlinPluginEnabled: Boolean? = null,
) {
    private val logged = CopyOnWriteArrayList<Throwable>()
    private var logging: AccessToken? = null
    @Volatile private var probe: Throwable? = null

    /** Starts collecting errors logged on any thread; call it before the test's `super.setUp()`. */
    public fun collectErrors() {
        logging?.finish()
        logged.clear()
        logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                logged += t ?: AssertionError(message + "\n" + details.joinToString("\n"))
                return if (t != null && t === probe) Action.NONE else super.processError(category, message, details, t)
            }
        })
    }

    /** Stops collecting and fails with every error logged since [collectErrors] as a suppressed cause. */
    public fun assertNoErrors() {
        logging?.finish()
        logging = null
        val found = logged.toList()
        logged.clear()
        if (found.isNotEmpty()) throw AssertionError("${found.size} error(s) logged; the first: ${found.first()}").apply { found.forEach(::addSuppressed) }
    }

    /** The collection itself works: an error logged on a pooled thread is seen (and then let go). */
    public fun anErrorLoggedOnAPooledThreadIsSeen() {
        check(logging != null) { "collectErrors() was not called before setUp" }
        val error = AssertionError("an expected probe of the isolation contract")
        probe = error
        try {
            ApplicationManager.getApplication().executeOnPooledThread { Logger.getInstance(PluginIsolationContract::class.java).error(error) }.get()
            expect(logged.any { it === error }) { "an error logged on a pooled thread was not collected" }
        } finally {
            probe = null
            logged.removeIf { it === error }
        }
    }

    /** The plugin and its siblings are installed and enabled, each with its own plugin class loader. */
    public fun thePluginAndItsSiblingsAreInstalledApart() {
        siblings()
        kotlinPluginEnabled?.let { expected ->
            val enabled = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))?.isEnabled == true
            expect(enabled == expected) { "the Kotlin plugin is ${if (enabled) "enabled" else "not enabled"} in this run" }
        }
    }

    /** [ownedPackages] load from the plugin's own jars, and siblings bundling them have their own copies. */
    public fun ownedPackagesLoadFromThePlugin(): Unit = PluginIsolation.assertOwnsPackages(pluginId, ownedPackages, siblings())

    /** [languageIds] are registered by the plugin's classes and parse a statement as written. */
    public fun theLanguagesAreThePlugins(project: Project): Unit = PluginIsolation.assertLanguages(project, PluginIsolation.loaderOf(pluginId), languageIds)

    /** Each Execute action's chain holds the plugin's wrapper and every wrapping sibling's, and ends in the stock action. */
    public fun everyExecuteChainKeepsEachPluginsWrapper() {
        if (wrapsExecuteActions) PluginIsolation.assertExecuteChains(wrappingLoaders())
    }

    /** Registering the installed Execute customizers in every order reaches each action exactly once. */
    public fun everyRegistrationOrderReachesEachActionOnce(project: Project) {
        if (wrapsExecuteActions) PluginIsolation.assertEveryRegistrationOrder(project, PluginIsolation.installedCustomizers(wrappingLoaders()))
    }

    private fun siblings(): Map<String, ClassLoader> = PluginIsolation.assertInstalledApart(listOf(pluginId) + siblingPluginIds) - pluginId

    private fun wrappingLoaders(): List<ClassLoader> {
        val wrapper = PluginIsolation.EXECUTE_WRAPPER.replace('.', '/') + ".class"
        return listOf(PluginIsolation.loaderOf(pluginId)) + siblings().values.filter { it.getResource(wrapper) != null }
    }
}

private inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private fun assertEquals(message: String, expected: Any?, actual: Any?) = expect(expected == actual) { "$message: expected <$expected> but was <$actual>" }

private fun assertSame(message: String, expected: Any?, actual: Any?) = expect(expected === actual) { "$message: expected the same <$expected> but was <$actual>" }

private fun assertNotSame(message: String, unexpected: Any?, actual: Any?) = expect(unexpected !== actual) { "$message: <$actual>" }

private fun assertTrue(message: String, condition: Boolean) = expect(condition) { message }
