package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.TestPipeDialect
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.TRINO_FUNCTION_CATALOG
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Text explain against a real DuckDB data source through the IDE's driver process. The profile explains
 * with Trino's rules (DuckDB's own are refused), which DuckDB's `EXPLAIN SELECT` runs as a plain plan; a
 * recording console stands for the console, so any use of its session would be recorded.
 */
class TextExplainServiceTest : BasePlatformTestCase() {
    class Profile(
        override val dbms: Dbms,
        override val dialect: String = "trino",
        override val searchPath: CatalogSearchPathStrategy? = DUCKDB_SEARCH_PATH,
        override val pipe: PipeDialect? = null,
        override val enabled: Boolean = true,
    ) : TextExplainProfile {
        override val productLabel = "Fixture"
        override val builtinFunctions: FunctionCatalog = TRINO_FUNCTION_CATALOG
    }

    open class Service(project: Project, scope: CoroutineScope, override val profile: TextExplainProfile) : TextExplainService(project, scope)

    /** Counts the helper connections opened to the data source. */
    private inner class Connections : DatabaseConnectionInterceptor {
        override suspend fun interceptConnection(proto: DatabaseConnectionInterceptor.ProtoConnection, silent: Boolean): Boolean =
            proto.connectionPoint.dataSource === dataSource

        override suspend fun handleConnected(connection: DatabaseConnectionCore, proto: DatabaseConnectionInterceptor.ProtoConnection) {
            opened.incrementAndGet()
        }
    }

    private lateinit var errors: BackgroundErrors
    private lateinit var dataSource: LocalDataSource
    private lateinit var recording: RecordingConsole
    private lateinit var catalog: String
    private val opened = AtomicInteger()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        catalog = withIdeConnection(project, dataSource, otherRequestor()) { connection ->
            execute(connection, "CREATE SCHEMA web")
            execute(connection, "CREATE TABLE web.t(x INT)")
            checkNotNull(firstColumn(connection, "SELECT current_database()").single())
        }
        ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DatabaseConnectionInterceptor>("com.intellij.database.connectionInterceptor")
            .registerExtension(Connections(), testRootDisposable)
        val file = myFixture.addFileToProject("explain-${UUID.randomUUID()}.sql", "SELECT 1").virtualFile
        recording = RecordingConsole(project, file, dataSource)
        recording.console.setSearchPath(SearchPath.of(ObjectPath.create(catalog, ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
    }

    override fun tearDown() {
        try {
            recording.close()
            errors.awaitScopes()
            errors.assertNone()
        } finally {
            super.tearDown()
        }
    }

    private fun explain(text: String, profile: Profile = Profile(dataSource.dbms)): ExplainOutcome {
        val service = Service(project, errors.newScope(testRootDisposable), profile)
        return runBlocking { service.explain(recording.console, text) }
    }

    fun testAQueryIsExplainedOnAHelperConnectionInTheConsolesNamespace() {
        val outcome = explain("SELECT count(*) FROM t WHERE x > 1")
        assertEquals("DuckDB's first plan column", ExplainOutcome.Plan("physical_plan"), outcome)
        assertEquals(1, opened.get())
        assertEmpty("the console's session is never used", recording.requests)
        assertFalse(recording.console.session.isConnected)
    }

    /** A namespace the console knows but the helper cannot reach is refused, never explained in the default namespace. */
    fun testAKnownNamespaceWithoutAStrategyIsRefused() {
        val outcome = explain("SELECT count(*) FROM t", Profile(dataSource.dbms, searchPath = null))
        assertTrue(outcome.toString(), (outcome as ExplainOutcome.Refused).reason.contains("no search path strategy"))
        assertEquals(0, opened.get())
    }

    fun testAConsoleWithoutANamespaceExplainsInTheDefaultOne() {
        val file = myFixture.addFileToProject("plain-${UUID.randomUUID()}.sql", "SELECT 1").virtualFile
        RecordingConsole(project, file, dataSource).use { plain ->
            val service = Service(project, errors.newScope(testRootDisposable), Profile(dataSource.dbms, searchPath = null))
            assertEquals(ExplainOutcome.Plan("physical_plan"), runBlocking { service.explain(plain.console, "SELECT count(*) FROM web.t") })
        }
    }

    /** Trino cannot USE a catalog alone: a catalog-only namespace is refused for a strategy that cannot switch to it. */
    fun testACatalogOnlyNamespaceTheStrategyCannotSwitchToIsRefused() {
        recording.console.setSearchPath(SearchPath.of(ObjectPath.create(catalog, ObjectKind.DATABASE)))
        val schemaOnly = CatalogSchemaSearchPath('"', "USE {catalog}.{schema}", catalogProbes = emptyList(), schemaProbes = emptyList())
        val refused = explain("SELECT count(*) FROM web.t", Profile(dataSource.dbms, searchPath = schemaOnly))
        assertTrue(refused.toString(), (refused as ExplainOutcome.Refused).reason.contains("choose a schema"))
        assertEquals(0, opened.get())
        assertEquals("a strategy that switches to a catalog explains", ExplainOutcome.Plan("physical_plan"), explain("SELECT count(*) FROM web.t"))
    }

    fun testTheHelperConnectionIsReleased() {
        assertEquals(ExplainOutcome.Plan("physical_plan"), explain("SELECT count(*) FROM t"))
        assertEmpty(DatabaseConnectionManager.getInstance().activeConnections.filter { it.connectionPoint.dataSource === dataSource })
    }

    /** The live catalog skips the helper connection: explaining never harvests. */
    fun testTheHelperConnectionIsNotHarvested() {
        val harvester = CountingHarvester()
        val live = LiveService(errors.newScope(testRootDisposable), LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT, dbms = dataSource.dbms), null)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, live, testRootDisposable)
        ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DatabaseConnectionInterceptor>("com.intellij.database.connectionInterceptor")
            .registerExtension(LiveInterceptor(), testRootDisposable)
        assertEquals(ExplainOutcome.Plan("physical_plan"), explain("SELECT count(*) FROM t"))
        assertEquals(0, harvester.harvests.get())
    }

