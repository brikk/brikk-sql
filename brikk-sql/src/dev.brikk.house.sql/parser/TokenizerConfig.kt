package dev.brikk.house.sql.parser

/**
 * Immutable tokenizer configuration for one dialect.
 *
 * sqlglot: the class-level tables of tokens.Tokenizer (plus the dialect-level flags
 * that tokens.Tokenizer._init_core pulls from the Dialect). Dialects will provide
 * their own instances of this in later phases; defaults are the base "sqlglot"
 * dialect tables from [BaseTokenizerTables].
 */
class TokenizerConfig(
    val singleTokens: Map<Char, TokenType> = BaseTokenizerTables.SINGLE_TOKENS,
    val keywords: Map<String, TokenType> = BaseTokenizerTables.KEYWORDS,
    val quotes: Map<String, String> = BaseTokenizerTables.QUOTES,
    val formatStrings: Map<String, StringFormat> = BaseTokenizerTables.FORMAT_STRINGS,
    val identifiers: Map<Char, String> = BaseTokenizerTables.IDENTIFIERS,
    val comments: Map<String, String?> = BaseTokenizerTables.COMMENTS,
    val stringEscapes: Set<Char> = BaseTokenizerTables.STRING_ESCAPES,
    val byteStringEscapes: Set<Char> = BaseTokenizerTables.BYTE_STRING_ESCAPES,
    val identifierEscapes: Set<Char> = BaseTokenizerTables.IDENTIFIER_ESCAPES,
    val escapeFollowChars: Set<Char> = BaseTokenizerTables.ESCAPE_FOLLOW_CHARS,
    val commands: Set<TokenType> = BaseTokenizerTables.COMMANDS,
    val commandPrefixTokens: Set<TokenType> = BaseTokenizerTables.COMMAND_PREFIX_TOKENS,
    val nestedComments: Boolean = BaseTokenizerTables.NESTED_COMMENTS,
    val hintStart: String = BaseTokenizerTables.HINT_START,
    val tokensPrecedingHint: Set<TokenType> = BaseTokenizerTables.TOKENS_PRECEDING_HINT,
    val hasBitStrings: Boolean = false,
    val hasHexStrings: Boolean = false,
    val numericLiterals: Map<String, String> = BaseTokenizerTables.NUMERIC_LITERALS,
    val varSingleTokens: Set<Char> = BaseTokenizerTables.VAR_SINGLE_TOKENS,
    val stringEscapesAllowedInRawStrings: Boolean =
        BaseTokenizerTables.STRING_ESCAPES_ALLOWED_IN_RAW_STRINGS,
    val heredocTagIsIdentifier: Boolean = BaseTokenizerTables.HEREDOC_TAG_IS_IDENTIFIER,
    val heredocStringAlternative: TokenType = BaseTokenizerTables.HEREDOC_STRING_ALTERNATIVE,
    // sqlglot: Dialect.NUMBERS_CAN_BE_UNDERSCORE_SEPARATED (base default false)
    val numbersCanBeUnderscoreSeparated: Boolean = false,
    val numbersCanHaveDecimals: Boolean = BaseTokenizerTables.NUMBERS_CAN_HAVE_DECIMALS,
    // sqlglot: Dialect.IDENTIFIERS_CAN_START_WITH_DIGIT (base default false)
    val identifiersCanStartWithDigit: Boolean = false,
    // sqlglot: Dialect.UNESCAPED_SEQUENCES (base default empty)
    val unescapedSequences: Map<String, String> = emptyMap(),
) {
    // Keep the published constructor/default bridge intact while adding escape policy.
    private var numericPolicy = NumericPolicy()
    private var commentPolicy = CommentPolicy()
    private var bytePolicy = false
    val byteStringsAreBytes: Boolean get() = bytePolicy

    fun withByteStringPolicy(byteStringsAreBytes: Boolean): TokenizerConfig =
        copyWith(emptyMap(), unescapedSequences).also { it.bytePolicy = byteStringsAreBytes }
    val dashCommentRequiresBoundary: Boolean get() = commentPolicy.dashBoundary
    val commentsTerminateAtNewlineOnly: Boolean get() = commentPolicy.newlineOnly

    private data class CommentPolicy(val dashBoundary: Boolean = false, val newlineOnly: Boolean = false)

    fun withCommentPolicy(
        dashCommentRequiresBoundary: Boolean = this.dashCommentRequiresBoundary,
        commentsTerminateAtNewlineOnly: Boolean = this.commentsTerminateAtNewlineOnly,
    ): TokenizerConfig = copyWith(emptyMap(), unescapedSequences).also {
        it.commentPolicy = CommentPolicy(dashCommentRequiresBoundary, commentsTerminateAtNewlineOnly)
    }
    private var lexicalPolicy = LexicalPolicy()
    val nestedBlockComments: Boolean get() = lexicalPolicy.nestedBlockComments
    val dashCommentLineContinuation: Boolean get() = lexicalPolicy.dashCommentLineContinuation
    val untaggedDollarStrings: Boolean get() = lexicalPolicy.untaggedDollarStrings

    private data class LexicalPolicy(
        val nestedBlockComments: Boolean = false,
        val dashCommentLineContinuation: Boolean = false,
        val untaggedDollarStrings: Boolean = false,
    )

    /**
     * brikk extension #53 (docs/brikk-extensions.md): native lexer rules sqlglot's tables lack.
     * [nestedBlockComments]: block comments nest, also inside optimizer hints, ending where the
     * native lexer ends them; editor tokenization refuses one that no depth count closes there.
     * [dashCommentLineContinuation]: backslash + LF (not CRLF) continues a `--` comment.
     * [untaggedDollarStrings]: `$$...$$` is a raw HEREDOC_STRING unless a `$`-spelled
     * identifier is the longer match. An unclosed one stays an identifier, except in editor
     * tokenization, where it is a lexical failure.
     */
    fun withLexicalRules(
        comments: Map<String, String?> = this.comments,
        nestedBlockComments: Boolean = this.nestedBlockComments,
        dashCommentLineContinuation: Boolean = this.dashCommentLineContinuation,
        untaggedDollarStrings: Boolean = this.untaggedDollarStrings,
    ): TokenizerConfig = copyWith(emptyMap(), unescapedSequences, comments).also {
        it.lexicalPolicy = LexicalPolicy(nestedBlockComments, dashCommentLineContinuation, untaggedDollarStrings)
    }

    private var insertDataPolicy = false

    /**
     * brikk extension #56 (docs/brikk-extensions.md): in editor tokenization, the data of an
     * `INSERT ... FORMAT <name>` (other than `Values`) is one [TokenType.INSERT_DATA] token,
     * framed as ClickHouse clients frame it rather than read as SQL.
     */
    val inlineInsertData: Boolean get() = insertDataPolicy

    fun withInlineInsertData(enabled: Boolean = true): TokenizerConfig =
        copyWith(emptyMap(), unescapedSequences).also { it.insertDataPolicy = enabled }

    val numericEscapes: Map<Char, NumericEscape> get() = numericPolicy.escapes
    val dropUnknownEscapes: Boolean get() = numericPolicy.dropUnknown
    val numericEscapesAreBytes: Boolean get() = numericPolicy.bytes
    val loneSurrogateReplacement: String get() = numericPolicy.loneSurrogateReplacement

    /** sqlglot: Tokenizer.NUMERIC_ESCAPES tuples (base, min digits, max digits, max value). */
    data class NumericEscape(val base: Int, val minDigits: Int, val maxDigits: Int, val maxValue: Int)

    private data class NumericPolicy(
        val escapes: Map<Char, NumericEscape> = emptyMap(),
        val dropUnknown: Boolean = false,
        val bytes: Boolean = false,
        val loneSurrogateReplacement: String = "",
    )

    /** A new configuration, leaving this configuration and its ABI unchanged. */
    fun withStringEscapes(
        numericEscapes: Map<Char, NumericEscape> = this.numericEscapes,
        dropUnknownEscapes: Boolean = this.dropUnknownEscapes,
        numericEscapesAreBytes: Boolean = this.numericEscapesAreBytes,
        loneSurrogateReplacement: String = this.loneSurrogateReplacement,
        unescapedSequences: Map<String, String> = this.unescapedSequences,
    ): TokenizerConfig = copyWith(emptyMap(), unescapedSequences).also {
        it.numericPolicy = NumericPolicy(numericEscapes.toMap(), dropUnknownEscapes,
            numericEscapesAreBytes, loneSurrogateReplacement)
    }

    /**
     * sqlglot: _TokenizerBase.__init_subclass__ _KEYWORD_TRIE — a trie over all
     * keyword/comment/quote/format-string starts that contain a space or any
     * single-token char (plain word keywords are resolved via [keywords] lookup
     * in Tokenizer.scanVar instead).
     */
    val keywordTrie: TrieNode = buildTrie(
        (keywords.keys.asSequence() +
            comments.keys.asSequence() +
            quotes.keys.asSequence() +
            formatStrings.keys.asSequence())
            .filter { key -> " " in key || key.any { it in singleTokens } }
            .map { it.uppercase() }
            .asIterable()
    )

    /**
     * brikk-native (no sqlglot counterpart): this config with [extra] keywords layered over
     * [keywords]. Lets a dialect extend a GENERATED `<Dialect>TokenizerTables.CONFIG`
     * without editing the generated file (e.g. DorisDialect.TOKENIZER_CONFIG).
     */
    fun withKeywords(extra: Map<String, TokenType>): TokenizerConfig = copyWith(extra, unescapedSequences)

    private fun copyWith(
        extra: Map<String, TokenType>,
        sequences: Map<String, String>,
        comments: Map<String, String?> = this.comments,
    ): TokenizerConfig = TokenizerConfig(
        singleTokens = singleTokens,
        keywords = keywords + extra,
        quotes = quotes,
        formatStrings = formatStrings,
        identifiers = identifiers,
        comments = comments,
        stringEscapes = stringEscapes,
        byteStringEscapes = byteStringEscapes,
        identifierEscapes = identifierEscapes,
        escapeFollowChars = escapeFollowChars,
        commands = commands,
        commandPrefixTokens = commandPrefixTokens,
        nestedComments = nestedComments,
        hintStart = hintStart,
        tokensPrecedingHint = tokensPrecedingHint,
        hasBitStrings = hasBitStrings,
        hasHexStrings = hasHexStrings,
        numericLiterals = numericLiterals,
        varSingleTokens = varSingleTokens,
        stringEscapesAllowedInRawStrings = stringEscapesAllowedInRawStrings,
        heredocTagIsIdentifier = heredocTagIsIdentifier,
        heredocStringAlternative = heredocStringAlternative,
        numbersCanBeUnderscoreSeparated = numbersCanBeUnderscoreSeparated,
        numbersCanHaveDecimals = numbersCanHaveDecimals,
        identifiersCanStartWithDigit = identifiersCanStartWithDigit,
        unescapedSequences = sequences,
    ).also {
        it.numericPolicy = numericPolicy; it.commentPolicy = commentPolicy; it.bytePolicy = bytePolicy
        it.lexicalPolicy = lexicalPolicy
        it.insertDataPolicy = insertDataPolicy
    }

    companion object {
        /** The base "sqlglot" dialect configuration. */
        val BASE: TokenizerConfig = TokenizerConfig()
    }
}
