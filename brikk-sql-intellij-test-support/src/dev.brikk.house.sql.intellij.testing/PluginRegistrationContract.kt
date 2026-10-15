package dev.brikk.house.sql.intellij.testing

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.serviceContainer.BaseKeyedLazyInstance
import com.intellij.util.KeyedLazyInstance
import org.jdom.Element
import org.jetbrains.annotations.ApiStatus
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Every registration of an installed plugin works as IntelliJ loads it: its `plugin.xml`, the descriptors it
 * includes and the optional descriptors of enabled dependencies are read from the plugin's own jars, each
 * extension registration has an extension point and an adapter of the plugin, and every adapter instantiates
 * through its bean (a lazy instance's class, a configurable) to the class the XML names. Every light service
 * (`@Service`) in the plugin's classes under [servicePackage] is final and instantiates at each of its levels.
 * Run [check] from a light platform test of the plugin; every problem is reported together.
 */
@ApiStatus.Experimental
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
        val root = checkNotNull(documents["META-INF/plugin.xml"]) { "$pluginId's installed plugin.xml is missing" }
        check(root.getChildTextTrim("id") == pluginId) { "The installed plugin.xml is ${root.getChildTextTrim("id")}'s" }
        val descriptors = mutableListOf(root)
        for (include in root.getChildren("include")) {
            val href = include.getAttributeValue("href").removePrefix("/")
            descriptors += checkNotNull(documents[href]) { "plugin.xml includes $href, which the plugin does not hold" }
        }
        for (depends in root.getChildren("depends")) {
            val config = depends.getAttributeValue("config-file") ?: continue
            if (PluginManagerCore.getPlugin(PluginId.getId(depends.textTrim))?.isEnabled != true) continue
            descriptors += checkNotNull(documents["META-INF/$config"]) { "plugin.xml names $config, which the plugin does not hold" }
        }
        val services = classes.mapNotNull { name -> runCatching { loader.loadClass(name) }.getOrNull()?.takeIf { it.isAnnotationPresent(Service::class.java) } }
        return check(project, descriptors, services)
    }

    /**
     * Checks [descriptors] (`idea-plugin` elements) of the plugin registered as [pluginId] and the light
     * [services]: the part of [check] after reading the installed jars. Returns the registrations and services checked.
     */
    public fun check(project: Project, descriptors: List<Element>, services: List<Class<*>>): Pair<Int, Int> {
        val id = PluginId.getId(pluginId)
        val failures = ArrayList<String>()
        var extensions = 0
        for (descriptor in descriptors) for (group in descriptor.getChildren("extensions")) {
            val namespace = group.getAttributeValue("defaultExtensionNs") ?: group.getAttributeValue("xmlns")
            for ((name, registrations) in group.children.groupBy { "$namespace.${it.name}" }) {
                extensions += registrations.size
                val application = ApplicationManager.getApplication()
                val area = listOf(application.extensionArea, project.extensionArea).firstOrNull { it.hasExtensionPoint(name) }
                if (area == null) {
                    failures += "no extension point $name (wrong namespace?)"
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val point = area.getExtensionPoint<Any>(name) as ExtensionPointImpl<Any>
                val adapters = point.sortedAdapters.filter { it.pluginDescriptor.pluginId == id }
                if (adapters.size < registrations.size) failures += "$name: ${registrations.size} registration(s), ${adapters.size} adapter(s)"
                val instances = adapters.mapNotNull { adapter ->
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
        return extensions to checked
    }

    private companion object {
        val CLASS_ATTRIBUTES = listOf("implementation", "implementationClass", "instance", "serviceImplementation")
    }
}
