package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.ColumnUnit
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Verifies SQL against Apache Doris's own FE parser (the Nereids ANTLR grammar) from the
 * `fe-sql-parser` jar (vendored as vendor/lib/doris-fe-sql-parser-*.jar; see vendor/README.md).
 *
 * Doris does not publish `fe-sql-parser` to a Maven repository, so this library cannot link it
 * at compile time. [create] links it at run time instead: from the caller's classpath (a plugin
 * that bundles the jar beside this library) or from an explicit jar path. It never searches the
 * file system or reads system properties. The ANTLR runtime comes from this library's own class
 * loader, so only the Doris classes come from the jar.
 *
 * Cold start is class loading plus ANTLR grammar initialization on the first parse (tens of
 * milliseconds). One instance is safe to share across threads: every call builds a fresh lexer
 * and parser.
 */
public class DorisVerifier private constructor(private val linked: LinkedParser) : SqlVerifier {
    override val engine: String = "doris"

    /** Verifies one statement; Doris stops at the first syntax error. */
    override fun verify(sql: String): VerifyResult = parse(linked.parseStatement, sql)

    override fun verifyExpression(sql: String): VerifyResult = parse(linked.parseExpression, sql)

    private fun parse(method: Method, sql: String): VerifyResult = try {
        method.invoke(linked.instance, sql)
        VerifyResult.Accepted
    } catch (e: InvocationTargetException) {
        val cause = e.targetException
        if (cause !is Exception) throw cause
        val message = cause.message?.trim()
        VerifyResult.Rejected(
            errorAt(sql, if (isParseException(cause)) message.orEmpty() else "${cause::class.java.simpleName}: $message"),
        )
    }

    /** True when [cause] is (a subclass of) Doris's ParseException, checked by name across class loaders. */
    private fun isParseException(cause: Throwable): Boolean =
        generateSequence<Class<*>>(cause.javaClass) { it.superclass }.any { it.name == PARSE_EXCEPTION_CLASS }

    /** Doris appends `(line N, pos P)` to parse errors, with `pos` a 0-based code-point column. */
    private fun errorAt(sql: String, message: String): VerifyError {
        val match = POSITION.find(message) ?: return VerifyError(message)
        val (line, column) = match.destructured
        return VerifyError.at(sql, message, line.toInt(), column.toInt(), ColumnUnit.CODE_POINT, firstColumn = 0)
    }

