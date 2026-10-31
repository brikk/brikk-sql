package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

/** Candidates are research SQL, not an enabled lowering path or a certification ledger. */
class ClickhouseLoweringAssessmentTest {
    @Test
    fun candidateLambdasRoundTripButProductionConversionStillRefusesOriginals() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/lowering-cases.json").readText()).jsonObject
        for (row in fixture.getValue("cases").jsonArray) {
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val source = case.getValue("source").jsonPrimitive.content
            val ast = parseOne(source, "clickhouse")
            val before = Serde.dump(ast)
            assertNotNull(ast.find<ClickhouseComposition>(), id)
            val explicit = parseOne(case.getValue("explicit").jsonPrimitive.content, "clickhouse")
            assertTrue(explicit.findAll<Lambda>().any(), id)
            assertNull(explicit.find<ClickhouseComposition>(), id)
            assertEquals(explicit, Serde.load(Serde.dump(explicit)), id)
            assertEquals(explicit, parseOne(explicit.sql("clickhouse"), "clickhouse"), id)
            for ((target, candidate) in case.getValue("targets").jsonObject) {
                assertFailsWith<UnsupportedError>("$id/$target") { SqlFragment(source, "clickhouse").toExecutable(target) }
                val parsed = parseOne(candidate.jsonPrimitive.content, target)
                assertNull(parsed.find<ClickhouseComposition>(), "$id/$target")
                assertNull(parsed.find<ClickhouseImplicitLambda>(), "$id/$target")
                assertEquals(parsed, Serde.load(Serde.dump(parsed)), "$id/$target")
            }
            assertEquals(before, Serde.dump(ast), id)
        }
    }

    @Test
    fun invalidUnresolvedCapturedAndVolatileFormsDoNotGainAutomaticLowering() {
        for (sql in listOf("SELECT arrayMap(trim | upper,['x'])", "SELECT arrayMap(my_udf | upper,['x'])",
            "SELECT arrayMap(rand64(_) | CONCAT(_1,_1),[1])",
            "SELECT arrayMap(CONCAT(_1,t.s) | upper,t.a) FROM t",
            "SELECT arrayMap(arrayMap(lower | upper,_1),[['x']])")) {
            for (target in listOf("duckdb", "trino", "doris"))
                assertFailsWith<UnsupportedError>("$sql/$target") { SqlFragment(sql, "clickhouse").toExecutable(target) }
        }
    }
}
