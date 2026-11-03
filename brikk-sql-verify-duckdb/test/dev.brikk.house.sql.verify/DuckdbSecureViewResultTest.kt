package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.SecureProperty
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.DefinedObject
import dev.brikk.house.sql.shape.definitionQuery
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbSecureViewResultTest {
    private fun render(sql: String, generated: Boolean): String = if (generated) {
        assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb")
    } else sql
    private fun Connection.execute(sql: String, generated: Boolean = false) = createStatement().use { s ->
        s.queryTimeout = 5
        s.execute(render(sql, generated))
        Unit
    }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.scalar(sql: String): String = rows(sql).single().single()!!
    private fun modes(block: (Connection, Boolean) -> Unit) {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { block(it, generated) }
    }
    private fun scratch(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-secure-view-")
        try { block(directory) } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun literal(path: Path): String = "'${path.toString().replace("'", "''")}'"
    private fun Connection.stored(name: String): String {
        val reader = assertNotNull(definitionQuery("duckdb", DefinedObject.VIEW, listOf(scalar("SELECT current_database()"), "main", name)))
        return createStatement().use { statement -> statement.executeQuery(reader.sql).use { rows ->
            val values = buildList<Map<String, Any?>> { while (rows.next()) add(mapOf("sql" to rows.getString(1))) }
            assertNotNull(reader.definition(values))
        } }
    }
    private fun assertSecureDefinition(sql: String) {
        val tree = assertIs<Create>(Dialects.DUCKDB.parseOne(sql))
        assertEquals(1, tree.findAll<SecureProperty>().count(), sql)
        assertTrue("SECURE" in assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb"))
    }

    @Test
    fun resultsModifiersAliasesAndRecursiveViewsSurviveGeneratedDefinitions() = modes { c, generated ->
        c.execute("CREATE TABLE private_users(id INT,access_level VARCHAR)")
        c.execute("INSERT INTO private_users VALUES(1,'standard'),(2,'admin'),(3,'standard')")
        c.execute("CREATE SECURE VIEW public_users(user_id) AS SELECT id FROM private_users WHERE access_level='standard'", generated)
        c.execute("CREATE SECURE VIEW IF NOT EXISTS public_users AS SELECT 999", generated)
        assertEquals(listOf(listOf("1"), listOf("3")), c.rows("SELECT * FROM public_users ORDER BY user_id"))
        c.execute("CREATE VIEW wrapper AS SELECT * FROM public_users")
        c.execute("CREATE SECURE VIEW nested AS SELECT * FROM wrapper WHERE user_id>1", generated)
        assertEquals(listOf(listOf("3")), c.rows("SELECT * FROM nested"))
        c.execute("CREATE OR REPLACE SECURE VIEW public_users(user_id) AS SELECT id FROM private_users WHERE id=1", generated)
        assertEquals(listOf(listOf("1")), c.rows("SELECT * FROM public_users"))
        c.execute("CREATE TEMP SECURE VIEW temporary_users AS SELECT * FROM private_users", generated)
        assertEquals("3", c.scalar("SELECT count(*) FROM temporary_users"))
        c.execute("CREATE SECURE RECURSIVE VIEW rv(n) AS SELECT 1 UNION ALL SELECT n+1 FROM rv WHERE n<3", generated)
        assertEquals(listOf(listOf("1"), listOf("2"), listOf("3")), c.rows("SELECT * FROM rv ORDER BY n"))
        c.execute("CREATE TABLE secure(secure INT)", generated)
        c.execute("INSERT INTO secure VALUES(7)")
        assertEquals("7", c.scalar("SELECT secure FROM secure"))
    }

    @Test
    fun statisticsHideFilteredRowsButMayExposeValuesAvailableThroughAnUnfilteredView() = modes { c, generated ->
        c.execute("CREATE TABLE hidden_values AS SELECT i,('secret' || i) AS s FROM range(1000) t(i)")
        c.execute("CREATE VIEW regular_values AS SELECT * FROM hidden_values")
        for ((name, body) in listOf("filtered" to "SELECT * FROM hidden_values WHERE i<10", "limited" to "SELECT * FROM hidden_values LIMIT 10",
            "distinct_values" to "SELECT DISTINCT i FROM hidden_values", "grouped" to "SELECT i,count(*) AS c FROM hidden_values GROUP BY i",
            "joined" to "SELECT a.i FROM hidden_values a JOIN hidden_values b USING(i)")) {
            c.execute("CREATE SECURE VIEW $name AS $body", generated)
            assertEquals("{'has_no_null': true, 'has_null': true}", c.scalar("SELECT stats(i)::VARCHAR FROM $name LIMIT 1"), name)
        }
        assertTrue("'max': 999" in c.scalar("SELECT stats(i)::VARCHAR FROM regular_values LIMIT 1"))
        val textStats = c.scalar("SELECT stats(s)::VARCHAR FROM filtered LIMIT 1")
        assertFalse("secret" in textStats)
        assertFalse("min" in textStats || "max" in textStats || "approx_unique" in textStats)
        c.execute("CREATE VIEW wrapper AS SELECT * FROM filtered")
        assertEquals("{'has_no_null': true, 'has_null': true}", c.scalar("SELECT stats(i)::VARCHAR FROM wrapper LIMIT 1"))
        c.execute("CREATE SECURE VIEW all_values AS SELECT * FROM hidden_values", generated)
        assertTrue("'max': 999" in c.scalar("SELECT stats(i)::VARCHAR FROM all_values LIMIT 1"))
        assertEquals("10", c.scalar("SELECT count(*) FROM filtered"))
        for (value in listOf(100000, 1000000000)) {
            val plan = c.rows("EXPLAIN SELECT * FROM filtered WHERE i>$value").joinToString().uppercase()
            assertTrue("SECURE_VIEW" in plan || "SECURE VIEW" in plan)
            assertFalse("EMPTY_RESULT" in plan, "An impossible predicate must not remove the secure boundary using hidden statistics")
            assertEquals("0", c.scalar("SELECT count(*) FROM filtered WHERE i>$value"))
        }
    }

    @Test
    fun logicalPhysicalAnalyzeAndJsonPlansDoNotExposeTheSecureBodyOrScanCounters() = modes { c, generated ->
        c.execute("SET profiling_renderer_settings=MAP {'operator_casing':'upper'}")
        c.execute("CREATE TABLE confidential_source AS SELECT i,i%10 AS j FROM range(1000)t(i)")
        c.execute("CREATE TABLE confidential_lookup AS SELECT i,i*2 AS k FROM range(100)t(i)")
        val body = "SELECT a.j,count(*) AS c,max(b.k) AS mk FROM confidential_source a JOIN confidential_lookup b USING(i) WHERE a.j<>7 GROUP BY a.j"
        c.execute("CREATE SECURE VIEW protected AS $body", generated)
        c.execute("CREATE VIEW exposed AS $body")
        assertTrue("confidential_source" in c.rows("EXPLAIN SELECT * FROM exposed").joinToString())
        c.execute("SET explain_output='all'")
        val plans = c.rows("EXPLAIN SELECT * FROM protected WHERE c>0")
        assertEquals(setOf("logical_plan", "logical_opt", "physical_plan"), plans.map { it[0] }.toSet())
        c.execute("SET explain_output='physical_only'")
        for (plan in plans.map { it[1]!! } + listOf(
            c.rows("EXPLAIN ANALYZE SELECT * FROM protected WHERE c>0").single()[1]!!,
            c.rows("EXPLAIN ANALYZE (FORMAT json) SELECT * FROM protected WHERE c>0").single()[1]!!,
        )) {
            assertTrue("SECURE_VIEW" in plan)
            for (hidden in listOf("confidential_source", "confidential_lookup", "HASH_JOIN", "HASH_GROUP_BY", "SEQ_SCAN", "TABLE_SCAN")) assertFalse(hidden in plan, hidden)
        }
        c.execute("CREATE SECURE VIEW counted AS SELECT * FROM confidential_source WHERE j=3", generated)
        val json = c.rows("EXPLAIN ANALYZE (FORMAT json) SELECT * FROM counted").single()[1]!!
        assertTrue(Regex("\"total_rows_scanned\":\\s*0").containsMatchIn(json))
        assertTrue(Regex("\"total_row_groups_scanned\":\\s*0").containsMatchIn(json))
        assertTrue("\"timing\"" in json)
        assertEquals("100", c.scalar("SELECT count(*) FROM counted"))
    }

    @Test
    fun definitionReadersExposeTheAuthoredSqlAndRegenerationRetainsSecurity() = modes { c, generated ->
        c.execute("CREATE TABLE hidden_definition(id INT)")
        c.execute("INSERT INTO hidden_definition VALUES(1),(2)")
        c.execute("CREATE SECURE VIEW visible_definition(x) AS SELECT id FROM hidden_definition WHERE id=1", generated)
        val definition = c.stored("visible_definition")
        assertSecureDefinition(definition)
        assertTrue("hidden_definition" in definition, "SECURE is not definition encryption or catalog authorization")
        val informationSchema = c.scalar("SELECT view_definition FROM information_schema.views WHERE table_name='visible_definition'")
        assertEquals(definition, informationSchema)
        c.execute("DROP VIEW visible_definition")
        c.execute(definition, generated = true)
        assertEquals("1", c.scalar("SELECT * FROM visible_definition"))
        assertSecureDefinition(c.stored("visible_definition"))
        val tree = qualify(Dialects.DUCKDB.parseOne(definition), dialect = Dialects.DUCKDB, schema = mapOf("hidden_definition" to mapOf("id" to "INT")))
        c.execute("DROP VIEW visible_definition")
        c.execute(tree.sql("duckdb"))
        assertSecureDefinition(c.stored("visible_definition"))
    }

    @Test
    fun sqlPlanExportKeepsTheViewReferenceRatherThanInliningItsPrivateBody() = modes { c, generated ->
        c.execute("CREATE TABLE confidential_rows(i INT,s VARCHAR)")
        c.execute("INSERT INTO confidential_rows VALUES(1,'7'),(2,'bad'),(1,'7'),(1,NULL)")
        c.execute("CREATE SECURE VIEW public_text AS SELECT s FROM confidential_rows WHERE i=1", generated)
        val query = "SELECT s::INT AS n FROM public_text WHERE s::INT=7 ORDER BY n"
        val expected = listOf(listOf("7"), listOf("7"))
        assertEquals(expected, c.rows(query))
        val exported = c.rows("EXPLAIN (SQL) $query").single()[1]!!
        assertTrue("public_text" in exported)
        assertFalse("confidential_rows" in exported)
        assertEquals(expected, c.rows(render(exported, true)))
        // Authored outer predicates cannot be evaluated against rows hidden by the view.
        c.execute("CREATE SEQUENCE secure_probe")
        assertEquals("3", c.scalar("SELECT count(*) FROM public_text WHERE nextval('secure_probe')>0"))
        assertEquals("3", c.scalar("SELECT currval('secure_probe')"))
        c.execute("CREATE TABLE visible_keys AS SELECT 1 AS i")
        c.execute("CREATE SECURE VIEW joined_text AS SELECT t.s FROM confidential_rows t JOIN visible_keys USING(i)", generated)
        c.execute("SET disabled_optimizers='join_filter_pushdown'")
        try {
            val exposed = assertFailsWith<SQLException> {
                c.rows("SELECT t.s FROM confidential_rows t JOIN visible_keys USING(i) WHERE t.s::INT=7")
            }
            assertTrue("bad" in exposed.message.orEmpty(), "The ordinary query exposes the hidden bad value through its conversion error")
            assertEquals(expected, c.rows("SELECT s::INT FROM joined_text WHERE s::INT=7 ORDER BY 1"))
        } finally { c.execute("RESET disabled_optimizers") }
    }

    @Test
    fun checkpointReopenAndDatabaseExportImportRetainTheSecureBoundary() = scratch { directory ->
        val database = directory.resolve("secure.duckdb")
        val export = directory.resolve("exported")
        DriverManager.getConnection("jdbc:duckdb:$database").use { c ->
            c.execute("CREATE TABLE persisted_private AS SELECT i FROM range(100)t(i)")
            c.execute("CREATE SECURE VIEW persisted_secure AS SELECT * FROM persisted_private WHERE i<3", true)
            c.execute("CREATE SECURE RECURSIVE VIEW persisted_recursive(n) AS SELECT 1 UNION ALL SELECT n+1 FROM persisted_recursive WHERE n<3", true)
            c.execute("CHECKPOINT")
        }
        DriverManager.getConnection("jdbc:duckdb:$database").use { c ->
            assertSecureDefinition(c.stored("persisted_secure"))
            assertSecureDefinition(c.stored("persisted_recursive"))
            assertEquals(listOf(listOf("0"), listOf("1"), listOf("2")), c.rows("SELECT * FROM persisted_secure ORDER BY i"))
            assertEquals("{'has_no_null': true, 'has_null': true}", c.scalar("SELECT stats(i)::VARCHAR FROM persisted_secure LIMIT 1"))
            c.execute("EXPORT DATABASE ${literal(export)}")
        }
        val schema = Files.readString(export.resolve("schema.sql"))
        assertTrue("SECURE VIEW" in schema)
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("IMPORT DATABASE ${literal(export)}")
            assertSecureDefinition(c.stored("persisted_secure"))
            assertSecureDefinition(c.stored("persisted_recursive"))
            assertEquals("3", c.scalar("SELECT count(*) FROM persisted_recursive"))
            assertTrue(c.rows("EXPLAIN SELECT * FROM persisted_secure").joinToString().contains("Secure View", ignoreCase = true))
        }
    }

    @Test
    fun invalidObjectsAndOldStorageAreNativeErrorsNotAutomaticSecurityDowngrades() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (sql in listOf("CREATE SECURE TABLE t(i INT)", "CREATE SECURE SCHEMA ns", "CREATE SECURE TEMP VIEW v AS SELECT 1",
                "CREATE PERSISTENT SECURE VIEW v AS SELECT 1", "CREATE OR REPLACE PERSISTENT SECURE VIEW v AS SELECT 1",
                "CREATE SECURE RECURSIVE VIEW v AS SELECT 1", "CREATE RECURSIVE SECURE VIEW v(n) AS SELECT 1")) {
                val error = assertFailsWith<SQLException> { c.execute(sql) }
                assertTrue("Parser Error" in error.message.orEmpty())
            }
            c.execute("ATTACH ${literal(directory.resolve("old.duckdb"))} AS old (STORAGE_VERSION 'v1.4.4')")
            c.execute("CREATE TABLE old.private_rows(i INT)")
            val error = assertFailsWith<SQLException> { c.execute("CREATE SECURE VIEW old.v AS SELECT * FROM old.private_rows", true) }
            assertTrue("storage versions v2.0.0 and higher" in error.message.orEmpty())
            c.execute("CREATE VIEW old.regular AS SELECT * FROM old.private_rows")
            c.execute("CREATE TEMP SECURE VIEW temporary_safe AS SELECT * FROM old.private_rows", true)
            assertEquals("0", c.scalar("SELECT count(*) FROM temporary_safe"))
        }
    }

    @Test
    fun parseOnlyCheckingNeverCreatesAViewOrEvaluatesItsDefinition() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            user.execute("CREATE SEQUENCE secure_check_probe START 1")
            val sql = "CREATE SECURE VIEW checked_only AS SELECT nextval('secure_check_probe') AS n"
            Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            assertIs<VerifyResult.Unverified>(ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(user.duplicate()) }).verify(sql))
            ParseOnlyDuckdbVerifier().use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            assertEquals("0", user.scalar("SELECT count(*) FROM duckdb_views() WHERE view_name='checked_only'"))
            assertEquals("1", user.scalar("SELECT nextval('secure_check_probe')"))
        }
    }
}
