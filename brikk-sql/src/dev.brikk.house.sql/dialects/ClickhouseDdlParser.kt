package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.parser.*
import kotlin.Boolean
import kotlin.String

/** Native utility grammar shared by ClickhouseParser. Unknown statement families stay Command. */
open class ClickhouseDdlParser(errorLevel: ErrorLevel? = null, tokenizerConfig: TokenizerConfig) :
    Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // Mandatory native clauses cannot produce a partially structured statement under IGNORE.
    private fun ddlError(message: String): Nothing {
        raiseError(message)
        throw ParseError(message)
    }

    override fun parseStatement(): Expression? {
        val start = index
        val head = currToken
        if (matchTextSeq("EXPLAIN")) {
            if (matchTexts(setOf("ANALYZE", "WHATIF"))) {
                val kind = prevToken.text.uppercase()
                val settings = if (nextToken.tokenType == TokenType.EQ) parseNativeSettings(false) else null
                val query = parseStatement() ?: return raiseError("Expected explained query")
                if (query !is Query && query !is Insert && query !is Create && query !is ClickhouseSystem)
                    raiseError("Unsupported explained statement")
                return expression(ClickhouseExplain(args("this" to query, "kind" to kind, "settings" to settings)), token = head)
            }
            return nativeRawCommand(head)
        }
        if (matchTexts(setOf("CREATE", "ALTER", "DROP"))) {
            val kind = prevToken.text.uppercase()
            if (matchTextSeq("HANDLER")) return parseHandler(kind)
            if (kind == "CREATE" && matchTextSeq("TOKEN")) return parseToken()
            if (kind == "CREATE" && matchTextSeq("HYPOTHETICAL", "INDEX")) return parseHypotheticalIndex()
            if (kind != "DROP" && matchTextSeq("USER") && tokens.drop(index).any {
                    it.tokenType !in setOf(TokenType.STRING, TokenType.IDENTIFIER) && it.text.equals("GRANTS", true)
                }) {
                return parseUserAuthentication(kind)
            }
        }
        retreat(start)
        if (matchTextSeq("SYSTEM")) {
            val actionStart = index
            if (matchTexts(setOf("START", "STOP", "PAUSE", "CANCEL", "REFRESH"))) {
                val kind = prevToken.text.uppercase()
                val all = matchTextSeq("ALL", "BACKGROUND")
                if (all || currToken.exists && currToken.text.uppercase() !in setOf("MERGES", "VIEW", "VIEWS", "FETCHES", "MOVES", "DISTRIBUTED")) {
                    val table = if (all) null else parseTableParts(schema = true)
                    if (!all && table == null) raiseError("Expected SYSTEM target")
                    return expression(ClickhouseSystem(args("kind" to kind, "this" to table, "all" to all)))
                }
            }
            retreat(actionStart)
            return nativeRawCommand(head)
        }
        retreat(start)
        return super.parseStatement()
    }

    private fun nativeRawCommand(head: Token): Expression {
        val first = currToken
        while (currToken.exists) advance()
        warnUnsupported()
        val tail = if (first.exists) Literal.string(findSql(first, prevToken).trim()).updatePositions(prevToken) else null
        return expression(Command(args("this" to head.text.uppercase(), "expression" to tail)), comments = head.comments)
    }

    private fun requireWords(vararg words: String) {
        if (!matchTextSeq(*words)) raiseError("Expected ${words.joinToString(" ")}")
    }

    private fun parseNativeCluster(): Expression? {
        if (!matchTextSeq("ON", "CLUSTER")) return null
        return parseString() ?: parseIdVar() ?: raiseError("Expected cluster")
    }

    private fun parseHandler(kind: String): Expression {
        val exists = when (kind) {
            "CREATE" -> matchTextSeq("IF", "NOT", "EXISTS")
            "DROP" -> matchTextSeq("IF", "EXISTS")
            else -> false
        }
        val name = parseIdVar() ?: ddlError("Expected handler name")
        val cluster = parseNativeCluster()
        if (kind == "DROP") return expression(ClickhouseHandler(args("this" to name, "kind" to kind,
            "exists" to exists, "cluster" to cluster)))
        val protocol = if (matchTextSeq("PROTOCOL")) parseIdVar(anyToken = true) ?: ddlError("Expected protocol") else null
        var match: String? = null
        val url = if (matchTextSeq("URL")) {
            match = if (matchTexts(setOf("PREFIX", "REGEXP"))) prevToken.text.uppercase() else null
            parseString() ?: ddlError("Expected handler URL literal")
        } else null
        val methods = if (matchTextSeq("METHODS")) parseWrappedCsv({
            val method = parseVar(anyToken = true, upper = true)
            if (method?.name !in setOf("GET", "POST", "PUT", "DELETE")) raiseError("Invalid handler method")
            method
        }) else null
        if (methods != null && methods.isEmpty()) raiseError("Expected handler methods")
        val type = if (matchTextSeq("TYPE")) parseIdVar(anyToken = true) ?: ddlError("Expected handler type") else null
        val query = if (matchTextSeq("AS")) {
            parseStatement() ?: ddlError("Expected handler query")
        } else null
        if (kind == "CREATE" && url == null) raiseError("CREATE HANDLER requires URL")
        return expression(ClickhouseHandler(args("this" to name, "kind" to kind, "exists" to exists,
            "cluster" to cluster, "protocol" to protocol, "url" to url, "match" to match,
            "methods" to methods, "type" to type, "expression" to query)))
    }

    private fun parseHypotheticalIndex(): Expression {
        val exists = matchTextSeq("IF", "NOT", "EXISTS")
        val name = parseIdVar() ?: ddlError("Expected hypothetical index name")
        requireWords("ON")
        val table = parseTableParts(schema = true) ?: ddlError("Expected index table")
        val body = if (currToken.tokenType == TokenType.L_PAREN) {
            val values = parseWrappedCsv({ parseAssignment() })
            if (values.isEmpty()) raiseError("Expected index expression")
            Tuple(args("expressions" to values))
        } else parseAssignment() ?: ddlError("Expected index expression")
        val type = if (matchTextSeq("TYPE")) parseField(anonymousFunc = true) ?: ddlError("Expected index type") else null
        val granularity = if (matchTextSeq("GRANULARITY")) parseNumber() ?: ddlError("Expected granularity") else null
        return expression(ClickhouseHypotheticalIndex(args("this" to name, "table" to table, "expression" to body,
            "type" to type, "granularity" to granularity, "exists" to exists)))
    }

    private fun parseValidity(target: Expression): Boolean {
        if (!matchTextSeq("VALID")) return false
        if (target.args["valid"] != null) raiseError("Duplicate validity clause")
        val kind = if (matchTexts(setOf("FOR", "UNTIL"))) prevToken.text.uppercase() else ddlError("Expected FOR or UNTIL")
        val start = index
        val value = if (kind == "UNTIL") parseString() else parseAssignment()
        if (value == null) raiseError("Expected validity value")
        // Generic interval parsing historically stringifies numeric leaves. Validity is new
        // grammar: keep the actual literal type rather than change the native authentication AST.
        if (kind == "FOR" && value != null) {
            val counts = (start until index).filter { tokens[it].tokenType == TokenType.INTERVAL }.mapNotNull { tokens.getOrNull(it + 1) }
            for ((interval, count) in value.findAll<Interval>().zip(counts.asSequence())) {
                val literal = interval.thisArg as? Literal
                if (count.tokenType == TokenType.NUMBER && literal?.name == count.text)
                    interval.set("this", Literal.number(count.text).updatePositions(count))
            }
        }
        target.set("valid_kind", kind)
        target.set("valid", value)
        return true
    }

    private fun parseGrants(): List<Expression> {
        if (!match(TokenType.L_PAREN)) ddlError("Expected ( after GRANTS")
        val grants = mutableListOf<Expression>()
        do {
            val privileges = mutableListOf<Expression>()
            do {
                val words = mutableListOf<String>()
                while (currToken.exists && currToken.tokenType !in setOf(TokenType.ON, TokenType.COMMA, TokenType.R_PAREN, TokenType.L_PAREN)) {
                    words.add(currToken.text.uppercase()); advance()
                }
                if (words.isEmpty()) ddlError("Expected grant privilege")
                val word = words.joinToString(" ")
                val privilege = if (currToken.tokenType == TokenType.L_PAREN)
                    Anonymous(args("this" to word, "expressions" to parseWrappedCsv({ parseIdVar() })))
                else Var(args("this" to word))
                privileges.add(privilege)
            } while (match(TokenType.COMMA))
            requireWords("ON")
            val target = parseTableParts(schema = true) ?: ddlError("Expected grant object")
            grants.add(expression(ClickhouseGrant(args("this" to target, "expressions" to privileges))))
        } while (match(TokenType.COMMA))
        matchRParen()
        return grants
    }

    private fun parseToken(): Expression {
        val token = ClickhouseCreateToken()
        while (true) {
            if (parseValidity(token)) continue
            if (matchTextSeq("GRANTS")) {
                if (token.args["grants"] != null) raiseError("Duplicate GRANTS clause")
                token.set("grants", parseGrants()); continue
            }
            break
        }
        if (matchTextSeq("FORMAT")) token.set("format", parseIdVar(anyToken = true) ?: ddlError("Expected output format"))
        return expression(token)
    }

    private fun parseUserAuthentication(kind: String): Expression {
        val exists = if (kind == "CREATE") matchTextSeq("IF", "NOT", "EXISTS") else matchTextSeq("IF", "EXISTS")
        val name = parseIdVar() ?: ddlError("Expected user name")
        val cluster = parseNativeCluster()
        val add = kind == "ALTER" && matchTextSeq("ADD")
        requireWords("IDENTIFIED")
        matchTextSeq("WITH")
        val methods = parseCsv {
            val type = parseVar(anyToken = true) ?: return@parseCsv raiseError("Expected authentication method")
            val method = ClickhouseAuthenticationMethod(args("this" to type))
            if (matchTextSeq("BY")) method.set("expression", ClickhouseCredential(args("this" to
                (parseString() ?: return@parseCsv raiseError("Expected credential literal")))))
            while (true) {
                if (method.args["grants"] != null && currToken.text.equals("VALID", true))
                    ddlError("Authentication validity must precede GRANTS")
                if (parseValidity(method)) continue
                if (matchTextSeq("GRANTS")) {
                    if (method.args["grants"] != null) raiseError("Duplicate GRANTS clause")
                    method.set("grants", parseGrants()); continue
                }
                break
            }
            expression(method)
        }
        return expression(ClickhouseUserAuthentication(args("this" to name, "kind" to kind, "exists" to exists,
            "cluster" to cluster, "add" to add, "expressions" to methods)))
    }

    protected fun parseNativeSettings(wrapped: Boolean): Expression {
        fun setting(): Expression? {
            val name = parseIdVar(anyToken = true) ?: return raiseError("Expected setting name")
            if (!match(TokenType.EQ)) return raiseError("Expected setting assignment")
            return PropertyEQ(args("this" to name, "expression" to (parseAssignment() ?: return raiseError("Expected setting value"))))
        }
        val values = if (wrapped) parseWrappedCsv({ setting() }) else parseCsv { setting() }
        if (values.isEmpty()) raiseError("Expected settings")
        return ClickhouseFunctionSettings(args("expressions" to values))
    }

    protected fun parseNativeProjection(name: Expression): Expression {
        val index = matchTextSeq("INDEX")
        val body = if (index) parseAssignment() else parseWrapped({ parseStatement() })
        if (body == null) ddlError("Expected projection expression")
        val type = if (index) { requireWords("TYPE"); parseField(anonymousFunc = true) ?: ddlError("Expected projection index type") } else null
        val settings = if (matchTextSeq("WITH", "SETTINGS")) parseNativeSettings(true) else null
        // Preserve the established AST for ordinary projections without new clauses.
        if (!index && settings == null) return expression(ProjectionDef(args("this" to name, "expression" to body)))
        return expression(ClickhouseProjection(args("this" to name, "expression" to body, "index" to index,
            "type" to type, "settings" to settings)))
    }

    protected fun parseNativeModify(): Expression? {
        val start = index
        if (matchTextSeq("COLUMN")) {
            val exists = matchTextSeq("IF", "EXISTS")
            val name = parseIdVar()
            if (name != null && matchTextSeq("ADD", "ENUM", "VALUES")) {
                val values = parseWrappedCsv({
                    val label = parseString() ?: return@parseWrappedCsv raiseError("Expected enum label")
                    if (match(TokenType.EQ)) {
                        val number = parseUnary() ?: return@parseWrappedCsv raiseError("Expected enum number")
                        val literal = if (number is Neg) number.thisArg as? Literal else number as? Literal
                        if (literal == null || literal.isString || literal.name.toLongOrNull() == null)
                            ddlError("Enum value must be an integer")
                        EQ(args("this" to label, "expression" to number))
                    } else label
                })
                if (values.isEmpty()) raiseError("Expected enum values")
                return expression(ClickhouseAlterAction(args("this" to name, "kind" to "ENUM", "exists" to exists, "expressions" to values)))
            }
        }
        retreat(start)
        if (matchTextSeq("CONSTRAINT")) {
            val exists = matchTextSeq("IF", "EXISTS")
            val name = parseIdVar() ?: return raiseError("Expected constraint name")
            val kind = if (matchTexts(setOf("CHECK", "ASSUME"))) prevToken.text.uppercase() else return raiseError("Expected CHECK or ASSUME")
            val body = parseAssignment() ?: return raiseError("Expected constraint expression")
            return expression(ClickhouseAlterAction(args("this" to name, "kind" to kind, "exists" to exists, "expression" to body)))
        }
        if (matchTextSeq("PROJECTION")) {
            val exists = matchTextSeq("IF", "EXISTS")
            val name = parseIdVar() ?: return raiseError("Expected projection name")
            val projection = parseNativeProjection(name)
            return expression(ClickhouseAlterAction(args("this" to name.copy(), "kind" to "PROJECTION", "exists" to exists, "expression" to projection)))
        }
        retreat(start)
        return null
    }

    protected fun parseNativeProjectionAction(kind: String): Expression? {
        if (!matchTextSeq("PROJECTION")) return null
        val exists = if (kind == "ADD") matchTextSeq("IF", "NOT", "EXISTS") else matchTextSeq("IF", "EXISTS")
        val name = parseIdVar() ?: ddlError("Expected projection name")
        val body = if (kind == "ADD") parseNativeProjection(name.copy()) else null
        val first = kind == "ADD" && matchTextSeq("FIRST")
        val after = if (kind == "ADD" && matchTextSeq("AFTER")) parseIdVar() ?: ddlError("Expected preceding projection") else null
        val partition = if (kind in setOf("CLEAR", "MATERIALIZE") && matchTextSeq("IN")) parsePartition() else null
        return expression(ClickhouseAlterAction(args("this" to name, "kind" to "$kind PROJECTION",
            "exists" to exists, "expression" to body, "first" to first, "after" to after, "partition" to partition)))
    }
}
