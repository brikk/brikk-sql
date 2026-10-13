package dev.brikk.house.sql.intellij.relation

import com.intellij.database.types.DasTypeSystemBase
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlTableTypeBase
import dev.brikk.house.sql.scope.ColumnSource
import dev.brikk.house.sql.scope.RelationKind
import dev.brikk.house.sql.scope.ScopeColumn
import org.jetbrains.annotations.ApiStatus

/**
 * The optional native adapter: a slot call's columns as the platform's own table type. A plugin
 * returns [of] from its function-call element's type computation (an element factory override);
 * PIPE stages, which have no such PSI, keep the analysis.
 */
@ApiStatus.Experimental
public object RelationTableTypes {
    /**
     * The table type of [call] when it is a slot (an unqualified call without arguments in a FROM
     * position) of a provider's relation in [profile]; null otherwise, and while facts are pending,
     * so the platform's own typing applies. Column types stay unknown: a display type proves no SQL type.
     *
     * Each column's element is the column's symbol, as the resolve extension gives it: equivalent to
     * the provider's navigation target for the column, so the platform's find usages and rename of
     * that declaration include the SQL referring to it, and navigating to it, else to the relation's
     * target, else to the call. It is equal across calls.
     */
    public fun of(call: SqlFunctionCallExpression, profile: RelationScopeProfile): SqlTableType? {
        if (call.parent?.node?.elementType != SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return null
        if (!call.parameterList?.expressionList.isNullOrEmpty() || call.nameElement?.qualifierExpression != null) return null
        val name = call.nameElement?.name ?: return null
        val file = call.containingFile ?: return null
        val analysis = RelationAnalysis.of(file, profile) ?: return null
        val provider = analysis.facts.providerOf(RelationKind.SLOT, name)?.takeUnless { it.platformResolves } ?: return null
        val relation = analysis.facts.merged.slots[name] ?: return null
        val fallback = relation.provenance?.let { provider.navigationTarget(file, it) } ?: call
        var table: SqlTableType = SqlTableTypeBase.EMPTY_TABLE
        for (fact in relation.columns) {
            val column = ScopeColumn(
                fact.name, name, fact.origin, fact.nullability, fact.sqlType, fact.displayType, ColumnSource(name, fact), provenance = fact.provenance,
            )
            val symbol = RelationColumnSymbol(file, provider, column, fallback)
            table = table.add(SqlTableTypeBase.createType(symbol, DasTypeSystemBase.UNKNOWN, fact.name, false, false, true, null, null))
        }
        return table
    }
}
