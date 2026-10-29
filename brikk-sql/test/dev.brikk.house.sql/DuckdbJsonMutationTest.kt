package dev.brikk.house.sql

import dev.brikk.house.sql.ast.JSONRemove
import dev.brikk.house.sql.ast.JSONSet
import dev.brikk.house.sql.ast.JSONStripNulls
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbJsonMutationTest {
    private val calls = listOf(
        "json_set(doc,path,value)", "json_insert(doc,path,value)", "json_replace(doc,path,value)",
        "json_remove(doc,path)", "json_deep_merge(doc,patch,value)", "json_merge_patch_diff(doc,patch)",
        "json_normalize(doc)", "json_strip_nulls(doc)",
    )
    private val fields = listOf(ColumnFact("doc", "JSON", nullability = Nullability.NON_NULL, provenance = "documents.doc"),
        ColumnFact("path", "VARCHAR", nullability = Nullability.NON_NULL),
        ColumnFact("value", "JSON", nullability = Nullability.NON_NULL),
        ColumnFact("patch", "JSON", nullability = Nullability.NULLABLE))
    private val facts = RelationFacts(tables = mapOf("documents" to RelationFact(fields)), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(mapOf("documents" to Shape(fields.map { ColumnShape(it.name, it.sqlType!!, it.nullability == Nullability.NULLABLE) })))

    @Test
    fun existingNodesAndRawLiteralPathsSurviveCopySerdeAndNativeGeneration() {
        assertIs<JSONSet>(Dialects.DUCKDB.parseOne("SELECT json_set('{}','$.a','1')").selects.single())
        assertIs<JSONRemove>(Dialects.DUCKDB.parseOne("SELECT json_remove('{}','$.a')").selects.single())
        assertIs<JSONStripNulls>(Dialects.DUCKDB.parseOne("SELECT json_strip_nulls('{}')").selects.single())
        for (name in listOf("json_set", "json_insert", "json_replace", "json_remove")) for (path in listOf(
            "$.a", "/a~1b/~0x", "$[0]", "$[#-1]", "$[#]", "$", "", "/", "$.\"a.b\"", "$.\"a\\\"b\"",
        )) {
            val suffix = if (name == "json_remove") "" else ",'\"hello\"'"
            val tree = Dialects.DUCKDB.parseOne("SELECT $name('{}','$path'$suffix) AS changed")
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertTrue(tree.findAll<Literal>().any { it.isString && it.name == path }, path)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), path)
        }
        for (call in calls + listOf("json_set(value:=value,path:=path,json:=doc)", "json_merge_patch_diff(new:=patch,old:=doc)")) {
            val tree = Dialects.DUCKDB.parseOne("SELECT $call AS result FROM documents")
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val qualified = qualify(tree.copy(), dialect = Dialects.DUCKDB, schema = fields.associate { it.name to it.sqlType }.let { mapOf("documents" to it) })
            val generated = qualified.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
        }
        assertTrue(SqlFragment("SELECT json_set('{}','$.a','{\"\$b\":\"? :x\"}')", "duckdb").scalarParams.isEmpty())
    }

    @Test
    fun scopesAndShapesUseJsonReturnContractsExceptVarcharNormalization() {
        for (call in calls) {
            val sql = "SELECT $call AS result FROM documents"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$call: ${scope.diagnostics}")
            val output = scope.output!!.columns.single()
            val expected = if (call.startsWith("json_normalize")) "VARCHAR" else "JSON"
            assertEquals(expected, output.sqlType, call)
            assertEquals(ColumnOrigin.COMPUTED, output.origin)
            assertNull(output.provenance)
            assertEquals(expected, SqlFragment(sql, "duckdb").outputShape(catalog).columns.single().type, call)
        }
        val sql = "SELECT json_set(doc,path,value) FROM documents"
        val caret = sql.indexOf("value")
        assertTrue(RelationScope.complete(sql.replace("value", ""), "duckdb", facts, caret).items.map { it.name }.containsAll(listOf("doc", "path", "value", "patch")))
        assertTrue(RelationScope.analyze(sql.replace("value", "missing"), "duckdb", facts).diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }

    @Test
    fun sqlNullHandlingUsesArgumentRolesAndDoesNotAssumeAllFunctionsAreStrict() {
        for ((call, nullable) in listOf(
            "json_set(doc,path,value)" to false, "json_insert(doc,path,patch)" to true,
            "json_replace(doc,path,value)" to false, "json_remove(doc,path)" to true,
            "json_strip_nulls(doc)" to false, "json_normalize(patch)" to true,
            "json_deep_merge(patch,doc)" to false, "json_deep_merge(doc,NULL,value)" to false,
            "json_deep_merge(doc,value,NULL)" to true, "json_merge_patch_diff(patch,doc)" to false,
            "json_merge_patch_diff(doc,patch)" to true, "json_merge_patch_diff(new:=doc,old:=patch)" to false,
            "json_deep_merge(json2:=doc,json1:=patch)" to false,
        )) {
            val sql = "SELECT $call AS result FROM documents"
            assertEquals(nullable, SqlFragment(sql, "duckdb").outputShape(catalog).columns.single().nullable, call)
            assertEquals(if (nullable) Nullability.NULLABLE else Nullability.NON_NULL,
                RelationScope.analyze(sql, "duckdb", facts).output!!.columns.single().nullability, call)
        }
    }

    @Test
    fun binderInvalidSignaturesAreNotRewrittenIntoDifferentValidCalls() {
        for (call in listOf("json_set(doc,path,value,path,value)", "json_remove(doc,path,path)",
            "json_set(doc,['$.a','$.b'],['1','2'])", "json_strip_nulls(doc,false)", "json_deep_merge(doc)")) {
            val tree = Dialects.DUCKDB.parseOne("SELECT $call AS result FROM documents")
            val before = Serde.dump(tree)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            assertEquals(before, Serde.dump(tree))
            assertEquals(tree.findAll<Literal>().map { it.name }.toList(), Dialects.DUCKDB.parseOne(generated).findAll<Literal>().map { it.name }.toList())
            if (!call.contains("['$.a'")) {
                assertEquals("UNKNOWN", SqlFragment("SELECT $call AS result FROM documents", "duckdb").outputShape(catalog).columns.single().type, call)
            }
        }
    }

    @Test
    fun sameNamesAreNotCrossTargetSemanticProofAndQualifiedUdfsStayUnknown() {
        for (call in calls) for (target in listOf("trino", "postgres", "doris", "clickhouse", "bigquery", "mysql")) {
            val fragment = SqlFragment("SELECT $call FROM documents", "duckdb")
            assertFailsWith<UnsupportedError>("$target: $call") { fragment.toExecutable(target) }
            assertTrue(fragment.transpileTo(target).unsupportedMessages.any { "DuckDB JSON" in it }, "$target: $call")
        }
        for (source in listOf("mysql", "bigquery")) {
            assertFailsWith<UnsupportedError>(source) { SqlFragment("SELECT json_set(doc,'$.x','hello') FROM documents", source).toExecutable("duckdb") }
        }
        for (call in listOf("custom.json_insert(doc,path,value)", "custom.json_normalize(doc)")) {
            val scope = RelationScope.analyze("SELECT $call AS result FROM documents", "duckdb", facts)
            assertNull(scope.output!!.columns.single().sqlType, call)
        }
        val native = SqlFragment("SELECT json_set('{}','$.x','\"hello\"')", "duckdb").toExecutable("duckdb")
        assertTrue(native.unsupportedMessages.isEmpty())
    }
}
