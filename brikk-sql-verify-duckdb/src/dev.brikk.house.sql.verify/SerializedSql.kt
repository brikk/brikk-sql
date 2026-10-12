package dev.brikk.house.sql.verify

import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What DuckDB's `json_serialize_sql` reported for one SQL text. */
internal sealed interface SerializedSql {
    data object Parsed : SerializedSql

    /** [position] is DuckDB's 0-based code-point offset into the SQL. */
    data class Failed(
        val type: String?,
        val message: String?,
        val position: Int?,
        val document: String,
    ) : SerializedSql {
        fun errorIn(sql: String, message: String): VerifyError =
            position?.let { VerifyError.atCodePoint(sql, message, it) } ?: VerifyError(message)
    }
}

/**
 * Parses [sql] with `json_serialize_sql`, which never binds or runs it. Invalid SQL is a
 * [SerializedSql.Failed] document, not an exception; only SELECT statements serialize, others
 * fail with type `not implemented`.
 *
 * @throws java.sql.SQLException when the query itself fails.
 * @throws IllegalArgumentException when DuckDB's document is not a JSON object.
 */
internal fun Connection.serializeSql(sql: String): SerializedSql {
    // json_serialize_sql requires a constant VARCHAR argument (a JDBC parameter fails with "first
    // argument must be a VARCHAR"), so the SQL is inlined as an escaped string literal.
    val literal = "'" + sql.replace("'", "''") + "'"
    val document = createStatement().use { statement ->
        statement.executeQuery("SELECT json_serialize_sql($literal)").use { rows ->
            check(rows.next()) { "json_serialize_sql returned no row" }
            rows.getString(1)
        }
    }
    val json = Json.parseToJsonElement(document).jsonObject
    if (json["error"]?.jsonPrimitive?.booleanOrNull != true) return SerializedSql.Parsed
    return SerializedSql.Failed(
        type = json["error_type"]?.jsonPrimitive?.contentOrNull,
        message = json["error_message"]?.jsonPrimitive?.contentOrNull,
        position = json["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        document = document,
    )
}
