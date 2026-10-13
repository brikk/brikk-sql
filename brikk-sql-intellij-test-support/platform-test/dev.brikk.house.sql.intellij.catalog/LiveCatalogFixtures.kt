package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import com.intellij.database.dataSource.connection.ConnectionRequestor
import dev.brikk.house.sql.intellij.catalog.helperRequestor
import com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper
import com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.DuckdbFunctionRow
import dev.brikk.house.sql.metadata.DuckdbKeywordRow
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionOverload
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.toSnapshot
import dev.brikk.house.sql.metadata.userFunctions
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBDriver

/**
 * DuckDB's harvest as a dialect would write it, over the metadata capture: built-in and persistent
 * functions are the server's; temporary macros (database `temp`) and the functions of loaded extensions
 * that are not statically linked (known from the bundled extension functions) are the session's.
 */
object DuckdbTestHarvester : LiveCatalogHarvester {
    override fun harvest(rows: CatalogRowSource): Harvest? {
        var version: String? = null
        rows.forEachRow("SELECT version()") { version = it.string(1) }
        val functions = ArrayList<DuckdbFunctionRow>()
        rows.forEachRow(DUCKDB_FUNCTIONS.sql) { row -> functions += DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { row.string(it + 1) }) }
        val keywords = ArrayList<DuckdbKeywordRow>()
        rows.forEachRow(DUCKDB_KEYWORDS.sql) { row -> keywords += DUCKDB_KEYWORDS.decode(listOf(row.string(1), row.string(2))) }
        val linked = ArrayList<String>()
        val loaded = ArrayList<String>()
        rows.forEachRow("SELECT extension_name, install_mode FROM duckdb_extensions() WHERE loaded ORDER BY 1") { row ->
            row.string(1)?.let { if (row.string(2) == "STATICALLY_LINKED") linked += it else loaded += it }
        }
        val engine = version?.removePrefix("v") ?: return null
        val dump = DuckdbDump(engine, functions, keywords)
        val fromLoaded = DUCKDB_METADATA.default.extensionFunctions.orEmpty().filter { it.extension in loaded }.mapTo(HashSet()) { it.name }
        val (sessionBuiltIn, builtIn) = dump.toSnapshot().functions.functions.map(::LiveFunction).partition { it.def.name in fromLoaded }
        if (builtIn.isEmpty()) return null
        val (temporary, persistent) = dump.userFunctions().partition { it.database == "temp" }
        val shared = builtIn + persistent.map { LiveFunction(it.def, listOf(it.database, it.schema)) }
        val keywordDefs = dump.toSnapshot().keywords?.keywords
        return Harvest(
            LiveInventory(engine, shared, signatures = true, keywords = keywordDefs, extensions = linked),
            SessionFacts(sessionBuiltIn + temporary.map { LiveFunction(it.def, listOf(it.database, it.schema)) }, loaded),
        )
    }
}

/** A harvester that counts its harvests and answers with [answer]. */
class CountingHarvester(var answer: (CatalogRowSource) -> Harvest? = DuckdbTestHarvester::harvest) : LiveCatalogHarvester {
    val harvests = AtomicInteger()

    override fun harvest(rows: CatalogRowSource): Harvest? {
        harvests.incrementAndGet()
        return answer(rows)
    }
}

fun embeddedDuckdb(): Connection = DuckDBDriver().connect("jdbc:duckdb:", Properties())

/** A second connection to [connection]'s database, with its own temporary objects. */
fun sameDatabase(connection: Connection): Connection = connection.unwrap(DuckDBConnection::class.java).duplicate()

/**
 * A Database Tools connection over a local JDBC [connection], through the platform's remote wrapper,
 * as the interceptor receives one: [requestor] says who opened it.
 */
