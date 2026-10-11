package dev.brikk.house.sql.intellij

import com.intellij.database.script.ScriptModel
import com.intellij.database.script.ScriptModelUtilCore
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.IElementType
import com.intellij.util.containers.JBIterable

/** Expected, displayable refusal. Unexpected failures and cancellation propagate separately. */
public class SqlExecutionRefusal(message: String) : RuntimeException(message)

/** Map, when present, indexes this rendering's exact input, not an earlier document version. */
public data class RenderedSql(public val sql: String, public val input: String, public val mapToInput: ((Int, Int) -> TextRange?)? = null)
public data class SqlParameterSpan(public val name: String, public val start: Int, public val end: Int)

public interface SqlExecutionAdapter {
    public fun claims(text: String): Boolean
    /** Implementations enforce their own findings/native-validation policy or throw a refusal. */
    public fun translate(text: String, beforeSubstitution: Boolean): RenderedSql
    public fun parameters(text: String): List<SqlParameterSpan> = emptyList()
}

/**
 * Only claimed statements change query text. Platform parameters, selection, request sequencing,
 * original ranges and owner remain with the console. finalizeTranslations must run AFTER parameter
 * prompting and BEFORE executeQueries. It validates the entire selected script before submission.
 */
public class TranslatingScriptModel<E>(
    private val delegate: ScriptModel<E>,
    private val editor: Editor,
    private val adapter: SqlExecutionAdapter,
) : ScriptModel<E>() {
    private val snapshot = EditorSnapshot.capture(editor.document)
    private data class Key(val range: TextRange, val offset: Long)
    private fun key(it: StatementIt<E>) = Key(it.range(), it.rangeOffset())
    private inner class Plan(statement: StatementIt<E>, val original: String) {
        val range = statement.range()
        val offset = statement.rangeOffset()
        val type = statement.type()
        val api = statement.api()
        val value = statement.`object`()
        val externals = statement.externals().map { external ->
            val text = external.text(); val range = external.range()
            val type = external.type(); val api = external.api(); val offset = external.rangeOffset(); val value = external.`object`()
            object : ExternalIt<E> {
                override fun text() = text
                override fun range() = range
                override fun type() = type
                override fun api() = api
                override fun rangeOffset() = offset
                override fun `object`() = value
            }
        }.toList()
        private val nativeParameters = snapshotParameters(statement.parameters())
        private val inspection = ParameterInspection(original, nativeParameters.map {
            TextRange(it.range().startOffset - range.startOffset, it.range().endOffset - range.startOffset)
        })
        val parameters = (nativeParameters + adapter.parameters(inspection.text).mapNotNull { span -> inspection.original(span) }.map { span ->
            require(span.start >= 0 && span.end <= original.length && span.start < span.end)
            object : ParamIt<E> {
                override fun name() = span.name
                override fun description(): Iterable<String> = emptyList()
                override fun text() = original.substring(span.start, span.end)
                override fun range() = TextRange(range.startOffset + span.start, range.startOffset + span.end)
                override fun type() = type
                override fun api() = api
                override fun rangeOffset() = offset
                override fun `object`() = value
            }
        }).distinctBy { it.name() to it.range() }
        // Parameters can occur in lowering-sensitive positions such as LIMIT. The platform
        // needs original text while prompting; generation is deferred until real values exist.
        var rendered: RenderedSql? = if (parameters.isEmpty()) adapter.translate(original, true) else null
        var finalized = false
    }
    private val baseParameters = snapshotParameters(delegate.parameters())
    private val plans: Map<Key, Plan> = delegate.statements().mapNotNull { statement ->
        ProgressManager.checkCanceled()
        val rawOffset = statement.rangeOffset()
        if (rawOffset !in 0..Int.MAX_VALUE.toLong()) throw SqlExecutionRefusal("Invalid statement offset")
        val range = statement.range().shiftRight(rawOffset.toInt())
        val cached = statement.query()
        if (range.isEmpty || range.startOffset < 0 || range.endOffset > snapshot.text.length) {
            if (adapter.claims(cached)) throw SqlExecutionRefusal("The prepared statement is stale. Run the current SQL again.")
            return@mapNotNull null
        }
        val current = snapshot.text.substring(range.startOffset, range.endOffset)
        if (!adapter.claims(current)) {
            if (adapter.claims(cached)) throw SqlExecutionRefusal("The prepared statement changed. Run the current SQL again.")
            return@mapNotNull null
        }
        key(statement) to Plan(statement, current)
    }.toList().toMap()

    public fun finalizeTranslations(storage: PStorage) {
        snapshot.requireCurrent()
        for (plan in plans.values) {
            ProgressManager.checkCanceled()
            TranslatedStatement(plan).consoleQuery(storage, Conditions.alwaysFalse())
            plan.finalized = true
        }
        snapshot.requireCurrent()
    }

    /** Authored coordinates are available only when parameter expansion preserved exact input. */
    public fun authoredMap(range: TextRange, rangeOffset: Long): ((Int, Int) -> TextRange?)? {
        val plan = plans[Key(range, rangeOffset)] ?: return null
        val rendered = plan.rendered ?: return null
        if (!plan.finalized || rendered.input != plan.original || editor.document.modificationStamp != snapshot.revision) return null
        return rendered.mapToInput
    }

    override fun isActual(): Boolean = delegate.isActual && editor.document.modificationStamp == snapshot.revision
    override fun subModel(range: TextRange?): ScriptModel<E> = TranslatingScriptModel(delegate.subModel(range), editor, adapter)
    override fun everything(): JBIterable<E> = delegate.everything()
    override fun statements(): JBIterable<out StatementIt<E>> = delegate.statements().transform { plans[key(it)]?.let(::TranslatedStatement) ?: it }
    override fun parameters(): JBIterable<out ParamIt<E>> = JBIterable.from(
        (baseParameters + plans.values.flatMap { it.parameters }).distinctBy { it.name() to it.range() })
    override fun externals(): JBIterable<out ExternalIt<E>> = delegate.externals()
    override fun getVirtualFile(): VirtualFile? = delegate.virtualFile
    override fun getTextRange(): TextRange = delegate.textRange
    override fun getLanguage(): Language = delegate.language
    override fun <EE : Any?> rawTransform(transform: com.intellij.util.Function<in SyntaxTraverser<E>, out SyntaxTraverser<EE>>): ScriptModel<EE> =
        TranslatingScriptModel(delegate.rawTransform(transform), editor, adapter)

    private inner class TranslatedStatement(private val plan: Plan) : StatementIt<E> {
        override fun query() = plan.rendered?.sql ?: plan.original
        override fun text() = plan.original
        override fun range() = plan.range
        override fun rangeOffset() = plan.offset
        override fun type(): IElementType = plan.type
        override fun api() = plan.api
        override fun `object`() = plan.value
        override fun externals(): JBIterable<out ExternalIt<E>> = JBIterable.from(plan.externals)
        override fun parameters(): JBIterable<out ParamIt<E>> = JBIterable.from(plan.parameters)
        override fun consoleQuery(storage: PStorage, condition: Condition<in ParamIt<E>>): String {
            snapshot.requireCurrent()
            if (!plan.finalized) plan.rendered = adapter.translate(ScriptModelUtilCore.statementText(this, storage, condition), false)
            return requireNotNull(plan.rendered).sql
        }
    }
}

