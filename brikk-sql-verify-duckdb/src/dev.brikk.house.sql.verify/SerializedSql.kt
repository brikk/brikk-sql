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
    ) : SerializedSql
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

/** The error at DuckDB's code-point [position] in [sql], unpositioned when it lies outside. */
internal fun errorAtCodePoint(sql: String, message: String, position: Int?): VerifyError {
    if (position == null || position < 0 || position > sql.codePointCount(0, sql.length)) return VerifyError(message)
    return VerifyError.atOffset(sql, message, sql.offsetByCodePoints(0, position))
}
