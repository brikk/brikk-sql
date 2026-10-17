package dev.brikk.house.sql.metadata

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DialectInitializationTest {

    /** Loads the test classpath again, isolated, and can tell which classes it has loaded. */
    private class IsolatedLoader : URLClassLoader(
        System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray(),
        getPlatformClassLoader(),
    ) {
        fun loaded(name: String): Boolean = findLoadedClass(name) != null
    }

    @Test
    fun theDuckdbCatalogDoesNotLoadOtherDialects() {
        IsolatedLoader().use { loader ->
            val facade = loader.loadClass("dev.brikk.house.sql.metadata.DuckdbMetadataKt")
            assertNotNull(facade.getMethod("getDUCKDB_FUNCTION_CATALOG").invoke(null))

            for (dialect in listOf("Trino", "Doris", "Starrocks", "Clickhouse")) {
                assertNull(
                    "dev.brikk.house.sql.metadata.Generated${dialect}FunctionCatalogKt".takeIf(loader::loaded),
                    "$dialect catalog loaded",
                )
            }
        }
    }
}
