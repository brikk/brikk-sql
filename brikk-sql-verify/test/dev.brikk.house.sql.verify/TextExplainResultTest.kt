package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.DefinedObject
import dev.brikk.house.sql.shape.ExplainPreparation
import dev.brikk.house.sql.shape.definitionQuery
import dev.brikk.house.sql.shape.prepareTextExplain
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Text explain and definitions against a live Trino 483 (its default memory catalog). */
class TextExplainResultTest {
    private val client = listOf("trino", "--output-format=TSV_HEADER", "--execute")

    private fun container(): String {
        val value = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!value.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        return checkNotNull(value)
    }

    private fun schema(container: String): String =
        "explain_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}".also { runCatchupEngineSql(container, client, "CREATE SCHEMA memory.$it") }

    /** The rows of a TSV_HEADER result, by lower-case column label. */
    private fun rows(output: String): List<Map<String, String?>> {
        val lines = output.lines().filter { it.isNotEmpty() }
        val labels = lines.firstOrNull()?.split('\t')?.map(String::lowercase) ?: return emptyList()
        return lines.drop(1).map { line -> labels.zip(line.split('\t').map { it.replace("\\n", "\n") }).toMap() }
    }

    private fun tables(container: String, schema: String) = rows(runCatchupEngineSql(container, client, "SHOW TABLES FROM memory.$schema")).mapNotNull { it["table"] }

    /** Why only queries are explained: Trino 483 creates the table of an EXPLAIN CREATE TABLE AS. */
    @Test
    fun liveTrinoExplainOfCreateTableAsCreatesTheTableSoOnlyQueriesAreExplained() {
        val container = container()
        val schema = schema(container)
        val ctas = "CREATE TABLE memory.$schema.created AS SELECT 1 AS x"
        assertIs<ExplainPreparation.Refused>(prepareTextExplain(ctas, "trino") { true })
        runCatchupEngineSql(container, client, "EXPLAIN $ctas")
        assertEquals(listOf("created"), tables(container, schema))
    }

    @Test
    fun liveTrinoExplainsAQueryWithoutChangingData() {
        val container = container()
        val schema = schema(container)
        runCatchupEngineSql(container, client, "CREATE TABLE memory.$schema.t (x bigint)")
        val query = "SELECT count(*) FROM memory.$schema.t WHERE x > 1"
        val ready = assertIs<ExplainPreparation.Ready>(prepareTextExplain(query, "trino") { true })
        val plan = rows(runCatchupEngineSql(container, client, ready.context.joinToString("; ", postfix = "; ") + ready.sql)).last().values.single().orEmpty()
        assertTrue("Fragment" in plan && "Trino version" in plan, plan)
        assertEquals(listOf("t"), tables(container, schema))
        assertEquals("0", rows(runCatchupEngineSql(container, client, "SELECT count(*) AS n FROM memory.$schema.t")).single()["n"])
    }

    /** Why routine declarations are refused: an inline function takes a built-in's name. */
    @Test
    fun liveTrinoInlineFunctionsOverrideBuiltins() {
        val container = container()
        val sql = "WITH FUNCTION abs(x bigint) RETURNS bigint RETURN x + 100 SELECT abs(1) AS v"
        assertEquals("101", rows(runCatchupEngineSql(container, client, sql)).single()["v"])
        assertIs<ExplainPreparation.Refused>(prepareTextExplain(sql, "trino") { true })
    }

    /**
     * A built-in's name does not prove the built-in binds: with a routine's schema on the session path,
     * Trino falls through to it for an unmatched overload and evaluates it while planning EXPLAIN. The
     * prepared context (`SET PATH system.builtin`) leaves only built-ins, so nothing user-defined runs.
     */
    @Test
    fun liveTrinoBuiltinOnlyContextKeepsRoutinesFromBinding() {
        val container = container()
        val schema = schema(container)
        val pathClient = listOf("trino", "--catalog", "memory", "--schema", schema, "--output-format=TSV_HEADER", "--execute")
        runCatchupEngineSql(container, client, "CREATE FUNCTION memory.$schema.abs(x varchar) RETURNS varchar RETURN 'routine ran'")
        val ready = assertIs<ExplainPreparation.Ready>(prepareTextExplain("SELECT abs('text')", "trino") { true })
        val userPath = "SET PATH memory.$schema; "
        assertTrue("routine ran" in runCatchupEngineSql(container, pathClient, userPath + ready.sql), "without the context the routine is planned")
        val guarded = runCatching { runCatchupEngineSql(container, pathClient, userPath + ready.context.joinToString("; ", postfix = "; ") + ready.sql) }
        val output = guarded.exceptionOrNull()?.message ?: guarded.getOrThrow()
        assertTrue("Unexpected parameters" in output && "routine ran" !in output, output)
    }

    /** `EXPLAIN (` opens Trino's options list; a parenthesized query is explained as a derived table. */
    @Test
    fun liveTrinoExplainsAParenthesizedQuery() {
        val container = container()
        val ready = assertIs<ExplainPreparation.Ready>(prepareTextExplain("(SELECT 1) UNION (SELECT 2)", "trino") { true })
        assertTrue("Fragment" in runCatchupEngineSql(container, client, ready.sql))
    }

    @Test
    fun liveTrinoShowsDefinitions() {
        val container = container()
        val schema = schema(container)
        runCatchupEngineSql(container, client, "CREATE TABLE memory.$schema.t (x bigint)")
        runCatchupEngineSql(container, client, "CREATE VIEW memory.$schema.v AS SELECT x FROM memory.$schema.t")
        for ((kind, path, expected) in listOf(
            Triple(DefinedObject.TABLE, listOf("memory", schema, "t"), "CREATE TABLE memory.$schema.t (\n   x bigint\n)"),
            Triple(DefinedObject.VIEW, listOf("memory", schema, "v"), "CREATE VIEW \"memory\".\"$schema\".\"v\" SECURITY DEFINER AS\nSELECT x\nFROM\n  memory.$schema.t"),
            Triple(DefinedObject.SCHEMA, listOf("memory", schema), "CREATE SCHEMA memory.$schema"),
        )) {
            val query = checkNotNull(definitionQuery("trino", kind, path))
            assertEquals(expected, query.definition(rows(runCatchupEngineSql(container, client, query.sql))), query.sql)
        }
    }
}
