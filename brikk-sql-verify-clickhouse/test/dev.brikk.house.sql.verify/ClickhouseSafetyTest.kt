package dev.brikk.house.sql.verify

import dev.brikk.house.sql.verify.ClickhouseServer.run
import dev.brikk.house.sql.verify.ClickhouseServer.scalar
import java.sql.Connection
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The editor verifier must never execute, bind or analyze user SQL on the user's server. Each probe
 * below reaches outside the parser when the server analyzes it (the control proves it for `EXPLAIN
 * AST optimize = 1`): a `url()` or `s3()` request back to this server, a `remote()` query, a
 * dictionary load, a scalar subquery, a write, a file, the statement's own `SETTINGS`. Verifying them
 * must leave no trace: no marker query in `system.query_log`, no rows, no table, no file, the
 * dictionary still unloaded.
 */
internal class ClickhouseSafetyTest {
    private val marker = "brikk_" + UUID.randomUUID().toString().replace("-", "")
    private val database = "safety_$marker"
    private lateinit var admin: Connection

    /** A query this server runs when [label]'s probe reaches it over HTTP. */
    private fun selfQuery(label: String) = "http://localhost:8123/?query=SELECT+%27${marker}_$label%27"

    private val probes by lazy {
        mapOf(
            "url" to "SELECT * FROM url('${selfQuery("url")}', 'TSV', 'x String')",
            "url_inferred" to "SELECT * FROM url('${selfQuery("infer")}+FORMAT+TSVWithNames', 'TSVWithNames')",
            "s3" to "SELECT * FROM s3('${selfQuery("s3")}', 'TSV', 'x String')",
            "remote" to "SELECT '${marker}_remote' FROM remote('127.0.0.1', system.one)",
            "dictionary" to "SELECT dictGet('$database.d', 'v', toUInt64(1))",
            "scalar" to "SELECT (SELECT count() FROM url('${selfQuery("scalar")}', 'TSV', 'x String'))",
            "settings" to "SELECT 1 SETTINGS log_comment = '${marker}_settings'",
            "insert" to "INSERT INTO $database.t SELECT 42",
            "insert_file" to "INSERT INTO FUNCTION file('${marker}_file.tsv', 'TSV', 'x String') SELECT 'x'",
            "inline_data" to "INSERT INTO $database.t FORMAT TSV\n77\n",
            "values" to "INSERT INTO $database.t VALUES (78)",
            "input" to "INSERT INTO $database.t SELECT a FROM input('a UInt64') FORMAT TSV\n88\n",
            "create" to "CREATE TABLE $database.created (a UInt8) ENGINE = Memory",
            "drop" to "DROP TABLE $database.t",
            "system" to "SYSTEM RELOAD DICTIONARY $database.d",
            "explain" to "EXPLAIN PIPELINE SELECT * FROM url('${selfQuery("explain")}', 'TSV', 'x String')",
            "settings_prefix" to "optimize = 1 SELECT * FROM url('${selfQuery("prefix")}+FORMAT+TSVWithNames', 'TSVWithNames')",
        )
    }

    @BeforeTest
    fun createFixtures() {
        admin = ClickhouseServer.open()
        admin.run("CREATE DATABASE $database")
        admin.run("CREATE TABLE $database.t (a UInt64) ENGINE = MergeTree ORDER BY a")
        admin.run(
            "CREATE DICTIONARY $database.d (k UInt64, v String) PRIMARY KEY k " +
                "SOURCE(CLICKHOUSE(QUERY 'SELECT 1 AS k, ''${marker}_dictionary'' AS v')) LAYOUT(FLAT()) LIFETIME(0)",
        )
    }

    @AfterTest
    fun dropFixtures() {
        if (!::admin.isInitialized) return
        admin.run("DROP DATABASE IF EXISTS $database SYNC")
        admin.close()
    }

    @Test
    fun verifyingTouchesNothing() {
        val results = probes.mapValues { (_, sql) -> ClickhouseServer.verifier().verify(sql) }
        assertEquals(emptyList(), traces(), "verification reached outside the parser")
        assertUntouched()

        for (name in listOf("url", "url_inferred", "s3", "remote", "dictionary", "scalar", "settings", "insert", "insert_file", "create", "drop", "system", "explain")) {
            assertEquals(VerifyResult.Accepted, results[name], name)
        }
        for (name in listOf("inline_data", "values", "input")) assertIs<VerifyResult.Unverified>(results[name], name)
        assertIs<VerifyResult.Rejected>(results["settings_prefix"])
    }

    @Test
    fun explainAstWithTheAnalyzerDoesTouchThem() {
        for (name in listOf("url_inferred", "dictionary")) admin.scalar("EXPLAIN AST optimize = 1 ${probes.getValue(name)}")
        assertEquals("LOADED", admin.scalar("SELECT status FROM system.dictionaries WHERE database = '$database' AND name = 'd'"))
        val reached = traces().joinToString("\n")
        assertTrue("${marker}_infer" in reached, reached)
    }

    /** Queries other than the test's own that carry the marker, as `system.query_log` recorded them. */
    private fun traces(): List<String> {
        admin.run("SYSTEM FLUSH LOGS")
        return admin.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT query FROM system.query_log WHERE type = 'QueryStart' AND event_date >= yesterday() " +
                    "AND (query LIKE '%${marker}%' OR log_comment LIKE '%${marker}%') " +
                    "AND query NOT LIKE '%formatQuery(unhex(%' AND query NOT LIKE 'EXPLAIN AST%' " +
                    "AND query NOT LIKE 'CREATE %' AND query NOT LIKE '%system.query_log%' AND query NOT LIKE 'SYSTEM FLUSH%'",
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun assertUntouched() {
        assertEquals("0", admin.scalar("SELECT count() FROM $database.t"))
        assertEquals("NOT_LOADED", admin.scalar("SELECT status FROM system.dictionaries WHERE database = '$database' AND name = 'd'"))
        assertEquals("0", admin.scalar("EXISTS TABLE $database.created"))
        assertEquals("0", admin.scalar("SELECT count() FROM file('${marker}_file*.tsv', 'TSV', 'x String')"))
    }
}
