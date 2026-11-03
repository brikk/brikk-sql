package dev.brikk.house.sql.verify

internal const val MAX_DUCKDB_PARSE_CHARACTERS: Int = 1_048_576
internal const val MAX_DUCKDB_PARSE_NESTING: Int = 256
internal const val MAX_DUCKDB_PARSE_RESPONSE_CHARACTERS: Int = 8_388_608

/** Bound JSON nesting before the recursive JSON decoder sees an untrusted transport response. */
internal fun duckdbParserResponseWithinBudget(document: String): Boolean {
    if (document.length > MAX_DUCKDB_PARSE_RESPONSE_CHARACTERS) return false
    var depth = 0
    var quoted = false
    var escaped = false
    for (ch in document) {
        if (quoted) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
        } else when (ch) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > MAX_DUCKDB_PARSE_NESTING) return false
            '}', ']' -> depth--
        }
    }
    return true
}

/** A conservative editor budget, not grammar validation or a connection setting change. */
internal fun duckdbParseInputIssue(sql: String, checkCanceled: () -> Unit = {}): String? {
    if (sql.length > MAX_DUCKDB_PARSE_CHARACTERS) return "DuckDB parse-check input exceeds the 1048576 UTF-16 character limit."
    var nextCheck = 0
    fun poll(position: Int) {
        if (position >= nextCheck) { checkCanceled(); nextCheck = position + 1024 }
    }
    var unicode = 0
    while (unicode < sql.length) {
        poll(unicode)
        val ch = sql[unicode]
        if (ch == '\u0000') return "DuckDB cannot parse-check SQL that contains a NUL character."
        if (ch.isHighSurrogate()) {
            if (unicode + 1 == sql.length || !sql[unicode + 1].isLowSurrogate()) return "DuckDB cannot parse-check malformed UTF-16 input."
            unicode += 2
        } else {
            if (ch.isLowSurrogate()) return "DuckDB cannot parse-check malformed UTF-16 input."
            unicode++
        }
    }
    var index = 0
    var depth = 0
    nextCheck = 0
    while (index < sql.length) {
        poll(index)
        val ch = sql[index]
        when {
            sql.startsWith("--", index) -> {
                while (index < sql.length && sql[index] != '\n' && sql[index] != '\r') {
                    poll(index)
                    index++
                }
            }
            sql.startsWith("/*", index) -> {
                var comments = 1
                index += 2
                while (index < sql.length && comments > 0) {
                    poll(index)
                    when {
                        sql.startsWith("/*", index) -> { comments++; index += 2 }
                        sql.startsWith("*/", index) -> { comments--; index += 2 }
                        else -> index++
                    }
                    if (comments > MAX_DUCKDB_PARSE_NESTING) return "DuckDB parse-check input exceeds the 256-level nesting limit."
                }
            }
            ch == '\'' || ch == '"' || ch == '`' -> {
                val escaped = ch == '\'' && index > 0 && sql[index - 1] in "eE" &&
                    (index == 1 || !(sql[index - 2].isLetterOrDigit() || sql[index - 2] == '_'))
                index++
                while (index < sql.length) {
                    poll(index)
                    if (escaped && sql[index] == '\\') { index = minOf(index + 2, sql.length); continue }
                    if (sql[index++] == ch) {
                        if (index < sql.length && sql[index] == ch) index++ else break
                    }
                }
            }
            ch == '$' -> {
                var end = index + 1
                if (end < sql.length && (sql[end].isLetter() || sql[end] == '_')) {
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
                }
                if (end < sql.length && sql[end] == '$') {
                    val delimiter = sql.substring(index, end + 1)
                    val close = sql.indexOf(delimiter, end + 1)
                    index = if (close < 0) sql.length else close + delimiter.length
                } else index++
            }
            ch in "([{" -> {
                depth++
                if (depth > MAX_DUCKDB_PARSE_NESTING) return "DuckDB parse-check input exceeds the 256-level nesting limit."
                index++
            }
            ch in ")]}" -> { depth = (depth - 1).coerceAtLeast(0); index++ }
            else -> index++
        }
    }
    return null
}

internal fun SerializedSql.Failed.isResourceLimit(): Boolean =
    type in setOf("out of memory", "interrupt") || listOf("expression depth", "parser depth", "stack limit", "memory limit")
        .any { message.orEmpty().contains(it, ignoreCase = true) }
