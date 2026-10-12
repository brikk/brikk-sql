package dev.brikk.house.sql.parser

import dev.brikk.house.sql.dialects.Dialects

/** Token offsets and ranges are UTF-16; lexical failure leaves the remainder opaque. */
data class EditorTokens(val tokens: List<Token>, val failureOffset: Int? = null, val error: String? = null)

data class SqlStatementRange(
    val start: Int,
    val end: Int,
    val hasSql: Boolean,
    val hasPipe: Boolean,
    val boundaryError: String? = null,
    /** The offset of the `;` token that ends the statement, or null when none does. */
    val terminator: Int? = null,
    /** The end of the statement's inline INSERT data (docs/brikk-extensions.md #56), or null without it. */
    val dataEnd: Int? = null,
)

/** Half-open ranges retain leading trivia and the terminator. Trailing trivia is its own range. */
data class SqlScriptSnapshot(val text: String, val dialect: String, val statements: List<SqlStatementRange>) {
    fun at(offset: Int): SqlStatementRange? = statements.firstOrNull { offset >= it.start && offset < it.end }
        ?: statements.lastOrNull()?.takeIf { offset == it.end }

    companion object {
        /**
         * Uses the dialect tokenizer, including its quote/comment rules. Compound Trino routines
         * retain internal semicolons. ClickHouse inline INSERT data is framed as data, not SQL
         * (docs/brikk-extensions.md #56). Lexically incomplete or unbalanced input is never split into
         * an apparently executable suffix. This proves boundaries, not SQL grammar validity.
         */
        fun parse(text: String, dialect: String): SqlScriptSnapshot = parse(text, dialect) {}

        /**
         * The statements of [text] that no continuation of it can change, for reading a long text a
         * window at a time: the text's end is not taken as the end of a line or of the text, and the
         * statement reaching it is left out. Every other range is that of any text starting with
         * [text].
         */
        fun parsePrefix(text: String, dialect: String, checkCanceled: () -> Unit): List<SqlStatementRange> =
            parse(text, dialect, checkCanceled, prefix = true).statements.filter { it.end < text.length }

        /** [parse] that calls [checkCanceled] periodically; whatever it throws propagates unchanged. */
        fun parse(text: String, dialect: String, checkCanceled: () -> Unit): SqlScriptSnapshot = parse(text, dialect, checkCanceled, prefix = false)

        private fun parse(text: String, dialect: String, checkCanceled: () -> Unit, prefix: Boolean): SqlScriptSnapshot {
            val source = Dialects.forName(dialect)
            val tokenizer = Tokenizer(source.tokenizerConfig)
            val lexed = if (prefix) tokenizer.tokenizeForEditorPrefix(text, checkCanceled) else tokenizer.tokenizeForEditor(text, checkCanceled)
            val tokens = lexed.tokens
            val ranges = ArrayList<SqlStatementRange>()
            var start = 0
            var hasSql = false
            var hasPipe = false
            var error: String? = null
            var head: String? = null
            var routine = false
            var afterEnd = false
            // The closer each open bracket expects.
            val brackets = ArrayList<TokenType>()
            val blocks = ArrayList<String>()
            var dataEnd: Int? = null
            fun flush(end: Int, terminator: Int? = null) {
                if (end > start) ranges.add(SqlStatementRange(start, end, hasSql, hasPipe, error, terminator, dataEnd))
                start = end
                dataEnd = null
                hasSql = false; hasPipe = false; error = null; head = null; routine = false; afterEnd = false
            }
            for ((index, token) in tokens.withIndex()) {
                if (index and 1023 == 1023) checkCanceled()
                val kind = token.tokenType
                val word = token.text.uppercase()
                val visibleWord = kind != TokenType.IDENTIFIER && kind != TokenType.STRING && kind != TokenType.INSERT_DATA &&
                    !kind.name.endsWith("_STRING") && token.text.firstOrNull()?.isLetter() == true
                if (head == null && visibleWord) head = word
                val previousWord = tokens.getOrNull(index - 1)?.text?.uppercase()
                val declaration = brackets.isEmpty() && head in setOf("CREATE", "WITH") &&
                    previousWord in setOf("CREATE", "REPLACE", "WITH", "TEMP", "TEMPORARY", "AGGREGATE")
                if (visibleWord && word == "FUNCTION" && declaration) routine = true
                if (head == "DELIMITER" || visibleWord && declaration && word in setOf("PROCEDURE", "TRIGGER"))
                    error = "Unsupported procedural/client delimiter boundary; select a complete supported statement"
                when (kind) {
                    TokenType.L_PAREN -> brackets.add(TokenType.R_PAREN)
                    TokenType.L_BRACE -> brackets.add(TokenType.R_BRACE)
                    TokenType.L_BRACKET -> brackets.add(
                        if (source.halfOpenPartitionRanges && tokens.getOrNull(index - 1)?.tokenType == TokenType.VALUES) {
                            TokenType.R_PAREN
                        } else TokenType.R_BRACKET,
                    )
                    TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> {
                        if (brackets.lastOrNull() == kind) brackets.removeAt(brackets.lastIndex)
                        else error = "Unbalanced SQL delimiter; statement boundary is unknown"
                    }
                    else -> {}
                }
                if (routine && visibleWord) {
                    when {
                        afterEnd && word in setOf("IF", "CASE", "WHILE", "LOOP", "REPEAT") -> afterEnd = false
                        word == "END" -> {
                            if (blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
                            else error = "Unmatched routine END"
                            afterEnd = true
                        }
                        word == "BEGIN" || word == "CASE" ||
                            blocks.isNotEmpty() && word in setOf("IF", "WHILE", "LOOP", "REPEAT") &&
                            (tokens.getOrNull(index + 1)?.tokenType != TokenType.L_PAREN ||
                                tokens.getOrNull(index - 1)?.tokenType == TokenType.SEMICOLON ||
                                tokens.getOrNull(index - 1)?.text?.uppercase() in setOf("BEGIN", "THEN", "ELSE")) -> {
                            blocks.add(word); afterEnd = false
                        }
                        else -> afterEnd = false
                    }
                }
                if (kind == TokenType.SEMICOLON && brackets.isNotEmpty() && !source.semicolonsInBrackets)
                    error = "Statement separator inside brackets; statement boundary is unknown"
                if (kind == TokenType.PIPE_GT) hasPipe = true
                if (kind == TokenType.SEMICOLON && brackets.isEmpty() && blocks.isEmpty() && error == null) {
                    flush(token.end + 1, token.start)
                } else if (kind != TokenType.SEMICOLON) hasSql = true
                if (kind == TokenType.INSERT_DATA) dataEnd = token.end + 1
                // Inline INSERT data that a blank line ends also ends its statement.
                if (kind == TokenType.INSERT_DATA && tokens.getOrNull(index + 1)?.tokenType != TokenType.SEMICOLON &&
                    brackets.isEmpty() && blocks.isEmpty() && error == null) flush(token.end + 1)
            }
            if (lexed.error != null) { error = lexed.error; hasSql = true }
            if (brackets.isNotEmpty() || blocks.isNotEmpty()) error = "Unterminated SQL construct; statement boundary is unknown"
            flush(text.length)
            return SqlScriptSnapshot(text, dialect, ranges)
        }
    }
}
