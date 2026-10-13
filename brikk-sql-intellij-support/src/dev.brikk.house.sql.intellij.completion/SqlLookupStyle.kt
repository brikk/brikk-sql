package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import icons.DatabaseIcons
import java.util.Locale
import javax.swing.Icon
import org.jetbrains.annotations.ApiStatus

/** The kinds of completion item a dialect presents; each has one [SqlLookupStyle] in [SqlLookupStyles]. */
@ApiStatus.Experimental
public enum class SqlLookupKind {
    KEYWORD,
    FUNCTION,
    AGGREGATE,
    WINDOW,
    MACRO,
    TABLE_FUNCTION,
    TABLE_MACRO,

    /** A function an extension adds; only the type text applies, the rest comes from its function kind. */
    EXTENSION_FUNCTION,

    /** A function only the connected server reports; only the type text applies, the rest comes from its function kind. */
    SERVER_FUNCTION,
    TABLE_FUNCTION_PROPERTY,
    TABLE_FUNCTION_VALUE,
    COLUMN,

    /** A stage phrase right after `|>`, such as `WHERE` or `LEFT JOIN`. */
    PIPE_STAGE,
    PIPE_INPUT_COLUMN,
    BRIKK_COLUMN,

    /** A column of an external relation, or computed over one, that no other item offers. */
    RELATION_COLUMN,
    CATALOG,
    SCHEMA,
    TABLE,
}

/** Icons the platform lacks, in its new UI style; dark themes load the `_dark` variant. */
@ApiStatus.Experimental
public object SqlLookupIcons {
    /** A "W" in the platform's function circle. */
    public val WindowFunction: Icon = load("windowFunction")

    /** Five bricks laid 2-1-2. */
    public val BrikkColumn: Icon = load("brikkColumn")

    private fun load(name: String): Icon =
        IconLoader.getIcon("dev/brikk/house/sql/intellij/icons/$name.svg", SqlLookupIcons::class.java.classLoader)
}

/** How a lookup string is spelled relative to the name metadata or the model gives. */
@ApiStatus.Experimental
public enum class LookupCase {
    AS_GIVEN,
    LOWER,
    UPPER,
    ;

    public fun apply(name: String): String = when (this) {
        AS_GIVEN -> name
        LOWER -> name.lowercase(Locale.ROOT)
        UPPER -> name.uppercase(Locale.ROOT)
    }
}

/**
 * Presentation of one [SqlLookupKind]. In [typeText], `%s` stands for the owner: the extension
 * of an extension function, the table function of a property or value. Functions show their
 * argument types as tail text when [showsSignature], and their return type instead of
 * [typeText] when [showsReturnType] and one is known.
 */
@ApiStatus.Experimental
public data class SqlLookupStyle(
    val icon: Icon? = null,
    val typeText: String? = null,
    val bold: Boolean = false,
    val priority: Double = 0.0,
    val case: LookupCase = LookupCase.AS_GIVEN,
    val showsSignature: Boolean = false,
    val showsReturnType: Boolean = false,
)

/**
 * One style per [SqlLookupKind]. [DEFAULT] is the proposed shared presentation; a dialect
 * replaces the rows it presents differently with [with].
 */
@ApiStatus.Experimental
public class SqlLookupStyles private constructor(private val styles: Map<SqlLookupKind, SqlLookupStyle>) {
    public operator fun get(kind: SqlLookupKind): SqlLookupStyle = styles.getValue(kind)

    public fun with(vararg rows: Pair<SqlLookupKind, SqlLookupStyle>): SqlLookupStyles = SqlLookupStyles(styles + rows)

    public companion object {
        public val DEFAULT: SqlLookupStyles = SqlLookupStyles(
            SqlLookupKind.entries.associateWith { kind ->
                when (kind) {
                    SqlLookupKind.KEYWORD -> SqlLookupStyle(bold = true, case = LookupCase.UPPER)
                    SqlLookupKind.FUNCTION -> callable(DatabaseIcons.Function, "function")
                    SqlLookupKind.AGGREGATE -> callable(DatabaseIcons.Aggregate, "aggregate")
                    SqlLookupKind.WINDOW -> callable(SqlLookupIcons.WindowFunction, "window function")
                    SqlLookupKind.MACRO -> callable(DatabaseIcons.Routine, "macro")
                    SqlLookupKind.TABLE_FUNCTION -> SqlLookupStyle(DatabaseIcons.Table, "table function", showsSignature = true)
                    SqlLookupKind.TABLE_MACRO -> SqlLookupStyle(DatabaseIcons.Table, "table macro", showsSignature = true)
                    SqlLookupKind.EXTENSION_FUNCTION -> SqlLookupStyle(typeText = "requires %s")
                    SqlLookupKind.SERVER_FUNCTION -> SqlLookupStyle(typeText = "server")
                    SqlLookupKind.TABLE_FUNCTION_PROPERTY -> SqlLookupStyle(AllIcons.Nodes.Parameter, "%s property", priority = 100.0)
                    SqlLookupKind.TABLE_FUNCTION_VALUE -> SqlLookupStyle(AllIcons.Nodes.Parameter, "%s value", priority = 100.0)
                    SqlLookupKind.COLUMN -> SqlLookupStyle(DatabaseIcons.Col)
                    SqlLookupKind.PIPE_STAGE -> SqlLookupStyle(typeText = "PIPE stage", bold = true, case = LookupCase.UPPER)
                    SqlLookupKind.PIPE_INPUT_COLUMN -> SqlLookupStyle(DatabaseIcons.Col, "PIPE input column", priority = 95.0)
                    SqlLookupKind.BRIKK_COLUMN -> SqlLookupStyle(SqlLookupIcons.BrikkColumn, priority = 120.0)
                    SqlLookupKind.RELATION_COLUMN -> SqlLookupStyle(DatabaseIcons.Col, priority = 110.0)
                    SqlLookupKind.CATALOG -> SqlLookupStyle(DatabaseIcons.Database, "catalog", priority = 96.0)
                    SqlLookupKind.SCHEMA -> SqlLookupStyle(DatabaseIcons.Schema, "schema", priority = 96.0)
                    SqlLookupKind.TABLE -> SqlLookupStyle(DatabaseIcons.Table, "table", priority = 96.0)
                }
            },
        )

        private fun callable(icon: Icon, typeText: String) =
            SqlLookupStyle(icon, typeText, showsSignature = true, showsReturnType = true)
    }
}

/** A plain item for [name] in this style, such as a catalog path segment. */
@ApiStatus.Experimental
public fun SqlLookupStyle.lookup(name: String, typeText: String? = this.typeText): LookupElement =
    lookup(name, name, typeText)

internal fun SqlLookupStyle.lookup(
    identity: Any,
    name: String,
    typeText: String?,
    tailText: String? = null,
    insertHandler: InsertHandler<LookupElement>? = null,
): LookupElement {
    var element = LookupElementBuilder.create(identity, case.apply(name)).withBoldness(bold)
    icon?.let { element = element.withIcon(it) }
    typeText?.let { element = element.withTypeText(it, true) }
    tailText?.let { element = element.withTailText(it, true) }
    insertHandler?.let { element = element.withInsertHandler(it) }
    return if (priority == 0.0) element else PrioritizedLookupElement.withPriority(element, priority)
}

internal fun SqlLookupStyle.typeText(owner: String): String? = typeText?.replace("%s", owner)
