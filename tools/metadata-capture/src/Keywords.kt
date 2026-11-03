import io.trino.grammar.sql.SqlBaseParser
import io.trino.sql.ReservedIdentifiers
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.atn.ATN
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Keywords of an ANTLR grammar: every lexer token whose literal is one word, RESERVED unless
 * the parser's `nonReserved` rule can start with it.
 */
private fun antlrKeywords(atn: ATN, vocabulary: Vocabulary, ruleNames: List<String>): Dump {
    val nonReserved = atn.nextTokens(atn.ruleToStartState[ruleNames.indexOf("nonReserved")])
    val rows = (1..vocabulary.maxTokenType).mapNotNull { type ->
        val word = vocabulary.getLiteralName(type)?.removeSurrounding("'")?.takeIf(WORD::matches)
        word?.let { listOf(it, if (nonReserved.contains(type)) "UNRESERVED" else "RESERVED", vocabulary.getSymbolicName(type)) }
    }
    return Dump(KEYWORD_COLUMNS, rows.sortedBy { it[0] })
}

private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val KEYWORD_COLUMNS = listOf("word", "category", "token")

/** Writes vendor/data/trino-keywords-484.tsv from the trino-grammar on the classpath. */
fun captureTrinoKeywords(vendorData: Path) {
    val dump = antlrKeywords(SqlBaseParser._ATN, SqlBaseParser.VOCABULARY, SqlBaseParser.ruleNames.toList())
    val reserved = dump.rows.filter { it[1] == "RESERVED" }.map { it[0] }.toSet()
    check(reserved == ReservedIdentifiers.reservedIdentifiers()) {
        "nonReserved-derived reserved words disagree with ReservedIdentifiers: " +
            "${reserved - ReservedIdentifiers.reservedIdentifiers()} / ${ReservedIdentifiers.reservedIdentifiers() - reserved}"
    }
    writeDump(vendorData.resolve("trino-keywords-484.tsv"), dump)
}

/** Writes vendor/data/doris-keywords-4.1.5.tsv from the vendored Doris parser jar. */
fun captureDorisKeywords(vendorData: Path) {
    val jar = vendorData.resolveSibling("lib/doris-fe-sql-parser-4.1.5-g917b20be8387.jar")
    URLClassLoader(arrayOf(jar.toUri().toURL()), ATN::class.java.classLoader).use { loader ->
        val parser = loader.loadClass("org.apache.doris.nereids.DorisParser")
        fun <T> static(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return parser.getField(name).get(null) as T
        }
        val dump = antlrKeywords(static("_ATN"), static("VOCABULARY"), static<Array<String>>("ruleNames").toList())
        writeDump(vendorData.resolve("doris-keywords-4.1.5.tsv"), dump)
    }
}
