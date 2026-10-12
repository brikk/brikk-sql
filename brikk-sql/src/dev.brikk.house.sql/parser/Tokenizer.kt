package dev.brikk.house.sql.parser

/**
 * Hand-written, single-pass, table-driven SQL tokenizer.
 *
 * sqlglot: tokenizer_core.TokenizerCore — a faithful port. Method-level provenance
 * comments reference the Python counterparts (pinned to v30.12.0-44-g93d16591).
 *
 * Not thread-safe; create one instance per thread or per use (construction is cheap,
 * the config tables are shared immutable objects).
 */
class Tokenizer(private val config: TokenizerConfig = TokenizerConfig.BASE) {

    private var sql: String = ""
    private var size: Int = 0
    private var tokens: MutableList<Token> = mutableListOf()
    private var start: Int = 0
    private var current: Int = 0
    private var line: Int = 1
    private var col: Int = 0
    private var comments: MutableList<String> = mutableListOf()
    private var char: Char = NONE
    private var end: Boolean = false
    private var peek: Char = NONE
    private var prevTokenLine: Int = -1
    private var editorMode: Boolean = false
    private var textEnds: Boolean = true
    private var checkCanceled: () -> Unit = {}
    private var cancelBudget: Int = CANCEL_INTERVAL
    private var breaksScanned: Int = 0
    private var lastBreak: Int = -1
    private val insertData = InsertDataState()

    // sqlglot: TokenizerCore.reset
    private fun reset() {
        sql = ""
        size = 0
        tokens = mutableListOf()
        start = 0
        current = 0
        line = 1
        col = 0
        comments = mutableListOf()
        char = NONE
        end = false
        peek = NONE
        prevTokenLine = -1
        cancelBudget = CANCEL_INTERVAL
        breaksScanned = 0
        lastBreak = -1
        insertData.reset()
    }

    /**
     * Returns the list of tokens corresponding to [sql].
     *
     * sqlglot: TokenizerCore.tokenize
     */
    fun tokenize(sql: String): List<Token> = try {
        scanAll(sql)
    } catch (e: Canceled) {
        throw e.signal
    }

    private fun scanAll(sql: String): List<Token> {
        reset()
        this.sql = sql
        this.size = sql.length

        try {
            scan()
        } catch (e: TokenError) {
            throw e
        } catch (e: Canceled) {
            throw e
        } catch (e: Exception) {
            val ctxStart = maxOf(current - 50, 0)
            val ctxEnd = minOf(current + 50, size - 1).coerceAtLeast(ctxStart)
            val context = this.sql.substring(ctxStart, ctxEnd)
            throw TokenError("Error tokenizing '$context'", e)
        }

        return tokens
    }

    /**
     * [tokenizeForEditor] that calls [checkCanceled] periodically; whatever it throws (for
     * example an IDE cancellation) propagates unchanged.
     */
    fun tokenizeForEditor(sql: String, checkCanceled: () -> Unit): EditorTokens {
        val previous = this.checkCanceled
        this.checkCanceled = checkCanceled
        return try { tokenizeForEditor(sql) } finally { this.checkCanceled = previous }
    }

    /**
     * [tokenizeForEditor] of [sql] as the start of a longer text: the end of [sql] is not a line end,
     * so no rule decides from it what the rest of the text could change (docs/brikk-extensions.md #55).
     */
    fun tokenizeForEditorPrefix(sql: String, checkCanceled: () -> Unit): EditorTokens {
        textEnds = false
        return try { tokenizeForEditor(sql, checkCanceled) } finally { textEnds = true }
    }

    /** Editor recovery stops at the failing lexical construct, never inside its tail. */
    fun tokenizeForEditor(sql: String): EditorTokens {
        val previous = editorMode
        editorMode = true
        return try {
            EditorTokens(scanAll(sql).toList())
        } catch (e: TokenError) {
            EditorTokens(tokens.toList(), start.coerceIn(0, sql.length), e.message ?: "Incomplete SQL token")
        } catch (e: Canceled) {
            throw e.signal
        } finally { editorMode = previous }
    }

