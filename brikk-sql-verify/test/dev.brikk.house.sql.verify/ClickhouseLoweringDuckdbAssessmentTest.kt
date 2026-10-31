package dev.brikk.house.sql.verify

import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ClickhouseLoweringDuckdbAssessmentTest {
    @Test
    fun targetNativeGrammarsAcceptCandidatesWithoutClaimingSemanticAcceptance() {
        val input = Json.parseToJsonElement(File("../vendor/data/clickhouse-semantic/lowering-cases.json").readText()).jsonObject
        for (row in input.getValue("cases").jsonArray) {
            for ((target, candidate) in row.jsonObject.getValue("targets").jsonObject) {
                val result = SqlVerifiers.forEngine(target)!!.verify(candidate.jsonPrimitive.content)
                assertIs<VerifyResult.Accepted>(result, "$target/$candidate: $result")
            }
        }
    }
    @Test
    fun executeResearchCandidatesOnTheExactEmbeddedEngineWithoutEnablingConversion() {
        val fixture = File("../vendor/data/clickhouse-semantic/lowering-cases.json").canonicalFile
        val input = Json.parseToJsonElement(fixture.readText()).jsonObject
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val jar = File(Class.forName("org.duckdb.DuckDBDriver").protectionDomain.codeSource.location.toURI())
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val version = connection.createStatement().use { it.executeQuery("SELECT version()").use { rows -> rows.next(); rows.getString(1) } }
            assertEquals("v2.0.0", version)
            val cases = input.getValue("cases").jsonArray.map { row ->
                val case = row.jsonObject
                val candidate = case.getValue("targets").jsonObject.getValue("duckdb").jsonPrimitive.content
                val query = "SELECT to_json(v) FROM ($candidate) AS probe"
                buildJsonObject {
                    put("id", case.getValue("id")); put("candidate", candidate); put("query", query)
                    try {
                        val value = connection.createStatement().use { statement -> statement.executeQuery(query).use { rows ->
                            assertTrue(rows.next()); val text = rows.getString(1); assertTrue(!rows.next()); text
                        } }
                        put("status", "value"); put("raw", value)
                        put("value", Json.parseToJsonElement(value))
                    } catch (failure: SQLException) {
                        put("status", "error"); put("message", failure.message); put("sql_state", failure.sqlState)
                    }
                }
            }
            val byId = cases.associate { it.getValue("id").jsonPrimitive.content to it }
            assertEquals(Json.parseToJsonElement("[\"AB\",\"CD\"]"), byId.getValue("CHSEM-010-E01")["value"])
            assertEquals(Json.parseToJsonElement("[\"ä!\",\"abc!\"]"), byId.getValue("CHSEM-010-E02")["value"])
            assertEquals(Json.parseToJsonElement("[\"3\",null]"), byId.getValue("CHSEM-010-E05")["value"])
            val report = buildJsonObject {
                put("engine", "duckdb"); put("version", version); put("jdbc_artifact", "org.duckdb:duckdb_jdbc:2.0.0.0")
                put("jdbc_sha256", hash(jar)); put("fixture_sha256", hash(fixture)); put("cases", JsonArray(cases))
            }
            System.getProperty("brikk.clickhouseLoweringDuckdbOut")?.let { path -> File(path).apply {
                parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
            } }
        }
    }
}
