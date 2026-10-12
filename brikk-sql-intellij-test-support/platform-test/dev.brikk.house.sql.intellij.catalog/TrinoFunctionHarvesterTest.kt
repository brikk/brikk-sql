package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.TRINO_METADATA
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class TrinoFunctionHarvesterTest : BasePlatformTestCase() {
    /** `SHOW FUNCTIONS` and `version()` answered from rows, as the server's driver returns them. */
    private class Rows(private val functions: List<List<String?>>, private val version: String? = "483") : CatalogRowSource {
        override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) = when (sql) {
            TrinoFunctionHarvester.SQL_VERSION -> onRow { version }
            TrinoFunctionHarvester.SQL_FUNCTIONS -> functions.forEach { row -> onRow { row.getOrNull(it - 1) } }
            else -> error("unexpected $sql")
        }
    }

    /** The vendored `SHOW FUNCTIONS` output of Trino 483, from which the bundled catalog is generated. */
    private fun dump(): List<List<String?>> {
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("vendor/data/trino-functions-483.tsv")) }
        return Files.readAllLines(root.resolve("vendor/data/trino-functions-483.tsv")).filter(String::isNotBlank).map { it.split('\t') }
    }

    fun testTheHarvestOfTheDumpEqualsTheBundledCatalog() {
        val harvest = checkNotNull(TrinoFunctionHarvester.harvest(Rows(dump())))
        assertEquals("483", harvest.shared.serverVersion)
        assertTrue(harvest.shared.signatures)
        val bundled = TRINO_METADATA.forServerVersion("483").snapshot.functions.functions
        val order = compareBy<FunctionDef>({ it.name }, { it.kind })
        assertEquals(bundled.sortedWith(order), harvest.shared.functions.map { it.def }.sortedWith(order))
        assertEquals(SessionFacts.NONE, harvest.session)
    }

    fun testArgumentTypesSplitOnTopLevelCommasOnly() {
        val harvest = checkNotNull(TrinoFunctionHarvester.harvest(Rows(listOf(
            listOf("f", "bigint", "map(K,V), decimal(p, s),row(x bigint, y double)", "scalar", "true", ""),
            listOf("f", "bigint", "", "scalar", "true", ""),
        ))))
        assertEquals(listOf(emptyList(), listOf("map(K,V)", "decimal(p, s)", "row(x bigint, y double)")),
            harvest.shared.functions.single().def.overloads.map { it.argTypes })
    }

    fun testUnknownKindsAndNonIdentifiersAreLeftOut() {
        val harvest = checkNotNull(TrinoFunctionHarvester.harvest(Rows(listOf(
            listOf("abs", "bigint", "bigint", "scalar", "true", ""),
            listOf("future", "bigint", "", "procedure", "true", ""),
            listOf("\$operator\$add", "bigint", "bigint, bigint", "scalar", "true", ""),
            listOf("rank", "bigint", "", "window", "true", ""),
        ))))
        assertEquals(listOf("abs" to FunctionKind.SCALAR, "rank" to FunctionKind.WINDOW), harvest.shared.functions.map { it.def.name to it.def.kind })
    }

    fun testNoFunctionsIsIncomplete() {
        assertNull(TrinoFunctionHarvester.harvest(Rows(emptyList())))
    }

    class Disabled : TrinoLiveCatalogProfile {
        override val dbms: Dbms = Dbms.POSTGRES
    }

    class Service(scope: CoroutineScope) : LiveCatalogService(scope) {
        override val profile = Disabled()
    }

    class Interceptor : HarvestConnectionInterceptor(Service::class.java)

    fun testOffUntilTheProfileTurnsItOn() {
        val errors = BackgroundErrors(testRootDisposable)
        val service = Service(errors.newScope(testRootDisposable))
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(Service::class.java, service, testRootDisposable)
        val proto = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as DatabaseConnectionInterceptor.ProtoConnection
        assertFalse("no connection is intercepted", runBlocking { Interceptor().interceptConnection(proto, silent = true) })
        val dataSource = LocalDataSource().apply { url = "jdbc:trino://nowhere:8080" }
        assertEquals(RefreshOutcome.Disabled, runBlocking { service.refresh(project, dataSource) })
        assertEquals(LiveCatalogSelection.BUNDLED, service.selection(dataSource))
        errors.assertNone()
    }
}
