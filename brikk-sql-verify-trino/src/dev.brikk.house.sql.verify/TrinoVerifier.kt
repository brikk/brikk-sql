package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.ColumnUnit
import io.trino.sql.parser.ParsingException
import io.trino.sql.parser.SqlParser

/**
 * Verifies SQL against Trino's own parser (`io.trino:trino-parser`), the exact ANTLR
 * grammar the Trino engine runs. Pinned to Trino 484, matching the vendored function
 * catalog (vendor/data/trino-functions-484.tsv). The parser stops at the first error.
 *
 * Cold start is just parser class loading (milliseconds); instances are cheap and
 * thread-safe ([SqlParser] is stateless across calls). trino-parser needs a Java 25 runtime.
 */
public class TrinoVerifier : SqlVerifier {
    override val engine: String = "trino"

    private val parser = SqlParser()

    override fun verify(sql: String): VerifyResult = parse(sql) { parser.createStatement(sql) }

    override fun verifyExpression(sql: String): VerifyResult = parse(sql) { parser.createExpression(sql) }

    private inline fun parse(sql: String, block: () -> Unit): VerifyResult = try {
        block()
        VerifyResult.Accepted
    } catch (e: ParsingException) {
        val message = e.errorMessage ?: e.message ?: "syntax error"
        // Trino's code-point stream reports 1-based code-point columns.
        VerifyResult.Rejected(
            if (e.lineNumber > 0 && e.columnNumber > 0) {
                VerifyError.at(sql, message, e.lineNumber, e.columnNumber, ColumnUnit.CODE_POINT)
            } else {
                VerifyError(message)
            },
        )
    }
}
