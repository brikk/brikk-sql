import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * The vendored dump format: a header line of column names, then one line per row with
 * tab-separated values. `\N` is NULL; `\\`, `\t`, `\n` and `\r` escape the value.
 * tools/generate_duckdb_functions.py reads the same format.
 */
class Dump(val columns: List<String>, val rows: List<List<String?>>)

fun writeDump(path: Path, dump: Dump) {
    val lines = listOf(dump.columns.joinToString("\t")) +
        dump.rows.map { row -> row.joinToString("\t") { it?.escape() ?: NULL } }
    path.writeText(lines.joinToString("\n", postfix = "\n"))
    println("wrote ${dump.rows.size} rows -> $path")
}

fun readDump(path: Path): Dump {
    val lines = path.readLines()
    val columns = lines.first().split('\t')
    val rows = lines.drop(1).map { line -> line.split('\t').map { if (it == NULL) null else it.unescape() } }
    rows.forEach { require(it.size == columns.size) { "$path: ${it.size} values for ${columns.size} columns: $it" } }
    return Dump(columns, rows)
}

private const val NULL = "\\N"

private fun String.escape(): String = buildString {
    for (c in this@escape) when (c) {
        '\\' -> append("\\\\")
        '\t' -> append("\\t")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        else -> append(c)
    }
}

private fun String.unescape(): String = buildString {
    var i = 0
    while (i < this@unescape.length) {
        val c = this@unescape[i++]
        if (c != '\\') {
            append(c)
            continue
        }
        append(
            when (val e = this@unescape[i++]) {
                '\\' -> '\\'
                't' -> '\t'
                'n' -> '\n'
                'r' -> '\r'
                else -> error("bad escape \\$e in ${this@unescape}")
            },
        )
    }
}
