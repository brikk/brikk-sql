package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.StatementEffect
import dev.brikk.house.sql.shape.statementEffect
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.String

class DatafusionUtilityTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("vendor/data/datafusion-spice/utilities")) }
    private val directory = root.resolve("vendor/data/datafusion-spice/utilities")
    private fun read(name: String) = Json.parseToJsonElement(Files.readString(directory.resolve(name))).jsonObject

    @Test fun nativeUtilityCorpusHasStructuredSerializableRoundTripsAndStrictRejections() {
        val exported = read("brikk.json").getValue("cases").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val dialect = Dialects.DATAFUSION
        for (item in read("cases.json").getValue("cases").jsonArray) {
            val row = item.jsonObject
            val id = row.getValue("id").jsonPrimitive.content
            val source = row.getValue("sql").jsonPrimitive.content
            if (row.getValue("area").jsonPrimitive.content == "negative") {
                assertFailsWith<ParseError>(id) { dialect.parse(source) }
                continue
            }
            val trees = dialect.parse(source).filterNotNull()
            assertTrue(trees.isNotEmpty(), id)
            assertTrue(trees.all { it.walk().none { node -> node is Command } }, id)
            val generated = trees.joinToString("; ") { dialect.generate(it, sourceDialect = "datafusion") }
            assertEquals(exported.getValue(id).getValue("generated").jsonPrimitive.content, generated, id)
            val reparsed = dialect.parse(generated).filterNotNull()
            assertEquals(trees, reparsed, id)
            for (tree in trees) {
                val loaded = Serde.load(Serde.dump(tree)) as Expression
                assertEquals(tree.sql("datafusion"), loaded.sql("datafusion"), id)
                for (target in listOf("postgres", "duckdb", "spice"))
                    assertFailsWith<UnsupportedError>("$id -> $target") { loaded.sql(target) }
            }
        }
    }

    @Test fun preparedBodiesArgumentsAndClusterTuplesRemainTraversable() {
        val prepare = assertIs<DatafusionPrepare>(parseOne("PREPARE p(INT, VARCHAR) AS SELECT $1, $2 FROM t", "datafusion"))
        assertEquals(2, prepare.expressionsArg.size)
        assertIs<Select>(prepare.expressionArg)
        assertEquals(2, prepare.walk().filterIsInstance<Parameter>().count())
        val execute = assertIs<DatafusionExecute>(parseOne("EXECUTE p(1, 2 + 3, 'x')", "datafusion"))
        assertEquals(3, execute.expressionsArg.size)
        assertIs<Add>(execute.expressionsArg[1])
        val tuple = parseOne("CREATE TABLE t (a INT, b INT) CLUSTER BY (a, b)", "datafusion").find<ClusterProperty>()!!
        assertEquals(1, tuple.expressionsArg.size)
        assertIs<Tuple>(tuple.expressionsArg.single())
        val keys = parseOne("CREATE TABLE t (a INT, b INT) CLUSTER BY a, b", "datafusion").find<ClusterProperty>()!!
        assertEquals(2, keys.expressionsArg.size)
        assertTrue(keys.expressionsArg.all { it is Column })
    }

    @Test fun orderedExplainOptionsAndPreparedBodiesHaveConservativeExecutionEffects() {
        val off = "EXPLAIN (ANALYZE, ANALYZE FALSE, TIMING OFF, METRICS all) SELECT 1"
        val on = "EXPLAIN (ANALYZE FALSE, ANALYZE 'on') SELECT 1"
        assertFalse(assertIs<DatafusionExplain>(parseOne(off, "datafusion")).executesQuery)
        assertTrue(assertIs<DatafusionExplain>(parseOne(on, "datafusion")).executesQuery)
        assertEquals(StatementEffect.PLANS_OR_INSPECTS, SqlFragment(off, "datafusion").statementEffect)
        assertEquals(StatementEffect.EXECUTES_QUERY, SqlFragment(on, "datafusion").statementEffect)
        assertEquals(StatementEffect.MODIFIES_STATE, SqlFragment("PREPARE p AS SELECT 1", "datafusion").statementEffect)
        assertEquals(StatementEffect.UNKNOWN, SqlFragment("EXECUTE p", "datafusion").statementEffect)
        assertEquals(StatementEffect.PLANS_OR_INSPECTS, SqlFragment("SHOW TABLES", "datafusion").statementEffect)
    }

    @Test fun spiceKeepsItsOwnNodesAndPostgresLexicalProfile() {
        assertIs<SpiceExplain>(parseOne("EXPLAIN SELECT 1", "spice"))
        assertIs<SpicePrepare>(parseOne("PREPARE p(INT) AS SELECT $1", "spice"))
        assertIs<SpiceExecute>(parseOne("EXECUTE p(1)", "spice"))
        assertIs<SpiceShow>(parseOne("SHOW TABLES", "spice"))
        assertFailsWith<ParseError> { parseOne("CREATE TABLE `t` (id INT) CLUSTER BY (id)", "spice") }
        assertFailsWith<ParseError> { parseOne("EXPLAIN (ANALYZE, ANALYZE FALSE) SELECT 1", "spice") }
    }

    @Test fun structuredEditsDoNotLoseChangedFlagsOrParameterTypes() {
        val explain = assertIs<DatafusionExplain>(parseOne("EXPLAIN (ANALYZE) SELECT 1", "datafusion"))
        (explain.expressionsArg.single() as PropertyEQ).set("expression", Var(args("this" to "FALSE")))
        assertFalse(explain.executesQuery)
        assertEquals("EXPLAIN (ANALYZE FALSE) SELECT 1", explain.sql("datafusion"))
        (explain.expressionsArg.single() as PropertyEQ).set("expression", dev.brikk.house.sql.ast.Boolean(args("this" to true)))
        assertTrue(explain.executesQuery)
        assertEquals("EXPLAIN (ANALYZE TRUE) SELECT 1", explain.sql("datafusion"))
        val prepare = assertIs<DatafusionPrepare>(parseOne("PREPARE p AS SELECT $1", "datafusion"))
        prepare.set("expressions", listOf(DataType.build(DType.INT)))
        assertEquals("PREPARE p(INT) AS SELECT $1", prepare.sql("datafusion"))
        for (sql in listOf("EXECUTE IMMEDIATE 'SELECT 1'", "EXECUTE p OUTPUT", "EXECUTE p DEFAULT", "SHOW TERSE TABLES", "SHOW CREATE TABLE t"))
            assertFailsWith<ParseError>(sql) { parseOne(sql, "datafusion") }
    }

    @Test fun everyPreviouslyOpaqueHandoffControlNowHasANativeUtilityOrCreateNode() {
        val historical = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/round12/qualification.json"))).jsonObject
        val excluded = historical.getValue("structured_syntax_exclusions").jsonObject.getValue("cases").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val cases = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/cases.json"))).jsonObject.getValue("cases").jsonArray
        val selected = cases.map { it.jsonObject }.filter { it.getValue("id").jsonPrimitive.content in excluded }
        assertEquals(10, selected.size)
        for (row in selected) {
            val tree = parseOne(row.getValue("sql").jsonPrimitive.content, "datafusion")
            assertTrue(tree is DatafusionShow || tree is DatafusionExplain || tree is DatafusionPrepare || tree is DatafusionExecute || tree is Create)
            assertFalse(tree.walk().any { it is Command })
        }
    }
}
