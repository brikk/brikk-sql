package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.DatafusionCatalogProfile
import dev.brikk.house.sql.intellij.dialect.DatafusionFamilyDialect
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.isEnabled
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenType
import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.verify.UnavailableVerifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Flight SQL preparation/EXPLAIN can plan and call table functions. Neither is a background parser. */
public class DatafusionNativeErrorProfile(
    override val language: DatafusionFamilyDialect,
    private val catalog: DatafusionCatalogProfile,
    private val pipe: PipeDialect? = null,
) : NativeErrorProfile {
    init { require(language.profile.coreDialect == catalog.product) }
    override val dialect: String = catalog.product
    override val label: String = catalog.productLabel
    override val localParser: Boolean = true
    override val reportsPsiErrors: Boolean = true
    override val sizeCap: Int = 100_000
    override val budget: Duration = 2.seconds
    private val verification = DataSourceVerification(catalog.dataSources) {
        // Deliberately do not call its connection supplier: no qualified parse-only Flight SQL operation.
        UnavailableVerifier(dialect, "$label has no qualified parser-only Flight SQL verifier. EXPLAIN and statement preparation may plan or execute functions.")
    }
    override fun validator(file: PsiFile): NativeSqlValidator? = verification.validator(file)
    override fun modificationCount(file: PsiFile): Long = java.util.Objects.hash(verification.modificationCount(file),
        catalog.dataSources.resolve(file).dataSource?.let { catalog.identity(it) }, language.editorProfile.parserSettings(file), pipe?.isEnabled(file.project)).toLong()

    override fun statementDiagnostics(file: PsiFile, script: String, statement: SqlStatementRange): List<SqlDiagnostic>? {
        if (pipe?.isEnabled(file.project) != true || !statement.hasPipe || statement.end - statement.start > sizeCap) return null
        val text = script.substring(statement.start, statement.terminator ?: statement.end)
        val source = Dialects.forName(dialect)
        val tokens = Tokenizer(source.tokenizerConfig).tokenizeForEditor(text, ProgressManager::checkCanceled).tokens
        val diagnostics = tokens.indices.mapNotNull { index ->
            ProgressManager.checkCanceled()
            if (tokens[index].tokenType != TokenType.PIPE_GT) return@mapNotNull null
            val next = tokens.getOrNull(index + 1) ?: return@mapNotNull null
            val name = if (next.tokenType == TokenType.ORDER_BY) "ORDER BY" else next.text.uppercase()
            if (name in source.pipeRunnableStages.orEmpty()) null else SqlDiagnostic(DiagnosticSeverity.ERROR,
                "Brikk PIPE: $name has no qualified lowering for $dialect", statement.start + next.start,
                statement.start + next.end + 1, DiagnosticSource.OTHER)
        }
        return diagnostics.takeIf { it.isNotEmpty() }
    }
}
