package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import kotlin.test.*

/** Real PostgreSQL 18.4, in a test-owned embedded instance, not a user's connection. */
class PrecedencePostgresResultTest {
    private companion object {
        val pg by lazy {
            EmbeddedPostgres.builder().start().also { instance ->
                Runtime.getRuntime().addShutdownHook(Thread { instance.close() })
            }
        }
    }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map(r::getString)) } }
    }
    private fun native(block: (Connection) -> Unit) = pg.postgresDatabase.connection.use { c ->
        assertTrue(c.rows("SHOW server_version").single().single()!!.startsWith("18.4"))
        block(c)
    }

    @Test
    fun isAndGeneratedColumnsRetainNativeBooleanResults() = native { c ->
        for (source in listOf("SELECT 1 = NULL IS NULL AS v", "SELECT NULL = 1 IS NULL AS v",
            "SELECT FALSE IS DISTINCT FROM NULL = NULL AS v", "SELECT NULL IS NOT NULL IS FALSE AS v")) {
            assertEquals(listOf(listOf("t")), c.rows(source))
            val rendered = Dialects.POSTGRES.parseOne(source).sql("postgres")
            assertEquals(c.rows(source), c.rows(rendered))
            c.createStatement().use { s -> s.executeQuery(rendered).use { r -> assertEquals("bool", r.metaData.getColumnTypeName(1)) } }
        }
        val ddl = "CREATE TEMPORARY TABLE sg29_generated (a INT, b BOOLEAN GENERATED ALWAYS AS (a = 1 IS NULL) STORED)"
        c.createStatement().use { s ->
            s.execute(Dialects.POSTGRES.parseOne(ddl).sql("postgres"))
            s.execute("INSERT INTO sg29_generated(a) VALUES(NULL),(1)")
        }
        assertEquals(listOf(listOf("t"), listOf("f")), c.rows("SELECT b FROM sg29_generated ORDER BY a NULLS FIRST"))
    }

    @Test
    fun nativeSetRowsKeepOuterVersusArmModifiersAndTypedValues() = native { c ->
        for ((source, expected) in listOf(
            "SELECT 1 AS v UNION SELECT 2 INTERSECT SELECT 2 ORDER BY v" to listOf(listOf("1"), listOf("2")),
            "SELECT 3 AS v UNION ALL (SELECT 1 INTERSECT SELECT 1) ORDER BY v LIMIT 1 OFFSET 1" to listOf(listOf("3")),
            "SELECT 1 AS v UNION ALL (VALUES (2), (3) ORDER BY 1 DESC LIMIT 1) ORDER BY v" to listOf(listOf("1"), listOf("3")),
        )) {
            assertEquals(expected, c.rows(source))
            assertEquals(expected, c.rows(Dialects.POSTGRES.parseOne(source).sql("postgres")))
        }
        val hive = "SELECT 1 AS v UNION SELECT 2 INTERSECT SELECT 2"
        assertEquals(listOf(listOf("2")), c.rows(transpile(hive, "hive", "postgres")))
        val typed = transpile("SELECT CAST(1 AS BIGINT) AS v UNION ALL (VALUES (CAST(2 AS DOUBLE)))", "duckdb", "postgres")
        c.createStatement().use { s -> s.executeQuery(typed).use { r -> assertEquals("float8", r.metaData.getColumnTypeName(1)) } }
    }

    @Test
    fun nullAwareXorAndBitwiseGroupingAreActualExecutablePostgresSql() = native { c ->
        for (a in listOf("TRUE", "FALSE", "NULL")) for (b in listOf("TRUE", "FALSE", "NULL")) for (d in listOf("TRUE", "FALSE", "NULL")) {
            val expected = c.rows("SELECT ($a)::BOOLEAN <> (($b)::BOOLEAN AND ($d)::BOOLEAN)")
            val sql = transpile("SELECT $a XOR $b AND $d AS v", "mysql", "postgres")
            assertEquals(expected, c.rows(sql), sql)
            val nested = transpile("SELECT $a AND ($b XOR $d) AS v", "mysql", "postgres")
            assertEquals(c.rows("SELECT ($a)::BOOLEAN AND (($b)::BOOLEAN <> ($d)::BOOLEAN)"), c.rows(nested), nested)
            val underOr = transpile("SELECT $a OR $b XOR $d AS v", "mysql", "postgres")
            assertEquals(c.rows("SELECT ($a)::BOOLEAN OR (($b)::BOOLEAN <> ($d)::BOOLEAN)"), c.rows(underOr), underOr)
        }
        for ((source, expected) in listOf("SELECT 1 | 2 & 0 AS v" to "1", "SELECT 1 ^ 2 * 3 AS v" to "9",
            "SELECT 2 + 1 ^ 3 AS v" to "4", "SELECT 8 & 3 + 1 AS v" to "0", "SELECT 1 | 2 << 1 & 2 AS v" to "1")) {
            assertEquals(listOf(listOf(expected)), c.rows(transpile(source, "mysql", "postgres")), source)
        }
    }
}
