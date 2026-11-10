package dev.brikk.house.sql.diagnostics

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.parser.*
import dev.brikk.house.sql.shape.DatafusionArgumentRole
import dev.brikk.house.sql.shape.datafusionCallSite
import kotlin.String

/** Only exhaustive captured signatures constrain arity. Unknown signatures/UDFs stay unknown. */
fun datafusionFunctionDiagnostics(sql: String, dialect: String, snapshot: MetadataSnapshot, checkCanceled: () -> Unit = {}): List<SqlDiagnostic> {
    if (dialect !in setOf("datafusion", "spice")) return emptyList()
    val source = Dialects.forName(dialect)
    val result = mutableListOf<SqlDiagnostic>()
    for (range in SqlScriptSnapshot.parse(sql, dialect, checkCanceled).statements) {
        val text = sql.substring(range.start, range.terminator ?: range.end)
        val lexed = Tokenizer(source.tokenizerConfig).tokenizeForEditor(text, checkCanceled)
        if (lexed.failureOffset != null) continue
        val roots = try { source.parser(ErrorLevel.IMMEDIATE).parse(lexed.tokens, text, checkCanceled).filterNotNull() } catch (_: ParseError) { continue }
        val tokens = lexed.tokens
        val byStart = tokens.withIndex().associate { it.value.start to it.index }
        for (node in roots.asSequence().flatMap { it.walk() }) {
            if (node.metaOrNull?.get(BrikkMetaKeys.DATAFUSION_NATIVE_CALL) != true || node !is Func) continue
            checkCanceled()
            val start = (node.metaOrNull?.get("start") as? Number)?.toInt() ?: continue
            val index = byStart[start] ?: continue
            val token = tokens[index]
            if (token.tokenType == TokenType.IDENTIFIER || tokens.getOrNull(index - 1)?.tokenType == TokenType.DOT) continue
            val defs = snapshot.functions.getAll(token.text)
            if (defs.isEmpty()) continue
            val table = node.parent is Table || datafusionCallSite(text, start, dialect)?.role == DatafusionArgumentRole.SEARCH_INPUT
            fun report(message: String) { result += SqlDiagnostic(DiagnosticSeverity.ERROR, message, range.start + token.start, range.start + token.end + 1, DiagnosticSource.OTHER) }
            if (!table && defs.any { it.nativeKind == "SCALAR_STUB" } && defs.none { it.kind != FunctionKind.TABLE_VALUED && it.nativeKind != "SCALAR_STUB" }) {
                report("${token.text} is a relation function; its scalar registration only supports nested search syntax")
                continue
            }
            val callable = defs.filter { (it.kind == FunctionKind.TABLE_VALUED) == table && it.nativeKind != "SCALAR_STUB" }
            if (callable.isEmpty() || callable.any { it.overloads.isEmpty() }) continue
            var depth = 0
            var count = 0
            var named = false
            for (argument in tokens.drop(index + 1)) {
                when (argument.tokenType) {
                    TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                    TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> if (--depth == 0) break
                    TokenType.COMMA -> if (depth == 1) count++
                    else -> {}
                }
                if (depth == 1 && argument.text == "=>") named = true
            }
            count = if (tokens.getOrNull(index + 2)?.tokenType == TokenType.R_PAREN) 0 else count + 1
            if (named) continue
            val overloads = callable.flatMap { it.overloads }
            if (overloads.none { if (it.variadic) count >= it.argTypes.size else count == it.argTypes.size }) {
                report("${token.text} expects ${overloads.map { it.argTypes.size }.distinct().sorted().joinToString(" or ")} arguments; found $count")
            } else if (dialect == "datafusion" && snapshot.engineVersion == "55.2.0" && token.text.equals("sqrt", true)) {
                val value = node.thisArg as? Expression
                val castType = (value as? Cast)?.args?.get("to") as? DataType
                if (value is dev.brikk.house.sql.ast.Array || value is Struct || value is VarMap ||
                    castType?.thisArg in setOf(DType.ARRAY, DType.STRUCT, DType.MAP)) {
                    report("sqrt requires a numeric argument, not a list, struct or map")
                }
            }
        }
    }
    return result.distinct()
}
