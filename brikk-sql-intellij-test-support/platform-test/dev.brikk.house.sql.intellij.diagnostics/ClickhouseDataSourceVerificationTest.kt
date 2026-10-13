package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.otherRequestor
import dev.brikk.house.sql.verify.ClickhouseQueryConnection
import dev.brikk.house.sql.verify.ParseOnlyClickhouseVerifier
import dev.brikk.house.sql.verify.SqlVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Verification against a real ClickHouse data source, reached through the IDE's remote JDBC process with
 * the ClickHouse plugin's driver (`com.clickhouse:clickhouse-jdbc` 0.9.8, its all-in-one jar). Runs when
 * `BRIKK_CLICKHOUSE_URL` names a server and `BRIKK_CLICKHOUSE_DRIVER` the jar
 * (`tools/clickhouse-verify-server.sh up`); without them every test returns at once.
 */
class ClickhouseDataSourceVerificationTest : BasePlatformTestCase() {
    private val url: String? = System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank)
        ?.takeIf { System.getenv("BRIKK_CLICKHOUSE_DRIVER") != null }
    private lateinit var dataSource: LocalDataSource
    private val user = "brikk_ide_" + UUID.randomUUID().toString().replace("-", "")

    /** Runs [sql] as the server's `default` user over its HTTP interface. */
    private fun admin(url: String, sql: String) {
        val http = url.replace("jdbc:clickhouse://", "http://").substringBeforeLast('/')
        val request = HttpRequest.newBuilder(URI.create("$http/")).POST(HttpRequest.BodyPublishers.ofString(sql)).build()
        val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        check(response.statusCode() == 200) { response.body() }
    }

    override fun setUp() {
        super.setUp()
        val url = url ?: return
        project.basePath?.let { Files.createDirectories(Path.of(it)) }
        admin(url, "CREATE USER $user IDENTIFIED WITH plaintext_password BY '$PASSWORD'")
        admin(url, "GRANT SELECT ON system.* TO $user")
        Disposer.register(testRootDisposable) { admin(url, "DROP USER IF EXISTS $user") }
        // The all-in-one jar and the slf4j-api the script fetches beside it.
        val driverJar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER"))
        val jars = listOf(driverJar.toString(), driverJar.resolveSibling("slf4j-api-2.0.7.jar").toString())
        val driver = DatabaseDriverManager.getInstance().createDriver("verify-clickhouse-${UUID.randomUUID()}", "Verify ClickHouse", UrlTemplate("default", PLUGIN_URL_TEMPLATE)) as DatabaseDriverImpl
        driver.setDriverClass("com.clickhouse.jdbc.ClickHouseDriver")
        driver.setAdditionalClasspathElements(jars.flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") })
        dataSource = LocalDataSource().apply {
            name = "verify clickhouse ${UUID.randomUUID()}"
            databaseDriver = driver
            // The IDE passes `session_id`, which this driver rejects unless told to ignore unknown keys.
            this.url = "$url?ignore_unknown_config_key=true"
            username = user
            isAutoSynchronize = false
            // The user's saved password: a helper never asks for one.
            passwordStorage = LocalDataSource.Storage.MEMORY
        }
        DatabaseCredentials.getInstance().setPassword(dataSource, OneTimeString(PASSWORD))
        LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(dataSource) }
        myFixture.configureByText("verify-${UUID.randomUUID()}.sql", "SELECT 1")
    }

    private fun verification(properties: Map<String, String>) =
        DataSourceVerification(DialectDataSourceResolver(dataSource.dbms), connectionProperties = { properties }) { connect ->
            ParseOnlyClickhouseVerifier({ connect()?.let(::Bridge) }, checkCanceled = ProgressManager::checkCanceled)
        }

    private class Bridge(private val helper: DataSourceQueryConnection) : ClickhouseQueryConnection {
        override fun queryString(sql: String, timeoutSeconds: Int) = helper.queryString(sql, timeoutSeconds)
        override fun cancel() = helper.cancel()
        override fun close() = helper.close()
    }

    /**
     * Verifies [sql] while the user is connected. The data source keeps the driver's defaults; the user's
     * own session connects uncompressed (on 262 and later the IDE's connect-time queries fail otherwise),
     * so only [properties] decide how the helper connects.
     */
    private fun verify(properties: Map<String, String>, sql: String): VerifyResult {
        val point = object : DatabaseConnectionPoint.Adapter(dataSource) {
            override fun getConnectionProperties(): Properties = Properties().also {
                super.getConnectionProperties()?.let(it::putAll)
                it.putAll(UNCOMPRESSED)
            }
        }
        val session = runBlocking { DatabaseConnectionManager.getInstance().build(project, point).setRequestor(otherRequestor()).create() }
        return checkNotNull(session).use { checkNotNull(verification(properties).validator(myFixture.file)).verify(sql, null) }
    }

    /** Runs [use] while the user's own session (uncompressed, see [verify]) is open. */
    private fun <T> whileConnected(use: () -> T): T {
        val point = object : DatabaseConnectionPoint.Adapter(dataSource) {
            override fun getConnectionProperties(): Properties = Properties().also {
                super.getConnectionProperties()?.let(it::putAll)
                it.putAll(UNCOMPRESSED)
            }
        }
        val session = runBlocking { DatabaseConnectionManager.getInstance().build(project, point).setRequestor(otherRequestor()).create() }
        return checkNotNull(session).use { use() }
    }

    fun testUncompressedHelpersGetPositionedVerdicts() {
        url ?: return
        val properties = UNCOMPRESSED
        assertEquals(VerifyResult.Accepted, verify(properties, "SELECT 'ü😀' FROM numbers(1)"))
        val sql = "SELECT 'ü😀' FRM t"
        val error = (verify(properties, sql) as VerifyResult.Rejected).error
        assertEquals(sql.lastIndexOf('t'), error.offset)
    }

    /** A URL asking for compression does not override the helper's uncompressed properties (with this driver, the URL wins over properties). */
    fun testTheHelpersPropertiesWinOverTheUrl() {
        url ?: return
        val written = checkNotNull(dataSource.url)
        dataSource.url = "$written&compress=true"
        Disposer.register(testRootDisposable) { dataSource.url = written }
        val user = HelperConnectionPoint(dataSource, UNCOMPRESSED)
        val session = runBlocking { DatabaseConnectionManager.getInstance().build(project, user).setRequestor(otherRequestor()).create() }
        checkNotNull(session).use {
            val result = checkNotNull(verification(UNCOMPRESSED).validator(myFixture.file)).verify("SELECT 1 FROM", null)
            assertInstanceOf(result, VerifyResult.Rejected::class.java)
            assertNotNull("a positioned verdict, not an unreadable answer: $result", (result as VerifyResult.Rejected).error.offset)
        }
        assertEquals("$written&compress=true", dataSource.url)
    }

    /** What the hook is for: this driver's default compressed transport fails on the server's answers. */
    fun testTheDriversDefaultsGetNoVerdict() {
        url ?: return
        val result = verify(emptyMap(), "SELECT 'ü😀' FRM t")
        assertInstanceOf(result, VerifyResult.Unavailable::class.java)
    }

    /**
     * The data source's user and the password stored only in `DatabaseCredentials` (not in the URL) reach
     * the driver on a helper, which never asks for a password; without a stored one there is no verdict.
     */
    fun testTheStoredLoginReachesTheDriverOnAHelper() {
        url ?: return
        assertFalse("the URL holds no password", PASSWORD in dataSource.url.orEmpty())
        val probe = DataSourceVerification(DialectDataSourceResolver(dataSource.dbms), connectionProperties = { UNCOMPRESSED }) { connect ->
            object : SqlVerifier {
                override val engine = "probe"
                override fun verify(sql: String): VerifyResult = try {
                    val helper = connect() ?: return VerifyResult.Unavailable("disconnected")
                    VerifyResult.Rejected(VerifyError(helper.use { it.queryString("SELECT currentUser()", 10).orEmpty() }))
                } catch (e: SQLException) {
                    VerifyResult.Unavailable(e.message.orEmpty())
                }
                override fun verifyExpression(sql: String) = verify(sql)
            }
        }
        whileConnected {
            val validator = checkNotNull(probe.validator(myFixture.file))
            assertEquals("the helper's session user", VerifyResult.Rejected(VerifyError(user)), validator.verify("SELECT 1", null))
            DatabaseCredentials.getInstance().setPassword(dataSource, null)
            assertInstanceOf(validator.verify("SELECT 1", null), VerifyResult.Unavailable::class.java)
        }
    }

    private companion object {
        const val PASSWORD = "brikk-verify-secret"

        /**
         * The ClickHouse plugin's driver URL template. Its user and password placeholders make the IDE
         * require authentication, so the data source's user and stored password reach the driver.
         */
        const val PLUGIN_URL_TEMPLATE =
            "jdbc:clickhouse://{host::localhost}?[:{port::8123}][/{database}?][\\?<&,user={user},password={password},{:identifier}={:identifier}>]"
        val UNCOMPRESSED = mapOf("compress" to "false", "decompress" to "false")
    }
}
