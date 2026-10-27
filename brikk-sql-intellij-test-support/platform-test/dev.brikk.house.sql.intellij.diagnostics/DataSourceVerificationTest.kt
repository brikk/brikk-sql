package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.foreignHelperRequestor
import dev.brikk.house.sql.intellij.catalog.otherRequestor
import dev.brikk.house.sql.intellij.catalog.queryRows
import dev.brikk.house.sql.intellij.catalog.realDuckdbDataSource
import dev.brikk.house.sql.intellij.catalog.withIdeConnection
import dev.brikk.house.sql.verify.SqlVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs the SQL it is given as a query on the connection it is handed, answering with the first value: a probe of the wiring. */
private class QueryingVerifier(private val connect: () -> DataSourceQueryConnection?) : SqlVerifier {
    override val engine: String = "test"

    override fun verify(sql: String): VerifyResult {
        val connection = connect() ?: return VerifyResult.Unavailable("disconnected")
        return connection.use {
            try {
                VerifyResult.Rejected(VerifyError(it.queryString(sql, 30).orEmpty()))
            } catch (e: SQLException) {
                VerifyResult.Unverified(e.message.orEmpty())
            }
        }
    }

    override fun verifyExpression(sql: String): VerifyResult = verify(sql)
}

/** Verification reaches the file's data source over its own helper connection, only while the user is connected. */
class DataSourceVerificationTest : BasePlatformTestCase() {
    private lateinit var dataSource: LocalDataSource
    private lateinit var verification: DataSourceVerification
    private val opened = mutableListOf<DataSourceQueryConnection?>()

    override fun setUp() {
        super.setUp()
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        verification = DataSourceVerification(DialectDataSourceResolver(dataSource.dbms)) { connect ->
            QueryingVerifier { connect().also { opened += it } }
        }
        myFixture.configureByText("verify-${UUID.randomUUID()}.sql", "SELECT 1")
    }

    private fun answer(sql: String): VerifyResult = checkNotNull(verification.validator(myFixture.file)).verify(sql, null)

    private fun helpers() = DatabaseConnectionManager.getInstance().activeConnections.count { it.connectionPoint.dataSource == dataSource }

    fun testADisconnectedDataSourceIsUnavailableAndNothingConnects() {
        assertEquals(VerifyResult.Unavailable("disconnected"), answer("SELECT 1"))
        assertEquals(listOf(null), opened)
        assertEquals(0, helpers())
    }

    fun testAConnectedDataSourceIsAskedOverASeparateHelperThatCloses() {
        withIdeConnection(project, dataSource, otherRequestor()) { user ->
            user.remoteConnection.createStatement().execute("CREATE TEMP TABLE user_session_marker AS SELECT 1 AS a")
            val seen = answer("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'user_session_marker'")
            assertEquals("the helper is not the user's session", VerifyResult.Rejected(VerifyError("0")), seen)
            assertEquals("only the user's connection stays open", 1, helpers())
        }
        assertEquals(0, helpers())
    }

    fun testAHelperCanApplyLocalGrammarSettingsWithoutChangingTheUsersSession() {
        withIdeConnection(project, dataSource, otherRequestor()) { user ->
            verification = DataSourceVerification(DialectDataSourceResolver(dataSource.dbms)) { connect ->
                object : SqlVerifier {
                    override val engine = "duckdb"
                    override fun verify(sql: String): VerifyResult = checkNotNull(connect()).use { helper ->
                        helper.execute("SET active_grammar_extensions=[]", 10)
                        helper.execute("SET preserve_identifier_case=false", 10)
                        VerifyResult.Rejected(VerifyError(helper.queryString("SELECT current_setting('preserve_identifier_case')::VARCHAR", 10).orEmpty()))
                    }
                    override fun verifyExpression(sql: String) = verify(sql)
                }
            }
            assertEquals(VerifyResult.Rejected(VerifyError("lowercase")), answer("SELECT 1"))
            assertEquals("preserve_case", user.queryRows("SELECT current_setting('preserve_identifier_case')::VARCHAR AS setting", 1, 10).single()["setting"])
            assertEquals("only the user's connection stays open", 1, helpers())
        }
    }

