package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DatafusionResetEvidenceTest {
    @Test fun resetRoundTripsPreserveNativeNamesAndRejectMalformedStatements() {
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("vendor/data/datafusion-spice/reset")) }
        val dir = root.resolve("vendor/data/datafusion-spice/reset")
        val cases = Json.parseToJsonElement(Files.readString(dir.resolve("cases.json"))).jsonObject.getValue("cases").jsonArray
        val capture = Json.parseToJsonElement(Files.readString(dir.resolve("native.json"))).jsonObject
            .getValue("profiles").jsonObject.getValue("datafusion-55.2.0").jsonObject.getValue("cases").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val dialect = Dialects.DATAFUSION
        val exported = Json.parseToJsonElement(Files.readString(dir.resolve("brikk.json"))).jsonObject.getValue("cases").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        for (value in cases) {
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val row = capture.getValue(id)
            val original = row.getValue("original").jsonObject.getValue("parse").jsonObject
            if (case.getValue("area").jsonPrimitive.content == "negative") {
                assertEquals("rejected", original.getValue("status").jsonPrimitive.content, id)
                assertTrue(runCatching { dialect.parse(sql) }.isFailure, id)
            } else {
                val generated = row.getValue("generated").jsonObject.getValue("parse").jsonObject
                assertEquals("accepted", original.getValue("status").jsonPrimitive.content, id)
                assertEquals("accepted", generated.getValue("status").jsonPrimitive.content, id)
                fun resets(result: JsonObject) = result.getValue("statements").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("kind").jsonPrimitive.content == "Reset" }.map { it.getValue("ast_debug") }
                assertEquals(resets(original), resets(generated), id)
                val text = dialect.parse(sql).filterNotNull().joinToString("; ") { dialect.generate(it, sourceDialect = "datafusion") }
                assertEquals(exported.getValue(id).getValue("generated").jsonPrimitive.content, text, id)
            }
        }
    }
}
