package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

class CatalogSearchPathTest : BasePlatformTestCase() {
    /**
     * Doris: per-part backticks, `use [catalog.]db`, `SWITCH catalog`; read-back with fallbacks. The first
     * catalog probe names a function the embedded engine lacks, as an older Doris lacks `current_catalog()`.
     */
    private val doris = CatalogSchemaSearchPath(
        quote = '`',
        useSchema = "use {catalog}.{schema}",
        useBareSchema = "use {schema}",
        useCatalog = "SWITCH {catalog}",
        catalogProbes = listOf(NamespaceProbe.Value("select doris_current_catalog()"), NamespaceProbe.FlaggedRow("SELECT * FROM catalogs", "CatalogName", "IsCurrent")),
        schemaProbes = listOf(NamespaceProbe.Value("select current_schema()")),
        defaultCatalog = "internal",
    )

    /** Trino: `USE "c"."s"` only; catalog and schema read back from one row, both required. */
    private val trino = CatalogSchemaSearchPath(
        quote = '"',
        useSchema = "USE {catalog}.{schema}",
        catalogProbes = listOf(NamespaceProbe.Column(TRINO_READ_BACK, "catalog")),
        schemaProbes = listOf(NamespaceProbe.Column(TRINO_READ_BACK, "schema")),
        schemaRequired = true,
    )

    /** DuckDB: `USE "c"."s"` or `USE "c"` (its default schema); catalog and schema read back from one row. */
    private val duckdb = CatalogSchemaSearchPath(
        quote = '"',
        useSchema = "USE {catalog}.{schema}",
        useCatalog = "USE {catalog}",
        catalogProbes = listOf(NamespaceProbe.Column(DUCKDB_READ_BACK, "catalog")),
        schemaProbes = listOf(NamespaceProbe.Column(DUCKDB_READ_BACK, "schema")),
    )

    private val connections = ArrayList<Connection>()

    override fun tearDown() {
        try {
            connections.forEach(Connection::close)
        } finally {
            super.tearDown()
        }
    }

    private fun catalog(name: String) = ObjectPath.create(name, ObjectKind.DATABASE)
    private fun path(vararg names: String): SearchPath =
        SearchPath.of(names.drop(1).fold(catalog(names[0])) { parent, name -> parent.append(name, ObjectKind.SCHEMA) })

    fun testDorisSwitchStatements() {
        assertEquals("use `hive`.`web`", doris.switchSql(path("hive", "web")))
        assertEquals("use `db`", doris.switchSql(SearchPath.of(ObjectPath.create("db", ObjectKind.SCHEMA))))
        assertEquals("SWITCH `hive`", doris.switchSql(path("hive")))
        assertEquals("a quote stands doubled", "use `a``b`.`c`", doris.switchSql(path("a`b", "c")))
        assertNull(doris.switchSql(SearchPath.of(catalog("hive").append("web", ObjectKind.SCHEMA).append("t", ObjectKind.TABLE))))
    }

    fun testInsertedNamesAreNotReadAsTemplates() {
        assertEquals("USE \"cat{schema}\".\"main\"", trino.switchSql(path("cat{schema}", "main")))
        assertEquals("USE \"{catalog}\".\"{schema}\"", trino.switchSql(path("{catalog}", "{schema}")))
    }

    fun testCancellationPropagatesFromAProbe() {
        val cancelled = databaseConnection(Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ ->
            throw kotlin.coroutines.cancellation.CancellationException("cancelled")
        } as Connection, LocalDataSource(), Dbms.POSTGRES, otherRequestor())
        assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) { trino.readBack(cancelled) }
    }

    fun testTrinoSwitchStatements() {
        assertEquals("USE \"hive\".\"web\"", trino.switchSql(path("hive", "web")))
        assertNull("no catalog to use", trino.switchSql(SearchPath.of(ObjectPath.create("web", ObjectKind.SCHEMA))))
        assertNull("Trino cannot use a bare catalog", trino.switchSql(path("hive")))
    }

    /** What the switch statements select is what the engine reads back. */
    fun testDuckdbSwitchesAndReadsBackOnTheEngine() {
        val engine = embeddedDuckdb()
        val connection = connection(engine)
        engine.createStatement().use {
            it.execute("ATTACH ':memory:' AS \"la\"\"ke\"")
            it.execute("CREATE SCHEMA \"la\"\"ke\".web")
        }
        for (target in listOf(path("la\"ke", "web"), path("la\"ke"), path("memory", "main"))) {
            val sql = checkNotNull(duckdb.switchSql(target))
            engine.createStatement().use { it.execute(sql) }
            val switched = checkNotNull(target.current)
            val expected = if (switched.kind == ObjectKind.DATABASE) switched.append("main", ObjectKind.SCHEMA) else switched
            assertEquals(sql, expected, duckdb.readBack(connection)?.current)
        }
    }

    fun testOneRowReadBackRunsItsQueryOnce() {
        val statements = AtomicInteger()
        val connection = connection(counting(embeddedDuckdb(), statements))
        assertEquals(path("memory", "main").current, trino.readBack(connection)?.current)
        assertEquals(1, statements.get())
    }

    fun testReadBackFallsBackThroughItsProbes() {
        val engine = embeddedDuckdb()
        val connection = connection(engine)
        engine.createStatement().use {
            it.execute("CREATE TABLE catalogs(CatalogName VARCHAR, IsCurrent VARCHAR)")
            it.execute("INSERT INTO catalogs VALUES ('internal', 'No'), ('lake', 'Yes')")
        }
        assertEquals("the flagged row names the catalog", path("lake", "main").current, doris.readBack(connection)?.current)
        engine.createStatement().use { it.execute("DROP TABLE catalogs") }
        assertEquals("then the default", path("internal", "main").current, doris.readBack(connection)?.current)
    }

    fun testAMissingSchemaGivesTheCatalogOrNothing() {
        val engine = embeddedDuckdb()
        val noSchema = listOf(NamespaceProbe.Value("SELECT NULL"))
        val catalogOnly = CatalogSchemaSearchPath('"', "USE {catalog}.{schema}", catalogProbes = listOf(NamespaceProbe.Value("SELECT 'lake'")), schemaProbes = noSchema)
        assertEquals(catalog("lake"), catalogOnly.readBack(connection(engine))?.current)
        val required = CatalogSchemaSearchPath('"', "USE {catalog}.{schema}", catalogProbes = listOf(NamespaceProbe.Value("SELECT 'lake'")), schemaProbes = noSchema, schemaRequired = true)
        assertNull(required.readBack(connection(engine)))
        assertNull("no catalog and no default", trino.readBack(failing()))
    }

    private fun connection(engine: Connection) =
        databaseConnection(engine.also { connections += it }, LocalDataSource(), Dbms.POSTGRES, otherRequestor())

    /** A connection whose every statement fails. */
    private fun failing() =
        databaseConnection(Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            throw java.sql.SQLException("refused: ${method.name}")
        } as Connection, LocalDataSource(), Dbms.POSTGRES, otherRequestor())

    private fun counting(engine: Connection, statements: AtomicInteger): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "createStatement") statements.incrementAndGet()
            try {
                method.invoke(engine, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Connection

    private companion object {
        const val TRINO_READ_BACK = "SELECT current_database() AS catalog, current_schema() AS schema"
        const val DUCKDB_READ_BACK = "SELECT current_database() AS catalog, current_schema() AS schema"
    }
}
