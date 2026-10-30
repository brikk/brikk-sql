package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.ExplainPreparation
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.prepareTextExplain
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import org.duckdb.DuckDBConnection
import kotlin.test.*

internal class DuckdbExplainResultTest {
    private fun render(sql: String, generated: Boolean) = if (generated)
        assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb") else sql
    private fun Connection.execute(sql: String) = createStatement().use { s ->
        s.queryTimeout = 5
        if (s.execute(sql)) s.resultSet.use { while (it.next()) { /* once, never retry */ } }
    }
    private fun Connection.rows(sql: String, generated: Boolean = false): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(render(sql, generated)).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.scalar(sql: String) = rows(sql).single().single()!!
    private fun Connection.sequence(name: String) = scalar("SELECT coalesce(last_value,0) FROM duckdb_sequences() WHERE sequence_name='$name'").toLong()
    private fun modes(block: (DuckDBConnection, Boolean) -> Unit) {
        for (generated in listOf(false, true)) (DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("autoinstall_known_extensions", "false")
            setProperty("autoload_known_extensions", "false")
        }) as DuckDBConnection).use { block(it, generated) }
    }

    @Test
    fun planFormatsReturnTwoVarcharColumnsAndDoNotBecomeJsonOrChildResultTypes() = modes { c, generated ->
        val formats = listOf("text", "json", "yaml", "html", "graphviz", "mermaid", "compact", "default", "query_tree", "query_tree_optimizer", "no_output")
        for (format in formats) {
            val sql = "EXPLAIN (FORMAT $format) SELECT 42 AS answer"
            c.createStatement().use { s -> s.executeQuery(render(sql, generated)).use { r ->
                val shape = SqlFragment(sql, "duckdb").outputShape()
                assertEquals(shape.columns.map { it.name }, (1..r.metaData.columnCount).map { r.metaData.getColumnName(it) })
                assertEquals(listOf("VARCHAR", "VARCHAR"), (1..r.metaData.columnCount).map { r.metaData.getColumnTypeName(it) })
                assertTrue(r.next())
                assertEquals("physical_plan", r.getString(1))
                val plan = assertNotNull(r.getString(2))
                when (format) {
                    "json" -> Json.parseToJsonElement(plan)
                    "graphviz" -> assertTrue("digraph" in plan)
                    "mermaid" -> assertTrue("flowchart" in plan)
                    "html" -> assertTrue("<!DOCTYPE html>" in plan)
                    "no_output" -> assertEquals("", plan)
                    else -> assertTrue(plan.isNotEmpty())
                }
            } }
        }
        for (format in listOf("'JSON'", "foo.json", "('json')")) Json.parseToJsonElement(c.rows("EXPLAIN (FORMAT $format) SELECT 1", generated).single()[1]!!)
        for (setting in listOf("all", "optimized_only", "physical_only")) {
            c.execute("SET explain_output='$setting'")
            assertEquals(if (setting == "all") 3 else 1, c.rows("EXPLAIN SELECT 1", generated).size)
            assertEquals("sql", c.rows("EXPLAIN (SQL) VALUES(1)", generated).single()[0])
        }
    }

    @Test
    fun sqlModeExportsExecutableSelectTextWithoutExecutingVolatileOrErrorExpressions() = modes { c, generated ->
        c.execute("CREATE TABLE data(id INT,g VARCHAR)")
        c.execute("INSERT INTO data VALUES(1,'a'),(1,'a'),(2,'b'),(NULL,'b')")
        val query = "WITH counts AS MATERIALIZED (SELECT g,count(*) AS n FROM data GROUP BY g) SELECT g,n FROM counts ORDER BY g"
        val exported = c.rows("EXPLAIN (SQL) $query", generated).single()
        assertEquals("sql", exported[0])
        assertEquals(c.rows(query), c.rows(assertNotNull(exported[1])))
        assertEquals(c.rows(query), c.rows(render(exported[1]!!, true)))
        c.execute("CREATE SEQUENCE export_only")
        val volatile = c.rows("EXPLAIN (SQL) SELECT nextval('export_only') AS v FROM range(2)", generated).single()[1]!!
        assertEquals(0, c.sequence("export_only"))
        assertTrue("nextval" in volatile.lowercase())
        assertEquals(listOf(listOf("1"), listOf("2")), c.rows(volatile))
        assertEquals(2, c.sequence("export_only"))
        val error = c.rows("EXPLAIN (SQL) SELECT error('execution only')", generated).single()[1]!!
        assertTrue("execution only" in error)
        assertEquals("0", c.scalar("SELECT count(*) FROM duckdb_extensions() WHERE extension_name='autocomplete' AND loaded"))
    }

    @Test
    fun analyzeFalseStillRunsTheChildAndRepeatedAnalyzeDoesNotExecuteItTwice() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT)")
        c.execute("CREATE SEQUENCE executed")
        c.execute("CREATE SEQUENCE ignored")
        c.rows("EXPLAIN INSERT INTO target VALUES(1)", generated)
        c.rows("EXPLAIN (SQL) INSERT INTO target VALUES(1)", generated)
        assertEquals("0", c.scalar("SELECT count(*) FROM target"))
        c.rows("EXPLAIN (ANALYZE false) INSERT INTO target VALUES(nextval('executed'))", generated)
        assertEquals("1", c.scalar("SELECT count(*) FROM target"))
        assertEquals(1, c.sequence("executed"))
        val plan = c.rows("EXPLAIN (ANALYSE nextval('ignored'), ANALYZE 0, FORMAT json) INSERT INTO target VALUES(nextval('executed'))", generated).single()
        assertEquals("analyzed_plan", plan[0])
        Json.parseToJsonElement(plan[1]!!)
        assertEquals("2", c.scalar("SELECT count(*) FROM target"))
        assertEquals(2, c.sequence("executed"))
        assertEquals(0, c.sequence("ignored"), "ANALYZE option values are ignored, not evaluated as booleans")
    }

    @Test
    fun sqlExportDoesNotMutateDmlButExecutingTheReturnedSqlDoesSoOnce() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT,v INT)")
        c.execute("INSERT INTO target VALUES(1,10)")
        val operations = listOf("INSERT INTO target VALUES(2,20)", "UPDATE target SET v=v+1 WHERE id=1", "DELETE FROM target WHERE id=2",
            "MERGE INTO target t USING (VALUES(1,77),(3,30)) s(id,v) ON t.id=s.id WHEN MATCHED THEN UPDATE SET v=s.v WHEN NOT MATCHED THEN INSERT VALUES(s.id,s.v)")
        val expected = listOf(listOf(listOf("1", "10"), listOf("2", "20")), listOf(listOf("1", "11"), listOf("2", "20")),
            listOf(listOf("1", "11")), listOf(listOf("1", "77"), listOf("3", "30")))
        for ((index, operation) in operations.withIndex()) {
            val before = c.rows("SELECT * FROM target ORDER BY id")
            val exported = c.rows("EXPLAIN (SQL) $operation", generated).single()
            assertEquals("sql", exported[0])
            assertEquals(before, c.rows("SELECT * FROM target ORDER BY id"))
            c.execute(exported[1]!!)
            assertEquals(expected[index], c.rows("SELECT * FROM target ORDER BY id"))
        }
    }

    @Test
    fun bindTimeTableFunctionArgumentsCanHaveEffectsEvenWithoutAnalyzeOrChildExecution() = modes { c, generated ->
        c.execute("CREATE SEQUENCE binds")
        c.execute("CREATE SEQUENCE scalar_only")
        c.rows("EXPLAIN SELECT * FROM range(nextval('binds'))", generated)
        assertEquals(1, c.sequence("binds"))
        c.rows("EXPLAIN (SQL) SELECT * FROM range(nextval('binds'))", generated)
        assertEquals(2, c.sequence("binds"))
        c.rows("EXPLAIN (SQL) SELECT nextval('scalar_only')", generated)
        assertEquals(0, c.sequence("scalar_only"))
        assertIs<ExplainPreparation.Refused>(prepareTextExplain("SELECT * FROM range(nextval('binds'))", "duckdb"))
    }

    @Test
    fun invalidOptionsAndUnsupportedSqlExportBodiesKeepTheirNativeFailureStages() = modes { c, generated ->
        for ((sql, reason) in listOf("EXPLAIN (SQL true) SELECT 1" to "SQL must", "EXPLAIN (SQL,SQL) SELECT 1" to "SQL must",
            "EXPLAIN (SQL,ANALYZE false) SELECT 1" to "cannot be combined", "EXPLAIN (SQL,FORMAT default) SELECT 1" to "cannot be combined",
            "EXPLAIN (FORMAT json,FORMAT yaml) SELECT 1" to "more than once", "EXPLAIN (FORMAT) SELECT 1" to "requires a single",
            "EXPLAIN (FORMAT 1+1) SELECT 1" to "requires a single", "EXPLAIN (FORMAT NULL) SELECT 1" to "Expected a string",
            "EXPLAIN (FORMAT bogus) SELECT 1" to "not a valid FORMAT", "EXPLAIN (WHATEVER) SELECT 1" to "Unimplemented explain type",
            "EXPLAIN (SQL) CREATE TABLE forbidden(id INT)" to "supports SELECT")) {
            val error = assertFailsWith<SQLException>(sql) { c.rows(sql, generated) }
            assertTrue(reason in error.message.orEmpty(), reason)
        }
        assertEquals("0", c.scalar("SELECT count(*) FROM duckdb_tables() WHERE table_name='forbidden'"))
        c.execute("CREATE SEQUENCE pivot_effect")
        assertFailsWith<SQLException> { c.rows("EXPLAIN (SQL) PIVOT (SELECT nextval('pivot_effect') AS i) ON i", generated) }
        assertEquals(0, c.sequence("pivot_effect"))
    }

    @Test
    fun copyAnalyzeWritesOnlyTheOwnedScratchFileAndStandardExplainDoesNot() = modes { c, generated ->
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-explain-")
        try {
            val file = directory.resolve("analyzed.csv")
            val copy = "COPY (SELECT 42 AS value) TO '${file.toString().replace("'", "''")}' (FORMAT CSV,HEADER)"
            c.rows("EXPLAIN $copy", generated)
            assertFalse(Files.exists(file))
            c.rows("EXPLAIN ANALYZE $copy", generated)
            assertTrue(Files.readString(file).contains("42"))
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test
    fun everyVerifierStaysParseOnlyAndSeparatesSyntaxFromOptionAndRendererValidation() = modes { c, _ ->
        c.execute("CREATE SEQUENCE untouched")
        c.execute("CREATE TABLE unchanged(id INT)")
        val prepares = AtomicInteger()
        for (sql in listOf("EXPLAIN SELECT * FROM range(nextval('untouched'))", "EXPLAIN (SQL) SELECT * FROM range(nextval('untouched'))",
            "EXPLAIN ANALYZE INSERT INTO unchanged VALUES(1)", "EXPLAIN (ANALYZE false) INSERT INTO unchanged VALUES(1)",
            "EXPLAIN (SQL,FORMAT json) SELECT 1", "EXPLAIN (FORMAT 1+1) SELECT 1", "EXPLAIN (FORMAT web) SELECT 1")) {
            Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            RelationScope.analyze(sql, "duckdb", RelationFacts())
            val helper = c.duplicate()
            val guarded = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") { prepares.incrementAndGet(); throw AssertionError("EXPLAIN validation must never prepare") }
                try { method.invoke(helper, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            } as Connection
            DuckdbVerifier({ guarded }).use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            assertIs<VerifyResult.Unverified>(ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) }).verify(sql))
            ParseOnlyDuckdbVerifier().use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
        }
        assertEquals(0, prepares.get())
        assertEquals(0, c.sequence("untouched"))
        assertEquals("0", c.scalar("SELECT count(*) FROM unchanged"))
        DuckdbVerifier().use { assertIs<VerifyResult.Rejected>(it.verify("EXPLAIN () SELECT 1")) }
        val secret = "dummy-explain-credential"
        val invalid = "EXPLAIN LOAD 'https://user:$secret@host' trailing extra"
        ParseOnlyDuckdbVerifier().use { assertFalse(secret in it.verify(invalid).toString()) }
    }
}
