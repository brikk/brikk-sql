// Run against built core/metadata outputs plus kotlinx-serialization-json/core on the classpath.
// Captures observations, not assertions or claims of native semantic equivalence.
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import kotlinx.serialization.json.*
import java.io.File

val fixtures = Json.parseToJsonElement(File(args[0]).readText()).jsonObject
val cases = fixtures["cases"]!!.jsonArray + fixtures["execution"]!!.jsonArray
val observations = cases.map { element ->
    val row = element.jsonObject
    val query = (row["brikk_sql"] ?: row["sql"]!!).jsonPrimitive.content
    val source = row["brikk_dialect"]?.jsonPrimitive?.content ?: "clickhouse"
    buildJsonObject {
        put("id", row["id"]!!)
        try {
            val ast = parseOne(query, source)
            put("root", ast::class.simpleName)
            put("ast", ast.toString())
            val generated = when {
                row["lower_pipes"]?.jsonPrimitive?.booleanOrNull == true ->
                    dev.brikk.house.sql.shape.SqlFragment(query, source).toExecutable("clickhouse").sql
                source != "clickhouse" -> dev.brikk.house.sql.shape.SqlFragment(query, source).transpileTo("clickhouse").sql
                else -> ast.sql("clickhouse")
            }
            put("generated", generated)
        } catch (e: Exception) {
            put("error", e.message)
        }
    }
}
println(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(observations)))