/** Core parses normalized inspection text; native IDE parameter ranges retain original identity. */
private class ParameterInspection(original: String, native: List<TextRange>) {
    private data class Edit(val original: TextRange, val rendered: TextRange)
    private val edits = mutableListOf<Edit>()
    val text: String = buildString {
        var cursor = 0
        for ((index, range) in native.distinct().sortedBy { it.startOffset }.withIndex()) {
            require(range.startOffset >= cursor && range.endOffset <= original.length && !range.isEmpty) { "Invalid platform parameter range" }
            append(original, cursor, range.startOffset)
            val start = length
            append(":__brikk_ide_parameter_$index")
            edits.add(Edit(range, TextRange(start, length)))
            cursor = range.endOffset
        }
        append(original, cursor, original.length)
    }
    fun original(span: SqlParameterSpan): SqlParameterSpan? {
        if (edits.any { span.start < it.rendered.endOffset && span.end > it.rendered.startOffset }) return null
        val delta = edits.filter { it.rendered.endOffset <= span.start }.sumOf { it.rendered.length - it.original.length }
        return span.copy(start = span.start - delta, end = span.end - delta)
    }
}

private fun <E> snapshotParameters(parameters: Iterable<ScriptModel.ParamIt<E>>): List<ScriptModel.ParamIt<E>> = parameters.map { parameter ->
    val name = parameter.name(); val description = parameter.description().toList(); val text = parameter.text()
    val range = parameter.range(); val type = parameter.type(); val api = parameter.api()
    val offset = parameter.rangeOffset(); val value = parameter.`object`()
    object : ScriptModel.ParamIt<E> {
        override fun name() = name
        override fun description(): Iterable<String> = description
        override fun text() = text
        override fun range() = range
        override fun type() = type
        override fun api() = api
        override fun rangeOffset() = offset
        override fun `object`() = value
    }
}
