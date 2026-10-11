package dev.brikk.house.sql.verify

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

internal fun runCatchupEngineSql(container: String, client: List<String>, sql: String): String {
    val output = File.createTempFile("catchup-engine-", ".log", File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    try {
        val process = ProcessBuilder(listOf("docker", "exec", container) + client + sql)
            .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Catch-up engine probe timed out")
        }
        val text = output.readText().trimEnd('\r', '\n')
        assertEquals(0, process.exitValue(), "$sql\n$text")
        return text
    } finally { output.delete() }
}
