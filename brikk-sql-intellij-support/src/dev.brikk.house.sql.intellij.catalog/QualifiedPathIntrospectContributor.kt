package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import org.jetbrains.annotations.ApiStatus

/**
 * Completion after a qualified path (`hive.` or `hive.web.` with the caret after the dot or in the
 * next name) asks the plugin's [AutoIntrospectService] to introspect what the path names. It offers
 * nothing itself; the platform's completion shows the loaded names when invoked again. Only a
 * console or data source of the profile's dbms is used. Needs no indexes, so it also works while
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
                if (!requests.profile.autoIntrospect) return
                val path = qualifiedPathBefore(file.text, parameters.offset, requests.profile.quoteChars) ?: return
                requests.requestPath(file, path)
            }
        })
    }
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
