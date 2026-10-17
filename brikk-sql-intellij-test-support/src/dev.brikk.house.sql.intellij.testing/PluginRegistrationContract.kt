package dev.brikk.house.sql.intellij.testing

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.serviceContainer.BaseKeyedLazyInstance
import com.intellij.util.KeyedLazyInstance
import org.jdom.Element
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Every registration of an installed plugin works as IntelliJ loads it: its `plugin.xml`, the descriptors it
 * includes and the optional descriptors of enabled dependencies are read from the plugin's own jars, each
 * extension registration has an extension point and an adapter of the plugin, and every adapter instantiates
 * through its bean (a lazy instance's class, a configurable, the file type the platform registered) to the class
 * the XML names. Every service the XML declares (`applicationService`, `projectService`) instantiates through
 * its container, and every light service (`@Service`) in the plugin's classes under [servicePackage] is final
 * and instantiates at each of its levels.
 * Run [check] from a light platform test of the plugin; every problem is reported together.
 */
public class PluginRegistrationContract(private val pluginId: String, private val servicePackage: String) {
    /** Checks the installed plugin; returns the number of extension registrations and services checked. */
    public fun check(project: Project): Pair<Int, Int> {
        val id = PluginId.getId(pluginId)
        val plugin = checkNotNull(PluginManagerCore.getPlugin(id)) { "$pluginId is not installed" }
        val loader = checkNotNull(plugin.pluginClassLoader) { "$pluginId has no class loader" }
        val jars = Files.walk(plugin.pluginPath).use { paths -> paths.filter { it.toString().endsWith(".jar") }.toList() }
        val documents = HashMap<String, Element>()
        val classes = ArrayList<String>()
        val prefix = servicePackage.replace('.', '/') + "/"
        for (jar in jars) ZipFile(jar.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".xml")) {
                    val root = runCatching { JDOMUtil.load(zip.getInputStream(entry).use { it.readBytes() }) }.getOrNull()
                    if (root?.name == "idea-plugin") documents[entry.name] = root
                }
                if (entry.name.startsWith(prefix) && entry.name.endsWith(".class")) classes += entry.name.removeSuffix(".class").replace('/', '.')
            }
        }
        val services = classes.mapNotNull { name -> runCatching { loader.loadClass(name) }.getOrNull()?.takeIf { it.isAnnotationPresent(Service::class.java) } }
        return check(project, documents, services)
    }

    /**
     * Checks the plugin registered as [pluginId] from [documents], its jars' `META-INF` descriptors by path
     * (`META-INF/plugin.xml` and those it includes or names), and the light [services]: [check] after reading
     * the installed jars. Returns the registrations and services checked.
     */
    public fun check(project: Project, documents: Map<String, Element>, services: List<Class<*>>): Pair<Int, Int> {
        val id = PluginId.getId(pluginId)
        val failures = ArrayList<String>()
        val descriptors = descriptors(documents)
        val unloadedOptional = unloadedOptionalDependencies(descriptors.first())
        // By extension point across every descriptor: a plugin may register one point in several groups or files.
        val byPoint = descriptors.flatMap { it.getChildren("extensions") }.flatMap { group ->
            val namespace = group.getAttributeValue("defaultExtensionNs") ?: group.getAttributeValue("xmlns")
            group.children.map { "$namespace.${it.name}" to it }
        }.groupBy({ it.first }, { it.second })
        for ((name, registrations) in byPoint) {
            SERVICE_LEVELS[name]?.let { level ->
                registrations.mapNotNullTo(failures) { declaredServiceProblem(project, id, level, it) }
                continue
            }
            val application = ApplicationManager.getApplication()
            val area = listOf(application.extensionArea, project.extensionArea).firstOrNull { it.hasExtensionPoint(name) }
            if (area == null) {
                // A point of an optional dependency that is not loaded (Kotlin's supportsKotlinPluginMode marker),
                // which the platform leaves unregistered.
                if (name.substringBeforeLast('.') !in unloadedOptional) failures += "no extension point $name (wrong namespace?)"
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val point = area.getExtensionPoint<Any>(name) as ExtensionPointImpl<Any>
            val adapters = point.sortedAdapters.filter { it.pluginDescriptor.pluginId == id }
            if (adapters.size < registrations.size) failures += "$name: ${registrations.size} registration(s), ${adapters.size} adapter(s)"
            val instances = if (name == FILE_TYPE) registrations.mapNotNull { registeredFileType(it, failures) } else adapters.mapNotNull { adapter ->
                runCatching {
                    val bean = checkNotNull(adapter.createInstance<Any>(point.componentManager))
                    val instance: Any = when (bean) {
                        is ConfigurableEP<*> -> checkNotNull(bean.createConfigurable())
                        is BaseKeyedLazyInstance<*> -> checkNotNull(bean.instance)
                        is KeyedLazyInstance<*> -> checkNotNull(bean.instance)
                        else -> bean
                    }
                    if (instance is UnnamedConfigurable) instance.disposeUIResources()
                    instance
                }.onFailure { failures += "$name: $adapter does not instantiate: $it" }.getOrNull()
            }
            for (registration in registrations) {
                val expected = CLASS_ATTRIBUTES.firstNotNullOfOrNull { registration.getAttributeValue(it) } ?: registration.getChildTextTrim("className")
                // An instance attribute may name a field rather than a class: only names of loadable classes are compared.
                if (expected == null || adapters.none { runCatching { it.pluginDescriptor.pluginClassLoader?.loadClass(expected) ?: Class.forName(expected) }.isSuccess }) continue
                if (instances.none { it.javaClass.name == expected }) failures += "$name did not instantiate $expected"
            }
        }
        var checked = 0
        for (type in services) {
            val service = type.getAnnotation(Service::class.java) ?: continue
            if (!Modifier.isFinal(type.modifiers)) failures += "light service ${type.name} is not final"
            for (level in service.value) {
                val manager = if (level == Service.Level.APP) ApplicationManager.getApplication() else project
                runCatching { checkNotNull(manager.getService(type)) }.onFailure { failures += "light service ${type.name} ($level) does not instantiate: $it" }
                checked++
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} registration problem(s) in $pluginId:\n" + failures.joinToString("\n"))
        return byPoint.values.sumOf { it.size } to checked
    }

    /** `META-INF/plugin.xml` and the descriptors it includes (`<xi:include>` or `<include>`) or names for enabled dependencies. */
    private fun descriptors(documents: Map<String, Element>): List<Element> {
        val root = checkNotNull(documents["META-INF/plugin.xml"]) { "$pluginId's installed plugin.xml is missing" }
        check(root.getChildTextTrim("id") == pluginId) { "The installed plugin.xml is ${root.getChildTextTrim("id")}'s" }
        // The local name: Doris, DuckDB and Trino include their PIPE descriptors as `xi:include`.
        val included = root.children.filter { it.name == "include" }.map { include ->
            val href = include.getAttributeValue("href").removePrefix("/")
            checkNotNull(documents[href]) { "plugin.xml includes $href, which the plugin does not hold" }
        }
        val optional = root.getChildren("depends").mapNotNull { depends ->
            val config = depends.getAttributeValue("config-file") ?: return@mapNotNull null
            if (PluginManagerCore.getPlugin(PluginId.getId(depends.textTrim))?.isEnabled != true) return@mapNotNull null
            checkNotNull(documents["META-INF/$config"]) { "plugin.xml names $config, which the plugin does not hold" }
        }
        return listOf(root) + included + optional
    }

    /**
     * The file type the platform registered for a `fileType` [registration], found by its name through
     * `FileTypeManager`, which instantiates it from the bean (by constructor or the `fieldName` singleton).
     */
    private fun registeredFileType(registration: Element, failures: MutableList<String>): FileType? {
        val typeName = registration.getAttributeValue("name")
        return FileTypeManager.getInstance().findFileTypeByName(typeName)
            .also { if (it == null) failures += "$FILE_TYPE: no file type named $typeName is registered" }
    }

    /** The ids of the plugins [root] depends on optionally that are not enabled. */
    private fun unloadedOptionalDependencies(root: Element): Set<String> =
        root.getChildren("depends").filter { it.getAttributeValue("optional") == "true" }.map { it.textTrim }
            .filterTo(HashSet()) { PluginManagerCore.getPlugin(PluginId.getId(it))?.isEnabled != true }

    /** What is wrong with a declared [level] service [declaration]: its class does not load or it does not instantiate; null when nothing is. */
    private fun declaredServiceProblem(project: Project, id: PluginId, level: Service.Level, declaration: Element): String? {
        val implementation = declaration.getAttributeValue("serviceImplementation") ?: return "${declaration.name} declares no serviceImplementation"
        val type = declaration.getAttributeValue("serviceInterface") ?: implementation
        val loader = PluginManagerCore.getPlugin(id)?.pluginClassLoader ?: javaClass.classLoader
        val service = runCatching { Class.forName(type, true, loader) }.getOrElse { return "${declaration.name} $type does not load: $it" }
        val manager = if (level == Service.Level.APP) ApplicationManager.getApplication() else project
        return runCatching { checkNotNull(manager.getService(service)) }.exceptionOrNull()?.let { "${declaration.name} $implementation does not instantiate: $it" }
    }

    private companion object {
        val CLASS_ATTRIBUTES = listOf("implementation", "implementationClass", "instance", "serviceImplementation")
        const val FILE_TYPE = "com.intellij.fileType"
        val SERVICE_LEVELS = mapOf("com.intellij.applicationService" to Service.Level.APP, "com.intellij.projectService" to Service.Level.PROJECT)
    }
}
