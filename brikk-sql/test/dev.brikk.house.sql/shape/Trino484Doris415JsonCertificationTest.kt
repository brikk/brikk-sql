package dev.brikk.house.sql.shape

import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Trino484Doris415JsonCertificationTest {
    @Test
    fun dorisRenderingRetainsTheParserInsteadOfReturningTheOriginalText() {
        for (source in listOf("trino", "doris")) {
            assertEquals("SELECT JSON_PARSE(payload)", parseOne("SELECT JSON_PARSE(payload)", source).sql("doris"))
        }
        // MySQL's deliberately different mapping is not changed by the Doris override.
        assertEquals("SELECT payload", parseOne("SELECT JSON_PARSE(payload)", "doris").sql("mysql"))
    }

    @Test
    fun scalarAndJsonExtractionDoNotReceiveUnconditionalIdentity() {
        for (function in listOf("json_extract", "json_extract_scalar", "json_parse")) {
            val evidence = assertNotNull(HazardRegistry.lookup("trino", "doris", function))
            assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, evidence.verdict)
            assertTrue(evidence.provenance.contains("trino-484-doris-4.1.5-json"), evidence.provenance)
        }
        for (sql in listOf(
            "SELECT JSON_EXTRACT_SCALAR(j, '$.a') FROM t",
            "SELECT JSON_EXTRACT(j, '$.a') FROM t",
            "SELECT JSON_PARSE(j) FROM t",
        )) {
            val report = SqlFragment(sql, "trino").certify("doris")
            assertTrue(report.findings.any {
                it.kind == FindingKind.SEMANTIC_HAZARD &&
                    it.provenance?.contains("trino-484-doris-4.1.5-json") == true
            }, "$sql: ${report.findings}")
        }
    }

    @Test
    fun reverseLookupsAlsoCarryTheMeasuredWarnings() {
        for (function in listOf("json_extract", "json_unquote", "get_json_string", "json_parse")) {
            val evidence = assertNotNull(HazardRegistry.lookup("doris", "trino", function))
            assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, evidence.verdict)
            assertTrue(evidence.provenance.contains("trino-484-doris-4.1.5-json"))
        }
    }
}
