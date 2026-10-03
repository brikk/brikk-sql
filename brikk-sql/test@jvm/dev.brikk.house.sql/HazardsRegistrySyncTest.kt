package dev.brikk.house.sql

import dev.brikk.house.sql.metadata.FunctionHazard
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the shipped hazard registry (brikk-sql-metadata Generated…Hazards.kt) against
 * its sources of truth under testResources/semantics/ (the pair hazards JSON files):
 * tools/generate_hazards_registry.py is byte-deterministic, and this test fails when
 * the JSON changes without a regeneration (or vice versa).
 *
 * Checks all pair files, both orientations, aliases, full evidence fields and
 * worst-verdict/first-in-JSON collision resolution without hardcoded inventory counts.
 */
class HazardsRegistrySyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadPairs(path: String, source: String, target: String): List<FunctionHazard> {
        val root = json.parseToJsonElement(testResource(path)).jsonObject
        return root.getValue("pairs").jsonArray.map { entry ->
            val obj = entry.jsonObject
            FunctionHazard(
                sourceName = obj.getValue(source).jsonPrimitive.content,
                targetName = obj.getValue(target).jsonPrimitive.content,
                verdict = HazardVerdict.valueOf(
                    obj.getValue("verdict").jsonPrimitive.content.uppercase().replace('-', '_')
                ),
                hazard = obj["hazard"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                areas = obj["areas"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                provenance = obj.getValue("provenance").jsonPrimitive.content,
            )
        }
    }

    /** Worst-first severity rank (must match tools/generate_hazards_registry.py). */
    private val rank = mapOf(
        HazardVerdict.DIVERGENT to 0,
        HazardVerdict.UNCLEAR to 1,
        HazardVerdict.CONDITIONALLY_EQUIVALENT to 2,
        HazardVerdict.NO_EQUIVALENT to 3,
        HazardVerdict.IDENTICAL to 4,
    )

    @Test
    fun everyJsonLookupMatchesOrientedEvidenceAndCollisionPolicy() {
        val files = File(testResourcesRoot(), "semantics")
            .listFiles { file -> file.name.endsWith("-hazards.json") }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no hazard evidence files found")
        for (file in files) {
            val dialects = file.name.removeSuffix("-hazards.json").split('-')
            assertTrue(dialects.size == 2 && dialects.all { it.isNotBlank() }, "bad file: ${file.name}")
            for ((source, target) in listOf(dialects[0] to dialects[1], dialects[1] to dialects[0])) {
                val pairs = loadPairs("semantics/${file.name}", source, target)
                assertTrue(pairs.isNotEmpty(), "${file.name} has no live-probe evidence")
                val expected = linkedMapOf<String, FunctionHazard>()
                for (pair in pairs) {
                    val sourceName = assertNotNull(pair.sourceName, "${file.name}: missing source field")
                    val targetName = assertNotNull(pair.targetName, "${file.name}: missing target field")
                    assertTrue(sourceName.isNotBlank() || targetName.isNotBlank(), "${file.name}: both names are blank")
                    if (sourceName.isBlank() || targetName.isBlank()) {
                        assertEquals(HazardVerdict.NO_EQUIVALENT, pair.verdict, "${file.name}: blank equivalent name")
                    }
                    assertTrue(pair.provenance.isNotBlank(), "${file.name}: missing provenance")
                    for (key in nameKeys(sourceName)) {
                        val previous = expected[key]
                        if (previous == null || rank.getValue(pair.verdict) < rank.getValue(previous.verdict)) {
                            expected[key] = pair
                        }
                    }
                }
                for ((key, evidence) in expected) {
                    assertEquals(evidence, HazardRegistry.lookup(source, target, key), "$source->$target: $key")
                }
            }
        }
    }

    private fun nameKeys(name: String): Set<String> = buildSet {
        add(name.trim().uppercase())
        for (alternative in name.split('/')) {
            val bare = alternative.trim().removeSuffix("()")
            if (Regex("[A-Za-z_][A-Za-z_0-9]*").matches(bare)) add(bare.uppercase())
        }
    }

    @Test
    fun dorisJsonsCarryLiveProbePairs() {
        // Exact evidence and registry structure are checked without inventory pins.
        assertNotNull(HazardRegistry.lookup("duckdb", "doris", "lower"))
        assertNotNull(HazardRegistry.lookup("trino", "doris", "ascii"))
    }

    @Test
    fun clickhouseJsonsCarryLiveProbePairs() {
        assertNotNull(HazardRegistry.lookup("duckdb", "clickhouse", "length"))
        assertNotNull(HazardRegistry.lookup("trino", "clickhouse", "date_format"))
    }

    @Test
    fun dorisClickhouseJsonCarriesLiveProbePairs() {
        // The last core pair, filled by the doris<->clickhouse live differential probe.
        assertNotNull(HazardRegistry.lookup("doris", "clickhouse", "round"))
        assertNotNull(HazardRegistry.lookup("clickhouse", "doris", "length"))
    }

    @Test
    fun starrocksDorisJsonCarriesLiveProbePairs() {
        // StarRocks 4.1.4 <-> Doris 4.1.3 behavior-matrix probe (common MySQL lineage).
        assertNotNull(HazardRegistry.lookup("starrocks", "doris", "abs"))
        assertNotNull(HazardRegistry.lookup("doris", "starrocks", "abs"))
        // element_at: positive index agrees, negative index diverges (StarRocks NULL vs
        // Doris wrap-around) — a probe-verified CONDITIONALLY_EQUIVALENT verdict.
        val elemAt = HazardRegistry.lookup("starrocks", "doris", "element_at")
        assertNotNull(elemAt)
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, elemAt.verdict)
        // date_add returns DATETIME on StarRocks vs DATE on Doris — DIVERGENT.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("starrocks", "doris", "date_add")!!.verdict)
    }

}
