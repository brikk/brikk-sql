package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.parseParameterOccurrences
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.*
import kotlin.test.*

/** SG29-005..007, real private DuckDB 2.0.0/d54cbbe3df, no extensions or user data. */
internal class SqlglotPrecedenceResultTest {
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map(r::getString)) } }
    }
    private fun native(block: (Connection) -> Unit) = DriverManager.getConnection("jdbc:duckdb:").use { c ->
        assertEquals(listOf(listOf("v2.0.0")), c.rows("SELECT version()"))
        assertTrue(c.rows("SELECT source_id FROM pragma_version()").single().single()!!.startsWith("d54cbbe3df"))
        block(c)
    }

    @Test
    fun isGroupingAndGeneratedBooleanMetadataAgreeWithNativeResults() = native { c ->
        for (source in listOf("SELECT 1 = NULL IS NULL AS v", "SELECT NULL = 1 IS NULL AS v",
            "SELECT FALSE IS DISTINCT FROM NULL = NULL AS v", "SELECT NULL IS NOT NULL IS FALSE AS v")) {
            assertEquals(listOf(listOf("true")), c.rows(source))
            val ast = Dialects.DUCKDB.parseOne(source)
            val before = Serde.dump(ast)
            val output = ast.sql("duckdb")
            assertEquals(c.rows(source), c.rows(output), output)
            c.createStatement().use { s -> s.executeQuery(output).use { r ->
                assertEquals("BOOLEAN", r.metaData.getColumnTypeName(1))
            } }
            assertEquals(before, Serde.dump(ast))
        }
    }

    @Test
    fun setPrecedenceArmPaginationAndValuesWrappersHaveMeasuredRows() = native { c ->
        for ((source, expected) in listOf(
            "SELECT 1 AS v UNION SELECT 2 INTERSECT SELECT 2 ORDER BY v" to listOf(listOf("1"), listOf("2")),
            "SELECT 3 AS v UNION ALL SELECT 1 INTERSECT SELECT 1 ORDER BY v LIMIT 1" to listOf(listOf("1")),
            "SELECT 3 AS v UNION ALL (SELECT 1 INTERSECT SELECT 1) ORDER BY v LIMIT 1 OFFSET 1" to listOf(listOf("3")),
            "SELECT 1 AS v UNION ALL (VALUES (2), (3) ORDER BY 1 DESC LIMIT 1) ORDER BY v" to listOf(listOf("1"), listOf("3")),
            "WITH c AS (VALUES (2), (3) ORDER BY 1 DESC LIMIT 1) SELECT * FROM c" to listOf(listOf("3")),
        )) {
            assertEquals(expected, c.rows(source), source)
            val output = Dialects.DUCKDB.parseOne(source).sql("duckdb")
            assertEquals(expected, c.rows(output), output)
            assertEquals(output, Dialects.DUCKDB.parseOne(output).sql("duckdb"))
        }
        // The same unparenthesized text has Hive's left-associated meaning, not DuckDB's.
        val hive = "SELECT 1 AS v UNION SELECT 2 INTERSECT SELECT 2"
        assertEquals(listOf(listOf("2")), c.rows(transpile(hive, "hive", "duckdb")))
        val pipe = "SELECT 1 AS v |> UNION ALL (SELECT 2 AS v) |> INTERSECT DISTINCT (SELECT 2 AS v)"
        assertEquals(listOf(listOf("2")), c.rows(SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql))
        val duplicates = transpile("SELECT 1 AS x, 2 AS x UNION ALL SELECT 3, 4 INTERSECT SELECT 3, 4", "hive", "duckdb")
        c.createStatement().use { s -> s.executeQuery(duplicates).use { r ->
            assertEquals(listOf("x", "x"), (1..r.metaData.columnCount).map(r.metaData::getColumnLabel))
        } }
    }

    @Test
    fun exactFrozenScopeCaseNowHasStandardIntersectRows() = native { c ->
        c.createStatement().use { s ->
            s.execute("CREATE TEMP TABLE t(x INTEGER)"); s.execute("INSERT INTO t VALUES(1)")
            s.execute("CREATE TEMP TABLE u(y INTEGER)"); s.execute("INSERT INTO u VALUES(2)")
            s.execute("CREATE TEMP TABLE v(z INTEGER)"); s.execute("INSERT INTO v VALUES(2)")
        }
        val source = "SELECT x FROM t UNION SELECT y FROM u INTERSECT SELECT z FROM v"
        val expected = listOf(listOf("1"), listOf("2"))
        assertEquals(expected, c.rows("SELECT * FROM ($source) AS q ORDER BY 1"))
        assertEquals(expected, c.rows("SELECT * FROM (${Dialects.DUCKDB.parseOne(source).sql("duckdb")}) AS q ORDER BY 1"))
        val priorGrouping = "(SELECT x FROM t UNION SELECT y FROM u) INTERSECT SELECT z FROM v"
        assertEquals(listOf(listOf("2")), c.rows(priorGrouping))
    }

    @Test
    fun allNullableBooleanXorInputsAndMixedBitwiseBindingsSurviveTranslation() = native { c ->
        for (a in listOf("TRUE", "FALSE", "NULL")) for (b in listOf("TRUE", "FALSE", "NULL")) for (d in listOf("TRUE", "FALSE", "NULL")) {
            val source = "SELECT $a XOR $b AND $d AS v"
            // DuckDB's XOR() is bitwise, not a Boolean overload. Boolean <> is the
            // independent native 3VL reference, with explicit types for NULL.
            val expected = c.rows("SELECT ($a)::BOOLEAN <> (($b)::BOOLEAN AND ($d)::BOOLEAN) AS v")
            val output = transpile(source, "mysql", "duckdb")
            assertEquals(expected, c.rows(output), output)
            val nested = transpile("SELECT $a AND ($b XOR $d) AS v", "mysql", "duckdb")
            assertEquals(c.rows("SELECT ($a)::BOOLEAN AND (($b)::BOOLEAN <> ($d)::BOOLEAN) AS v"), c.rows(nested), nested)
            val underOr = transpile("SELECT $a OR $b XOR $d AS v", "mysql", "duckdb")
            assertEquals(c.rows("SELECT ($a)::BOOLEAN OR (($b)::BOOLEAN <> ($d)::BOOLEAN) AS v"), c.rows(underOr), underOr)
        }
        for ((source, expected) in listOf("SELECT 1 | 2 & 0 AS v" to "1", "SELECT 1 ^ 2 * 3 AS v" to "9",
            "SELECT 2 + 1 ^ 3 AS v" to "4", "SELECT 8 & 3 + 1 AS v" to "0", "SELECT 1 | 2 << 1 & 2 AS v" to "1")) {
            assertEquals(listOf(listOf(expected)), c.rows(transpile(source, "mysql", "duckdb")), source)
        }
    }

    @Test
    fun namedBindIdentityAndFirstOccurrenceOrderSurviveBooleanLowering() = native { c ->
        val source = "SELECT :a XOR :b AND :c AS v"
        assertEquals(listOf("a", "b", "c"), parseParameterOccurrences(source, "mysql").map { it.name })
        val output = SqlFragment(source, "mysql").toExecutable("duckdb").sql
        val names = listOf("a", "b", "c")
        val occurrences = parseParameterOccurrences(output, "duckdb")
        assertEquals(names, occurrences.map { it.name }.distinct())
        c.prepareStatement(output).use { p ->
            assertEquals(3, p.parameterMetaData.parameterCount, output)
            p.setBoolean(1, true); p.setBoolean(2, true); p.setBoolean(3, false)
            // Established JDBC 2.0.0.0 limitation: positional setters cannot bind
            // named engine IDs. Do not present a parser success as a successful bind.
            assertTrue(assertFailsWith<SQLException> { p.executeQuery() }.message.orEmpty()
                .contains("Parameter argument/count mismatch"))
        }
        // Test-only adaptation of the VERIFIED marker spans to the numeric IDs this
        // JDBC supports. Repeated names retain their one identity; no editor text,
        // literals or SQL expressions are rewritten and no production binder is added.
        var ordinalSql = output
        for (parameter in occurrences.asReversed()) {
            assertTrue(parameter.name in names)
            ordinalSql = ordinalSql.replaceRange(parameter.range.start, parameter.range.end,
                "\$${names.indexOf(parameter.name) + 1}")
        }
        c.prepareStatement(ordinalSql).use { p ->
            assertEquals(3, p.parameterMetaData.parameterCount, ordinalSql)
            p.setBoolean(1, true); p.setBoolean(2, true); p.setBoolean(3, false)
            p.executeQuery().use { r -> assertTrue(r.next()); assertEquals(true, r.getBoolean(1)) }
            p.setNull(1, java.sql.Types.BOOLEAN)
            p.executeQuery().use { r -> assertTrue(r.next()); assertNull(r.getObject(1)) }
        }
    }

    @Test
    fun exactCoverageDeltaFormsKeepTheirFrozenAstMeaning() = native { c ->
        fun fixture(file: String, key: String, value: String): JsonObject =
            NativeCorpusAudit.json("ast-corpus/$file").getValue("cases").jsonArray
                .map { it.jsonObject }.single { it[key]?.jsonPrimitive?.content == value }
        for (file in listOf("clickhouse-serde.json", "clickhouse-annotated-serde.json")) {
            val input = fixture(file, "sql", "SELECT xor(0, 1, 1, 0)")
            val loaded = Serde.loadExpression(input.getValue("dump").jsonArray)
            val original = Dialects.CLICKHOUSE.parseOne(input.getValue("sql").jsonPrimitive.content)
            for (tree in listOf(loaded, original)) {
                val output = Dialects.DUCKDB.generator(sourceDialect = "clickhouse").generate(tree)
                assertEquals(listOf(listOf("false")), c.rows(output), output)
            }
        }
        // The handbuilt XOR has an AND operand and an OR operand. Both need to remain
        // units, including when negated. Check all nullable comparison outcomes.
        val handbuilt = fixture("handbuilt.json", "id", "comparison_zoo")
        val tree = Serde.loadExpression(handbuilt.getValue("dump").jsonArray)
        val values = " FROM (VALUES(NULL),(1),(2)) ta(a) CROSS JOIN (VALUES(NULL),(0),(2)) tb(b)" +
            " CROSS JOIN (VALUES(NULL),(3),(4)) tc(c) CROSS JOIN (VALUES(NULL),(1),(2)) td(d)" +
            " ORDER BY a NULLS FIRST,b NULLS FIRST,c NULLS FIRST,d NULLS FIRST"
        val reference = "SELECT (a <> 1 AND b >= 2) <> (c <= 3 OR d IS NOT DISTINCT FROM NULL)" + values
        assertEquals(c.rows(reference), c.rows("SELECT " + tree.sql("duckdb") + values))
        val identity = fixture("identity-serde.json", "sql", "x >> 1 | 1 & 1 ^ 1")
        val bitwise = Serde.loadExpression(identity.getValue("dump").jsonArray).sql("duckdb")
        assertEquals(listOf(listOf(null),listOf("0"),listOf("0"),listOf("0"),listOf("0")),
            c.rows("SELECT $bitwise FROM (VALUES(NULL::INTEGER),(-2),(0),(1),(8)) t(x) ORDER BY x NULLS FIRST"))
        val spark = fixture("spark-annotated-serde.json", "sql", "BIT_GET(11, 0)")
        assertEquals(listOf(listOf("1")), c.rows("SELECT " + Serde.loadExpression(spark.getValue("dump").jsonArray).sql("duckdb")))
    }
}
