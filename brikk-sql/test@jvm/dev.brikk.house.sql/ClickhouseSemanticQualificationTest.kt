package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.clickhouseSemanticDiagnostics
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.lineage
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.scope.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseSemanticQualificationTest {
    @Test
    fun finiteQualificationVectorsCombineSerdeTypesScopeAndPositionedDiagnostics() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/qualification-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            assertEquals(ast, Serde.load(Serde.dump(ast)), id)
            annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
            val projection = (ast as Select).selects.single() as Expression
            val type = if (projection.isType(DType.UNKNOWN)) "UNKNOWN" else projection.type?.sql("clickhouse") ?: "MISSING"
            assertEquals(case.getValue("type").jsonPrimitive.content, type, id)
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression), id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, id)
            assertTrue(report.errors.all { it.diagnostic.start >= 0 && it.diagnostic.end <= sql.length }, id)
            val scope = RelationScope.analyze(sql, "clickhouse", RelationFacts(catalog = CatalogKnowledge.CLOSED))
            assertTrue(scope.parsed, id)
            assertTrue(scope.diagnostics.isEmpty(), "$id: ${scope.diagnostics}")
            assertTrue(scope.references.none { it.kind == ReferenceKind.COLUMN }, id)
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", type)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("unknowns", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseQualificationOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
        } }
    }

    @Test
    fun physicalArrayAndCaptureDependenciesSurviveContextualTypingAndSerde() {
        val sql = "SELECT arrayMap(x -> CONCAT(x,t.s),t.a) AS v FROM t"
        val schema = mapOf("t" to mapOf("a" to "Array(String)", "s" to "String"))
        val ast = annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
        assertEquals("Array(String)", ((ast as Select).selects.single() as Expression).type?.sql("clickhouse"))
        val restored = Serde.load(Serde.dump(ast)) as Expression
        val downstream = lineage("v", restored.sql("clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE).downstream.map { it.name }.toSet()
        assertEquals(setOf("t.a", "t.s"), downstream)
        val facts = RelationFacts(tables = mapOf("t" to RelationFact(listOf(ColumnFact("a", "Array(String)"), ColumnFact("s", "String")))), catalog = CatalogKnowledge.CLOSED)
        val scope = RelationScope.analyze(sql, "clickhouse", facts)
        assertTrue(scope.diagnostics.isEmpty())
        assertEquals(setOf("a", "s"), scope.references.filter { it.kind == ReferenceKind.COLUMN }.map { it.name }.toSet())
    }
}
