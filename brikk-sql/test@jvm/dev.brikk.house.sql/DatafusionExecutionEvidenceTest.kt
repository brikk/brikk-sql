package dev.brikk.house.sql

import dev.brikk.house.sql.shape.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DatafusionExecutionEvidenceTest {
    @Test fun offeredStageLoweringsMatchExecutedSqlAndPinnedRuntimeRows() {
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("vendor/data/datafusion-spice/round10")) }
        for (dialect in listOf("datafusion", "spice")) {
            val capture = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/round10/$dialect-execution.json"))).jsonObject
            val cases = capture.getValue("observations").jsonObject.getValue("pipes").jsonArray
            assertEquals(DATAFUSION_PIPE_STAGES, cases.map { it.jsonObject.getValue("stage").jsonPrimitive.content }.toSet())
            for (value in cases) {
                val row = value.jsonObject
                val sql = row.getValue("source").jsonPrimitive.content
                val result = assertIs<PipeLowering.Ok>(lowerForExecution(sql, dialect))
                assertEquals(row.getValue("sql").jsonPrimitive.content, result.sql)
                assertTrue(result.blockers.isEmpty())
                assertEquals("accepted", row.getValue("lowered").jsonObject.getValue("status").jsonPrimitive.content, "$dialect: $sql")
            }
        }
    }
}
