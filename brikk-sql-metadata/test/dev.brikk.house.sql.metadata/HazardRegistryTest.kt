package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HazardRegistryTest {

    // ------------------------------------------------------------ lookups

    @Test
    fun lookupWorksInBothDirections() {
        // 'lower' | 'lower' divergent (Turkish İ case-folding probe).
        val fwd = HazardRegistry.lookup("trino", "duckdb", "lower")
        assertNotNull(fwd)
        assertEquals(HazardVerdict.DIVERGENT, fwd.verdict)
        assertTrue("unicode" in fwd.areas)
        assertTrue(fwd.provenance.isNotEmpty())

        val rev = HazardRegistry.lookup("duckdb", "trino", "lower")
        assertNotNull(rev)
        assertEquals(HazardVerdict.DIVERGENT, rev.verdict)
    }

    @Test
    fun sourceAndTargetNamesAreOrientedToLookupDirection() {
        // trino->doris 'contains' maps to Doris 'array_contains' (divergent).
        val fwd = HazardRegistry.lookup("trino", "doris", "contains")
        assertNotNull(fwd)
        assertEquals(HazardVerdict.DIVERGENT, fwd.verdict)
        assertEquals("contains", fwd.sourceName)
        assertEquals("array_contains", fwd.targetName)

        // Same entry, reverse direction: names must swap (the shipped-artifact bug the
        // per-direction orientation prevents).
        val rev = HazardRegistry.lookup("doris", "trino", "array_contains")
        assertNotNull(rev)
        assertEquals("array_contains", rev.sourceName)
        assertEquals("contains", rev.targetName)
    }

    @Test
    fun directionalKeysUseTheSourceSideName() {
        // 'day_of_week / dow' (trino) | 'isodow' (duckdb): each direction keys by its
        // own side's names.
        val byTrinoName = HazardRegistry.lookup("trino", "duckdb", "day_of_week")
        assertNotNull(byTrinoName)
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, byTrinoName.verdict)
        // 'dow' alternative from the ' / ' list is keyed too.
        assertEquals(byTrinoName, HazardRegistry.lookup("trino", "duckdb", "dow"))

        val byDuckdbName = HazardRegistry.lookup("duckdb", "trino", "isodow")
        assertNotNull(byDuckdbName)
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, byDuckdbName.verdict)
        // The trino-side name is NOT a duckdb->trino key (different entry may claim it,
        // but this entry's duckdb side is only 'isodow').
        assertNull(HazardRegistry.lookup("duckdb", "trino", "day_of_week"))
    }

    @Test
    fun conceptLookupResolvesRenderedTargetNameWithoutChangingDirectionalLookup() {
        // Directional lookup remains source-side only.
        assertNull(HazardRegistry.lookup("duckdb", "doris", "arrays_overlap"))
        // Certification's concept lookup also accepts the emitted target-side spelling
        // and recovers the list_has_any -> arrays_overlap evidence entry.
        val hazard = HazardRegistry.lookupConcept(
            "duckdb", "doris", listOf("ARRAY_OVERLAPS", "ARRAYS_OVERLAP"),
        )
        assertNotNull(hazard)
        assertEquals("list_has_any", hazard.sourceName)
        assertEquals("arrays_overlap", hazard.targetName)
        assertEquals(HazardVerdict.IDENTICAL, hazard.verdict)
        assertTrue(
            SemanticCoverage.coversConcept(
                "duckdb", "doris", listOf("ARRAY_OVERLAPS", "ARRAYS_OVERLAP")
            )
        )
    }

    @Test
    fun lookupIsCaseInsensitiveOnDialectsAndNames() {
        assertEquals(
            HazardRegistry.lookup("trino", "duckdb", "lower"),
            HazardRegistry.lookup("Trino", "DUCKDB", "LOWER"),
        )
    }

    @Test
    fun unknownPairsAndNamesReturnNull() {
        // mysql<->doris has no hazards file; same-dialect pairs never do.
        assertNull(HazardRegistry.lookup("mysql", "doris", "lower"))
        assertNull(HazardRegistry.lookup("duckdb", "duckdb", "lower"))
        assertNull(HazardRegistry.lookup("trino", "duckdb", "definitely_not_a_function"))
    }

    @Test
    fun constructEntriesStayRetrievableByRawString() {
        // Non-function constructs never match parsed function names but stay
        // addressable verbatim.
        val cast = HazardRegistry.lookup("trino", "duckdb", "CAST (primitive)")
        assertNotNull(cast)
        assertEquals(HazardVerdict.IDENTICAL, cast.verdict)
        val distinctFrom = HazardRegistry.lookup("trino", "duckdb", "IS [NOT] DISTINCT FROM")
        assertNotNull(distinctFrom)
    }

    @Test
    fun collisionsResolveToTheWorstVerdict() {
        // Trino 'concat' has both a divergent argument-coercion entry and an identical
        // '||'-mapping entry — the registry must keep the conservative one.
        assertEquals(
            HazardVerdict.DIVERGENT,
            HazardRegistry.lookup("trino", "duckdb", "concat")?.verdict,
        )
        // 'hour': identical extract entry vs conditionally-equivalent WTZ-session-zone
        // entry — conditional wins over identical.
        assertEquals(
            HazardVerdict.CONDITIONALLY_EQUIVALENT,
            HazardRegistry.lookup("trino", "duckdb", "hour")?.verdict,
        )
    }

    // ------------------------------------------------------- generated data structure

    private data class PairRegistry(
        val source: String,
        val target: String,
        val forwardEntries: List<FunctionHazard>,
        val reverseEntries: List<FunctionHazard>,
        val forward: Map<String, FunctionHazard>,
        val reverse: Map<String, FunctionHazard>,
    )

    private val registries = listOf(
        PairRegistry("trino", "duckdb", TRINO_TO_DUCKDB_HAZARD_ENTRIES, DUCKDB_TO_TRINO_HAZARD_ENTRIES,
            TRINO_TO_DUCKDB_HAZARDS, DUCKDB_TO_TRINO_HAZARDS),
        PairRegistry("duckdb", "doris", DUCKDB_TO_DORIS_HAZARD_ENTRIES, DORIS_TO_DUCKDB_HAZARD_ENTRIES,
            DUCKDB_TO_DORIS_HAZARDS, DORIS_TO_DUCKDB_HAZARDS),
        PairRegistry("trino", "doris", TRINO_TO_DORIS_HAZARD_ENTRIES, DORIS_TO_TRINO_HAZARD_ENTRIES,
            TRINO_TO_DORIS_HAZARDS, DORIS_TO_TRINO_HAZARDS),
        PairRegistry("duckdb", "clickhouse", DUCKDB_TO_CLICKHOUSE_HAZARD_ENTRIES, CLICKHOUSE_TO_DUCKDB_HAZARD_ENTRIES,
            DUCKDB_TO_CLICKHOUSE_HAZARDS, CLICKHOUSE_TO_DUCKDB_HAZARDS),
        PairRegistry("trino", "clickhouse", TRINO_TO_CLICKHOUSE_HAZARD_ENTRIES, CLICKHOUSE_TO_TRINO_HAZARD_ENTRIES,
            TRINO_TO_CLICKHOUSE_HAZARDS, CLICKHOUSE_TO_TRINO_HAZARDS),
        PairRegistry("doris", "clickhouse", DORIS_TO_CLICKHOUSE_HAZARD_ENTRIES, CLICKHOUSE_TO_DORIS_HAZARD_ENTRIES,
            DORIS_TO_CLICKHOUSE_HAZARDS, CLICKHOUSE_TO_DORIS_HAZARDS),
        PairRegistry("doris", "starrocks", DORIS_TO_STARROCKS_HAZARD_ENTRIES, STARROCKS_TO_DORIS_HAZARD_ENTRIES,
            DORIS_TO_STARROCKS_HAZARDS, STARROCKS_TO_DORIS_HAZARDS),
    )

    private fun verdictRank(verdict: HazardVerdict): Int = when (verdict) {
        HazardVerdict.DIVERGENT -> 0
        HazardVerdict.UNCLEAR -> 1
        HazardVerdict.CONDITIONALLY_EQUIVALENT -> 2
        HazardVerdict.NO_EQUIVALENT -> 3
        HazardVerdict.IDENTICAL -> 4
    }

    private fun expectedKeys(entries: List<FunctionHazard>): Map<String, FunctionHazard> = buildMap {
        val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
        for (entry in entries) {
            val name = assertNotNull(entry.sourceName)
            val keys = buildSet {
                add(name.trim().uppercase())
                for (alternative in name.split('/')) {
                    val bare = alternative.trim().removeSuffix("()")
                    if (identifier.matches(bare)) add(bare.uppercase())
                }
            }
            for (key in keys) {
                val previous = get(key)
                if (previous == null || verdictRank(entry.verdict) < verdictRank(previous.verdict)) {
                    put(key, entry)
                }
            }
        }
    }

    @Test
    fun generatedEntriesAndKeysAreConsistentInBothDirections() {
        for (registry in registries) {
            val label = "${registry.source}->${registry.target}"
            assertTrue(registry.forwardEntries.isNotEmpty(), "$label has no probe evidence")
            for (entry in registry.forwardEntries) {
                val sourceName = assertNotNull(entry.sourceName, "$label: missing source field")
                val targetName = assertNotNull(entry.targetName, "$label: missing target field")
                assertTrue(sourceName.isNotBlank() || targetName.isNotBlank(), "$label: both names are blank")
                // NO_EQUIVALENT evidence may deliberately leave the unavailable side blank.
                if (sourceName.isBlank() || targetName.isBlank()) {
                    assertEquals(HazardVerdict.NO_EQUIVALENT, entry.verdict, "$label: blank equivalent name")
                }
                assertTrue(entry.provenance.isNotBlank(), "$label: missing provenance")
            }
            assertEquals(
                registry.forwardEntries.map { it.copy(sourceName = it.targetName, targetName = it.sourceName) },
                registry.reverseEntries,
                "$label reverse entries must preserve evidence and swap names in JSON order",
            )
            assertEquals(expectedKeys(registry.forwardEntries), registry.forward, "$label key/alias/collision drift")
            assertEquals(expectedKeys(registry.reverseEntries), registry.reverse, "$label reverse key/alias/collision drift")
            for ((key, entry) in registry.forward) {
                assertEquals(entry, HazardRegistry.lookup(registry.source, registry.target, key), "$label: $key")
            }
            for ((key, entry) in registry.reverse) {
                assertEquals(entry, HazardRegistry.lookup(registry.target, registry.source, key), "$label reverse: $key")
            }
        }
    }

    @Test
    fun dorisPairsArePopulatedByLiveProbes() {
        // Populated by the doris live-probe program (REPORT-doris-differential-probe-
        // 2026-07-13); structure and exact JSON content are checked separately.
        assertTrue(DUCKDB_TO_DORIS_HAZARDS.isNotEmpty())
        assertTrue(DORIS_TO_DUCKDB_HAZARDS.isNotEmpty())
        assertTrue(TRINO_TO_DORIS_HAZARDS.isNotEmpty())
        assertTrue(DORIS_TO_TRINO_HAZARDS.isNotEmpty())
        // Live-probed verdict spot checks: unicode case folding diverges.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "doris", "lower")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("trino", "doris", "trim")?.verdict)
    }

    @Test
    fun clickhousePairsArePopulatedByLiveProbes() {
        // Populated by the clickhouse differential-probe program (REPORT-clickhouse-
        // differential-probe-2026-07-13; ClickHouse 26.5.1.1 via chdb vs DuckDB 1.5.4);
        // structure and exact JSON content are checked separately.
        assertTrue(DUCKDB_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_DUCKDB_HAZARDS.isNotEmpty())
        assertTrue(TRINO_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_TRINO_HAZARDS.isNotEmpty())
        // Live-probed verdict spot checks:
        // ClickHouse length() counts bytes; lower() is ASCII-only.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "length")?.verdict)
        // duckdb round reconciled to identical: source-aware half-away shim (2026-07-14).
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "round")?.verdict)
        // ClickHouse regexp_replace = replace-all, matching Trino (differs from DuckDB).
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "regexp_replace")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("trino", "clickhouse", "date_format")?.verdict)
    }

    @Test
    fun dorisClickhousePairIsPopulatedByLiveProbes() {
        // Populated by the doris<->clickhouse live differential probe (Doris FE pr62767-local
        // / BE 4.1.2 vs ClickHouse 26.5.1.1 via chdb; docs/research/probe-runs/doris-clickhouse.*).
        assertTrue(DORIS_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_DORIS_HAZARDS.isNotEmpty())
        // Live-probed spot checks: both round half-away vs banker's; both single-arg log is
        // NATURAL (identical — resolves what the conservative composition marked divergent).
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "round")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "dayofweek")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "log")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("clickhouse", "doris", "length")?.verdict)
    }

}
