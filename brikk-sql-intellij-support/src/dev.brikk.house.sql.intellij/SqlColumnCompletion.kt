package dev.brikk.house.sql.intellij

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyle
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.intellij.completion.lookup
import org.jetbrains.annotations.ApiStatus

/**
 * What a column completion item shows: its type, three-state [nullable] (null when it cannot
 * be proved), the alias of the relation supplying it and where the facts come from.
 */
@ApiStatus.Experimental
public data class SqlColumnFacts(
    val name: String,
    val typeName: String?,
    val nullable: Boolean?,
    val relationAlias: String? = null,
    val originLabel: String = "brikk column",
) {
    internal val typeText: String
        get() = (typeName ?: "unknown type") + when (nullable) {
            true -> "?"
            false -> ""
            null -> " (nullability unknown)"
        }

    internal val tailText: String
        get() = "  $originLabel" + alias?.let { " ($it)" }.orEmpty()

    // Different relations may supply the same column name; the alias keeps both lookup identities.
    internal val identity: Pair<String, String> get() = name to alias.orEmpty()

    private val alias: String? get() = relationAlias?.takeIf { it.isNotBlank() }
}

/** Shared column presentation. Consumers supply semantic facts; no Kotlin IDE types are required. */
public object SqlColumnCompletion {
    /** [nullable] is true/false when known and null when the contract cannot prove it. */
    public fun create(
        name: String,
        typeName: String?,
        nullable: Boolean?,
        relationAlias: String? = null,
        originLabel: String = "brikk column",
        priority: Double = 120.0,
    ): LookupElement = create(SqlColumnFacts(name, typeName, nullable, relationAlias, originLabel), SqlLookupStyle(priority = priority))

    /** A new item for [column]; the facts give its type and tail text, [style] the rest. */
    @ApiStatus.Experimental
    public fun create(column: SqlColumnFacts, style: SqlLookupStyle = SqlLookupStyles.DEFAULT[SqlLookupKind.BRIKK_COLUMN]): LookupElement =
        style.lookup(column.identity, column.name, column.typeText, column.tailText)

    /** [candidate], another contributor's item for [column], showing its facts; icon, insertion and priority stay. */
    @ApiStatus.Experimental
    public fun decorate(candidate: LookupElement, column: SqlColumnFacts): LookupElement =
        object : LookupElementDecorator<LookupElement>(candidate) {
            override fun renderElement(presentation: LookupElementPresentation) {
                super.renderElement(presentation)
                presentation.setTypeText(column.typeText)
                presentation.isTypeGrayed = true
                presentation.setTailText(column.tailText, true)
            }
        }

    /** A column of a PIPE stage's input; without a known [typeName] the style's type text shows. */
    @ApiStatus.Experimental
    public fun pipeInput(
        name: String,
        typeName: String? = null,
        style: SqlLookupStyle = SqlLookupStyles.DEFAULT[SqlLookupKind.PIPE_INPUT_COLUMN],
    ): LookupElement = style.lookup(name, typeName ?: style.typeText)

    /**
     * Offers [columns], the columns visible at the caret, once each: other contributors' items that
     * [match] maps to one of them are decorated, and the columns no item matched are created in
     * [style]. [match] identifies a candidate by its semantic column and source, so same-named
     * columns of different relations stay distinct. Call it from a contributor registered with
     * `order="first"`, since it runs the remaining contributors.
     */
    @ApiStatus.Experimental
    public fun createOrDecorate(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        columns: Collection<SqlColumnFacts>,
        style: SqlLookupStyle = SqlLookupStyles.DEFAULT[SqlLookupKind.BRIKK_COLUMN],
        match: (LookupElement) -> SqlColumnFacts?,
    ) {
        val visible = columns.toSet()
        val decorated = HashSet<SqlColumnFacts>()
        result.runRemainingContributors(parameters) { completion ->
            val column = match(completion.lookupElement)?.takeIf { it in visible }
            if (column == null) {
                result.passResult(completion)
            } else {
                decorated += column
                result.passResult(completion.withLookupElement(decorate(completion.lookupElement, column)))
            }
        }
        for (column in visible) if (column !in decorated) result.addElement(create(column, style))
    }
}
