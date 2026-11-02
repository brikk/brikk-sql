package dev.brikk.house.sql.scope

/** Fact keys may quote components containing dots or quotes. Unquoted keys keep their stored casing. */
internal fun catalogPathParts(key: String): List<String> {
    if ('"' !in key) return key.split('.')
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var quoted = false
    var at = 0
    while (at < key.length) {
        val ch = key[at++]
        when {
            ch == '"' && quoted && key.getOrNull(at) == '"' -> { part.append('"'); at++ }
            ch == '"' -> {
                if (!quoted && part.isNotEmpty()) return key.split('.')
                quoted = !quoted
                if (!quoted && at < key.length && key[at] != '.') return key.split('.')
            }
            ch == '.' && !quoted -> { parts += part.toString(); part.clear() }
            else -> part.append(ch)
        }
    }
    if (quoted) return key.split('.')
    parts += part.toString()
    return parts
}
