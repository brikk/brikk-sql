package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlGeneratedParserUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlElementTypes
import org.jetbrains.annotations.ApiStatus

/** Tokens a statement scan reads at most; every scan below is bounded by it. */
@ApiStatus.Experimental
public const val MAX_LOOKAHEAD: Int = 512

/**
 * Scans the statement from the current token to the next `;` or [limit] tokens, and returns the
 * first non-null [match] of a token's text and the previous token's text. Never consumes.
 */
@ApiStatus.Experimental
public fun <T : Any> PsiBuilder.scanStatement(limit: Int = MAX_LOOKAHEAD, match: (previous: String?, text: String) -> T?): T? {
    val marker = mark()
    try {
        var previous: String? = null
        var scanned = 0
        while (!eof() && scanned++ < limit) {
            val text = tokenText ?: break
            if (text == ";") break
            match(previous, text)?.let { return it }
            previous = text
            advanceLexer()
        }
        return null
    } finally {
        marker.rollbackTo()
    }
}

/** The upper-cased [index]th letter-led token of the statement from here, or null. */
@ApiStatus.Experimental
public fun PsiBuilder.wordAt(index: Int): String? {
    var seen = 0
    return scanStatement { _, text ->
        text.takeIf { it.firstOrNull()?.isLetter() == true }?.let { if (seen++ == index) it.uppercase() else null }
    }
}

/** Whether a token of the statement is one of the upper-case [words]. */
@ApiStatus.Experimental
public fun PsiBuilder.containsAny(vararg words: String): Boolean =
    scanStatement { _, text -> text.uppercase().takeIf { it in words } } != null

/** The first of the upper-case [words] the statement contains, or null. */
@ApiStatus.Experimental
public fun PsiBuilder.firstOf(vararg words: String): String? =
    scanStatement { _, text -> text.uppercase().takeIf { it in words } }

/** Whether the statement has the word [word] directly followed by the word [next], ignoring case. */
@ApiStatus.Experimental
public fun PsiBuilder.wordThenWord(word: String, next: String): Boolean =
    scanStatement { previous, text -> true.takeIf { previous.equals(word, true) && text.equals(next, true) } } != null

/** Whether the statement has the word [word] directly followed by exactly the token [token]. */
@ApiStatus.Experimental
public fun PsiBuilder.wordThenToken(word: String, token: String): Boolean =
    scanStatement { previous, text -> true.takeIf { previous.equals(word, true) && text == token } } != null

/** Advances one token, parsing a platform user parameter so the console still prompts for it. */
@ApiStatus.Experimental
public fun PsiBuilder.advanceLenient() {
    if (SqlGeneratedParserUtil.isExternalParameterFirst(tokenType) && SqlGeneratedParserUtil.parseUserParameter(this, false, true)) return
    advanceLexer()
}

/** Advances up to the next `;`, keeping user parameters. */
@ApiStatus.Experimental
public fun PsiBuilder.consumeLenient() {
    while (!eof() && tokenText != ";") advanceLenient()
}

/** Wraps the tokens up to the next `;` in one [type] node; statement boundaries stay right without a grammar. */
@ApiStatus.Experimental
public fun PsiBuilder.parseLenientStatement(type: IElementType = SqlCompositeElementTypes.SQL_STATEMENT): Boolean {
    val marker = mark()
    consumeLenient()
    marker.done(type)
    return true
}

/** Parses `:name`, a colon directly followed by an identifier, as a parameter reference. */
@ApiStatus.Experimental
public fun PsiBuilder.parseColonParameter(): Boolean {
    if (tokenText != ":") return false
    val marker = mark()
    val start = currentOffset
    advanceLexer()
    if (currentOffset == start + 1 && tokenText?.matches(PARAMETER_NAME) == true) {
        advanceLexer()
        marker.done(SqlElementTypes.SQL_PARAMETER_REFERENCE)
        return true
    }
    marker.rollbackTo()
    return false
}

private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z_0-9]*")