fun databaseConnection(connection: Connection, point: DatabaseConnectionPoint, dbms: Dbms, requestor: ConnectionRequestor): DatabaseConnection {
    val remote = RemoteConnectionImpl(connection, GenericJdbcHelper())
    return Proxy.newProxyInstance(DatabaseConnection::class.java.classLoader, arrayOf(DatabaseConnection::class.java)) { proxy, method, args ->
        when (method.name) {
            "getRemoteConnection" -> remote
            "getConnectionPoint" -> point
            "getDbms" -> dbms
            "getRequestor" -> requestor
            "getAutoCommit" -> connection.autoCommit
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "test connection"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as DatabaseConnection
}

/** An introspection's connection requestor: neither a console engine nor a refresh's helper. */
fun otherRequestor(): ConnectionRequestor = ConnectionRequestor.Anonymous()

/**
 * Another plugin's helper requestor: this library's helper requestor class defined again by a class
 * loader of its own, as each plugin's copy of the library defines it, so it is not this copy's class.
 */
fun foreignHelperRequestor(): ConnectionRequestor {
    val name = "dev.brikk.house.sql.intellij.catalog.HelperRequestor"
    val home = ConnectionRequestor::class.java.classLoader
    val bytes = checkNotNull(helperRequestor().javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")).use { it.readBytes() }
    val loader = object : ClassLoader(helperRequestor().javaClass.classLoader) {
        override fun loadClass(className: String, resolve: Boolean): Class<*> =
            if (className == name) synchronized(getClassLoadingLock(className)) { findLoadedClass(className) ?: defineClass(className, bytes, 0, bytes.size) }
            else super.loadClass(className, resolve)
    }
    check(home != null)
    val type = loader.loadClass(name)
    check(type !== helperRequestor().javaClass) { "the foreign copy must be another class" }
    return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as ConnectionRequestor
}

class LiveProfile(
    override val harvester: LiveCatalogHarvester = CountingHarvester(),
    override val trigger: HarvestTrigger = HarvestTrigger.ONCE_PER_SESSION,
    override val mergePolicy: LiveMergePolicy = LiveMergePolicy.REPLACE,
    override val enabled: Boolean = true,
    override val deadline: Duration = 5.seconds,
    override val legacyFiles: LegacyCatalogFiles? = null,
    override val persistenceId: String? = "live-test",
    override val dbms: Dbms = Dbms.POSTGRES,
    override val refreshDelay: Duration = 100.milliseconds,
) : LiveCatalogProfile {
    override val productLabel = "Fixture"
    override val metadata = DUCKDB_METADATA
}

class LiveService(scope: CoroutineScope, override val profile: LiveCatalogProfile, private val directory: Path?) : LiveCatalogService(scope) {
    /** What [onRefreshed] received, in order: what a notification shows. */
    val refreshed = CopyOnWriteArrayList<RefreshOutcome>()

    override fun persistenceDirectory(): Path? = directory

    override fun onRefreshed(project: Project, dataSource: LocalDataSource, outcome: RefreshOutcome, reason: RefreshReason) {
        refreshed += outcome
        super.onRefreshed(project, dataSource, outcome, reason)
    }
}

/** The interceptor a plugin registers for [LiveService]. */
class LiveInterceptor : HarvestConnectionInterceptor(LiveService::class.java)

/** Hands [connection] to the registered [LiveService] as the platform does once it connected. */
fun connected(connection: DatabaseConnection) = runBlocking {
    LiveInterceptor().handleConnected(connection, Proxy.newProxyInstance(DatabaseConnection::class.java.classLoader,
        arrayOf(DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, _ -> throw UnsupportedOperationException(method.name) }
        as DatabaseConnectionInterceptor.ProtoConnection)
}

fun liveFunction(name: String, vararg argTypes: String, returnType: String = "VARCHAR", kind: FunctionKind = FunctionKind.SCALAR) =
    LiveFunction(FunctionDef(name, kind, overloads = listOf(FunctionOverload(argTypes.toList(), returnType))))

fun inventory(vararg functions: LiveFunction, version: String? = "1.5.6", signatures: Boolean = true, keywords: List<KeywordDef>? = null) =
    LiveInventory(version, functions.toList(), signatures, keywords)

/**
 * A data source on a DuckDB file under the IDE's system directory, reached through the IDE's remote JDBC
 * process with the embedded driver's jar, as a user's data source is. Removed when [until] ends.
 */
fun realDuckdbDataSource(project: Project, until: Disposable): LocalDataSource {
    // The driver process starts in the project's directory, which a light project's earlier test may have deleted.
    project.basePath?.let { Files.createDirectories(Path.of(it)) }
    val jar = System.getProperty("java.class.path").split(File.pathSeparator).first { "duckdb_jdbc" in it }
    val driver = DatabaseDriverManager.getInstance().createDriver("live-duckdb-${UUID.randomUUID()}", "Live DuckDB", UrlTemplate("default", "jdbc:duckdb:{file}")) as DatabaseDriverImpl
    driver.setDriverClass("org.duckdb.DuckDBDriver")
    // The driver process unpacks the engine's native library into its temp directory: keep it under the build directory.
    driver.vmOptions = "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}"
    driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements("jar://$jar!/"))
    val file = Path.of(PathManager.getSystemPath(), "live-${UUID.randomUUID()}.db")
    val dataSource = LocalDataSource().apply {
        name = "live ${UUID.randomUUID()}"
        databaseDriver = driver
        url = "jdbc:duckdb:$file"
        isAutoSynchronize = false
    }
    LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
    Disposer.register(until) {
        LocalDataSourceManager.getInstance(project).removeDataSource(dataSource)
        Files.deleteIfExists(file)
    }
    return dataSource
}

/** Opens a connection to [dataSource] through the IDE, as an introspection or console does, and runs [use] on it. */
fun <T> withIdeConnection(project: Project, dataSource: LocalDataSource, requestor: ConnectionRequestor, use: (DatabaseConnection) -> T): T =
    runBlocking { DatabaseConnectionManager.getInstance().build(project, dataSource).setRequestor(requestor).create() }
        .let { checkNotNull(it) { "No connection" } }.use { use(it.get()) }

/**
 * How many rows [helper] inserted through [dataSource]'s startup script, set first as the data source's
 * own and then as its driver's, while the user has [dataSource] connected (the user's connection opened
 * before either was set, so it ran neither). A helper connection should run neither: both counts are 0.
 */
fun startupScriptRuns(project: Project, dataSource: LocalDataSource, helper: () -> Unit): List<Int> {
    val driver = dataSource.databaseDriver as DatabaseDriverImpl
    val table = "startup_counter_${UUID.randomUUID().toString().replace("-", "")}"
    return withIdeConnection(project, dataSource, otherRequestor()) { user ->
        user.remoteConnection.createStatement().execute("CREATE TABLE $table (n INTEGER)")
        listOf("INSERT INTO $table VALUES (1)" to null, null to "INSERT INTO $table VALUES (2)").map { (own, drivers) ->
            dataSource.initScript = own
            driver.initScript = drivers
            try {
                helper()
                val rows = user.remoteConnection.createStatement().executeQuery("SELECT count(*) FROM $table")
                rows.next()
                rows.getInt(1).also {
                    rows.close()
                    user.remoteConnection.createStatement().execute("DELETE FROM $table")
                }
            } finally {
                dataSource.initScript = null
                driver.initScript = null
            }
        }
    }
}

/**
 * [connection] as a remote driver may behave: a running query ignores query timeouts and interrupts of
 * the waiting thread, and ends only when it completes or its statement is cancelled.
 */
fun ignoringInterrupts(connection: Connection): Connection = wrap(connection, Connection::class.java) { method, args ->
    val result = method.invoke(connection, *args)
    if (result !is Statement) return@wrap result
    wrap(result, Statement::class.java) { call, callArgs ->
        if (call.name == "setQueryTimeout") null else uninterruptibly { call.invoke(result, *callArgs) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> wrap(target: T, type: Class<T>, call: (Method, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        try {
            call(method, arrayOf<Any?>(*(args ?: emptyArray())))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as T

private fun uninterruptibly(call: () -> Any?): Any? {
    var result: Result<Any?>? = null
    val worker = Thread { result = runCatching(call) }.apply { start() }
    var interrupted = false
    while (worker.isAlive) {
        try {
            worker.join()
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return checkNotNull(result).getOrThrow()
}
