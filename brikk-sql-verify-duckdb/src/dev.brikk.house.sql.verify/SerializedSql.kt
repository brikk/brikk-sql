package dev.brikk.house.sql.verify

import java.sql.Connection

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
 * fail with type `not implemented`. DuckDB itself extracts the document's fields.
 *
 * @throws java.sql.SQLException when the query itself fails.
 */
internal fun Connection.serializeSql(sql: String): SerializedSql {
    // json_serialize_sql requires a constant VARCHAR argument (a JDBC parameter fails with "first
    // argument must be a VARCHAR"), so the SQL is inlined as an escaped string literal.
    val literal = "'" + sql.replace("'", "''") + "'"
    val query = "SELECT d->>'error', d->>'error_type', d->>'error_message', d->>'position', d::VARCHAR " +
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
                    document = rows.getString(5),
                )
            }
        }
    }
}