    fun testConnectingAndDisconnectingRestartHighlightingButHelpersDoNot() {
        val restarted = mutableListOf<Project>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            DatabaseConnectionManager.TOPIC,
            object : VerificationConnectionListener() {
                override fun restart(project: Project) {
                    restarted += project
                }
            },
        )
        fun restartsReach(count: Int) = PlatformTestUtil.waitWithEventsDispatching("$count restarts", { restarted.size >= count }, 10)
        withIdeConnection(project, dataSource, otherRequestor()) {
            restartsReach(1)
            answer("SELECT 1")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("a helper opening and closing", listOf(project), restarted.toList())
        }
        restartsReach(2)
        assertEquals("disconnecting", listOf(project, project), restarted.toList())
    }

    /** Another plugin's helper connection (its own copy of this library) is no user connection: it neither enables verification nor restarts highlighting. */
    fun testAnotherPluginsHelperIsNotTheUsersConnection() {
        val restarted = mutableListOf<Project>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            DatabaseConnectionManager.TOPIC,
            object : VerificationConnectionListener() {
                override fun restart(project: Project) {
                    restarted += project
                }
            },
        )
        val disconnected = verification.modificationCount(myFixture.file)
        withIdeConnection(project, dataSource, foreignHelperRequestor()) {
            assertEquals(VerifyResult.Unavailable("disconnected"), answer("SELECT 1"))
            assertEquals(disconnected, verification.modificationCount(myFixture.file))
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(emptyList<Project>(), restarted.toList())
        withIdeConnection(project, dataSource, otherRequestor()) {
            PlatformTestUtil.waitWithEventsDispatching("a user's connection restarts", { restarted.isNotEmpty() }, 10)
        }
    }

    /** A startup script (the data source's, else its driver's) is for the user's sessions, not a verification's. */
    fun testAHelperRunsNoStartupScript() {
        val driver = dataSource.databaseDriver as DatabaseDriverImpl
        withIdeConnection(project, dataSource, otherRequestor()) { user ->
            user.remoteConnection.createStatement().execute("CREATE TABLE startup_counter (n INTEGER)")
            for ((own, drivers) in listOf("INSERT INTO startup_counter VALUES (1)" to null, null to "INSERT INTO startup_counter VALUES (2)")) {
                dataSource.initScript = own
                driver.initScript = drivers
                try {
                    answer("SELECT 1")
                    answer("SELECT 1")
                    val rows = user.remoteConnection.createStatement().executeQuery("SELECT count(*) FROM startup_counter")
                    rows.next()
                    assertEquals("a verification ran the startup INSERT ($own, $drivers)", "0", rows.getString(1))
                    rows.close()
                } finally {
                    dataSource.initScript = null
                    driver.initScript = null
                }
            }
        }
    }

    /** Opening a helper under a canceled indicator stops at once and opens nothing. */
    fun testACanceledCallerOpensNoHelper() {
        withIdeConnection(project, dataSource, otherRequestor()) {
            val validator = checkNotNull(verification.validator(myFixture.file))
            val indicator = EmptyProgressIndicator()
            val failure = CompletableFuture.supplyAsync {
                runCatching {
                    // Starting the process clears an earlier cancellation.
                    ProgressManager.getInstance().runProcess(Computable { indicator.cancel(); validator.verify("SELECT 1", null) }, indicator)
                }.exceptionOrNull()
            }.get(30, TimeUnit.SECONDS)
            assertInstanceOf(failure, ProcessCanceledException::class.java)
            assertEquals("only the user's connection", 1, helpers())
            assertEquals(emptyList<DataSourceQueryConnection?>(), opened)
        }
    }

    fun testTheValidatorIsOnePerDataSource() {
        assertSame(verification.validator(myFixture.file), verification.validator(myFixture.file))
        val validators = (1..8).map { CompletableFuture.supplyAsync { runReadAction { verification.validator(myFixture.file) } } }.map { it.get(30, TimeUnit.SECONDS) }
        assertEquals("one validator, even when asked at once", 1, validators.toSet().size)
    }

    /** A verifier remembers what the server allowed (its settings); a changed login or address starts a new one. */
    fun testAChangedLoginOrAddressGetsANewValidator() {
        val first = verification.validator(myFixture.file)
        val user = dataSource.username
        dataSource.username = "someone-else"
        Disposer.register(testRootDisposable) { dataSource.username = user }
        val second = verification.validator(myFixture.file)
        assertNotSame(first, second)
        assertSame(second, verification.validator(myFixture.file))
    }

    fun testTheCountChangesWithTheConnectionAndTheDataSourceNotWithHelpers() {
        val disconnected = verification.modificationCount(myFixture.file)
        withIdeConnection(project, dataSource, otherRequestor()) {
            val connected = verification.modificationCount(myFixture.file)
            assertTrue(connected != disconnected)
            answer("SELECT 1")
            assertEquals(connected, verification.modificationCount(myFixture.file))
        }
        assertEquals(disconnected, verification.modificationCount(myFixture.file))
        val url = dataSource.url
        dataSource.url = "$url-moved"
        Disposer.register(testRootDisposable) { dataSource.url = url }
        assertTrue(verification.modificationCount(myFixture.file) != disconnected)
    }

    fun testAFileWithoutADataSourceHasNoValidator() {
        val elsewhere = DataSourceVerification(DialectDataSourceResolver(com.intellij.database.Dbms.ORACLE)) { connect -> QueryingVerifier(connect) }
        assertNull(elsewhere.validator(myFixture.file))
        assertEquals(0L, elsewhere.modificationCount(myFixture.file))
    }

    fun testCancelStopsARunningQuery() {
        withIdeConnection(project, dataSource, otherRequestor()) {
            val helper = AtomicReference<DataSourceQueryConnection?>()
            val verification = DataSourceVerification(DialectDataSourceResolver(dataSource.dbms)) { connect ->
                object : SqlVerifier {
                    override val engine = "test"
                    override fun verify(sql: String): VerifyResult {
                        val connection = checkNotNull(connect()).also(helper::set)
                        return connection.use {
                            try {
                                it.queryString(sql, 0)
                                VerifyResult.Accepted
                            } catch (e: SQLException) {
                                VerifyResult.Unverified(e.message.orEmpty())
                            }
                        }
                    }
                    override fun verifyExpression(sql: String) = verify(sql)
                }
            }
            val validator = checkNotNull(verification.validator(myFixture.file))
            val running = CompletableFuture.supplyAsync { validator.verify("SELECT count(*) FROM range(100000000000) a, range(10) b", null) }
            val started = System.nanoTime()
            while (helper.get() == null && !running.isDone) Thread.sleep(10)
            Thread.sleep(300)
            checkNotNull(helper.get()).cancel()
            assertInstanceOf(running.get(30, TimeUnit.SECONDS), VerifyResult.Unverified::class.java)
            assertTrue("the query ran on after cancel", System.nanoTime() - started < 20_000_000_000L)
        }
    }
}