    /**
     * A driver may return normally from a cancelled statement: cancelled during its USE, the production
     * coroutine submits no EXPLAIN.
     */
    fun testCancellingDuringTheNamespaceSwitchSubmitsNothingMore() {
        val inUse = CountDownLatch(1)
        val released = CountDownLatch(1)
        val queries = CopyOnWriteArrayList<String>()
        val engine = embeddedDuckdb()
        val ignoring = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = method.invoke(engine, *(args ?: emptyArray()))
            if (result !is Statement) return@newProxyInstance result
            Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, call, callArgs ->
                when (call.name) {
                    "execute" -> {
                        inUse.countDown()
                        // Returns normally once cancelled, ignoring interrupts.
                        while (true) {
                            try {
                                if (released.await(10, TimeUnit.SECONDS)) break
                            } catch (_: InterruptedException) {
                            }
                        }
                        false
                    }
                    "cancel" -> released.countDown().let { null }
                    "executeQuery" -> {
                        queries += callArgs[0] as String
                        call.invoke(result, *callArgs)
                    }
                    else -> call.invoke(result, *(callArgs ?: emptyArray()))
                }
            } as Statement
        } as Connection
        val connection = databaseConnection(ignoring, dataSource, dataSource.dbms, otherRequestor())
        val service = object : Service(project, errors.newScope(testRootDisposable), Profile(dataSource.dbms)) {
            override suspend fun <T> withHelperConnection(console: JdbcConsole, use: suspend (DatabaseConnectionCore) -> T): T = use(connection)
        }
        try {
            runBlocking {
                val running = launch(Dispatchers.Default) { service.explain(recording.console, "SELECT count(*) FROM t") }
                assertTrue(inUse.await(30, TimeUnit.SECONDS))
                running.cancelAndJoin()
            }
            assertEquals(0, released.count)
            assertEmpty("no EXPLAIN after cancelling", queries)
        } finally {
            engine.close()
        }
    }

    /** Trino 483 creates the table of an EXPLAIN CREATE TABLE AS: statements that are not plain queries never reach a connection. */
    fun testRefusedStatementsOpenNoConnection() {
        for (sql in listOf(
            "CREATE TABLE web.u AS SELECT 1 AS x",
            "INSERT INTO web.t VALUES (1)",
            "SELECT * FROM TABLE(system.query(query => 'DELETE FROM t'))",
            "SELECT my_udf(x) FROM t",
            "SELECT 1; SELECT 2",
        )) assertTrue(sql, explain(sql) is ExplainOutcome.Refused)
        assertEquals(0, opened.get())
        assertEmpty(withIdeConnection(project, dataSource, otherRequestor()) { firstColumn(it, "SELECT * FROM web.t") })
    }

    fun testDuckdbsOwnRulesRefuseEverything() {
        val outcome = explain("SELECT 1", Profile(dataSource.dbms, dialect = "duckdb"))
        assertTrue(outcome.toString(), (outcome as ExplainOutcome.Refused).reason.contains("table function arguments"))
        assertEquals(0, opened.get())
    }

    /** Why DuckDB is refused: its EXPLAIN evaluates table function arguments, so this one advances the sequence. */
    fun testDuckdbExplainRunsTableFunctionArguments() {
        embeddedDuckdb().use { engine ->
            engine.createStatement().use { statement ->
                statement.execute("CREATE SEQUENCE s")
                statement.executeQuery("EXPLAIN SELECT * FROM range(nextval('s'))").close()
                statement.executeQuery("SELECT currval('s')").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getLong(1))
                }
            }
        }
    }

    fun testPipeIsLoweredFirstAndUnresolvedParametersAreRefused() {
        val pipe = TestPipeDialect("trino", FirstStageRule.AnyQuery)
        assertEquals(ExplainOutcome.Plan("physical_plan"), explain("FROM t |> WHERE x > 1 |> SELECT x", Profile(dataSource.dbms, pipe = pipe)))
        val parameter = explain("FROM t |> WHERE x = :x", Profile(dataSource.dbms, pipe = pipe))
        assertTrue(parameter.toString(), (parameter as ExplainOutcome.Refused).reason.contains("parameters"))
        pipe.enabled = false
        assertTrue("PIPE off: the text is not a plain query", explain("FROM t |> WHERE x > 1", Profile(dataSource.dbms, pipe = pipe)) is ExplainOutcome.Refused)
    }

    fun testOnlyEnabledProfilesOwnTheirConsoles() {
        val service = Service(project, errors.newScope(testRootDisposable), Profile(dataSource.dbms))
        assertTrue(service.owns(recording.console))
        assertFalse(Service(project, errors.newScope(testRootDisposable), Profile(dataSource.dbms, enabled = false)).owns(recording.console))
        assertFalse(Service(project, errors.newScope(testRootDisposable), Profile(Dbms.MYSQL)).owns(recording.console))
    }

    private fun execute(connection: DatabaseConnectionCore, sql: String) {
        val statement = checkNotNull(JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() })
        try {
            JdbcNativeUtil.computeRemote { statement.execute(sql) }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private fun firstColumn(connection: DatabaseConnectionCore, sql: String): List<String?> {
        val statement = checkNotNull(JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() })
        try {
            val rows = checkNotNull(JdbcNativeUtil.computeRemote { statement.executeQuery(sql) })
            return buildList { while (rows.next()) add(rows.getString(1)) }.also { JdbcNativeUtil.performSafe { rows.close() } }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private companion object {
        val DUCKDB_READ_BACK = "SELECT current_database() AS catalog, current_schema() AS schema"
        val DUCKDB_SEARCH_PATH = CatalogSchemaSearchPath(
            '"', "USE {catalog}.{schema}", useCatalog = "USE {catalog}",
            catalogProbes = listOf(NamespaceProbe.Column(DUCKDB_READ_BACK, "catalog")),
            schemaProbes = listOf(NamespaceProbe.Column(DUCKDB_READ_BACK, "schema")),
        )
    }
}
