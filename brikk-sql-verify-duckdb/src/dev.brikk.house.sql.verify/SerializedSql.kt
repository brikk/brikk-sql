package dev.brikk.house.sql.verify

import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What DuckDB's `json_serialize_sql` reported for one SQL text. */
internal sealed interface SerializedSql {
    data object Parsed : SerializedSql

    /**
     * [position] is a 0-based code-point offset before DuckDB 2.0. When [location] is present,
     * both its start and length are UTF-8 bytes, as reported by the 2.0 PEG parser.
     */
    data class Failed(
        val type: String?,
        val message: String?,
        val position: Int?,
        val location: String?,
        val document: String,
    ) : SerializedSql {
        fun errorIn(sql: String, message: String): VerifyError {
            val safe = dev.brikk.house.sql.dialects.Dialects.DUCKDB.diagnosticMessage(sql, message)
            val bytes = LOCATION.matchEntire(location.orEmpty())?.destructured?.let { (start, length) ->
                start.toIntOrNull()?.let { it to length.toIntOrNull() }
            }
            return when {
                bytes != null -> VerifyError.atUtf8Byte(sql, safe, bytes.first, bytes.second?.let { bytes.first + it })
                position != null -> VerifyError.atCodePoint(sql, safe, position)
                else -> VerifyError(safe)
            }
        }
    }

    private companion object {
        val LOCATION = Regex("""\[(\d+),(\d+)]""")
    }
}

/** Same result mapping for the restricted parser and an already configured helper connection. */
internal fun SerializedSql.toParseOnlyResult(sql: String): VerifyResult = when (this) {
    SerializedSql.Parsed -> VerifyResult.Accepted
    is SerializedSql.Failed -> if (type == "parser") {
        VerifyResult.Rejected(errorIn(sql, "Parser Error: ${message.orEmpty()}"))
    } else {
        VerifyResult.Unverified(
            dev.brikk.house.sql.dialects.Dialects.DUCKDB.diagnosticMessage(sql,
                "Parse-only DuckDB verification cannot check this statement: " + listOfNotNull(type, message).joinToString(": ")),
        )
    }
}

/** Null for a missing or malformed response, which is never a parser verdict. */
internal fun serializedSqlDocument(document: String?): SerializedSql? {
    if (document == null) return null
    val root = try {
        Json.parseToJsonElement(document) as? JsonObject ?: return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    fun field(name: String): String? = (root[name] as? JsonPrimitive)?.contentOrNull
    val error = root["error"] as? JsonPrimitive ?: return null
    if (error.isString) return null
    return when (error.booleanOrNull) {
        false -> if (root["statements"] is JsonArray) SerializedSql.Parsed else null
        true -> {
            val type = field("error_type") ?: return null
            val message = field("error_message") ?: return null
            SerializedSql.Failed(type, message, (root["position"] as? JsonPrimitive)?.intOrNull, field("location"), document)
        }
        null -> null
    }
}

internal fun serializeSqlQuery(sql: String): String =
    // The helper shares a user's catalog, where an unqualified macro could shadow the parser.
    "SELECT system.main.json_serialize_sql('" + sql.replace("'", "''") + "')::VARCHAR"

/**
 * Parses [sql] with `json_serialize_sql`, which never binds or runs it. Invalid SQL is a
 * [SerializedSql.Failed] document, not an exception; only SELECT statements serialize, others
 * fail with type `not implemented`. DuckDB itself extracts the document's fields.
 *
 * @throws java.sql.SQLException when the query itself fails.
 */
internal fun Connection.serializeSql(sql: String): SerializedSql {
    // json_serialize_sql requires a constant VARCHAR argument (a JDBC parameter fails with "first
    // argument must be a VARCHAR"), so the SQL is inlined as an escaped string literal.
    val literal = "'" + sql.replace("'", "''") + "'"
    val query = "SELECT d->>'error', d->>'error_type', d->>'error_message', d->>'position', " +
        "d->>'location', d::VARCHAR " +
        "FROM (SELECT json_serialize_sql($literal) AS d)"
    return createStatement().use { statement ->
        statement.executeQuery(query).use { rows ->
            check(rows.next()) { "json_serialize_sql returned no row" }
            if (rows.getString(1) != "true") {
                SerializedSql.Parsed
            } else {
                SerializedSql.Failed(
                    type = rows.getString(2),
                    message = rows.getString(3),
                    position = rows.getString(4)?.toIntOrNull(),
                    location = rows.getString(5),
                    document = rows.getString(6),
                )
            }
        }
    }
}
