package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VARCHAR and matching ASCII samples do not prove unrestricted Unicode identity. */
class TrinoDorisCaseFoldCertificationTest {
    @Test
    fun unrestrictedCaseFoldingIsRefusedInBothDirections() {
        for ((source, target) in listOf("trino" to "doris", "doris" to "trino")) {
            for (function in listOf("LOWER", "UPPER")) {
                for (sql in listOf(
                    "SELECT $function(v) FROM t",
                    "SELECT v FROM t WHERE $function(v) = 'i'",
                    "SELECT $function(v), COUNT(*) FROM t GROUP BY $function(v)",
                    "SELECT LENGTH($function(COALESCE(v, 'absent'))) FROM t",
                    "SELECT $function(CAST(v AS VARCHAR)) FROM t",
                    "SELECT $function('İ')",
                )) {
                    val report = SqlFragment(sql, source).certify(target)
                    val hazards = report.findings.filter {
                        it.kind == FindingKind.SEMANTIC_HAZARD && it.subject == function
                    }
                    assertFalse(report.ok, "$source->$target: $sql: ${report.findings}")
                    assertTrue(hazards.isNotEmpty(), "$source->$target: $sql: ${report.findings}")
                    for (hazard in hazards) {
                        assertEquals(Severity.REFUSAL, hazard.severity)
                        assertTrue(hazard.detail.startsWith("divergent:"), hazard.detail)
                        assertTrue(hazard.provenance.orEmpty().contains("https://github.com/brikk/brikk-sql/issues/1"))
                    }
                }
            }
        }
    }
}
