package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.Resolution
import org.jetbrains.annotations.ApiStatus

/**
 * Completion after a qualified path (`hive.` or `hive.web.` with the caret after the dot or in the
 * next name) asks the plugin's [AutoIntrospectService] to introspect what the path names. It offers
 * nothing itself; the platform's completion shows the loaded names when invoked again. A first name the
 * statement defines (an alias, a CTE, a derived table, by core's scope analysis) is no namespace. Only a
 * console or data source of the profile's dbms is used; comments and string literals never trigger it. Needs no indexes, so it also works while
 * the IDE indexes.
 *
 * Register a no-arg subclass naming the plugin's service class as `completion.contributor` for the
 * dialect's language.
 */
@ApiStatus.Experimental
public abstract class QualifiedPathIntrospectContributor(private val service: Class<out AutoIntrospectService>) : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile
                val requests = file.project.getService(service) ?: return
                if (!requests.profile.autoIntrospect || inCommentOrString(parameters.position)) return
                val path = qualifiedPathBefore(parameters.editor.document.charsSequence, parameters.offset, requests.profile.quoteChars) ?: return
                val dialect = SqlDialectRegistry.forDbms(requests.profile.dbms)?.coreDialect
                if (dialect != null && definesQualifier(file.text, parameters.offset, path.first(), dialect)) return
                requests.requestPath(file, path)
            }
        })
    }
}

/**
 * Whether the statement at [offset] of [text] defines [name] as a relation it reads (an alias, a CTE, a derived
 * table), so `name.` qualifies its columns and names no namespace; read by core's scope analysis, with a name
 * put at the caret so the statement parses.
 */
internal fun definesQualifier(text: String, offset: Int, name: String, dialect: String): Boolean {
    val at = offset.coerceIn(0, text.length)
    val completed = text.substring(0, at) + PLACEHOLDER + text.substring(at)
    val statement = SqlScriptSnapshot.parse(completed, dialect, ProgressManager::checkCanceled).at(at) ?: return false
    val scope = RelationScope.analyze(completed.substring(statement.start, statement.end), dialect, RelationFacts())
    val local = at - statement.start
    return scope.references.any {
        it.kind == ReferenceKind.QUALIFIER && it.resolution == Resolution.RESOLVED && it.start < local && it.start >= local - PATH_WINDOW &&
            it.name.equals(name, ignoreCase = true)
    }
}

/** A name for the caret's place, so a statement whose name is being typed parses. */
private const val PLACEHOLDER = "brikk_completion_7f3a"

private fun inCommentOrString(position: PsiElement): Boolean {
    if (PsiTreeUtil.getNonStrictParentOfType(position, PsiComment::class.java) != null) return true
    val type = position.node?.elementType ?: return false
    val definition = LanguageParserDefinitions.INSTANCE.forLanguage(position.language) ?: return false
    return definition.stringLiteralElements.contains(type) || definition.commentTokens.contains(type)
}

/** How far back a path before the caret may start. */
private const val PATH_WINDOW = 400

/**
 * The names of the qualified path that ends with a dot just before [offset] (optionally followed by
 * spaces and the name being typed), unquoted; null when there is none. A doubled quote inside a
 * quoted name stands for the quote.
 */
internal fun qualifiedPathBefore(text: CharSequence, offset: Int, quoteChars: String): List<String>? {
    val end = offset.coerceIn(0, text.length)
    var names = mutableListOf<String>()
    var afterDot = false
    var afterName = false
    var i = (end - PATH_WINDOW).coerceAtLeast(0)
    while (i < end) {
        val c = text[i]
        when {
            c in quoteChars -> {
                val name = StringBuilder()
                var j = i + 1
                while (j < end && !(text[j] == c && (j + 1 >= end || text[j + 1] != c))) {
                    name.append(text[j])
                    j += if (text[j] == c) 2 else 1
                }
                if (afterName || !afterDot) names = mutableListOf()
                names += name.toString()
                afterName = true
                afterDot = false
                i = j + 1
                continue
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < end && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                if (afterName || !afterDot) names = mutableListOf()
                names += text.substring(i, j)
                afterName = true
                afterDot = false
                i = j
                continue
            }
            c == '.' && afterName -> {
                afterDot = true
                afterName = false
            }
            c.isWhitespace() && afterDot -> Unit
            else -> {
                names = mutableListOf()
                afterDot = false
                afterName = false
            }
        }
        i++
    }
    val path = if (afterDot) names else names.dropLast(1)
    return path.takeIf { it.isNotEmpty() && (afterDot || afterName) }
}
