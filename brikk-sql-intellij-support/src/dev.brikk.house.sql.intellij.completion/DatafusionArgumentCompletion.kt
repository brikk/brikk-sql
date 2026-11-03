package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasSymbol
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import dev.brikk.house.sql.intellij.dialect.DatafusionEditorProfile
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.shape.SqlNameContext
import dev.brikk.house.sql.shape.datafusionCallSite
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/** Register before the ordinary function contributor. Parameter labels use `=>`, not column insertion. */
public abstract class DatafusionArgumentCompletion(private val profile: DatafusionEditorProfile) : CompletionContributor(), DumbAware {
    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (parameters.completionType != CompletionType.BASIC || file.language != profile.language) return
        val source = PipeText.of(file)
        val start = source.textOffset(prefixAt(file, parameters.offset).first)
        val before = source.text.take(start)
        if (SqlNameContext.of(before, profile.dialect)?.inCode == false) return
        val site = datafusionCallSite(source.text, start, profile.dialect, ProgressManager::checkCanceled)
        val typePosition = datafusionTypePosition(before, profile.dialect)
        if (typePosition) {
            val offered = HashSet<String>()
            result.runRemainingContributors(parameters) { item -> offered += item.lookupElement.lookupString.uppercase(); result.passResult(item) }
            for (type in profile.snapshot(file).types.orEmpty()) {
                if (type.name.uppercase() !in offered) result.caseInsensitive().addElement(LookupElementBuilder.create(type.name)
                    .withTypeText("${profile.dialect} type", true))
            }
            return
        }
        if (profile.dialect != "spice" || site == null || !site.atArgumentStart) return
        val minimum = if (site.name in setOf("rrf", "rerank")) 1 else 2
        if (site.argument < minimum) return
        val names = profile.functions(file).filter { it.name.equals(site.name, true) }.flatMap { it.namedParameters }.distinct()
        val available = names.filterNot { it in site.usedNames }
        if (available.isEmpty()) return
        val keys = available.mapTo(HashSet()) { it.lowercase() }
        result.runRemainingContributors(parameters) { completion ->
            val item = completion.lookupElement
            val target = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.`object`
            val duplicate = item.lookupString.lowercase() in keys &&
                ((target as? DasSymbol)?.kind == ObjectKind.ARGUMENT ||
                    target is String && LookupElementPresentation.renderElement(item).isItemTextBold)
            if (!duplicate) result.passResult(completion)
        }
        for (name in available) {
            result.caseInsensitive().addElement(LookupElementBuilder.create(name).withTypeText("${site.name} argument", true)
                .withInsertHandler(NamedArrow))
        }
    }

    private object NamedArrow : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val tail = context.document.charsSequence.subSequence(context.tailOffset, context.document.textLength).toString()
            val existing = Tokenizer(TokenizerConfigs.forName("spice")).tokenizeForEditor(tail, ProgressManager::checkCanceled)
                .tokens.firstOrNull()?.takeIf { it.text == "=>" }
            val at = context.tailOffset
            if (existing == null) {
                context.document.insertString(at, " => ")
                context.editor.caretModel.moveToOffset(at + 4)
            } else {
                var end = existing.end + 1
                while (end < tail.length && tail[end].isWhitespace()) end++
                context.editor.caretModel.moveToOffset(at + end)
            }
        }
    }
}

internal fun datafusionTypePosition(before: String, dialect: String): Boolean {
    val context = SqlNameContext.of(before, dialect) ?: return false
    if (!context.inCode) return false
    if (context.tokens.lastOrNull()?.text == "::") return true
    val site = datafusionCallSite(before, before.length, dialect) ?: return false
    return site.name in setOf("cast", "try_cast") && context.tokens.lastOrNull()?.text.equals("AS", true)
}
