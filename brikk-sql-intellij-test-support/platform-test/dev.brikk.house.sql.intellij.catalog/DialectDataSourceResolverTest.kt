package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution.Ambiguous
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution.Console
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution.FileMapping
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution.NotFound
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution.OnlyDataSource
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.util.UUID

class DialectDataSourceResolverTest : BasePlatformTestCase() {
    private val trino = checkNotNull(Dbms.byName("TRINO"))
    private val resolver by lazy { DialectDataSourceResolver(trino) }
    private val added = mutableListOf<LocalDataSource>()
    private lateinit var file: PsiFile

    override fun setUp() {
        super.setUp()
        file = myFixture.configureByText("resolve-${UUID.randomUUID()}.sql", "SELECT 1")
    }

    override fun tearDown() {
        try {
            SqlResolveMappings.getInstance(project).apply {
                setMapping(file.virtualFile, null)
                setMapping(null, null)
            }
            added.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource)
            DbPsiFacade.getInstance(project).clearCaches()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheFilesConsoleWins() {
        dataSource(trino)
        dataSource(trino)
        RecordingConsole(project, file.virtualFile, driverId(trino)).use { recording ->
            assertEquals(Console(recording.console.dataSource), resolver.resolve(file))
            assertEquals("a completion copy resolves through its original", Console(recording.console.dataSource), resolver.resolve(file.copy() as PsiFile))
        }
    }

    fun testAConsoleOfAnotherDbmsIsPassedOver() {
        val only = dataSource(trino)
        RecordingConsole(project, file.virtualFile, driverId(Dbms.POSTGRES)).use {
            assertEquals(OnlyDataSource(only), resolver.resolve(file))
            assertEquals(emptyList<String>(), resolver.consoleNamespace(file))
        }
    }

    fun testTheConsoleNamespaceIsOutermostFirst() {
        RecordingConsole(project, file.virtualFile, driverId(trino)).use { recording ->
            assertEquals(emptyList<String>(), resolver.consoleNamespace(file))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("hive", ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
            assertEquals(listOf("hive", "web"), resolver.consoleNamespace(file))
        }
    }

    fun testTheFilesResolutionScopeChoosesAmongSeveral() {
        val first = dataSource(trino)
        val second = dataSource(trino)
        assertAmbiguous(first, second)
        mapTo(second)
        assertEquals(FileMapping(second), resolver.resolve(file))
        mapTo(first, second)
        assertAmbiguous(first, second)
    }

    fun testTheProjectsResolutionScopeApplies() {
        dataSource(trino)
        val mapped = dataSource(trino)
        SqlResolveMappings.getInstance(project).setMapping(null, scopeOf(mapped))
        assertEquals(FileMapping(mapped), resolver.resolve(file))
    }

    fun testAScopeNamingOnlyOtherDataSourcesFallsBackToTheOnlyDataSource() {
        val only = dataSource(trino)
        mapTo(dataSource(Dbms.POSTGRES))
        assertEquals(OnlyDataSource(only), resolver.resolve(file))
        assertEquals(NotFound, DialectDataSourceResolver(trino, onlyDataSourceFallback = false).resolve(file))
    }

    fun testTheOnlyDataSourceOfTheDbms() {
        assertEquals(NotFound, resolver.resolve(file))
        dataSource(Dbms.POSTGRES)
        val only = dataSource(trino)
        assertEquals(OnlyDataSource(only), resolver.resolve(file))
        assertEquals(NotFound, DialectDataSourceResolver(trino, onlyDataSourceFallback = false).resolve(file))
    }

    /** A plugin's own Trino dbms also serves files attached to stock Trino data sources. */
    fun testStockAliasesQualify() {
        val stock = dataSource(trino)
        val plugin = DialectDataSourceResolver(Dbms.create("TRINO_BRIKK", "Trino (test)", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("trino-brikk-test")))
        assertEquals(OnlyDataSource(stock), plugin.resolve(file))
        assertFalse(plugin.accepts(Dbms.POSTGRES))
        assertEquals(NotFound, DialectDataSourceResolver(CatalogTestDbms.FACADE).resolve(file))
    }

    private fun assertAmbiguous(vararg candidates: LocalDataSource) {
        val resolution = resolver.resolve(file)
        assertEquals(candidates.toSet(), (resolution as? Ambiguous)?.candidates?.toSet())
    }

    /** Stock drivers, whose data sources take the driver's dbms. */
    private fun driverId(dbms: Dbms): String = mapOf(trino to "trino", Dbms.POSTGRES to "postgresql").getValue(dbms)

    private fun dataSource(dbms: Dbms): LocalDataSource {
        val dataSource = LocalDataSource().apply {
            name = "resolver ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driverId(dbms)))
            url = "jdbc:offline:resolver"
            isAutoSynchronize = false
        }
        LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
        DbPsiFacade.getInstance(project).clearCaches()
        added += dataSource
        assertEquals(dbms, dataSource.dbms)
        return dataSource
    }

    private fun scopeOf(vararg dataSources: LocalDataSource) =
        TreePattern(SqlImportUtil.createDataSources(dataSources.map { ObjectName.quoted(it.uniqueId) }.toTypedArray()))

    private fun mapTo(vararg dataSources: LocalDataSource) = SqlResolveMappings.getInstance(project).setMapping(file.virtualFile, scopeOf(*dataSources))
}
