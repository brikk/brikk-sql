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
import com.intellij.database.util.TreePatternUtils
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

/** Resolves for a plugin's own Trino dbms; the stock `TRINO` is its registry alias. */
class DialectDataSourceResolverTest : BasePlatformTestCase() {
    private val own = CatalogTestDbms.TRINO_BRIKK
    private val stock = checkNotNull(Dbms.byName("TRINO"))
    private val resolver by lazy { DialectDataSourceResolver(own) }
    private val added = mutableListOf<LocalDataSource>()
    private lateinit var file: PsiFile

    override fun setUp() {
        super.setUp()
        registerDbms(own, testRootDisposable)
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
        dataSource(own)
        dataSource(own)
        RecordingConsole(project, file.virtualFile, STOCK_DRIVERS.getValue(stock)).use { recording ->
            assertEquals(Console(recording.console.dataSource), resolver.resolve(file))
            assertEquals("a completion copy resolves through its original", Console(recording.console.dataSource), resolver.resolve(file.copy() as PsiFile))
        }
    }

    /** A file bound to PostgreSQL never resolves to the project's Trino data source. */
    fun testAConsoleOfAnotherDbmsBindsTheFile() {
        dataSource(own)
        RecordingConsole(project, file.virtualFile, STOCK_DRIVERS.getValue(Dbms.POSTGRES)).use {
            assertEquals(NotFound, resolver.resolve(file))
            assertEquals(emptyList<String>(), resolver.consoleNamespace(file))
        }
    }

    fun testTheConsoleNamespaceIsOutermostFirst() {
        RecordingConsole(project, file.virtualFile, STOCK_DRIVERS.getValue(stock)).use { recording ->
            assertEquals(emptyList<String>(), resolver.consoleNamespace(file))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("hive", ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
            assertEquals(listOf("hive", "web"), resolver.consoleNamespace(file))
        }
    }

    fun testTheFilesResolutionScopeChoosesAmongSeveral() {
        val first = dataSource(own)
        val second = dataSource(own)
        assertAmbiguous(first, second)
        mapTo(second)
        assertEquals(FileMapping(second), resolver.resolve(file))
        mapTo(first, second)
        assertAmbiguous(first, second)
        val alias = dataSource(stock)
        mapTo(alias)
        assertEquals(FileMapping(alias), resolver.resolve(file))
    }

    fun testTheProjectsResolutionScopeApplies() {
        dataSource(own)
        val mapped = dataSource(own)
        SqlResolveMappings.getInstance(project).setMapping(null, scopeOf(mapped))
        assertEquals(FileMapping(mapped), resolver.resolve(file))
    }

    fun testAScopeOfAnotherDbmsBindsTheFile() {
        dataSource(own)
        mapTo(dataSource(Dbms.POSTGRES))
        assertEquals(NotFound, resolver.resolve(file))
    }

    fun testAnExcludingScopeResolvesNothing() {
        val only = dataSource(own)
        val mappings = SqlResolveMappings.getInstance(project)
        mappings.setMapping(file.virtualFile, TreePatternUtils.minus(SqlResolveMappings.ALL_DATA_SOURCES, scopeOf(only)))
        assertEquals(NotFound, resolver.resolve(file))
        mappings.setMapping(file.virtualFile, SqlResolveMappings.ALL_DATA_SOURCES)
        assertEquals(OnlyDataSource(only), resolver.resolve(file))
    }

    /** Aliases serve consoles and scopes, but the fallback counts only the dbms itself. */
    fun testTheOnlyDataSourceIsOfTheDbmsItself() {
        assertEquals(NotFound, resolver.resolve(file))
        dataSource(Dbms.POSTGRES)
        dataSource(stock)
        assertEquals(NotFound, resolver.resolve(file))
        val only = dataSource(own)
        assertEquals(OnlyDataSource(only), resolver.resolve(file))
        assertEquals(NotFound, DialectDataSourceResolver(own, onlyDataSourceFallback = false).resolve(file))
        assertFalse(DialectDataSourceResolver(CatalogTestDbms.FACADE).accepts(stock))
    }

    private fun assertAmbiguous(vararg candidates: LocalDataSource) {
        val resolution = resolver.resolve(file)
        assertEquals(candidates.toSet(), (resolution as? Ambiguous)?.candidates?.toSet())
    }

    /** Stock drivers give their data sources the driver's dbms; the plugin's dbms comes from its URL pattern. */
    private fun dataSource(dbms: Dbms): LocalDataSource {
        val driver = STOCK_DRIVERS[dbms]
        val dataSource = LocalDataSource().apply {
            name = "resolver ${UUID.randomUUID()}"
            if (driver != null) databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
            url = if (driver != null) "jdbc:offline:resolver" else "jdbc:brikkowntrino://offline"
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

    private companion object {
        val STOCK_DRIVERS = mapOf(checkNotNull(Dbms.byName("TRINO")) to "trino", Dbms.POSTGRES to "postgresql")
    }
}
