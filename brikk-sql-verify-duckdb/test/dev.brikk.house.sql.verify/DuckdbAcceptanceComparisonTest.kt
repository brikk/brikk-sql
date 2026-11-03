package dev.brikk.house.sql.verify

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

internal class DuckdbAcceptanceComparisonTest {
    @Test fun fullParserGeneratorTokenAndAstInputsHaveAnIsolatedVersionComparison() {
        val oldJar=System.getenv("BRIKK_DUCKDB_OLD_JDBC")
        assumeTrue(oldJar!=null,"Set BRIKK_DUCKDB_OLD_JDBC to the trusted 1.5.6.0 fixture")
        val inputs=NativeCorpusAudit.acceptanceInputs()
        val old=NativeCorpusAudit.probe("acceptance-156",Path.of(oldJar!!),inputs,"v1.5.6","069cc9f9b5")
        val current=NativeCorpusAudit.probe("acceptance-200",NativeCorpusAudit.currentJar(),inputs,"v2.0.0","d54cbbe3df")
        val changes=inputs.distinctBy { it.sql }.filter { old.verdicts.getValue(it.sql).status!=current.verdicts.getValue(it.sql).status }
        val report=buildJsonObject {
            put("contract","raw statement parse-only; standalone expressions are not silently SELECT-wrapped; non-SELECT serialization is unverified")
            put("inputs",inputs.size);put("unique_sql",inputs.map { it.sql }.distinct().size)
            put("old",NativeCorpusAudit.metadata(old));put("current",NativeCorpusAudit.metadata(current))
            put("old_jdbc_sha256",old.jarHash);put("current_jdbc_sha256",current.jarHash)
            put("old_counts",NativeCorpusAudit.counts(inputs,old));put("current_counts",NativeCorpusAudit.counts(inputs,current))
            put("old_fingerprint",NativeCorpusAudit.fingerprint(inputs,old));put("current_fingerprint",NativeCorpusAudit.fingerprint(inputs,current))
            put("changes",buildJsonArray { for(input in changes)add(buildJsonObject {
                put("sql",input.sql);put("origins",JsonArray(inputs.filter { it.sql==input.sql }.map { JsonPrimitive(it.origin) }))
                for((name,run)in listOf("old" to old,"current" to current))put(name,buildJsonObject {
                    val v=run.verdicts.getValue(input.sql);put("status",v.status);put("type",v.type);put("message",v.message)
                })
            }) })
        }
        NativeCorpusAudit.report("acceptance-comparison.json",report)
        assertTrue((old.verdicts.values+current.verdicts.values).none { it.status=="unavailable" },"transport failures cannot satisfy the comparison")
        val baseline=NativeCorpusAudit.root.resolve("brikk-sql-verify-duckdb/testResources/acceptance-baseline.json")
        assertTrue(Files.isRegularFile(baseline),"Review build/duckdb-parser-audit/acceptance-comparison.json before creating the baseline")
        val expected=Json.parseToJsonElement(Files.readString(baseline)).jsonObject
        for(key in expected.keys)assertEquals(expected[key],report[key],"acceptance baseline $key; investigate changes, do not update automatically")
        println("DDB20-049 ${inputs.size} inputs; ${changes.size} unique changes; report build/duckdb-parser-audit/acceptance-comparison.json")
    }
}
