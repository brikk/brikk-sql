package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.optimizer.normalizeIdentifiers
import dev.brikk.house.sql.optimizer.Resolver
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class Trino484Test {
    private fun roundTrip(source: String): Expression {
        val parsed = parseOne(source, "trino")
        val rendered = parsed.sql("trino")
        assertEquals(source, rendered)
        assertEquals(rendered, Serde.loadExpression(Serde.dump(parsed)).sql("trino"))
        assertEquals(Serde.dump(parsed), Serde.dump(parseOne(rendered, "trino")))
        return parsed
    }

    @Test
    fun jsonConstructorsAndSerialization() {
        for (source in listOf(
            "SELECT JSON('[1, 2]')",
            "SELECT JSON(payload FORMAT JSON ENCODING UTF16)",
            "SELECT JSON(JSON_PARSE('{}'))",
            "SELECT JSON_SERIALIZE(payload)",
            "SELECT JSON_SERIALIZE(payload FORMAT JSON ENCODING UTF8 NULL ON ERROR)",
            "SELECT JSON_SERIALIZE(payload RETURNING VARCHAR(20) ERROR ON ERROR)",
            "SELECT JSON_SERIALIZE(payload RETURNING VARBINARY FORMAT JSON ENCODING UTF32)",
            "SELECT JSON_SCALAR(1.20)",
            "SELECT JSON_ARRAY(1, 1.20 RETURNING JSON)",
            "SELECT JSON_ARRAY(j FORMAT JSON ENCODING UTF8 NULL ON NULL RETURNING VARBINARY FORMAT JSON ENCODING UTF16)",
            "SELECT JSON_EXISTS(j FORMAT JSON, 'lax $.n' TRUE ON ERROR)",
            "SELECT JSON_VALUE(j, 'lax ${'$'}n' PASSING 1.20 AS n RETURNING DECIMAL(10, 2))",
            "SELECT JSON_QUERY(j, 'lax $.n' RETURNING JSON WITH ARRAY WRAPPER)",
        )) roundTrip(source)
    }

    @Test
    fun jsonNamedFunctionsAreNotConstructors() {
        for (source in listOf("SELECT schema.json(x)", "SELECT json()", "SELECT json(x, y)", "SELECT json(DISTINCT x)")) {
            val parsed = parseOne(source, "trino")
            assertEquals(0, parsed.findAll(TrinoJsonConstructor::class).count(), source)
            assertNotNull(parsed.find(Anonymous::class), source)
            assertEquals(parsed.sql("trino"), parseOne(parsed.sql("trino"), "trino").sql("trino"))
        }
    }

    @Test
    fun jsonTypesAndReservedName() {
        val typed = annotateTypes(parseOne(
            "SELECT JSON('{}'), JSON_SCALAR(1), JSON_SERIALIZE(j), JSON_SERIALIZE(j RETURNING VARBINARY), " +
                "JSON_VALUE(j, 'lax $.x' RETURNING DECIMAL(10, 2))", "trino"), dialect = "trino")
        assertEquals(DType.JSON, typed.find(TrinoJsonConstructor::class)!!.type!!.thisArg)
        assertEquals(DType.JSON, typed.find(TrinoJsonScalar::class)!!.type!!.thisArg)
        assertEquals(listOf(DType.VARCHAR, DType.VARBINARY), typed.findAll(TrinoJsonSerialize::class).map { it.type!!.thisArg }.toList())
        assertEquals(DType.DECIMAL, typed.find(JSONValue::class)!!.type!!.thisArg)
        assertEquals("SELECT \"json_serialize\" FROM t", parseOne("SELECT json_serialize FROM t", "trino").sql("trino"))
        roundTrip("SELECT exclude, others FROM t")
        assertFailsWith<dev.brikk.house.sql.generator.UnsupportedError> {
            parseOne("SELECT JSON_SERIALIZE(j NULL ON ERROR)", "trino").sql("duckdb")
        }
        val passing = "SELECT JSON_VALUE(j, 'lax ${'$'}Some' PASSING 1 AS \"Some\" RETURNING INTEGER)"
        assertEquals(passing, normalizeIdentifiers(parseOne(passing, "trino"), Dialects.TRINO).sql("trino"))
    }

    @Test
    fun intervalQualifiersAndDatetimeSubtraction() {
        for (source in listOf(
            "SELECT INTERVAL '340' DAY(3)",
            "SELECT INTERVAL '1.123456789012' SECOND(13, 12)",
            "SELECT INTERVAL '1 01:02:03.123456789012' DAY(9) TO SECOND(12)",
            "SELECT INTERVAL '2-3' YEAR(4) TO MONTH",
            "SELECT CAST(x AS INTERVAL DAY(9) TO SECOND(12))",
            "SELECT CAST(x AS INTERVAL SECOND(13, 12))",
            "SELECT CAST(x AS INTERVAL YEAR(p) TO MONTH)",
            "SELECT (t1 - t2) DAY TO SECOND(6)",
            "SELECT (t1 - t2) HOUR(10) TO MINUTE",
            "SELECT (t1 - t2) SECOND(13, 12)",
            "SELECT (t1 - t2) HOUR AS elapsed",
            "SELECT CAST((t1 - t2) HOUR AS VARCHAR)",
            "CREATE TABLE spans (elapsed INTERVAL DAY(9) TO SECOND(12))",
        )) roundTrip(source)
        // A bare field after a parenthesized expression is an implicit projection alias.
        assertEquals("SELECT (t1 - t2) AS DAY", parseOne("SELECT (t1 - t2) DAY", "trino").sql("trino"))
    }

    @Test
    fun windowFrameExclusion() {
        for (kind in listOf("ROWS", "RANGE", "GROUPS")) {
            for (exclude in listOf("CURRENT ROW", "GROUP", "TIES", "NO OTHERS")) {
                val source = "SELECT SUM(x) OVER (ORDER BY k $kind BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE $exclude) FROM t"
                roundTrip(source)
                val generator = Dialects.TRINO.generator()
                generator.generate(parseOne(source, "trino"))
                assertEquals(emptyList(), generator.unsupportedMessages)
            }
        }
    }

    @Test
    fun viewsAndMaterializedViewProcedures() {
        for (source in listOf(
            "CREATE VIEW IF NOT EXISTS v AS SELECT 1",
            "COMMENT ON MATERIALIZED VIEW v IS 'description'",
            "COMMENT ON MATERIALIZED VIEW v IS NULL",
            "ALTER MATERIALIZED VIEW v EXECUTE optimize",
            "ALTER MATERIALIZED VIEW v EXECUTE optimize()",
            "ALTER MATERIALIZED VIEW v EXECUTE optimize(1)",
            "ALTER MATERIALIZED VIEW v EXECUTE optimize(file_size_threshold => '10MB') WHERE x = 1",
        )) roundTrip(source)
        assertIs<TrinoMaterializedViewExecute>(parseOne("ALTER MATERIALIZED VIEW v EXECUTE optimize", "trino"))
    }

    @Test
    fun userNamesPreserveCaseDuringNormalization() {
        for (source in listOf(
            "GRANT SELECT ON TABLE t TO Alice",
            "GRANT SELECT ON TABLE t TO USER Alice",
            "REVOKE SELECT ON TABLE t FROM USER Alice",
            "ALTER TABLE t SET AUTHORIZATION USER Alice",
            "ALTER VIEW v SET AUTHORIZATION Alice",
            "ALTER MATERIALIZED VIEW v SET AUTHORIZATION USER Alice",
            "ALTER SCHEMA s SET AUTHORIZATION USER Alice",
            "CREATE SCHEMA s AUTHORIZATION USER Alice",
            "CREATE SCHEMA IF NOT EXISTS s AUTHORIZATION Alice WITH (location='file:///tmp/s')",
        )) {
            val parsed = roundTrip(source)
            assertEquals(source, normalizeIdentifiers(parsed, Dialects.TRINO).sql("trino"))
        }
        for (source in listOf("GRANT SELECT ON TABLE t TO ROLE Admin", "CREATE SCHEMA s AUTHORIZATION ROLE Admin")) {
            assertTrue(normalizeIdentifiers(parseOne(source, "trino"), Dialects.TRINO).sql("trino").endsWith("ROLE admin"))
        }
    }

    @Test
    fun nearestRelationHasItsOwnScope() {
        for (source in listOf(
            "SELECT * FROM NEAREST (FROM items MATCH distance < 10)",
            "SELECT n.id FROM NEAREST (FROM items AS i WHERE i.active MATCH i.distance < 10) AS n",
            "SELECT n.id FROM NEAREST (FROM items AS i JOIN tags AS t ON i.id = t.id MATCH i.distance < 10) AS n",
        )) {
            val parsed = roundTrip(source)
            val root = assertNotNull(buildScope(parsed))
            val nearest = assertNotNull(root.udtfScopes.singleOrNull())
            assertIs<TrinoNearest>(nearest.expression)
            assertTrue(nearest.sources.isNotEmpty())
            assertEquals(0, root.columns.count { it.table == "i" }, "Inner columns must not leak into the outer scope")
        }
        roundTrip("SELECT nearest FROM nearest")
        val scope = buildScope(parseOne("SELECT n.id FROM NEAREST (FROM items MATCH distance < 10) AS n", "trino"))!!
        val resolver = Resolver(scope, MappingSchema(mapOf("items" to mapOf("id" to "INT", "distance" to "DOUBLE")), dialect = Dialects.TRINO))
        assertEquals(listOf("id", "distance"), resolver.getSourceColumns("n"))
        val typed = annotateTypes(parseOne("SELECT n.id FROM NEAREST (FROM items MATCH distance < 10) AS n", "trino"),
            schema = mapOf("items" to mapOf("id" to "INT", "distance" to "DOUBLE")), dialect = "trino")
        assertEquals(DType.INT, (typed.expressionsArg.single() as Column).type!!.thisArg)
        val correlated = buildScope(parseOne(
            "SELECT trades.id, quotes.price FROM trades CROSS JOIN NEAREST (FROM quotes WHERE quotes.symbol = trades.symbol MATCH quotes.ts <= trades.ts)",
            "trino"))!!
        assertEquals(setOf("trades", "quotes"), correlated.selectedSources.keys)
        assertTrue(correlated.udtfScopes.single().canBeCorrelated)
        assertTrue(correlated.udtfScopes.single().externalColumns.any { it.table == "trades" })
    }
}