    // sqlglot: TokenizerCore._scan
    private fun scan(checkSemicolon: Boolean = false) {
        val identifiers = config.identifiers

        while (size != 0 && !end) {
            var cur = current

            // Skip spaces here rather than iteratively calling advance() for performance reasons;
            // a long run is skipped in chunks, so cancellation is checked inside it.
            while (cur < size && cur - current < CANCEL_INTERVAL) {
                val c = sql[cur]
                if (c == ' ' || c == '\t') cur++ else break
            }

            val offset = if (cur > current) cur - current else 1

            start = cur
            advance(offset)

            if (char != NONE && !char.isWhitespace()) {
                when {
                    char in DIGIT_CHARS -> scanNumber()
                    identifiers.containsKey(char) -> scanIdentifier(identifiers.getValue(char))
                    config.untaggedDollarStrings && char == '$' && peek == '$' -> if (!scanDollarString()) scanKeywords()
                    else -> scanKeywords()
                }
                if (editorMode && config.inlineInsertData) scanInsertData()
            }

            if (checkSemicolon && peek == ';') break
        }

        if (tokens.isNotEmpty() && comments.isNotEmpty()) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
        }
    }

    /**
     * brikk extension #55: follows each statement's tokens and reads the inline data of an `INSERT`
     * as one INSERT_DATA token once its header ends: right after the name of a depth-0
     * `FORMAT <name>` (not `Values`), or after a `SETTINGS` clause on that name's line. There is no
     * inline data after `INFILE` or `VALUES`, nor after a `SELECT` or `WITH` unless the query reads
     * it through the `input(...)` table function.
     */
    private fun scanInsertData() {
        val state = insertData
        while (state.observed < tokens.size) {
            val token = tokens[state.observed++]
            val type = token.tokenType
            when (type) {
                TokenType.SEMICOLON -> if (state.depth == 0) { state.reset(state.observed); continue }
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> state.depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> state.depth--
                else -> {}
            }
            val word = token.text.takeIf { type != TokenType.IDENTIFIER && it.firstOrNull()?.isLetter() == true }?.uppercase()
            if (state.head == null) state.head = word ?: ""
            val previous = state.previous
            state.previous = word
            if (state.head != "INSERT" || state.noData) continue
            if (state.settings) {
                if (settingsEnd(state, type) && state.observed == tokens.size) scanInsertDataAfter(current)
                continue
            }
            if (type == TokenType.L_PAREN && previous == "INPUT") state.input = true
            if (state.depth != 0) continue
            when {
                state.afterFormat -> {
                    state.afterFormat = false
                    if (word == "VALUES" || word == null && type != TokenType.IDENTIFIER || state.query && !state.input) {
                        state.noData = true
                    } else if (state.observed == tokens.size) {
                        if (wordAt(skipSpaces(current), "SETTINGS")) state.settings = true else scanInsertDataAfter(current)
                    }
                }
                word in INSERT_WITHOUT_DATA -> state.noData = true
                word == "SELECT" || word == "WITH" -> state.query = true
                word == "FORMAT" -> state.afterFormat = true
            }
        }
    }

    /**
     * Follows `SETTINGS name = value, ...` after a format name; true once a value ends the clause,
     * that is, when no `,` follows it. A value may be signed or a bracketed literal.
     */
    private fun settingsEnd(state: InsertDataState, type: TokenType): Boolean {
        if (!state.settingValue) {
            if (type == TokenType.EQ) state.settingValue = true
            return false
        }
        when {
            state.depth > 0 -> return false
            type == TokenType.DASH || type == TokenType.PLUS -> return false
        }
        state.settingValue = false
        var next = current
        while (next < size && sql[next].isWhitespace()) next++
        return next >= size || sql[next] != ','
    }

    private fun wordAt(offset: Int, word: String): Boolean =
        sql.regionMatches(offset, word, 0, word.length, ignoreCase = true) &&
            sql.getOrNull(offset + word.length)?.let { it.isLetterOrDigit() || it == '_' } != true

    /**
     * Reads inline data from [from], the end of the INSERT's header. Data starts after the spaces
     * that follow the header and, when a line break follows them, after it, as ClickHouse's parser
     * starts it; a `;` ending the header's line instead means there is no data. Data ends before a
     * blank line, before a line holding only `;` (with spaces or tabs), or at the real end of the
     * text; any other `;` is data. Data that a blank line ends at once is empty.
     */
    private fun scanInsertDataAfter(from: Int) {
        var dataStart = from
        while (dataStart < size && isSpace(sql[dataStart])) dataStart++
        if (dataStart >= size || sql[dataStart] == ';' && isLineEnd(skipSpaces(dataStart + 1))) return
        if (isLineBreak(sql[dataStart])) dataStart = lineEnd(dataStart)
        if (dataStart < size && isLoneSemicolonLine(dataStart)) return
        var dataEnd = dataStart
        if (!isEmptyLine(dataStart)) {
            var lineStart = dataStart
            while (true) {
                var i = lineStart
                while (i < size && !isLineBreak(sql[i])) i++
                dataEnd = i
                if (i >= size) break
                val next = lineEnd(i)
                if (next >= size) {
                    dataEnd = size
                    break
                }
                if (isEmptyLine(next) || isLoneSemicolonLine(next)) break
                lineStart = next
            }
        }
        while (current < dataStart) advance()
        start = dataStart
        while (current < dataEnd) advance()
        add(TokenType.INSERT_DATA)
        // The data ends the statement, at a `;` line or a blank line: the next one starts afresh.
        insertData.reset(tokens.size)
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u000C'

    private fun skipSpaces(from: Int): Int {
        var offset = from
        while (offset < size && isSpace(sql[offset])) offset++
        return offset
    }

    /** Whether a line ends at [offset]; the end of a prefix is not known to be one. */
    private fun isLineEnd(offset: Int): Boolean = if (offset >= size) textEnds else isLineBreak(sql[offset])

    private fun isEmptyLine(offset: Int): Boolean = offset < size && isLineBreak(sql[offset])

    /** Whether the line at [offset] holds only `;`, with spaces or tabs. */
    private fun isLoneSemicolonLine(offset: Int): Boolean {
        val at = skipSpaces(offset)
        return at < size && sql[at] == ';' && isLineEnd(skipSpaces(at + 1))
    }

    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == '\r'

    /** The offset after the line break at [offset], CRLF and LFCR counting as one. */
    private fun lineEnd(offset: Int): Int {
        val next = offset + 1
        return if (next < size && isLineBreak(sql[next]) && sql[next] != sql[offset]) next + 1 else next
    }

    /** Statement context for [scanInsertData]. */
    private class InsertDataState {
        var observed = 0
        var depth = 0
        var head: String? = null
        var previous: String? = null
        var noData = false
        var query = false
        var input = false
        var afterFormat = false
        var settings = false
        var settingValue = false

        fun reset(observed: Int = 0) {
            this.observed = observed
            depth = 0
            head = null
            previous = null
            noData = false
            query = false
            input = false
            afterFormat = false
            settings = false
            settingValue = false
        }
    }

    // sqlglot: TokenizerCore._chars
    private fun chars(size: Int): String {
        if (size == 1) return char.toString()
        val s = current - 1
        val e = s + size
        return if (e <= this.size) sql.substring(s, e) else ""
    }

    // sqlglot: TokenizerCore._advance
    // Calls the cancellation hook after every CANCEL_INTERVAL scanned characters.
    private fun progress(scannedChars: Int) {
        cancelBudget -= scannedChars
        if (cancelBudget > 0) return
        cancelBudget = CANCEL_INTERVAL
        try { checkCanceled() } catch (e: Throwable) { throw Canceled(e) }
    }

    private fun advance(i: Int = 1, alnum: Boolean = false) {
        val before = current
        val c = char

        if (c == '\n' || c == '\r') {
            // Ensures we don't count an extra line if we get a \r\n line break sequence
            if (!(c == '\r' && peek == '\n')) {
                col = i
                line += 1
            }
        } else {
            col += i
        }

        current += i
        end = current >= size
        char = if (current - 1 in 0 until size) sql[current - 1] else NONE
        peek = if (end) NONE else sql[current]

        if (alnum && char.isLetterOrDigit()) {
            // Batch consecutive alphanumeric chars
            var c2 = col
            var cur = current
            var e2 = end
            var p2 = peek

            while (p2.isLetterOrDigit() && cur - before < CANCEL_INTERVAL) {
                c2 += 1
                cur += 1
                e2 = cur >= size
                p2 = if (e2) NONE else sql[cur]
            }

            col = c2
            current = cur
            end = e2
            peek = p2
            char = sql[cur - 1]
        }
        if (current > before) progress(current - before)
    }

    // sqlglot: TokenizerCore._text
    private val text: String
        get() = sql.substring(start, current)

    // sqlglot: TokenizerCore._add
    private fun add(tokenType: TokenType, text: String? = null) {
        prevTokenLine = line

        if (comments.isNotEmpty() && tokenType == TokenType.SEMICOLON && tokens.isNotEmpty()) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
        }

        val tokenText = text ?: sql.substring(start, current)

        // brikk-native: start-anchored line/col (the tracked line/col are the END).
        // colStart is 1-based: distance from the char after the preceding newline.
        // lineStart backs out the newlines the token itself spans (multi-line strings).
        var newlinesInToken = 0
        for (i in start until current) {
            if (sql[i] == '\n' || (sql[i] == '\r' && sql.getOrNull(i + 1) != '\n')) newlinesInToken++
        }
        val lineStart = line - newlinesInToken
        val colStart = start - lineBreakBefore(start)

        tokens.add(
            Token(
                tokenType,
                text = tokenText,
                line = line,
                col = col,
                start = start,
                end = current - 1,
                comments = comments,
                lineStart = lineStart,
                colStart = colStart,
            )
        )
        comments = mutableListOf()

        // If we have either a semicolon or a begin token before the command's token, we'll parse
        // whatever follows the command's token as a string
        if (
            !editorMode && tokenType in config.commands &&
            peek != ';' &&
            (tokens.size == 1 || tokens[tokens.size - 2].tokenType in config.commandPrefixTokens)
        ) {
            val commandStart = current
            val tokenCount = tokens.size
            scan(checkSemicolon = true)
            while (tokens.size > tokenCount) tokens.removeAt(tokens.size - 1)
            val commandText = sql.substring(commandStart, current).trim()
            if (commandText.isNotEmpty()) add(TokenType.STRING, commandText)
        }
    }

    // The last CR or LF before [offset]; token starts only move forward, so the scan is amortized.
    private fun lineBreakBefore(offset: Int): Int {
        if (offset < breaksScanned) return maxOf(sql.lastIndexOf('\n', offset - 1), sql.lastIndexOf('\r', offset - 1))
        progress(offset - breaksScanned)
        while (breaksScanned < offset) {
            val c = sql[breaksScanned]
            if (c == '\n' || c == '\r') lastBreak = breaksScanned
            breaksScanned++
        }
        return lastBreak
    }

    // sqlglot: TokenizerCore._scan_keywords
    private fun scanKeywords() {
        val singleTokens = config.singleTokens
        var matchSize = 0
        var word: String? = null
        var chars = StringBuilder().append(char)
        var c = char
        var cEmpty = false
        var prevSpace = false
        var skip = false
        var trie = config.keywordTrie
        var singleToken = singleTokens.containsKey(c)

        while (true) {
            if (!skip) {
                val sub = trie.children[asciiUpper(c)] ?: break
                trie = sub
                if (trie.isWord) word = chars.toString()
            }

            val endPos = current + matchSize
            matchSize += 1

            if (endPos < size) {
                c = sql[endPos]
                singleToken = singleToken || singleTokens.containsKey(c)
                val isSpace = c.isWhitespace()

                if (!isSpace || !prevSpace) {
                    if (isSpace) c = ' '
                    chars.append(c)
                    prevSpace = isSpace
                    skip = false
                } else {
                    skip = true
                }
            } else {
                cEmpty = true
                break
            }
        }

        if (word != null) {
            if (scanString(word)) return
            if (scanComment(word)) return
            if (prevSpace || singleToken || cEmpty) {
                val upper = word.uppercase()
                val type = config.keywords[upper]
                if (type != null) {
                    advance(matchSize - 1)
                    add(type, text = upper)
                    return
                }
            }
        }

        val single = singleTokens[char]
        if (single != null) {
            add(single, text = char.toString())
            return
        }

        scanVar()
    }

    // sqlglot: TokenizerCore._scan_comment
    private fun scanComment(commentStart: String): Boolean {
        if (!config.comments.containsKey(commentStart)) return false
        if (commentStart == "--" && config.dashCommentRequiresBoundary) {
            val boundary = sql.getOrNull(current + 1)
            if (boundary != null && !boundary.isWhitespace() && boundary.code >= 32 && boundary.code != 127) return false
        }

        val commentStartLine = line
        val commentStartSize = commentStart.length
        val commentEnd = config.comments[commentStart]

        if (commentEnd == "*/" && config.nestedBlockComments) {
            scanNestedBlockComment(commentStartSize, commentStartLine)
        } else if (commentEnd != null) {
            // Skip the comment's start delimiter
            advance(commentStartSize)

            var commentCount = 1
            val commentEndSize = commentEnd.length
            val nestedComments = config.nestedComments

            while (!end) {
                if (chars(commentEndSize) == commentEnd) {
                    commentCount -= 1
                    if (commentCount == 0) break
                }

                advance(alnum = true)

                // Nested comments are allowed by some dialects, e.g. databricks, duckdb, postgres
                if (nestedComments && !end && chars(commentEndSize) == commentStart) {
                    advance(commentStartSize)
                    commentCount += 1
                }
            }

            if (commentCount != 0) {
                throw TokenError("Missing $commentEnd from $commentStartLine:$start")
            }

            val t = text
            val sliceEnd = t.length - commentEndSize + 1
            comments.add(if (sliceEnd < commentStartSize) "" else t.substring(commentStartSize, sliceEnd))
            advance(commentEndSize - 1)
        } else {
            while (!end && (config.commentsTerminateAtNewlineOnly || peek != '\r')) {
                if (peek == '\n' && !(char == '\\' && config.dashCommentLineContinuation && commentStart == "--")) break
                advance(alnum = true)
            }
            comments.add(text.substring(commentStartSize))
        }

        if (
            commentStart == config.hintStart &&
            tokens.isNotEmpty() &&
            tokens.last().tokenType in config.tokensPrecedingHint
        ) {
            add(TokenType.HINT)
        }

        // Leading comment is attached to the succeeding token, whilst trailing comment to the
        // preceding. Multiple consecutive comments are preserved by appending them to the current
        // comments list.
        if (commentStartLine == prevTokenLine) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
            prevTokenLine = line
        }

        return true
    }

    // Native BRACKETED_COMMENT (`'/*' (BRACKETED_COMMENT | .)*? '*/'`) ends where its preferred
    // reading completes: at each offset close, else open a nested comment, else take one char.
    // When the plain depth count cannot close, that is a backtracking search. Editor tokenization
    // then requires a depth count that does not open at `/*/` to close at the same place.
    private fun scanNestedBlockComment(commentStartSize: Int, commentStartLine: Int) {
        val contentStart = start + 2
        val close = countedClose(contentStart, openOverlapping = true).takeIf { it >= 0 }
            ?: recoveredClose(contentStart).takeIf { it >= 0 }
            ?: throw TokenError("Missing */ from $commentStartLine:$start")
        advanceTo(close + 2)
        comments.add(sql.substring(start + commentStartSize, close))
    }

    private fun recoveredClose(contentStart: Int): Int {
        if (!editorMode) return nativeCommentClose(contentStart)
        val counted = countedClose(contentStart, openOverlapping = false)
        return if (counted >= 0 && nativeCommentClose(contentStart) == counted) counted else -1
    }

    // The offset of the closing `*/` by depth count, or -1.
    private fun countedClose(contentStart: Int, openOverlapping: Boolean): Int {
        var depth = 1
        var index = contentStart
        while (index < size) {
            progress(1)
            when {
                sql.startsWith("*/", index) -> if (--depth == 0) return index else index += 2
                sql.startsWith("/*", index) && (openOverlapping || sql.getOrNull(index + 2) != '/') -> { depth++; index += 2 }
                else -> index++
            }
        }
        return -1
    }

    private class CommentFrame(val delimiter: Int, val depth: Int) {
        var alternative = 0
    }

    // The first completion in the native lexer's preference order, or -1. The search only
    // branches at `/*` and `*/`, so its states are (delimiter, depth); depths beyond the closers
    // still ahead cannot complete, and failed states are not revisited.
    private fun nativeCommentClose(contentStart: Int): Int {
        val delimiters = delimitersFrom(contentStart)
        val count = delimiters.size
        val closersFrom = IntArray(count + 1)
        for (k in count - 1 downTo 0) closersFrom[k] = closersFrom[k + 1] + if (sql[delimiters[k]] == '*') 1 else 0
        fun nextDelimiter(offset: Int, from: Int): Int {
            var k = from
            while (k < count && delimiters[k] < offset) k++
            return k
        }
        val failed = HashSet<Long>()
        val frames = ArrayList<CommentFrame>()
        fun key(delimiter: Int, depth: Int) = delimiter.toLong() shl 32 or depth.toLong()
        fun visit(delimiter: Int, depth: Int) {
            if (delimiter < count && depth <= closersFrom[delimiter] && key(delimiter, depth) !in failed) {
                frames += CommentFrame(delimiter, depth)
            }
        }
        visit(0, 1)
        while (frames.isNotEmpty()) {
            progress(1)
            val frame = frames.last()
            val offset = delimiters[frame.delimiter]
            val closing = sql[offset] == '*'
            when (frame.alternative++) {
                0 -> if (closing) {
                    if (frame.depth == 1) return offset
                    visit(nextDelimiter(offset + 2, frame.delimiter), frame.depth - 1)
                }
                1 -> if (!closing) visit(nextDelimiter(offset + 2, frame.delimiter), frame.depth + 1)
                2 -> visit(nextDelimiter(offset + 1, frame.delimiter), frame.depth)
                else -> {
                    failed += key(frame.delimiter, frame.depth)
                    frames.removeAt(frames.lastIndex)
                }
            }
        }
        return -1
    }

    // Offsets of every `/*` and `*/` at or after [from], overlaps included.
    private fun delimitersFrom(from: Int): IntArray {
        var offsets = IntArray(16)
        var count = 0
        for (index in from until size - 1) {
            progress(1)
            val pair = sql[index] == '/' && sql[index + 1] == '*' || sql[index] == '*' && sql[index + 1] == '/'
            if (!pair) continue
            if (count == offsets.size) offsets = offsets.copyOf(count * 2)
            offsets[count++] = index
        }
        return offsets.copyOf(count)
    }

    // Native Doris DOLLAR_QUOTED_STRING; a longer `$`-identifier match wins, ties go to the string.
    private fun scanDollarString(): Boolean {
        val close = sql.indexOf("$$", start + 2)
        if (close == -1) {
            if (editorMode) throw TokenError("Missing $$ from $line:$start")
            return false
        }
        val identifierEnd = (start until size).firstOrNull { !isDollarIdentifierChar(sql[it]) } ?: size
        if (identifierEnd > close + 2) return false
        advanceTo(close + 2)
        add(TokenType.HEREDOC_STRING, sql.substring(start + 2, close))
        return true
    }

    private fun isDollarIdentifierChar(c: Char): Boolean = c == '$' || c == '_' || c.isLetterOrDigit() || c.code > 0x7F

    private fun advanceTo(exclusiveEnd: Int) {
        while (current < exclusiveEnd) advance()
    }

    // sqlglot: TokenizerCore._scan_number
    private fun scanNumber() {
        if (char == '0') {
            when (asciiUpper(peek)) {
                'B' -> return if (config.hasBitStrings) scanBits() else add(TokenType.NUMBER)
                'X' -> return if (config.hasHexStrings) scanHex() else add(TokenType.NUMBER)
            }
        }

        var decimal = false
        var scientific = 0
        val singleTokens = config.singleTokens

        var isUnderscoreSeparated = false
        var numberText = ""
        var numericLiteral = ""
        var numericType: TokenType? = null

        while (true) {
            if (peek in DIGIT_CHARS) {
                // Batch consecutive digits: scan ahead to find how many
                var e = current + 1
                while (e < size && sql[e] in DIGIT_CHARS) e++
                advance(e - current)
            } else if (peek == '.' && !decimal) {
                if (
                    (tokens.isNotEmpty() && tokens.last().tokenType == TokenType.PARAMETER) ||
                    !config.numbersCanHaveDecimals
                ) {
                    break
                }
                decimal = true
                advance()
            } else if ((peek == '-' || peek == '+') && scientific == 1) {
                // Only consume +/- if followed by a digit
                if (current + 1 < size && sql[current + 1] in DIGIT_CHARS) {
                    scientific += 1
                    advance()
                } else {
                    break
                }
            } else if (asciiUpper(peek) == 'E' && scientific == 0) {
                scientific += 1
                advance()
            } else if (peek == '_' && config.numbersCanBeUnderscoreSeparated) {
                isUnderscoreSeparated = true
                advance()
            } else if (peek.isLetter() || peek == '_') {
                numberText = text

                while (peek != NONE && !peek.isWhitespace() && !singleTokens.containsKey(peek)) {
                    numericLiteral += peek
                    advance()
                }

                numericType = config.numericLiterals[numericLiteral.uppercase()]
                    ?.let { config.keywords[it] }

                if (numericType != null) {
                    break
                } else if (config.identifiersCanStartWithDigit) {
                    add(TokenType.VAR)
                    return
                }

                advance(-numericLiteral.length)
                break
            } else {
                break
            }
        }

        var finalText = numberText.ifEmpty { sql.substring(start, current) }

        // Normalize inputs such as 100_000 to 100000
        if (isUnderscoreSeparated) finalText = finalText.replace("_", "")

        add(TokenType.NUMBER, finalText)

        // Normalize inputs such as 123L to 123::BIGINT so that they're parsed as casts
        if (numericType != null) {
            add(TokenType.DCOLON, "::")
            add(numericType, numericLiteral)
        }
    }

    // sqlglot: TokenizerCore._scan_bits
    private fun scanBits() {
        advance()
        val value = extractValue()
        // If `value` can't be converted to a binary, fallback to tokenizing it as an identifier
        val digits = value.drop(2)
        if (digits.isNotEmpty() && digits.all { it == '0' || it == '1' }) {
            add(TokenType.BIT_STRING, digits) // Drop the 0b
        } else {
            add(TokenType.IDENTIFIER)
        }
    }

    // sqlglot: TokenizerCore._scan_hex
    private fun scanHex() {
        advance()
        val value = extractValue()
        // If `value` can't be converted to a hex, fallback to tokenizing it as an identifier
        val digits = value.drop(2)
        if (digits.isNotEmpty() && digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            add(TokenType.HEX_STRING, digits) // Drop the 0x
        } else {
            add(TokenType.IDENTIFIER)
        }
    }

    // sqlglot: TokenizerCore._extract_value
    private fun extractValue(): String {
        while (true) {
            val c = peek
            if (c != NONE && !c.isWhitespace() && !config.singleTokens.containsKey(c)) {
                advance(alnum = true)
            } else {
                break
            }
        }
        return text
    }

    // sqlglot: TokenizerCore._scan_string
    private fun scanString(startDelim: String): Boolean {
        var base = 0
        var tokenType = TokenType.STRING
        var endDelim: String

        val quoteEnd = config.quotes[startDelim]
        val format = config.formatStrings[startDelim]

        if (quoteEnd != null) {
            endDelim = quoteEnd
        } else if (format != null) {
            endDelim = format.end
            tokenType = format.tokenType

            if (tokenType == TokenType.HEX_STRING) {
                base = 16
            } else if (tokenType == TokenType.BIT_STRING) {
                base = 2
            } else if (tokenType == TokenType.HEREDOC_STRING) {
                advance()

                val tag = if (endDelim.length == 1 && char == endDelim[0]) {
                    ""
                } else {
                    extractString(
                        endDelim,
                        rawString = true,
                        raiseUnmatched = !config.heredocTagIsIdentifier,
                    )
                }

                if (
                    tag.isNotEmpty() &&
                    config.heredocTagIsIdentifier &&
                    (end || tag.all { it.isDigit() } || tag.any { it.isWhitespace() })
                ) {
                    if (!end) advance(-1)
                    advance(-tag.length)
                    add(config.heredocStringAlternative)
                    return true
                }

                endDelim = "$startDelim$tag$endDelim"
            }
        } else {
            return false
        }

        advance(startDelim.length)
        val text = extractString(
            endDelim,
            escapes = if (tokenType == TokenType.BYTE_STRING) {
                config.byteStringEscapes
            } else {
                config.stringEscapes
            },
            rawString = tokenType == TokenType.RAW_STRING || tokenType == TokenType.HEREDOC_STRING,
            bytesLiteral = tokenType == TokenType.BYTE_STRING && config.byteStringsAreBytes,
        )

        if (base != 0 && text.isNotEmpty()) {
            val valid = when (base) {
                2 -> text.all { it == '0' || it == '1' }
                16 -> text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                else -> true
            }
            if (!valid) {
                throw TokenError("Numeric string contains invalid characters from $line:$start")
            }
        }

        add(tokenType, text)
        return true
    }

    // sqlglot: TokenizerCore._scan_identifier
    private fun scanIdentifier(identifierEnd: String) {
        advance()
        val escapes = if (identifierEnd.length == 1) {
            config.identifierEscapes + identifierEnd[0]
        } else {
            config.identifierEscapes
        }
        val text = extractString(identifierEnd, escapes = escapes)
        add(TokenType.IDENTIFIER, text)
    }

    // sqlglot: TokenizerCore._scan_var
    private fun scanVar() {
        val varSingleTokens = config.varSingleTokens
        val singleTokens = config.singleTokens

        while (true) {
            val p = peek
            if (p == NONE || p.isWhitespace()) break
            if (p !in varSingleTokens && singleTokens.containsKey(p)) break
            advance(alnum = true)
        }

        val tokenType = if (tokens.isNotEmpty() && tokens.last().tokenType == TokenType.PARAMETER) {
            TokenType.VAR
        } else {
            config.keywords[sql.substring(start, current).uppercase()] ?: TokenType.VAR
        }
        add(tokenType)
    }

    // sqlglot: TokenizerCore._read_numeric_escape (#8470 / #8484)
    private fun readNumericEscape(start: Int, spec: TokenizerConfig.NumericEscape): Pair<Int, Int> {
        var value = 0
        var end = start
        val limit = minOf(start + spec.maxDigits, size)
        while (end < limit) {
            val digit = sql[end].digitToIntOrNull(spec.base) ?: break
            if (value > (spec.maxValue - digit) / spec.base) break
            value = value * spec.base + digit
            end++
        }
        return (if (end - start >= spec.minDigits) value else -1) to end
    }

    private fun readByteEscape(pos: Int): Pair<Int, Int> {
        val next = sql.getOrNull(pos + 1) ?: return -1 to pos
        val octal = next in '0'..'7'
        val spec = config.numericEscapes[if (octal) '0' else next] ?: return -1 to pos
        if (spec.base != 8 && spec.maxValue > 0xFF) return -1 to pos
        val (value, end) = readNumericEscape(pos + if (octal) 1 else 2, spec)
        return (if (value >= 0) value and 0xFF else value) to end
    }

    private fun codePointText(value: Int): String = if (value <= 0xFFFF) value.toChar().toString() else {
        val cp = value - 0x10000
        "${(0xD800 + (cp shr 10)).toChar()}${(0xDC00 + (cp and 0x3FF)).toChar()}"
    }

    // Decode only valid, complete escapes; the caller handles unknown/truncated ones.
    private fun scanNumericEscape(): String? {
        val start = current
        val octal = peek in '0'..'7'
        val spec = config.numericEscapes[if (octal) '0' else peek] ?: return null
        var (value, end) = readNumericEscape(start + if (octal) 0 else 1, spec)
        val bytes = config.numericEscapesAreBytes && (spec.base == 8 || spec.maxValue <= 0xFF)
        if (bytes && value >= 0) value = value and 0xFF
        if (value in 0xD800..0xDFFF) {
            val (low, lowEnd) = if (value <= 0xDBFF && sql.getOrNull(end) == '\\' && sql.getOrNull(end + 1) == peek) {
                readNumericEscape(end + 2, spec)
            } else -1 to end
            if (low in 0xDC00..0xDFFF) {
                value = 0x10000 + ((value - 0xD800) shl 10) + (low - 0xDC00)
                end = lowEnd
            } else if (config.loneSurrogateReplacement.isNotEmpty() && end < size) {
                advance(end - start + 1)
                return config.loneSurrogateReplacement
            } else value = -1
        }
        if (bytes && value in 0xC2..0xF4) {
            val count = if (value < 0xE0) 2 else if (value < 0xF0) 3 else 4
            var cp = value and (0x7F shr count)
            var runEnd = end
            var read = 1
            while (read < count && sql.getOrNull(runEnd) == '\\') {
                val (byte, byteEnd) = readByteEscape(runEnd)
                if (byte !in 0x80..0xBF) break
                cp = (cp shl 6) or (byte and 0x3F)
                runEnd = byteEnd
                read++
            }
            val minimum = when (count) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
            if (read == count && cp in minimum..0x10FFFF && cp !in 0xD800..0xDFFF && runEnd < size) {
                advance(runEnd - start + 1)
                return codePointText(cp)
            }
        }
        if (value >= 0 && end < size) {
            advance(end - start + 1)
            return codePointText(value)
        }
        return null
    }

    // sqlglot: TokenizerCore._extract_string
    private fun extractString(
        delimiter: String,
        escapes: Set<Char>? = null,
        rawString: Boolean = false,
        raiseUnmatched: Boolean = true,
        bytesLiteral: Boolean = false,
    ): String {
        var text = StringBuilder()
        val delimSize = delimiter.length
        val esc = escapes ?: config.stringEscapes
        val unescapedSequences = config.unescapedSequences
        val escapeFollowChars = config.escapeFollowChars
        val quotes = config.quotes

        // Fast path via indexOf when the string is simple... no \ or other escapes
        if (delimSize == 1) {
            val delimChar = delimiter[0]
            val pos = current - 1
            val endIdx = sql.indexOf(delimChar, pos)

            if (
                // the closing delimiter was found
                endIdx != -1 &&
                // there's no doubled delimiter (e.g. '' escape), or the delimiter isn't an escape
                (endIdx + 1 >= size || sql[endIdx + 1] != delimChar || delimChar !in esc) &&
                // no backslash in the string that would need escape processing
                (
                    !(unescapedSequences.isNotEmpty() || '\\' in esc) ||
                        !hasBackslash(pos, endIdx)
                    )
            ) {
                var newlines = 0
                for (i in pos until endIdx) {
                    progress(1)
                    if (sql[i] == '\n' || (sql[i] == '\r' && sql.getOrNull(i + 1) != '\n')) newlines++
                }
                if (newlines > 0) {
                    line += newlines
                    col = endIdx - maxOf(sql.lastIndexOf('\n', endIdx - 1), sql.lastIndexOf('\r', endIdx - 1))
                } else {
                    col += endIdx - pos
                }

                current = endIdx + 1
                end = current >= size
                char = sql[endIdx]
                peek = if (end) NONE else sql[current]
                return byteChunk(sql.substring(pos, endIdx), bytesLiteral)
            }
        }

        while (true) {
            val backslashEscape = !rawString && char == '\\' && '\\' in esc
            if (backslashEscape && config.numericEscapes.isNotEmpty()) {
                val decoded = scanNumericEscape()
                if (decoded != null) {
                    text.append(decoded)
                    continue
                }
            }
            if (!rawString && unescapedSequences.isNotEmpty() && current < size && char in esc) {
                val unescaped = unescapedSequences["$char$peek"]
                if (unescaped != null) {
                    if (peek == '\n' || peek == '\r') {
                        advance()
                        advance()
                    } else advance(2)
                    text.append(unescaped)
                    continue
                }
            }
            if (backslashEscape && config.dropUnknownEscapes && current < size) {
                val next = peek
                advance()
                advance()
                text.append(byteChunk(next.toString(), bytesLiteral))
                continue
            }

            val isValidCustomEscape =
                escapeFollowChars.isNotEmpty() && char == '\\' && peek !in escapeFollowChars
            val escapedDelimiter =
                peek.toString() == delimiter ||
                    (delimSize > 1 && peek == delimiter[0] && quotes.containsKey(peek.toString()))

            if (
                (config.stringEscapesAllowedInRawStrings || !rawString) &&
                char in esc &&
                (
                    escapedDelimiter ||
                        peek in esc ||
                        isValidCustomEscape
                    ) &&
                (!quotes.containsKey(char.toString()) || char == peek)
            ) {
                if (escapedDelimiter) {
                    if (rawString) text.append(char).append(peek) else text.append(peek)
                } else if (isValidCustomEscape && char != peek) {
                    text.append(peek)
                } else {
                    text.append(char).append(peek)
                }

                if (current + 1 < size) {
                    advance(2)
                } else {
                    throw TokenError("Missing $delimiter from $line:$current")
                }
            } else {
                if (chars(delimSize) == delimiter) {
                    if (delimSize > 1) advance(delimSize - 1)
                    break
                }

                if (end) {
                    if (!raiseUnmatched) return text.append(char).toString()
                    throw TokenError("Missing $delimiter from $line:$start")
                }

                val cur = current - 1
                advance(alnum = true)
                if (bytesLiteral && sql[cur].isHighSurrogate() && char.isLowSurrogate()) advance()
                text.append(byteChunk(sql.substring(cur, current - 1), bytesLiteral))
            }
        }

        return text.toString()
    }

    private fun hasBackslash(from: Int, until: Int): Boolean {
        for (index in from until until) {
            progress(1)
            if (sql[index] == '\\') return true
        }
        return false
    }

    private fun byteChunk(text: String, bytes: Boolean): String = if (bytes && text.any { it.code >= 128 })
        text.encodeToByteArray(throwOnInvalidSequence = true).joinToString("") { (it.toInt() and 0xFF).toChar().toString() } else text

    private class Canceled(val signal: Throwable) : RuntimeException(signal)

    private companion object {
        const val CANCEL_INTERVAL = 1 shl 16
        private val INSERT_WITHOUT_DATA = setOf("INFILE", "VALUES")

        /** Stand-in for Python's empty-string "no char" sentinel. */
        const val NONE: Char = '\u0000'

        // sqlglot: tokenizer_core._DIGIT_CHARS
        val DIGIT_CHARS: CharRange = '0'..'9'

        // sqlglot: tokenizer_core._CHAR_UPPER (ASCII-only uppercase, a-z)
        fun asciiUpper(c: Char): Char = if (c in 'a'..'z') c - 32 else c
    }
}
