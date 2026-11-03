package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.String
import kotlin.test.*

class DatafusionAnalysisTest {
    private val table = RelationFact(listOf(
        ColumnFact("id", "BIGINT", nullability = Nullability.NON_NULL, origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "docs.id"),
        ColumnFact("body", "VARCHAR", nullability = Nullability.NULLABLE, origin = ColumnOrigin.PHYSICAL_TABLE),
        ColumnFact("payload", "Struct(\"a\": Int64, \"nested\": Struct(\"flag\": Boolean))", origin = ColumnOrigin.PHYSICAL_TABLE),
    ))
    private val facts = RelationFacts(tables = mapOf("docs" to table, "other" to RelationFact(listOf(ColumnFact("id", "BIGINT"), ColumnFact("extra", "INT")))), catalog = CatalogKnowledge.CLOSED)
    private fun scope(sql: String, dialect: String = "spice", facts: RelationFacts = this.facts) = RelationScope.analyze(sql, dialect, facts)
    private fun shape(sql: String, dialect: String = "spice") = SqlFragment(sql, dialect).outputShape()

    @Test
    fun primitiveAndNestedArrowFactsRetainNativeIdentityAndLogicalType() {
        val types = mapOf("UInt8" to DType.UTINYINT, "UInt16" to DType.USMALLINT, "UInt32" to DType.UINT, "UInt64" to DType.UBIGINT,
            "LargeUtf8" to DType.VARCHAR, "Utf8View" to DType.VARCHAR, "Binary" to DType.BINARY,
            "Decimal128(18, 3)" to DType.DECIMAL, "Decimal256(40, 6)" to DType.DECIMAL256,
            "Timestamp(Microsecond, Some(\"UTC\"))" to DType.TIMESTAMPTZ, "Timestamp(ns)" to DType.TIMESTAMP,
            "Duration(ms)" to DType.INTERVAL, "Interval(MonthDayNano)" to DType.INTERVAL,
            "List(Int64)" to DType.ARRAY, "LargeList(Utf8)" to DType.ARRAY, "FixedSizeList(3 x Float32)" to DType.ARRAY,
            "Struct(\"a\": Int64)" to DType.STRUCT,
            "Map(\"entries\": non-null Struct(\"key\": non-null Utf8, \"value\": Int64), unsorted)" to DType.MAP)
        for (dialect in listOf("datafusion", "spice")) for ((native, logical) in types) {
            val type = dataTypeFromStr(native, Dialects.forName(dialect))
            assertEquals(logical, type.thisArg, native)
            val sql = "SELECT arrow_cast(NULL, '${native.replace("'", "''")}') AS v"
            assertEquals(native, shape(sql, dialect).columns.single().type, sql)
            assertEquals(native, scope(sql, dialect).output!!.columns.single().sqlType, sql)
            assertEquals(type, Serde.load(Serde.dump(type)))
        }
        assertEquals(ShapeVerdict.HAS_LESS, Shape.of("v" to "FixedSizeList(3 x Float32)").compare(Shape.of("v" to "FixedSizeList(4 x Float32)"), "spice").verdict)
        assertEquals(ShapeVerdict.SATISFIES, Shape.of("v" to "List(Int64)").compare(Shape.of("v" to "List(Int64)"), "spice").verdict)
    }

    @Test
    fun measuredNumericRulesDoNotBorrowOtherEnginesPrecision() {
        for (dialect in listOf("datafusion", "spice")) {
            assertEquals(listOf("BIGINT", "DOUBLE", "BIGINT", "BIGINT", "DOUBLE"),
                shape("SELECT 1 AS i, 1.5 AS f, 3 / 2 AS d, sum(CAST(1 AS INT)) AS s, avg(1) AS a", dialect).columns.map { it.type })
            assertEquals("UNKNOWN", shape("SELECT CAST(1 AS DECIMAL(10,2)) / CAST(2 AS DECIMAL(12,3)) AS v", dialect).columns.single().type)
            val n = shape("SELECT count(*) AS n, CAST(NULL AS INT) AS v, random() AS r", dialect).columns
            assertEquals(false, n[0].nullable)
            assertEquals(true, n[1].nullable)
            assertNull(n[2].nullable)
        }
    }

    @Test
    fun parametersHaveAuthoredUtf16RangesAndNumberedIdentity() {
        val sql = "SELECT '😀' AS s, $1 AS a, $12 AS b, $1 AS c, ? AS d, '$2 ?' AS txt FROM docs WHERE body ? 'x'"
        val strict = parseParameterOccurrences(sql, "spice")
        assertEquals(listOf("$1", "$12", "$1", "?"), strict.map { sql.substring(it.range.start, it.range.end) })
        assertEquals(listOf(ParamStyle.NUMBERED_DOLLAR, ParamStyle.NUMBERED_DOLLAR, ParamStyle.NUMBERED_DOLLAR, ParamStyle.POSITIONAL), strict.map { it.style })
        assertEquals(strict, SqlScriptParameters.parse(sql, "spice", positional = true).occurrences)
        assertEquals(2, SqlFragment(sql, "spice").scalarParams.first { it.name == "1" }.count)
        assertTrue(scope(sql).references.none { it.kind == ReferenceKind.COLUMN && it.name in setOf("1", "12", "?") })
        val incomplete = sql + "; SELECT $3, 'unterminated $4 ?"
        val recovered = SqlScriptParameters.parse(incomplete, "spice", positional = true)
        assertNotNull(recovered.failureOffset)
        assertEquals(listOf("1", "12", "1", null, "3"), recovered.occurrences.map { it.name })
    }

    @Test
    fun sourceArgumentsResolveAsDatasetsAndColumnsInTheirOwnFrame() {
        val sql = "SELECT s.id, s._score FROM vector_search(docs, 'q', body, limit => 5) s"
        val result = scope(sql)
        assertTrue(result.parsed)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(result.references.any { it.name == "docs" && it.kind == ReferenceKind.TABLE && it.resolution == Resolution.RESOLVED })
        assertTrue(result.references.any { it.name == "body" && it.kind == ReferenceKind.COLUMN && it.resolution == Resolution.RESOLVED })
        assertTrue(result.references.none { it.kind == ReferenceKind.COLUMN && it.name in setOf("docs", "limit") })
        val output = result.output!!.columns
        assertEquals("docs.id", output[0].provenance)
        assertEquals(ColumnOrigin.COMPUTED, output[1].origin)
        assertEquals("DOUBLE", output[1].sqlType)
        assertTrue(scope("SELECT * FROM vector_search(docs, 'q', missing)").diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }

    @Test
    fun callSiteFactsDoNotLeakAcrossSameNamedCallsAndUnknownInputsStayOpen() {
        val sql = "SELECT a.*, b.* FROM vector_search(docs,'q') a CROSS JOIN vector_search(other,'q') b"
        val exact = RelationFact(listOf(ColumnFact("only_here", "INT")))
        val result = scope(sql, facts = facts.copy(callSites = mapOf(sql.indexOf("vector_search") to exact)))
        assertEquals(listOf("only_here", "id", "extra", "_score"), result.output!!.columns.map { it.name })
        assertEquals(Knowledge.PARTIAL, result.output!!.knowledge)
        val unknown = scope("SELECT * FROM vector_search(absent, 'q')", facts = RelationFacts())
        assertEquals(Knowledge.PARTIAL, unknown.output!!.knowledge)
        assertEquals(listOf("_score"), unknown.output!!.columns.map { it.name })
        val noScore = scope("SELECT * FROM vector_search(docs, 'q', include_score => false)")
        assertTrue(noScore.output!!.columns.none { it.name == "_score" })
        val aliases = scope("SELECT * FROM vector_search(absent, 'q') AS s(x,y)", facts = RelationFacts()).output!!
        assertEquals(listOf("x", "y"), aliases.columns.map { it.name })
        assertTrue(aliases.columns.all { it.sqlType == null })
        assertEquals(Knowledge.PARTIAL, aliases.knowledge)
        val completing = "SELECT s. FROM vector_search(docs, 'q') s"
        val completion = RelationScope.complete(completing, "spice", facts.copy(callSites = mapOf(
            completing.indexOf("vector_search") to exact)), completing.indexOf("s.") + 2)
        assertEquals(listOf("only_here"), completion.items.map { it.name })
    }

    @Test
    fun nestedSearchScoresAndCollisionsFollowPinnedSource() {
        val rrf = scope("SELECT * FROM rrf(vector_search(docs, 'q'), text_search(other, 'q'))")
        assertEquals(listOf("_fused_score", "id"), rrf.output!!.columns.map { it.name })
        val rerank = scope("SELECT * FROM rerank(rrf(vector_search(docs, 'q'), text_search(other, 'q')), document => 'id', model => 'local')")
        assertEquals(listOf("id", "rerank_score"), rerank.output!!.columns.map { it.name })
        assertEquals(Nullability.NON_NULL, rerank.output!!.columns.last().nullability)
        val collision = scope("SELECT rerank_score FROM rerank(docs, document => 'body', model => 'local')", facts = facts.copy(
            tables = mapOf("docs" to RelationFact(table.columns + ColumnFact("rerank_score", "INT")))))
        assertTrue(collision.diagnostics.any { it.problem == ScopeProblem.AMBIGUOUS_COLUMN })
    }

    @Test
    fun jsonTableAliasesAndNullabilityUseFixedSchemas() {
        val tree = shape("SELECT * FROM json_tree('{\"a\":1}')")
        assertEquals(listOf("key", "value", "type", "atom", "id", "parent", "fullkey", "path"), tree.names())
        assertEquals(listOf(true, true, false, true, false, true, false, true), tree.columns.map { it.nullable })
        val aliased = scope("SELECT j.p, j.v FROM flatten_json('{\"a\":1}') AS j(p, parent, k, v, t)")
        assertTrue(aliased.diagnostics.isEmpty(), aliased.diagnostics.toString())
        assertEquals(listOf("VARCHAR", "VARCHAR"), aliased.output!!.columns.map { it.sqlType })
        assertEquals("VARCHAR", shape("SELECT doc ->> 'a' AS v FROM docs").columns.single().type)
        assertTrue(shape("SELECT json_get('{}', 'a') AS v").columns.single().type.startsWith("Union("))
    }

    @Test
    fun literalDecisionsRefineStructsWithoutEvaluatingModels() {
        val sql = "SELECT ai_decide(body, '{\"urgent\":{\"type\":\"noul\",\"instructions\":\"Urgent?\"}}', model => 'missing') AS decision FROM docs"
        val out = scope(sql).output!!.columns.single()
        assertTrue(out.sqlType!!.contains("probability"), out.sqlType)
        assertEquals(Nullability.NULLABLE, out.nullability)
        assertNull(scope("SELECT ai_decide(body, questions) AS v FROM docs").output!!.columns.single().sqlType)
        for (questions in listOf("{}", "{\"x\":{\"type\":\"noul\"}}", "{\"x\":{\"type\":\"noul\",\"instructions\":\"a\"},\"x\":{\"type\":\"noul\",\"instructions\":\"b\"}}")) {
            assertNull(scope("SELECT ai_decide(body, '$questions') AS v FROM docs").output!!.columns.single().sqlType)
        }
        val nested = scope("WITH q AS ($sql) SELECT q.decision.urgent.probability FROM q")
        assertTrue(nested.diagnostics.isEmpty(), nested.diagnostics.toString())
        assertEquals("DOUBLE", nested.output!!.columns.single().sqlType)
    }

    @Test
    fun joinsCtesCorrelationsAndNestedFieldsKeepTheirScopes() {
        for (dialect in listOf("datafusion", "spice")) {
            val using = shape("SELECT * FROM (SELECT 1 AS a, 2 AS id) l JOIN (SELECT 2 AS id, 3 AS b) r USING(id)", dialect)
            assertEquals(listOf("a", "id", "b"), using.names())
            val nested = scope("SELECT payload.a, payload.nested.flag FROM docs", dialect)
            assertTrue(nested.diagnostics.isEmpty(), nested.diagnostics.toString())
            assertEquals(listOf("Int64", "Boolean"), nested.output!!.columns.map { it.sqlType })
            assertTrue(scope("SELECT id FROM docs JOIN other ON docs.id = other.id", dialect).diagnostics.any { it.problem == ScopeProblem.AMBIGUOUS_COLUMN })
            assertTrue(scope("WITH q AS (SELECT id FROM docs) SELECT q.id FROM q WHERE EXISTS (SELECT 1 FROM other WHERE other.id = q.id)", dialect).diagnostics.isEmpty())
            assertEquals("BIGINT", shape("SELECT u.v FROM unnest([1,2]) AS u(v)", dialect).columns.single().type)
            assertEquals(listOf("a", "id", "b"), shape("SELECT * FROM (SELECT 1 AS a, 2 AS id) l NATURAL JOIN (SELECT 2 AS id, 3 AS b) r", dialect).names())
            assertEquals(listOf("a", "id", "id", "b"), shape("SELECT l.*, r.* FROM (SELECT 1 AS a, 2 AS id) l JOIN (SELECT 2 AS id, 3 AS b) r USING(id)", dialect).names())
            val grouped = scope("SELECT body, sum(id) AS total FROM docs GROUP BY body HAVING total > 0 ORDER BY total", dialect)
            assertTrue(grouped.diagnostics.isEmpty(), grouped.diagnostics.toString())
            val window = scope("SELECT row_number() OVER (PARTITION BY body ORDER BY id) AS n FROM docs", dialect).output!!.columns.single()
            assertEquals("BIGINT", window.sqlType)
            assertEquals(Nullability.NON_NULL, window.nullability)
            val bound = ShapeCatalog(mapOf("db.docs" to Shape.of("id" to "BIGINT")), slots = mapOf("src" to Shape.of("value" to "INT")))
            assertEquals(listOf("value"), SqlFragment("SELECT * FROM src()", dialect).outputShape(bound).names())
        }
        assertEquals(listOf("id", "payload"), scope("SELECT * EXCEPT(body) FROM docs", "datafusion").output!!.columns.map { it.name })
    }

    @Test
    fun qualificationAndLineageKeepSearchArgumentsAndPhysicalDependencies() {
        val schema = mapOf("docs" to mapOf("id" to "BIGINT", "body" to "VARCHAR"))
        val sql = "SELECT s.id, s._score FROM vector_search(docs, 'q', body) s"
        val qualified = qualify(Dialects.SPICE.parseOne(sql), dialect = Dialects.SPICE, schema = schema)
        val rendered = qualified.sql("spice")
        assertTrue(rendered.contains("vector_search(\"docs\", 'q', \"body\")"), rendered)
        val typed = annotateTypes(qualified, schema = schema, dialect = Dialects.SPICE)
        assertEquals(listOf(DType.BIGINT, DType.DOUBLE), typed.expressionsArg.filterIsInstance<Expression>().map { it.type?.thisArg })
        val nodes = lineageAll(sql, schema = schema, dialect = Dialects.SPICE)
        for (name in listOf("id", "_score")) {
            assertTrue(nodes.getValue(name).walk().any { it.expression is Table && (it.expression as Table).name == "docs" }, name)
        }
        assertTrue(nodes.getValue("_score").walk().any { it.expression is Anonymous && it.name.endsWith("._score") })
        val catalog = ShapeCatalog(mapOf("docs" to Shape.of("id" to "BIGINT", "body" to "VARCHAR")))
        assertEquals(setOf("docs"), SqlFragment(sql, "spice").columnDependencies("id", catalog)["id"])
        val nested = "SELECT r.id FROM rrf(vector_search(docs, 'q'), text_search(docs, 'q')) r"
        assertEquals(setOf("docs"), SqlFragment(nested, "spice").columnDependencies("id", catalog)["id"])
    }
}
