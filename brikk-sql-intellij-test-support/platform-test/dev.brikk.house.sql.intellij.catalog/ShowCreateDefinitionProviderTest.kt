package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.DasObject
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.shape.DefinedObject
import java.sql.Connection

/** Definitions read from a real DuckDB's catalog for objects of a PostgreSQL-family model, as the DuckDB plugin's. */
class ShowCreateDefinitionProviderTest : BasePlatformTestCase() {
    class Provider : ShowCreateDefinitionProvider(
        "duckdb",
        mapOf(
            ObjectKind.TABLE to DefinedObject.TABLE,
            ObjectKind.VIEW to DefinedObject.VIEW,
            ObjectKind.ROUTINE to DefinedObject.MACRO,
            ObjectKind.SEQUENCE to DefinedObject.SEQUENCE,
            ObjectKind.INDEX to DefinedObject.INDEX,
        ),
        listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA),
    )

    private lateinit var engine: Connection

    override fun setUp() {
        super.setUp()
        engine = embeddedDuckdb()
        engine.createStatement().use { statement ->
            for (sql in listOf(
                "CREATE SCHEMA web",
                "CREATE TABLE web.t(id INT PRIMARY KEY, name VARCHAR DEFAULT 'x')",
                "CREATE VIEW web.v AS SELECT id FROM web.t",
                "CREATE MACRO web.add(a, b := 1) AS a + b, (a, b, c) AS a + b + c",
                "CREATE MACRO web.rows(n) AS TABLE SELECT * FROM range(n)",
                "CREATE SEQUENCE web.seq START 5",
                "CREATE INDEX ix ON web.t(name)",
                "ATTACH ':memory:' AS lake",
                "CREATE TABLE lake.main.o(x INT)",
            )) statement.execute(sql)
        }
    }

    override fun tearDown() {
        try {
            engine.close()
        } finally {
            super.tearDown()
        }
    }

    /** What the platform's `load` writes for [objects], through this provider. */
    private fun load(vararg objects: DasObject): String {
        val sources = StringBuilder()
        Provider().load(objects.toList(), databaseConnection(engine, LocalDataSource(), Dbms.POSTGRES, otherRequestor()), sources)
        return sources.toString()
    }

    fun testDefinitionsComeFromTheCatalog() {
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        val databases = (model.root as PgRoot).databases
        val web = databases.createOrGet("memory").schemas.createOrGet("web")
        val table = web.tables.createOrGet("t")
        val provider = Provider()
        assertTrue(provider.isSupported(table))
        assertFalse("a schema has no catalog definition", provider.isSupported(web))
        assertTrue(load(table), "CREATE TABLE web.t(id INTEGER PRIMARY KEY, \"name\" VARCHAR DEFAULT('x'));" in load(table))
        assertTrue("CREATE VIEW web.v AS SELECT id FROM web.t;" in load(web.views.createOrGet("v")))
        assertTrue("START 5" in load(web.sequences.createOrGet("seq")))
        assertTrue("CREATE INDEX ix ON web.t(\"name\");" in load(table.indices.createOrGet("ix")))
        assertTrue("CREATE TABLE o(x INTEGER);" in load(databases.createOrGet("lake").schemas.createOrGet("main").tables.createOrGet("o")))
    }

    fun testMacrosAreRebuiltWithTheirOverloadsAndSayWhatTheyLeaveOut() {
        val web = (ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES).root as PgRoot).databases.createOrGet("memory").schemas.createOrGet("web")
        val add = load(web.routines.createOrGet("add"))
        assertTrue(add, add.contains("-- DuckDB does not report macro parameter defaults; any are omitted here.\nCREATE MACRO web.add(a, b) AS (a + b), (a, b, c) AS ((a + b) + c);"))
        val rows = load(web.routines.createOrGet("rows"))
        assertTrue(rows, rows.contains("CREATE MACRO web.rows(n) AS TABLE SELECT * FROM \"range\"(n);"))
        engine.createStatement().use { it.execute("DROP MACRO web.add") }
        assertFalse("a macro the server no longer has", "CREATE MACRO" in load(web.routines.createOrGet("add")))
    }
}
