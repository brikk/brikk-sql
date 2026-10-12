package dev.brikk.house.sql.intellij.completion

import com.intellij.lang.Language
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.completionEntries
import java.util.Collections
import java.util.WeakHashMap
import org.jetbrains.annotations.ApiStatus

/**
 * One plugin's completion profile. The minimum is [language] and [metadata]; every other member
 * has a working default. Gates and filters are off by default.
 */
@ApiStatus.Experimental
public interface SqlCompletionProfile {
    /** Completion applies to files of this language or its dialects. */
    public val language: Language

    public val metadata: DialectMetadata

    /** The database knowledge completion reads in [file]. */
    public fun snapshot(file: PsiFile): MetadataSnapshot = metadata.default

    /** The functions offered in [file]. */
    public fun functions(file: PsiFile): List<FunctionPresentation> = snapshot(file).completableFunctions()

    /** Functions the connected server reports beyond [functions], presented as [SqlLookupKind.SERVER_FUNCTION]. */
    public fun serverOnlyFunctions(file: PsiFile): List<FunctionPresentation> = emptyList()

    public val styles: SqlLookupStyles get() = SqlLookupStyles.DEFAULT

    /** Lookup kinds for engine-native function kinds ([FunctionPresentation.nativeKind]) that the normalized kind does not tell apart. */
    public val nativeKinds: Map<String, SqlLookupKind> get() = emptyMap()

    public val functionGates: SqlFunctionGates get() = SqlFunctionGates.OFF

    /**
     * Where only table functions are offered; everywhere else they are hidden, and where the text
     * before the caret does not tell, every function is offered.
     */
    public val tablePositions: TablePositions get() = TablePositions.STANDARD

    public val keywords: KeywordScope get() = KeywordScope.ALL

    /** The keywords offered in [file], whose [snapshot] completion already read; a live harvest can supply its own. */
    public fun keywordCatalog(file: PsiFile, snapshot: MetadataSnapshot): KeywordCatalog? = snapshot.keywords

    /** The quote a table-function property or value is wrapped in when completed outside a string. */
    public val propertyQuote: Char get() = '\''

    /**
     * Which platform builtin-function items (`functions.xml` prototypes of the base dialect)
     * completion keeps in [file], by name; null keeps all of them.
     */
    public fun builtinFunctionFilter(file: PsiFile): ((name: String) -> Boolean)? = null
}

/** Which keywords completion offers. */
@ApiStatus.Experimental
public enum class KeywordScope { NONE, RESERVED, ALL }

/**
 * When function completion withholds functions. [digitPrefix] withholds them after a
 * digit-led prefix (`GROUP BY 1`), also skipping the autopopup there for every contributor
 * when [SqlCompletionConfidence] is registered. [autopopup], when set, offers functions in an
 * autopopup only at those expression positions; explicit completion always offers them.
 */
@ApiStatus.Experimental
public data class SqlFunctionGates(
    val qualifier: QualifierRule = QualifierRule.Unrestricted,
    val digitPrefix: Boolean = false,
    val autopopup: ExpressionPositions? = null,
) {
    public companion object {
        public val OFF: SqlFunctionGates = SqlFunctionGates()
    }
}

/** Which functions completion offers after a qualifier (`t.<caret>`). */
@ApiStatus.Experimental
public sealed interface QualifierRule {
    /** The same functions as without a qualifier. */
    public data object Unrestricted : QualifierRule

    /** None: the dialect has no qualified functions. */
    public data object Unqualified : QualifierRule

    /** The functions of the namespace that [qualifier] (its source text) names. */
    public fun interface Namespaced : QualifierRule {
        public fun functions(qualifier: String, file: PsiFile): List<FunctionPresentation>
    }
}

/**
 * Expression positions, recognized by the token before the word being typed: one of [operators]
 * or, ignoring case, one of [keywords].
 */
@ApiStatus.Experimental
public data class ExpressionPositions(val keywords: Set<String>, val operators: Set<String>) {
    public companion object {
        /** Standard SQL; `*` is left out, since after `SELECT *` a function is noise. */
        public val STANDARD: ExpressionPositions = ExpressionPositions(
            keywords = setOf(
                "SELECT", "WHERE", "HAVING", "ON", "AND", "OR", "NOT", "IN", "LIKE",
                "BETWEEN", "THEN", "ELSE", "WHEN", "CASE", "IS", "DISTINCT",
            ),
            operators = setOf("(", ",", "=", "<", ">", "<=", ">=", "<>", "!=", "+", "-", "/", "%", "||"),
        )
    }
}

private val completable: MutableMap<MetadataSnapshot, List<FunctionPresentation>> = Collections.synchronizedMap(WeakHashMap())

/**
 * [completionEntries] without extension functions whose name the base engine already has, and
 * without table functions metadata marks as not queryable.
 */
@ApiStatus.Experimental
public fun MetadataSnapshot.completableFunctions(): List<FunctionPresentation> = completable.getOrPut(this) {
    completionEntries().filter { entry ->
        when {
            entry.extension != null -> functions.getAll(entry.name).isEmpty()
            entry.kind == FunctionKind.TABLE_VALUED || entry.kind == FunctionKind.TABLE_GENERATING ->
                tableFunction(entry.name)?.queryable != false
            else -> true
        }
    }
}
