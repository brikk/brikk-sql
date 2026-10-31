package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.sql.dialects.postgres.PgParser
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlElementTypes
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as SqlArray
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.dialect.DatafusionIdeProfile
import dev.brikk.house.sql.intellij.lexer.CoreStatements
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.SqlScriptParameters
import kotlin.Boolean
import kotlin.String

/**
 * PG PSI for common syntax, with explicit native extensions retained as one statement. Core errors
 * remain PSI errors at their authored positions; no error filter or blanket acceptance is installed.
 * The fallback is editor structure, not a native execution verdict or permission to run a pipe.
 */
public class DatafusionFamilyParser(private val profile: DatafusionIdeProfile) : PgParser(false) {
    private val dialect = Dialects.forName(profile.coreDialect)
    private val boundaries = StatementBoundaryGate(profile.coreDialect, colonParameters = true)
    private var nested = false

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean {
        if (nested) return super.parseSqlStatement(builder, level)
        val source = builder.originalText
        val range = CoreStatements.of(source, source.length, profile.coreDialect).rangeAt(builder.currentOffset)
            ?: return super.parseSqlStatement(builder, level)
        val start = builder.currentOffset
        val end = range.terminator ?: range.end
        if (start >= end) return super.parseSqlStatement(builder, level)
        val sql = source.subSequence(start, end).toString()
        val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(sql, ProgressManager::checkCanceled)
        if (lexed.failureOffset != null) return nativeStatement(builder, start, end, sql,
            start + lexed.failureOffset!!, "${profile.coreDialect}: ${lexed.error ?: "Invalid token"}")
        val ast = try {
            dialect.parser(ErrorLevel.IMMEDIATE).parse(lexed.tokens, sql, ProgressManager::checkCanceled)
                .filterNotNull().filterNot { it is Semicolon }.singleOrNull()
        } catch (error: ParseError) {
            val info = error.errors.firstOrNull()
            return nativeStatement(builder, start, end, sql, start + (info?.start ?: 0),
                "${profile.coreDialect}: ${info?.description ?: "Invalid or incomplete SQL"}")
        }
        if (ast != null && ast !is Command && needsNativeStatement(ast)) return nativeStatement(builder, start, end, sql)
        nested = true
        return try { boundaries.parse(builder) { super.parseSqlStatement(builder, level) } } finally { nested = false }
    }

    override fun parseParameterOrVariableReference(builder: PsiBuilder, allowVariable: Boolean): Boolean =
        builder.parseColonParameter(named = true) || super.parseParameterOrVariableReference(builder, allowVariable)

    private fun needsNativeStatement(ast: Expression): Boolean = ast.walk().any {
        it is SpiceExplain || it is SpicePrepare || it is SpiceExecute || it is SpiceShow || it is SpiceReset ||
            it is ClusterProperty || it is FileFormatProperty || it is Copy || it is PipeQuery || it is Qualify ||
            it is SqlArray || it is TryCast ||
            it is Use || it is Group && it.args["all"] == true ||
            it is Order && it.expressionsArg.filterIsInstance<Ordered>().any { ordered ->
                val column = ordered.thisArg as? Column
                column != null && column.table.isEmpty() && column.name.equals("ALL", true) &&
                    (column.thisArg as? Identifier)?.quoted == false
            } ||
            it is Anonymous && it.name.lowercase() in setOf("like", "truncate", "map", "struct") ||
            it is Star && listOf("except_", "replace", "rename", "ilike").any { key ->
                val value = it.args[key]
                value != null && value != false && (value !is List<*> || value.isNotEmpty())
            }
    }

    /** Retain every token and bind; native extensions do not become opaque comment/string masks. */
    private fun nativeStatement(builder: PsiBuilder, start: Int, end: Int, sql: String, errorAt: Int? = null, message: String? = null): Boolean {
        val statement = builder.mark()
        val parameters = SqlScriptParameters.parse(sql, profile.coreDialect, positional = true, ProgressManager::checkCanceled)
            .occurrences.associateBy { start + it.range.start }
        var reported = false
        while (!builder.eof() && builder.currentOffset < end) {
            ProgressManager.checkCanceled()
            if (!reported && errorAt != null && builder.currentOffset >= errorAt) {
                val error = builder.mark()
                builder.advanceLexer()
                error.error(message ?: "Invalid SQL")
                reported = true
                continue
            }
            val parameter = parameters[builder.currentOffset]
            if (parameter != null) {
                val marker = builder.mark()
                while (!builder.eof() && builder.currentOffset < start + parameter.range.end) builder.advanceLexer()
                marker.done(SqlElementTypes.SQL_PARAMETER_REFERENCE)
            } else builder.advanceLenient()
        }
        if (!reported && errorAt != null) builder.error(message ?: "Incomplete SQL")
        statement.done(SqlCompositeElementTypes.SQL_STATEMENT)
        return true
    }
}
