package dev.brikk.house.sql.shape

import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import dev.brikk.house.sql.metadata.PairCoverage
import dev.brikk.house.sql.metadata.SemanticCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuckdbDorisCastCoverageTest {
    @Test
    fun postfixAndExplicitCastReceiveConditionalEvidenceInsteadOfUnknownCoverage() {
        for (expression in listOf("o_orderkey::BIGINT", "CAST(o_orderkey AS BIGINT)")) {
            val report = SqlFragment("SELECT $expression FROM lake.s.orders", "duckdb").certify("doris")
            assertEquals("SELECT CAST(o_orderkey AS BIGINT) FROM lake.s.orders", report.result.sql)
            assertTrue(report.ok, report.findings.toString())
            val finding = report.findings.single()
            assertEquals(FindingKind.SEMANTIC_HAZARD, finding.kind)
            assertEquals(Severity.WARNING, finding.severity)
            assertEquals("CAST", finding.subject)
            assertTrue(finding.detail.startsWith("conditionally-equivalent:"), finding.detail)
            assertTrue("enable_strict_cast" in finding.detail, finding.detail)
            assertTrue("numeric" in finding.areas, finding.areas.toString())
            assertNotNull(finding.provenance)
        }
    }

    @Test
    fun unsafeCastInputsAreNotSilentlyCertifiedAsIdentical() {
        for (sql in listOf(
            "SELECT CAST(3.5 AS BIGINT)",
            "SELECT CAST(-1.7 AS BIGINT)",
            "SELECT CAST('1e2' AS BIGINT)",
            "SELECT CAST('abc' AS BIGINT)",
            "SELECT CAST(128 AS TINYINT)",
            "SELECT CAST(TRUE AS VARCHAR)",
            "SELECT CAST(CAST('2024-01-02' AS DATE) AS BIGINT)",
        )) {
            val report = SqlFragment(sql, "duckdb").certify("doris")
            assertTrue(report.findings.none { it.kind == FindingKind.SEMANTIC_COVERAGE_UNKNOWN }, "$sql: ${report.findings}")
            val hazard = report.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD && it.subject == "CAST" }
            assertEquals(Severity.WARNING, hazard.severity, sql)
            assertTrue(hazard.detail.startsWith("conditionally-equivalent:"), hazard.detail)
        }
    }

    @Test
    fun castRegistrationDoesNotCompleteThePairOrCoverOtherPairsAndConcepts() {
        assertEquals(PairCoverage.PARTIAL, SemanticCoverage.coverage("duckdb", "doris"))
        assertTrue(SemanticCoverage.coversConcept("duckdb", "doris", listOf("CAST")))
        assertTrue(!SemanticCoverage.coversConcept("duckdb", "doris", listOf("UNRESEARCHED_CONCEPT")))
        assertTrue(!SemanticCoverage.coversConcept("duckdb", "clickhouse", listOf("CAST")))
        val hazard = assertNotNull(HazardRegistry.lookup("duckdb", "doris", "CAST"))
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, hazard.verdict)
        assertEquals(hazard, HazardRegistry.lookupConcept("duckdb", "doris", listOf("CAST")))
        // The evidence compares both engines; reverse lookup must retain its caveats.
        assertEquals(hazard, HazardRegistry.lookup("doris", "duckdb", "CAST"))
    }

    @Test
    fun researchedCastDoesNotClearAnUnresearchedCallInTheSameQuery() {
        val report = SqlFragment("SELECT CAST(o_orderkey AS BIGINT), unresearched_concept(x) FROM t", "duckdb")
            .certify("doris")
        assertTrue(!report.ok)
        assertTrue(report.findings.any { it.kind == FindingKind.SEMANTIC_HAZARD && it.subject == "CAST" })
        assertTrue(report.findings.any {
            it.kind == FindingKind.SEMANTIC_COVERAGE_UNKNOWN && it.subject == "UNRESEARCHED_CONCEPT"
        })
        assertFailsWith<TranspileCertificationError> { report.orThrow() }
    }

    @Test
    fun tryCastIsNotCoveredAsAnAliasAndConditionalWarningKeepsExistingStrictPolicy() {
        val tryCast = SqlFragment("SELECT TRY_CAST('abc' AS BIGINT)", "duckdb").certify("doris")
        assertTrue(tryCast.findings.any {
            it.kind == FindingKind.SEMANTIC_COVERAGE_UNKNOWN && it.subject == "TRY_CAST"
        }, tryCast.findings.toString())
        val cast = SqlFragment("SELECT o_orderkey::BIGINT FROM lake.s.orders", "duckdb").certify("doris")
        // Existing API policy accepts warnings; the finding remains for consumer review.
        assertEquals(cast.result, cast.orThrow())
        assertTrue(cast.findings.isNotEmpty())
    }
}
