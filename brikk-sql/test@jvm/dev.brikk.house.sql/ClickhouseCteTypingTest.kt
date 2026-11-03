package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseCteTypingTest {
    private val version = "26.10.2.103"
    private fun result(sql: String, schema: Schema? = null) =
        annotateClickhouseSemanticTypesWithProvenance(parseOne(sql, "clickhouse"), version, schema)
    private fun projection(result: ClickhouseSemanticTypeAnnotation): Expression {
        var node = (result.expression as Select).expressionsArg.first() as Expression
        while (node is Alias || node is Paren) node = node.thisArg as Expression
        return node
    }
    private fun expect(sql: String, expected: String, schema: Schema? = null) {
        val result = result(sql, schema)
        val node = projection(result)
        assertEquals(expected, node.type?.sql("clickhouse"), sql)
        assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, result.provenance.factFor(node)!!.evidence, sql)
    }

    @Test fun ordinaryCteAliasesQualifiedColumnsAndChainsPropagateNativeTypes() {
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+2 FROM c", "UInt16")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT c.x+2 FROM c", "UInt16")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT q.x FROM c AS q", "UInt8")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x), d AS (SELECT x+2 AS y FROM c), e AS (SELECT y*3 AS z FROM d), f AS (SELECT * FROM e) SELECT z+1 FROM f", "UInt64")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x), d AS (SELECT x AS x FROM c) SELECT x+1 FROM d", "UInt16")
        expect("WITH c AS (SELECT 1+2 AS x, x*3 AS y) SELECT y+1 FROM c", "UInt64")
    }

    @Test fun castsArraysCallbacksAndNullableInputsKeepExistingNativeContracts() {
        expect("WITH c AS (SELECT CAST([1,2] AS Array(UInt8)) AS xs) SELECT arrayMap(x -> x*2,xs) FROM c", "Array(UInt16)")
        expect("WITH c AS (SELECT CAST([1,2] AS Array(UInt8)) AS xs), d AS (SELECT arrayMap(x -> x+1,xs) AS ys FROM c) SELECT arrayMap(x -> x*2,ys) FROM d", "Array(UInt32)")
        expect("WITH c AS (SELECT CAST(1 AS Nullable(Int8)) AS x) SELECT x*2 FROM c", "Nullable(Int16)")
        expect("WITH c AS (SELECT CAST(1 AS Float32) AS x), d AS (SELECT x+1 AS y FROM c WHERE x>0 ORDER BY x LIMIT 1) SELECT y*2 FROM d", "Float64")
    }

    @Test fun nestedWithDerivedTablesAndRepeatedBindingsUseLexicalScope() {
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT q.x+1 FROM (SELECT x FROM c) AS q", "UInt16")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT q.x+1 FROM (WITH c AS (SELECT CAST(1 AS UInt32) AS x) SELECT x FROM c) AS q", "UInt64")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT a.x+b.x FROM c a CROSS JOIN c b", "UInt16")
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x), d AS (SELECT CAST(2 AS UInt16) AS y) SELECT a.x*b.y FROM c a INNER JOIN d b ON a.x=b.y", "UInt32")
    }

    @Test fun schemaBackedCtesAndStarProjectionDoNotUseGenericTypeSlots() {
        val schema = MappingSchema(mapOf("t" to mapOf("x" to "UInt8", "ys" to "Array(UInt16)")), dialect = Dialects.CLICKHOUSE)
        expect("WITH c AS (SELECT * FROM t), d AS (SELECT x,ys FROM c) SELECT x*2 FROM d", "UInt16", schema)
        expect("WITH c AS (SELECT t.x AS x,t.ys AS ys FROM t) SELECT arrayMap(y -> y+1,c.ys) FROM c", "Array(UInt32)", schema)
        val sql = "WITH c AS (SELECT unknown_udf(1) AS x) SELECT x+1 FROM c"
        val ast = parseOne(sql, "clickhouse")
        for (node in ast.walk()) node.typeSlot = DataType(args("this" to DType.UTINYINT, "nullable" to false))
        val result = annotateClickhouseSemanticTypesWithProvenance(ast, version)
        assertEquals(ClickhouseTypeEvidence.UNKNOWN, result.provenance.factFor(projection(result))!!.evidence)
    }

    @Test fun ambiguityRecursionSetOperationsAndOuterJoinsStayUnproved() {
        for (sql in listOf(
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+1 FROM c a CROSS JOIN c b",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+1 FROM c CROSS JOIN missing",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT a.x+1 FROM c a LEFT JOIN c b ON a.x=b.x",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x UNION ALL SELECT CAST(2 AS UInt8) AS x) SELECT x+1 FROM c",
            "WITH RECURSIVE c AS (SELECT 1 AS x UNION ALL SELECT x+1 FROM c WHERE x<2) SELECT x+1 FROM c",
            "WITH c AS (SELECT unknown_udf(1) AS x) SELECT x+1 FROM c")) {
            val result = result(sql)
            assertEquals(ClickhouseTypeEvidence.UNKNOWN, result.provenance.factFor(projection(result))!!.evidence, sql)
        }
    }

    @Test fun diagnosticsAreReadOnlyAndDoNotExecuteUnusedCtes() {
        val sql = "WITH c AS (SELECT arrayMap(trim | upper,['x']) AS xs) SELECT 1"
        val ast = parseOne(sql, "clickhouse")
        val before = Serde.dump(ast)
        assertTrue(validateClickhouseCompositions(ast, version).errors.isEmpty())
        assertEquals(before, Serde.dump(ast))
        val used = "WITH c AS (SELECT CAST([1] AS Array(UInt8)) AS xs) SELECT arrayMap((x,y) -> x,c.xs) FROM c"
        assertTrue(clickhouseSemanticDiagnostics(used, used.indices, version).errors.any { it.rule == ClickhouseSemanticRule.ARRAY_MAP_ARITY })
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypesWithProvenance(parseOne(used,"clickhouse"),version) { throw stop } }.exceptionOrNull())
    }

    @Test fun duplicateOutputsCatalogQualificationAndConflictingConsumerSettingsAreNotGuessed() {
        for (sql in listOf(
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x,CAST(2 AS UInt16) AS x) SELECT x+1 FROM c",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT db.c.x+1 FROM c",
            "WITH c AS (SELECT CAST(7 AS UInt8)/2 AS x), d AS (SELECT x FROM c SETTINGS sql_compatible_division_for_integers=1) SELECT x+1 FROM d SETTINGS sql_compatible_division_for_integers=0")) {
            val result = result(sql)
            assertEquals(ClickhouseTypeEvidence.UNKNOWN, result.provenance.factFor(projection(result))!!.evidence, sql)
        }
        val schema = MappingSchema(mapOf("c" to mapOf("x" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        expect("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+1 FROM c", "UInt16", schema)
    }

    @Test fun scriptIsolationOwnershipAndGenericTypingRemainUnchanged() {
        val query = "WITH c AS (SELECT CAST(7 AS UInt8) AS x) SELECT x/2 FROM c"
        val typed = annotateClickhouseScriptSemanticTypesWithProvenance("SET sql_compatible_division_for_integers=1; $query", version)
        val last = typed.last()
        assertEquals("UInt8", (last.annotation.expression as Select).selects.filterIsInstance<Expression>().first().type!!.sql("clickhouse"))
        val statement = result(query)
        assertEquals(ClickhouseTypeEvidence.UNKNOWN, statement.provenance.factFor(projection(statement))!!.evidence)
        val again = annotateClickhouseScriptSemanticTypesWithProvenance(query, version).single()
        assertNotSame(last.annotation.expression, again.annotation.expression)
        assertTrue(last.provenance!!.facts.none { fact -> again.provenance!!.facts.any { it.nodeId == fact.nodeId } })
        val generic = annotateTypes(parseOne("WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+2 FROM c", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(UNKNOWN)", (generic as Select).selects.filterIsInstance<Expression>().first().type!!.sql("clickhouse"))
    }

    @Test fun nativeFiniteAndScriptCteWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        for ((name, property) in listOf("cte-context" to "brikk.clickhouseCteOut", "cte-context-script" to "brikk.clickhouseCteScriptOut")) {
            val cases = Json.parseToJsonElement(File(folder, "$name-cases.json").readText()).jsonObject.getValue("execution").jsonArray
            val rows = cases.map { element ->
                val case = element.jsonObject
                val sql = case.getValue("sql").jsonPrimitive.content
                val units = annotateClickhouseScriptSemanticTypesWithProvenance(sql, version)
                assertTrue(units.all { it.annotation.expression != null && it.annotation.diagnostics.parseDiagnostics.isEmpty() }, sql)
                assertEquals(clickhouseScriptSemanticDiagnostics(sql, version), units.map { it.annotation.diagnostics }, sql)
                val facts = units.mapNotNull { unit ->
                    val select = unit.annotation.expression as? Select ?: return@mapNotNull null
                    var node = select.expressionsArg.first() as Expression
                    while (node is Alias || node is Paren) node = node.thisArg as Expression
                    unit.provenance!!.factFor(node)!!
                }
                val types = facts.map { if (it.evidence == ClickhouseTypeEvidence.UNKNOWN) "UNKNOWN" else it.typeSql ?: "MISSING" }
                assertEquals(case.getValue("types").jsonArray.map { it.jsonPrimitive.content }, types, sql)
                if (case.getValue("qualified").jsonPrimitive.boolean)
                    assertTrue(facts.all { it.evidence == ClickhouseTypeEvidence.NATIVE_CONDITIONAL }, sql)
                val rules = units.flatMap { it.annotation.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
                assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
                val generated = units.joinToString("; ", postfix = ";") { it.annotation.expression!!.sql("clickhouse") }
                val replay = annotateClickhouseScriptSemanticTypesWithProvenance(generated, version)
                val replayTypes = replay.mapNotNull { unit ->
                    val select = unit.annotation.expression as? Select ?: return@mapNotNull null
                    var node = select.expressionsArg.first() as Expression
                    while (node is Alias || node is Paren) node = node.thisArg as Expression
                    val fact = unit.provenance!!.factFor(node)!!
                    if (fact.evidence == ClickhouseTypeEvidence.UNKNOWN) "UNKNOWN" else fact.typeSql ?: "MISSING"
                }
                assertEquals(types, replayTypes, sql)
                for (unit in units) assertEquals(Serde.dump(unit.annotation.expression!!),
                    Serde.dump(Serde.load(Serde.dump(unit.annotation.expression!!)) as Expression))
                buildJsonObject {
                    put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                    put("types", JsonArray(types.map(::JsonPrimitive))); put("rules", JsonArray(rules.map(::JsonPrimitive)))
                    put("evidence", JsonArray(facts.map { JsonPrimitive(it.evidence.name) })); put("qualified", case.getValue("qualified"))
                }
            }
            System.getProperty(property)?.let { File(it).writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
        }
    }
}
