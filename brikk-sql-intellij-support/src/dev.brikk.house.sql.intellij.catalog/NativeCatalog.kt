package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.scope.*
import kotlinx.serialization.json.*

/** JDBC product identity is distinct from SELECT version(), which Spice delegates to DataFusion. */
public data class CatalogServerInfo(val product: String?, val version: String?)

/** Optional row-source capability. Failures propagate rather than certifying an incomplete harvest. */
public interface CatalogServerInfoSource {
    public fun serverInfo(): CatalogServerInfo?
}

/** Native Arrow type text. Null means unavailable, not absent. */
public data class NativeCatalogColumn(
    val name: String, val ordinal: Int, val type: String?, val nullable: Nullability,
    val defaultExpression: String? = null, val comment: String? = null,
)

public data class NativeCatalogRelation(
    val path: List<String>, val kind: String, val columns: List<NativeCatalogColumn>,
    val definition: String? = null, val comment: String? = null,
    val constraints: List<String>? = null,
)

public data class NativeCatalog(
    val product: String,
    val engineBuild: String,
    val settings: Map<String, String?>,
    val namespaces: List<List<String>>,
    val relations: List<NativeCatalogRelation>,
    val revision: String,
    val format: Int = 1,
) {
    /** Only use a namespace read from this console, never a helper's defaults. */
    public fun relationFacts(sessionNamespace: List<String> = emptyList()): RelationFacts = RelationFacts(
        tables = relations.associate { relation ->
            quotedCatalogPath(relation.path) to RelationFact(
                relation.columns.map { column -> ColumnFact(column.name, column.type, null, column.nullable,
                    ColumnOrigin.PHYSICAL_TABLE, quotedCatalogPath(relation.path + column.name)) },
                if (relation.columns.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL,
                quotedCatalogPath(relation.path),
            )
        },
        searchPath = if (sessionNamespace.isEmpty()) emptyList() else listOf(quotedCatalogPath(sessionNamespace)),
    )
}

public fun quotedCatalogPath(parts: List<String>): String = parts.joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" }

internal fun NativeCatalog.encoded(): String = buildJsonObject {
    put("product", product); put("engineBuild", engineBuild); put("revision", revision); put("format", format)
    put("settings", buildJsonObject { settings.forEach { (k, v) -> put(k, v) } })
    put("namespaces", JsonArray(namespaces.map { path -> JsonArray(path.map(::JsonPrimitive)) }))
    put("relations", JsonArray(relations.map { r -> buildJsonObject {
        put("path", JsonArray(r.path.map(::JsonPrimitive))); put("kind", r.kind)
        put("definition", r.definition); put("comment", r.comment)
        put("constraints", r.constraints?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        put("columns", JsonArray(r.columns.map { c -> buildJsonObject {
            put("name", c.name); put("ordinal", c.ordinal); put("type", c.type); put("nullable", c.nullable.name)
            put("default", c.defaultExpression); put("comment", c.comment)
        } }))
    } }))
}.toString()

internal fun decodeNativeCatalog(text: String): NativeCatalog {
    fun JsonObject.str(key: String): String? = getValue(key).jsonPrimitive.contentOrNull
    fun JsonElement.names(): List<String> = jsonArray.map { it.jsonPrimitive.content }
    val root = Json.parseToJsonElement(text).jsonObject
    require(root.getValue("format").jsonPrimitive.int == 1)
    return NativeCatalog(requireNotNull(root.str("product")), requireNotNull(root.str("engineBuild")),
        root.getValue("settings").jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull },
        root.getValue("namespaces").jsonArray.map { it.names() },
        root.getValue("relations").jsonArray.map { value ->
            val r = value.jsonObject
            NativeCatalogRelation(r.getValue("path").names(), requireNotNull(r.str("kind")),
                r.getValue("columns").jsonArray.map { value -> val c = value.jsonObject
                    NativeCatalogColumn(requireNotNull(c.str("name")), c.getValue("ordinal").jsonPrimitive.int, c.str("type"),
                        Nullability.valueOf(requireNotNull(c.str("nullable"))), c.str("default"), c.str("comment"))
                }, r.str("definition"), r.str("comment"), r.getValue("constraints").takeUnless { it == JsonNull }?.names())
        }, requireNotNull(root.str("revision")))
}
