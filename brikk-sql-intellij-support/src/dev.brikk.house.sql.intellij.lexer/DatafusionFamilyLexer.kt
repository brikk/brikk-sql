package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.dialect.DatafusionIdeProfile

/** Small token differences; literals, comments and their authored ranges stay with the PG lexer. */
public class DatafusionFamilyLexer(private val profile: DatafusionIdeProfile) : MaskingSqlLexer(PgLexer()) {
    override fun rule(base: Lexer, upper: String): Boolean {
        when {
            dollarString(base) -> return true
            profile == DatafusionIdeProfile.DATAFUSION && upper == "^" -> emitAs(base, SqlTokens.PG_OP_BITWISE_XOR)
            lastMeaningful in setOf("(", ",") && upper.firstOrNull()?.isLetter() == true && followedByNamedArrow(base) ->
                emitAs(base, SqlTokens.SQL_IDENT)
            upper == "QUALIFY" -> emitAs(base, SqlTokens.SQL_KEYWORD_TOKEN)
            else -> return false
        }
        return true
    }

    /** The formatter must not insert whitespace between a dollar string's delimiters and body. */
    private fun dollarString(base: Lexer): Boolean {
        val delimiter = base.tokenText
        if (!DOLLAR_TAG.matches(delimiter)) return false
        val close = base.bufferSequence.indexOf(delimiter, base.tokenEnd)
        if (close < 0 || close + delimiter.length > base.bufferEnd) return false
        val end = close + delimiter.length
        while (base.tokenType != null && base.tokenStart < end) base.advance()
        span(base, SqlTokens.SQL_STRING_TOKEN, "LITERAL")
        return true
    }

    /** Keywords such as `limit` can label native arguments, including across comments. */
    private fun followedByNamedArrow(base: Lexer): Boolean {
        val position = base.currentPosition
        return try {
            base.advance()
            while (base.tokenType != null && (base.tokenText.isBlank() || base.tokenType in SqlTokens.COMMENT_TOKENS)) base.advance()
            base.tokenText == "=>"
        } finally {
            base.restore(position)
        }
    }

    private companion object {
        val DOLLAR_TAG = Regex("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$")
    }
}
