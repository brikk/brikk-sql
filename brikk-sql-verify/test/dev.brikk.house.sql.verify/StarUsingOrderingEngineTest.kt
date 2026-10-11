package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares both header order and rows in the source engine, not a target-parser approximation. */
class StarUsingOrderingEngineTest {
    private val ctes = "WITH a AS (SELECT 10 AS a_id, 1 AS k1, 2 AS k2, 'a' AS av), " +
        "b AS (SELECT 2 AS k2, 20 AS b_id, 1 AS k1, 'b' AS bv), c AS (SELECT 30 AS c_id, 1 AS k1) "

    private fun command(name: String, client: List<String>, sql: String): List<String> {
        val temp = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val output = File.createTempFile("star-using-", ".log", temp)
        try {
            val process = ProcessBuilder(listOf("docker", "exec", name) + client + sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Star/USING engine probe timed out")
            }
            val text = output.readText().trimEnd('\n', '\r')
            assertEquals(0, process.exitValue(), "$sql\n$text")
            return text.lines()
        } finally { output.delete() }
    }

    private fun run(variable: String, dialect: String, client: List<String>) {
        val container = System.getenv(variable)
        assumeTrue(!container.isNullOrBlank(), "$variable is not configured")
        val name = container!!
        val queries = mutableListOf(
            "SELECT * FROM a JOIN b USING (k2, k1)", "SELECT * FROM a LEFT JOIN b USING (k2, k1)",
            "SELECT * FROM a RIGHT JOIN b USING (k2, k1)", "SELECT * FROM a JOIN b USING (k2, k1) JOIN c USING (k1)",
            "SELECT a.*, b.* FROM a LEFT JOIN b USING (k1)",
            "SELECT * FROM a NATURAL JOIN b", "SELECT * FROM (SELECT a_id FROM a) AS s CROSS JOIN b",
            "SELECT 0 AS before_value, *, 1 AS after_value FROM a JOIN b USING (k1)",
        )
        if (dialect != "mysql") queries += "SELECT *, * FROM a JOIN b USING (k1)"
        if (dialect == "starrocks") queries += listOf("SELECT a.*, b.*, a.* FROM a LEFT JOIN b USING (k1)",
            "SELECT *, a.* FROM a LEFT JOIN b USING (k1)", "SELECT a.*, * FROM a LEFT JOIN b USING (k1)")
        if (dialect == "mysql") queries.removeAll { it.startsWith("SELECT 0 AS before_value") }
        // These engines do not implement native NATURAL joins (StarRocks parses
        // NATURAL here as an implicit table alias). Do not claim native equivalence.
        if (dialect in setOf("trino", "starrocks")) queries.removeAll { "NATURAL JOIN" in it }
        if (dialect != "mysql") queries += "SELECT * FROM a FULL JOIN b USING (k1)"
        for (query in queries) {
            val source = ctes + query
            val d = Dialects.forName(dialect)
            val qualified = qualify(d.parseOne(source), dialect = d, inferSchema = false).sql(dialect)
            val expected = command(name, client, source)
            val actual = command(name, client, qualified)
            assertEquals(expected.first(), actual.first(), "Header order:\n$source\n$qualified")
            assertEquals(expected.drop(1).sorted(), actual.drop(1).sorted(), "Rows:\n$source\n$qualified")
        }
        if (dialect == "mysql") {
            val source = "WITH a AS (SELECT 10 AS a_id, 'a' COLLATE utf8mb4_0900_ai_ci AS k), " +
                "b AS (SELECT 'A' COLLATE utf8mb4_0900_ai_ci AS k, 20 AS b_id) SELECT * FROM a RIGHT JOIN b USING (k)"
            val qualified = qualify(Dialects.MYSQL.parseOne(source), dialect = Dialects.MYSQL, inferSchema = false).sql("mysql")
            assertEquals(command(name, client, source), command(name, client, qualified), qualified)
        }
    }

    @Test
    fun livePostgresUsingListAndSourceOrderMatchOriginalHeadersAndRows() = run("BRIKK_POSTGRES_TEST_CONTAINER", "postgres",
        listOf("psql", "-U", "postgres", "-A", "-P", "footer=off", "-v", "ON_ERROR_STOP=1", "-c"))
    @Test
    fun liveMysqlLeftTableAndRightJoinOrderingMatchOriginalHeadersAndRows() = run("BRIKK_MYSQL_TEST_CONTAINER", "mysql",
        listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-B", "-e"))
    @Test
    fun liveTrinoUsingListAndSourceOrderMatchOriginalHeadersAndRows() = run("BRIKK_TRINO_TEST_CONTAINER", "trino",
        listOf("trino", "--output-format=TSV_HEADER", "--execute"))
    @Test
    fun liveStarrocksUsingListAndSourceOrderMatchOriginalHeadersAndRows() = run("BRIKK_STARROCKS_TEST_CONTAINER", "starrocks",
        listOf("mysql", "-uroot", "-h127.0.0.1", "-P9030", "-B", "-e"))
}
