package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId

/** One dbms per test class, so each class starts from its own registrations. */
object CatalogTestDbms {
    @JvmField val FACADE: Dbms = create("BRIKK_TEST_FACADE")
    @JvmField val CATALOG_FACADE: Dbms = create("BRIKK_TEST_CATALOG_FACADE")
    @JvmField val GATED: Dbms = create("BRIKK_TEST_GATED")
    @JvmField val AUDITED: Dbms = create("BRIKK_TEST_AUDITED")

    private fun create(name: String) = Dbms.create(name, name, { AllIcons.Nodes.DataTables }, Dbms.defaultPattern(name.lowercase()))
}

private val testPlugin = DefaultPluginDescriptor(PluginId.getId("dev.brikk.house.sql.intellij.test"), CatalogTestDbms::class.java.classLoader)

/** Registers [dbms], a field of [CatalogTestDbms], as a plugin's `<dbms>` does, until [until] ends. */
fun registerDbms(dbms: Dbms, until: Disposable) {
    val bean = Dbms.DbmsBean().apply {
        id = dbms.name
        instance = "${CatalogTestDbms::class.java.name}.${dbms.name.removePrefix("BRIKK_TEST_")}"
        pluginDescriptor = testPlugin
    }
    ApplicationManager.getApplication().extensionArea.getExtensionPoint<Dbms.DbmsBean>("com.intellij.database.dbms").registerExtension(bean, until)
}

/** Registers [implementation] on the dbms-keyed extension point [name] (without its namespace), as plugin.xml does. */
fun registerDbmsExtension(name: String, dbms: Dbms, implementation: Class<*>, until: Disposable) {
    val bean = DbmsExtension.Bean<Any>().apply {
        dbmsStr = dbms.name
        implementationClass = implementation.name
        setPluginDescriptor(testPlugin)
    }
    ApplicationManager.getApplication().extensionArea.getExtensionPoint<DbmsExtension.Bean<Any>>("com.intellij.database.$name").registerExtension(bean, until)
}

/** Declares `<extensionFallback dbms="[dbms]" fallbackDbms="[fallback]"/>` until [until] ends. */
fun registerFallback(dbms: Dbms, fallback: Dbms, until: Disposable) {
    val bean = DbmsExtension.FallbackBean().apply {
        dbmsStr = dbms.name
        fallbackDbmsStr = fallback.name
    }
    ApplicationManager.getApplication().extensionArea.getExtensionPoint<DbmsExtension.FallbackBean>("com.intellij.database.extensionFallback").registerExtension(bean, until)
}
