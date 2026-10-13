package dev.brikk.house.sql.intellij.relation

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.ScopeColumn
import org.jetbrains.annotations.ApiStatus

/** What one provider knows about the relations SQL in a file can name. */
@ApiStatus.Experimental
public sealed interface RelationFactsResult {
    /** Copied [facts]; [revision] changes whenever they may (a model refresh, a new contract). */
    public data class Available(val facts: RelationFacts, val revision: Long) : RelationFactsResult

    /** Not known yet (indexing, a connection, a host analysis): nothing is analyzed or cached until it is. */
    public data class Pending(val reason: String) : RelationFactsResult
}

/**
 * A source of relation facts for SQL in files: a host language's row types, a catalog, a data
 * source's tables. Facts are copies, never live model or PSI objects.
 */
@ApiStatus.Experimental
public interface ExternalRelationFacts {
    /** The facts for the SQL of [file] (an injected fragment when the SQL is injected), or null when this provider has none for it. */
    public fun facts(file: PsiFile): RelationFactsResult?

    /** The element a fact's opaque [provenance] names, for navigation; null when none. */
    public fun navigationTarget(file: PsiFile, provenance: String): PsiElement? = null

    /**
     * Whether the platform already resolves references to these relations itself, as it does for
     * the tables of an attached data source: then they are only analyzed (completion, PIPE stages,
     * diagnostics), and get no light symbols, suppression or navigation of their own.
     */
    public val platformResolves: Boolean get() = false

    /** How this provider's columns are labeled in completion; null uses the profile's. */
    public val labels: RelationColumnLabels? get() = null
}

/**
 * One consumer's relation features: SQL of exactly [language] (files and injected fragments) is
 * analyzed in core [dialect] over the facts of [providers], merged in order (the first provider of
 * a slot, table or call key wins).
 */
@ApiStatus.Experimental
public interface RelationScopeProfile {
    public val language: Language

    public val dialect: String

    /** Stable instances: the analysis is cached per provider instance and its revision. */
    public val providers: List<ExternalRelationFacts>

    /** Labels of computed columns and of providers without their own. */
    public val labels: RelationColumnLabels get() = RelationColumnLabels()

    public val styles: SqlLookupStyles get() = SqlLookupStyles.DEFAULT
}

/**
 * Completion labels by column origin: the origin text shown with the relation, its lookup kind
 * (icon and priority from the profile's styles, for items no other contributor offers) and its type. A declared column shows the
 * provider's display type, a physical one its SQL type, a computed one what computes it.
 */
@ApiStatus.Experimental
public data class RelationColumnLabels(
    val declared: String = "declared column",
    val declaredKind: SqlLookupKind = SqlLookupKind.RELATION_COLUMN,
    val contract: String = "relation contract",
    val contractKind: SqlLookupKind = SqlLookupKind.RELATION_COLUMN,
    val computed: String = "computed column",
    val physical: String = "column",
) {
    /** How [column] shows in completion. */
    public fun label(column: ScopeColumn): RelationColumnLabel {
        val nullable = when (column.nullability) {
            Nullability.NON_NULL -> false
            Nullability.NULLABLE -> true
            Nullability.UNKNOWN -> null
        }
        val relation = column.relation.ifBlank { column.source?.relation.orEmpty() }
        return when (column.origin) {
            ColumnOrigin.DECLARED -> RelationColumnLabel(declared, relation, column.displayType ?: column.sqlType, nullable, declaredKind)
            ColumnOrigin.RELATION_CONTRACT -> RelationColumnLabel(contract, relation, column.displayType ?: column.sqlType, nullable, contractKind)
            ColumnOrigin.PHYSICAL_TABLE ->
                RelationColumnLabel(physical, column.source?.relation?.substringAfterLast('.') ?: relation, column.sqlType, nullable, SqlLookupKind.RELATION_COLUMN)
            ColumnOrigin.COMPUTED ->
                RelationColumnLabel(computed, column.computedBy ?: relation, column.sqlType ?: column.displayType, nullable, SqlLookupKind.RELATION_COLUMN)
        }
    }
}

/** A column's completion presentation: "[origin] ([relation])", its [type] and three-state [nullable]. */
@ApiStatus.Experimental
public data class RelationColumnLabel(
    val origin: String,
    val relation: String,
    val type: String?,
    val nullable: Boolean?,
    val kind: SqlLookupKind,
)
