import dev.brikk.house.sql.dialects.Dialects
import java.io.File
import kotlinx.serialization.json.*

private val auditJson = Json { prettyPrint = true }

/** Observation exporter, not a conformance assertion or an engine execution tool. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: dialect-audit <dialect> <cases.json> <output.json>" }
    val dialect = Dialects.forName(args[0])
    val fixture = Json.parseToJsonElement(File(args[1]).readText()).jsonObject
    val cases = fixture.getValue("cases").jsonArray
    val ids = mutableSetOf<String>()
    val observations = cases.map { element ->
        val row = element.jsonObject
        val id = row.getValue("id").jsonPrimitive.content
        require(ids.add(id)) { "Duplicate case: $id" }
        val source = row.getValue("sql").jsonPrimitive.content
        buildJsonObject {
            put("id", id)
            put("sql", source)
            try {
                val statements = dialect.parse(source).filterNotNull()
                put("status", "accepted")
                put("statements", buildJsonArray {
                    for (ast in statements) add(buildJsonObject {
                        put("root", ast::class.simpleName)
                        put("nodes", buildJsonArray { ast.walk().forEach { add(it::class.simpleName) } })
                        put("ast", ast.toString())
                    })
                })
                try {
                    put("generated", statements.joinToString("; ") { dialect.generate(it, sourceDialect = args[0]) })
                } catch (e: Exception) {
                    put("generation_error", cleanError(e))
                }
            } catch (e: Exception) {
                put("status", "rejected")
                put("error_class", e.javaClass.name)
                put("message", cleanError(e))
            }
        }
    }
    val output = buildJsonObject {
        put("schema_version", 1)
        put("dialect", args[0])
        put("evidence", "local parser and generator observations only")
        put("cases", JsonArray(observations))
    }
    val file = File(args[2])
    file.parentFile?.mkdirs()
    file.writeText(auditJson.encodeToString(JsonObject.serializer(), output) + "\n")
    println("Captured ${observations.size} ${args[0]} observations to ${args[2]}")
}

private fun cleanError(error: Exception): String =
    (error.message ?: error.javaClass.name).replace(Regex("\u001B\\[[;\\d]*m"), "")
