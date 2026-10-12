package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnection
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
import kotlinx.coroutines.runBlocking
import com.intellij.database.dataSource.connection.ConnectionRequestor
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
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBDriver

/**
 * DuckDB's harvest as a dialect would write it, over the metadata capture: built-in and persistent
 * functions are the server's, temporary macros (database `temp`) the session's.
 */
object DuckdbTestHarvester : LiveCatalogHarvester {
    override fun harvest(rows: CatalogRowSource): Harvest? {
        var version: String? = null
        rows.forEachRow("SELECT version()") { version = it.string(1) }
        val functions = ArrayList<DuckdbFunctionRow>()
        rows.forEachRow(DUCKDB_FUNCTIONS.sql) { row -> functions += DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { row.string(it + 1) }) }
        val keywords = ArrayList<DuckdbKeywordRow>()
        rows.forEachRow(DUCKDB_KEYWORDS.sql) { row -> keywords += DUCKDB_KEYWORDS.decode(listOf(row.string(1), row.string(2))) }
        val extensions = ArrayList<String>()
        rows.forEachRow("SELECT extension_name FROM duckdb_extensions() WHERE loaded ORDER BY 1") { row -> row.string(1)?.let(extensions::add) }
        val engine = version?.removePrefix("v") ?: return null
        val dump = DuckdbDump(engine, functions, keywords)
        val builtIn = dump.toSnapshot().functions.functions.map(::LiveFunction)
        if (builtIn.isEmpty()) return null
        val (temporary, persistent) = dump.userFunctions().partition { it.database == "temp" }
        val shared = builtIn + persistent.map { LiveFunction(it.def, listOf(it.database, it.schema)) }
        val keywordDefs = dump.toSnapshot().keywords?.keywords
        return Harvest(
            LiveInventory(engine, shared, signatures = true, keywords = keywordDefs, extensions = extensions),
            SessionFacts(temporary.map { LiveFunction(it.def, listOf(it.database, it.schema)) }),
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
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "test connection"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as DatabaseConnection
}

/** An introspection's connection requestor: neither a console engine nor a refresh's helper. */
fun otherRequestor(): ConnectionRequestor = ConnectionRequestor.Anonymous()

class LiveProfile(
    override val harvester: LiveCatalogHarvester = CountingHarvester(),
    override val trigger: HarvestTrigger = HarvestTrigger.ONCE_PER_SESSION,
    override val mergePolicy: LiveMergePolicy = LiveMergePolicy.REPLACE,
    override val enabled: Boolean = true,
    override val deadline: Duration = 5.seconds,
    override val legacyFiles: LegacyCatalogFiles? = null,
    override val persistenceId: String? = "live-test",
    override val dbms: Dbms = Dbms.POSTGRES,
) : LiveCatalogProfile {
    override val productLabel = "Fixture"
    override val metadata = DUCKDB_METADATA
}

class LiveService(scope: CoroutineScope, override val profile: LiveCatalogProfile, private val directory: Path?) : LiveCatalogService(scope) {
    override fun persistenceDirectory(): Path? = directory
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
