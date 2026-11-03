package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbVariantTest {
    private val facts = RelationFacts(tables = mapOf("events" to RelationFact(listOf(
        ColumnFact("id", "INT", nullability = Nullability.NON_NULL),
        ColumnFact("payload", "VARIANT", nullability = Nullability.NON_NULL, provenance = "events.payload"),
    ))), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(mapOf("events" to Shape(listOf(ColumnShape("id", "INT", false), ColumnShape("payload", "VARIANT", false)))))
    private val inputSchema = mapOf("events" to mapOf("id" to "INT", "payload" to "VARIANT"))

    @Test
    fun ddlCastsExtractionAndShreddingOptionsStayStructuredThroughSerde() {
        for (sql in listOf("CREATE TABLE events(id INT,payload VARIANT)", "INSERT INTO events VALUES(1,'{\"a\":1}'::JSON::VARIANT)",
            "SELECT (payload::VARIANT).Name,payload['Name'],payload[2],variant_extract(payload,'a[2]') FROM events",
            "COPY events TO 'never-written.parquet' (FORMAT PARQUET,SHREDDING {'payload':'STRUCT(\"Name\" VARCHAR)'})")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
        }
    }

    @Test
    fun functionOverloadsDistinguishScalarAndListPathsWithoutInferringTheEmbeddedValueType() {
        for ((call, type) in listOf(
            "variant_extract(payload,'Name')" to "VARIANT", "variant_typeof(payload)" to "VARCHAR",
            "variant_type(payload)" to "VARCHAR", "variant_type(payload,['Name','missing'])" to "ARRAY<VARCHAR>",
            "variant_keys(payload)" to "ARRAY<VARCHAR>", "variant_keys(payload,['Name','missing'])" to "ARRAY<ARRAY<VARCHAR>>",
            "variant_exists(payload,'Name')" to "BOOLEAN", "variant_exists(payload,['Name'])" to "ARRAY<BOOLEAN>",
            "variant_array_length(payload)" to "UBIGINT", "variant_array_length(payload,['Name'])" to "ARRAY<UBIGINT>",
            "variant_extract_string(payload,'Name')" to "VARCHAR", "variant_extract_string(payload,['Name'])" to "ARRAY<VARCHAR>",
            "variant_contains(payload,1::VARIANT)" to "BOOLEAN", "variant_normalize(payload)" to "VARIANT",
            "variant_comparator(payload)" to "BINARY", "variant_group_object('k',payload)" to "VARIANT",
            "variant_group_array(payload)" to "VARIANT",
        )) {
            val sql = "SELECT $call AS result FROM events"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$call: ${scope.diagnostics}")
            assertEquals(type, scope.output!!.columns.single().sqlType, call)
            assertEquals(type, SqlFragment(sql, "duckdb").outputShape(catalog).columns.single().type, call)
        }
        assertNull(RelationScope.analyze("SELECT variant_type(payload,\$path) AS result FROM events", "duckdb", facts).output!!.columns.single().sqlType)
        assertNull(RelationScope.analyze("SELECT custom.variant_type(payload) AS result FROM events", "duckdb", facts).output!!.columns.single().sqlType)
    }

    @Test
    fun fieldsRemainVariantNullableComputedAndDoNotInventFieldInventory() {
        for (field in listOf("payload.Name", "e.payload.Name", "e.payload.User.Name", "payload['Name']", "(payload::VARIANT).Name", "payload[2]")) {
            val sql = "SELECT $field AS result FROM events e"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$field: ${scope.diagnostics}")
            val output = scope.output!!.columns.single()
            assertEquals("VARIANT", output.sqlType, field)
            assertEquals(ColumnOrigin.COMPUTED, output.origin, field)
            assertNull(output.provenance, field)
            assertEquals(ColumnShape("result", "VARIANT", true), SqlFragment(sql, "duckdb").outputShape(catalog).columns.single(), field)
        }
    }

    @Test
    fun qualificationDoesNotFoldFieldCaseOrShiftOneBasedVariantIndexes() {
        for (expression in listOf("payload.Name", "payload[2]", "e.payload.User.Name", "(payload::VARIANT).Name")) {
            val tree = Dialects.DUCKDB.parseOne("SELECT $expression AS value FROM events e")
            val generated = qualify(tree.copy(), dialect = Dialects.DUCKDB, schema = inputSchema).sql("duckdb")
            if ("Name" in expression) assertTrue("Name" in generated, generated)
            if ("[2]" in expression) assertTrue("[2]" in generated, generated)
        }
        val cte = "WITH q AS (SELECT CAST(payload AS VARIANT) AS p FROM events) SELECT p.Name FROM q"
        assertTrue("Name" in qualify(Dialects.DUCKDB.parseOne(cte), dialect = Dialects.DUCKDB, schema = inputSchema).sql("duckdb"))
    }

    @Test
    fun crossTargetVariantIsNotAssumedEquivalentToJsonOrAnotherVariantType() {
        for (sql in listOf("CREATE TABLE events(payload VARIANT)", "SELECT '{\"a\":1}'::JSON::VARIANT", "SELECT variant_type(payload) FROM events")) {
            for (target in listOf("doris", "trino", "postgres", "clickhouse", "bigquery")) {
                assertFailsWith<UnsupportedError>("$target $sql") { SqlFragment(sql, "duckdb").toExecutable(target) }
                assertTrue(SqlFragment(sql, "duckdb").transpileTo(target).unsupportedMessages.any { "VARIANT" in it })
            }
        }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT CAST(x AS VARIANT) FROM t", "doris").toExecutable("duckdb") }
    }
}
