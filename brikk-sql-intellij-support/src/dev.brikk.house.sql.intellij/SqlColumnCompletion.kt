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
 * be proved), the alias of the relation supplying it and where the facts come from. [source]
 * identifies the supplying relation, opaquely and by equality; with [name] it is the column's
 * identity for matching, deduplication and the lookup object. The alias is only shown.
 */
@ApiStatus.Experimental
public data class SqlColumnFacts(
    val name: String,
    val typeName: String?,
    val nullable: Boolean?,
    val relationAlias: String? = null,
    val originLabel: String = "brikk column",
    val source: Any? = null,
) {
    internal val typeText: String
        get() = (typeName ?: "unknown type") + when (nullable) {
            true -> "?"
            false -> ""
            null -> " (nullability unknown)"
        }

    internal val tailText: String
        get() = "  $originLabel" + relationAlias?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    internal val identity: ColumnIdentity get() = ColumnIdentity(name, source)
}

internal data class ColumnIdentity(val name: String, val source: Any?)

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
    ): LookupElement {
        // Different relations may supply the same column name; here only the alias tells them apart.
        val source = relationAlias?.takeIf { it.isNotBlank() }
        return create(SqlColumnFacts(name, typeName, nullable, relationAlias, originLabel, source), SqlLookupStyle(priority = priority))
    }

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

    /**
     * A column of a PIPE stage's input, from the relation [source] identifies (see
     * [SqlColumnFacts.source]); without a known [typeName] the style's type text shows.
     */
    @ApiStatus.Experimental
    public fun pipeInput(
        name: String,
        typeName: String? = null,
        style: SqlLookupStyle = SqlLookupStyles.DEFAULT[SqlLookupKind.PIPE_INPUT_COLUMN],
        source: Any? = null,
    ): LookupElement = style.lookup(ColumnIdentity(name, source), name, typeName ?: style.typeText)

    /**
     * Offers [columns], the columns visible at the caret, once per identity: the first of other
     * contributors' items that [match] maps to a visible column is decorated and further ones are
     * dropped; the columns no item matched are created in [style]. [match] identifies a candidate
     * by its semantic column and source, so same-named columns of different relations stay
     * distinct. Call it from a contributor registered with `order="first"`, since it runs the
     * remaining contributors.
     */
    @ApiStatus.Experimental
    public fun createOrDecorate(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        columns: Collection<SqlColumnFacts>,
        style: SqlLookupStyle = SqlLookupStyles.DEFAULT[SqlLookupKind.BRIKK_COLUMN],
        match: (LookupElement) -> SqlColumnFacts?,
    ) {
        val visible = LinkedHashMap<ColumnIdentity, SqlColumnFacts>().apply { for (column in columns) putIfAbsent(column.identity, column) }
        val offered = HashSet<ColumnIdentity>()
        result.runRemainingContributors(parameters) { completion ->
            val column = match(completion.lookupElement)?.let { visible[it.identity] }
            when {
                column == null -> result.passResult(completion)
                offered.add(column.identity) -> result.passResult(completion.withLookupElement(decorate(completion.lookupElement, column)))
            }
        }
        for ((identity, column) in visible) if (offered.add(identity)) result.addElement(create(column, style))
    }
}