    /**
     * Verifies [script], one or more `;`-separated statements, and reports every syntax error
     * the grammar recovers past, in source order, each spanning its offending token. This is the
     * grammar alone: unlike [verify] it skips the SLL fast path, and a Doris post-processing
     * check that fails ends the scan with its error.
     *
     * [checkCanceled] runs before every grammar rule; whatever it throws propagates, so an
     * editor can cancel a long parse.
     */
    public fun verifyScript(script: String, checkCanceled: () -> Unit = {}): VerifyResult {
        checkCanceled()
        val errors = mutableListOf<VerifyError>()
        val collector = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?,
            ) {
                val message = msg ?: "syntax error"
                val token = offendingSymbol as? Token
                errors += if (token == null) {
                    VerifyError.at(script, message, line, charPositionInLine, ColumnUnit.CODE_POINT, firstColumn = 0)
                } else {
                    tokenError(script, message, token)
                }
            }
        }
        try {
            // Lexing happens inside newParser, so the lexer's listener must be in place first.
            val lexer = linked.newLexer.invoke(linked.instance, script) as Lexer
            lexer.removeErrorListeners()
            lexer.addErrorListener(collector)
            val parser = linked.newParser.invoke(linked.instance, lexer) as Parser
            parser.removeErrorListeners()
            parser.addErrorListener(collector)
            parser.addParseListener(CancellationListener(checkCanceled))
            linked.multiStatements.invoke(parser)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (!isParseException(cause)) throw cause
            errors += errorAt(script, cause.message?.trim().orEmpty())
        }
        return if (errors.isEmpty()) VerifyResult.Accepted else VerifyResult.Rejected(errors)
    }

    /** ANTLR token indices count code points; an EOF token has an empty span. */
    private fun tokenError(script: String, message: String, token: Token): VerifyError {
        val codePoints = script.codePointCount(0, script.length)
        fun utf16(index: Int): Int? = index.takeIf { it in 0..codePoints }?.let { script.offsetByCodePoints(0, it) }
        val start = utf16(token.startIndex) ?: return VerifyError(message)
        val end = utf16(token.stopIndex + 1)?.takeIf { it > start }
        return VerifyError.atOffset(script, message, start, end)
    }

    private class CancellationListener(private val checkCanceled: () -> Unit) : ParseTreeListener {
        override fun enterEveryRule(ctx: ParserRuleContext) = checkCanceled()
        override fun exitEveryRule(ctx: ParserRuleContext) = Unit
        override fun visitTerminal(node: TerminalNode) = Unit
        override fun visitErrorNode(node: ErrorNode) = Unit
    }

    private class LinkedParser(parserClass: Class<*>) {
        val instance: Any = parserClass.getDeclaredConstructor().newInstance()
        val parseStatement: Method = parserClass.getMethod("parseStatement", String::class.java)
        val parseExpression: Method = parserClass.getMethod("parseExpression", String::class.java)
        val newLexer: Method = parserClass.getMethod("newLexer", String::class.java)
        val newParser: Method = parserClass.getMethod("newParser", newLexer.returnType)
        val multiStatements: Method = newParser.returnType.getMethod("multiStatements")
    }

    public companion object {
        private const val PARSER_CLASS = "org.apache.doris.sqlparser.DorisSqlParser"
        private const val PARSE_EXCEPTION_CLASS = "org.apache.doris.nereids.exceptions.ParseException"
        private val POSITION = Regex("""line\s+(\d+),\s*pos\s+(\d+)""")
        private val JAR_CLASSES = ConcurrentHashMap<Path, Class<*>>()

        /**
         * Links the Doris parser from [parserJar], or when it is null from the class loader of
         * this library (where a caller bundling `fe-sql-parser` puts it).
         *
         * @throws IllegalStateException when the parser cannot be loaded; the message says why.
         */
        public fun create(parserJar: Path? = null): DorisVerifier {
            val source = parserJar?.let { "'$it'" } ?: "the classpath"
            return try {
                val parserClass = if (parserJar == null) {
                    Class.forName(PARSER_CLASS, false, DorisVerifier::class.java.classLoader)
                } else {
                    check(Files.isRegularFile(parserJar)) { "Doris parser jar '$parserJar' is not a regular file" }
                    loadFromJar(parserJar)
                }
                DorisVerifier(LinkedParser(parserClass))
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Doris parser could not be loaded from $source (${e.describe()})", e)
            } catch (e: IOException) {
                throw IllegalStateException("Doris parser could not be loaded from $source (${e.describe()})", e)
            } catch (e: LinkageError) {
                throw IllegalStateException("Doris parser could not be loaded from $source (${e.describe()})", e)
            }
        }

        private fun Throwable.describe(): String {
            val cause = (this as? InvocationTargetException)?.targetException ?: this
            return "${cause::class.java.simpleName}: ${cause.message ?: "no detail"}"
        }

        private fun loadFromJar(jar: Path): Class<*> {
            val real = jar.toRealPath()
            JAR_CLASSES[real]?.let { return it }
            synchronized(JAR_CLASSES) {
                JAR_CLASSES[real]?.let { return it }
                val loader = URLClassLoader(arrayOf(real.toUri().toURL()), DorisVerifier::class.java.classLoader)
                try {
                    return Class.forName(PARSER_CLASS, false, loader).also { JAR_CLASSES[real] = it }
                } catch (error: Throwable) {
                    try {
                        loader.close()
                    } catch (closeError: IOException) {
                        error.addSuppressed(closeError)
                    }
                    throw error
                }
            }
        }
    }
}
