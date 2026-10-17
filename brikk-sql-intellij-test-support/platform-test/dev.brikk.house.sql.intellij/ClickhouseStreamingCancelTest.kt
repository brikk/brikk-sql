package dev.brikk.house.sql.intellij

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.FinishedQuery
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cancel of a streaming console query on a real ClickHouse server, through the IDE's remote JDBC process
 * with the driver `BRIKK_CLICKHOUSE_DRIVER` names (`clickhouse-jdbc` 0.10.0, its all-in-one jar). Runs
 * when `BRIKK_CLICKHOUSE_URL` names a server (`tools/clickhouse-verify-server.sh up`); without them it
 * returns at once. A synthetic user runs only a synthetic query.
 */
class ClickhouseStreamingCancelTest : BasePlatformTestCase() {
    private class Cancel : StreamingCancelConfiguration(DialectDataSourceResolver(Dbms.CLICKHOUSE))

    private val url: String? = System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank)
        ?.takeIf { System.getenv("BRIKK_CLICKHOUSE_DRIVER") != null }
    private val user = "brikk_cancel_" + UUID.randomUUID().toString().replace("-", "")

    private fun admin(url: String, sql: String): String {
        val http = url.replace("jdbc:clickhouse://", "http://").substringBeforeLast('/')
        val request = HttpRequest.newBuilder(URI.create("$http/")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(sql)).build()
        val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        check(response.statusCode() == 200) { response.body() }
        return response.body().trim()
    }

    fun testStreamingCancelStopsTheServerQueryAndTheConsoleRunsAgain() {
        val url = url ?: return
        project.basePath?.let { Files.createDirectories(Path.of(it)) }
        admin(url, "CREATE USER $user IDENTIFIED WITH plaintext_password BY '$PASSWORD'")
        Disposer.register(testRootDisposable) { admin(url, "DROP USER IF EXISTS $user") }
        admin(url, "GRANT SELECT ON *.* TO $user")
        val driverJar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER"))
        val jars = listOf(driverJar.toString(), driverJar.resolveSibling("slf4j-api-2.0.7.jar").toString())
        val driver = DatabaseDriverManager.getInstance().createDriver("cancel-clickhouse-${UUID.randomUUID()}", "Cancel ClickHouse",
            UrlTemplate("default", "jdbc:clickhouse://{host::localhost}[:{port::8123}][/{database}?]")) as DatabaseDriverImpl
        driver.setDriverClass("com.clickhouse.jdbc.ClickHouseDriver")
        driver.setAdditionalClasspathElements(jars.flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") })
        val dataSource = LocalDataSource().apply {
            name = "cancel clickhouse ${UUID.randomUUID()}"
            databaseDriver = driver
            // This bare driver has no IDE authentication, so the synthetic user signs in through the URL.
            this.url = "$url?compress=false&decompress=false&ignore_unknown_config_key=true&user=$user&password=$PASSWORD"
            isAutoSynchronize = false
        }
        LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(dataSource) }
        DbPsiFacade.getInstance(project).clearCaches()
        val fetching = CopyOnWriteArrayList<DataRequest>()
        val finished = CopyOnWriteArrayList<DataRequest>()
        val errors = CopyOnWriteArrayList<Pair<DataRequest, String>>()
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun fetchStarted(context: DataRequest.Context, resultSetIndex: Int) {
                fetching += context.request
            }

            override fun error(context: DataRequest.Context, error: ErrorInfo) {
                errors += context.request to error.toString()
            }

            override fun requestFinished(context: DataRequest.Context) {
                finished += context.request
            }
        }, testRootDisposable)
        val cancel = Cancel().also { it.registerActions(ActionManager.getInstance()) }
        Disposer.register(testRootDisposable) { cancel.unregisterActions(ActionManager.getInstance()) }
        val file = myFixture.addFileToProject("cancel-${UUID.randomUUID()}.sql", "SELECT 1")
        val session = DatabaseSessionManager.openSession(project, dataSource, "cancel ${UUID.randomUUID()}")
        val console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(dataSource).useSession(session).build()
        fun submit(sql: String) = FinishedQuery(console, sql).also { session.messageBus.dataProducer.processRequest(it) }
        fun running() = admin(url, "SELECT query_id FROM system.processes WHERE user = '$user' FORMAT TSVRaw")
        var runningId = ""
        try {
            val warmUp = submit("SELECT 1")
            PlatformTestUtil.waitWithEventsDispatching("the console connects", { warmUp in finished }, 30)
            assertEmpty(errors)
            val streaming = submit("SELECT sleepEachRow(0.1) FROM numbers(600) SETTINGS max_block_size = 1, max_threads = 1, " +
                "max_execution_time = 25, http_response_buffer_size = 1")
            PlatformTestUtil.waitWithEventsDispatching("the results stream", { streaming in fetching && running().isNotEmpty() }, 15)
            runningId = running()
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(SessionClientHolder.CLIENT_KEY, console).build()
            checkNotNull(ActionManager.getInstance().getAction(CooperativeActionConfiguration.CANCEL)).actionPerformed(TestActionEvent.createTestEvent(context))
            // The EDT stays blocked: the server query ends without it.
            val blocked = System.nanoTime()
            while (running().isNotEmpty() && System.nanoTime() - blocked < 6_000_000_000) Thread.sleep(50)
            assertEquals("the server query ended", "", running())
            PlatformTestUtil.waitWithEventsDispatching("the cancelled request ends", { streaming in finished && session.isIdle }, 10)
            val next = submit("SELECT 42 AS after_cancel")
            PlatformTestUtil.waitWithEventsDispatching("the console runs its next query", { next in finished && session.isIdle }, 10)
            assertEmpty(errors.filter { it.first === next })
            admin(url, "SYSTEM FLUSH LOGS")
            assertEquals("1", admin(url, "SELECT count() FROM system.query_log WHERE query_id = '$runningId' AND type = 'ExceptionWhileProcessing' AND exception_code = 394"))
            assertEquals("1", admin(url, "SELECT count() FROM system.query_log WHERE user = '$user' AND type = 'QueryFinish' AND position(query, 'after_cancel') > 0"))
        } finally {
            if (Regex("[A-Za-z0-9_-]+").matches(runningId)) admin(url, "KILL QUERY WHERE query_id = '$runningId' SYNC")
            PlatformTestUtil.waitWithEventsDispatching("the console is idle", { session.isIdle }, 15)
            DatabaseSessionViewService.getInstance().destroyView(console)
            Disposer.dispose(console)
            Disposer.dispose(session)
        }
    }

    private companion object {
        const val PASSWORD = "synthetic-cancel-password"
    }
}
