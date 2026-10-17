package dev.brikk.house.sql.intellij.relation

import com.intellij.database.model.properties.DataTypeFactory
import com.intellij.database.types.DasTypeSystemBase
import com.intellij.database.types.DasTypeSystems
import com.intellij.openapi.progress.ProgressManager
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlTableTypeBase
import dev.brikk.house.sql.scope.ColumnSource
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
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
     * position) of a provider's relation in [profile]; null otherwise, so the platform's own typing
     * applies. While facts are pending, and under write access (a rename), where providers are never
     * asked, it is the type of the last analysis of unchanged SQL, else null. Column types stay
     * unknown: a display type proves no SQL type.
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
        val analysis = RelationAnalysis.of(file, profile, lastKnown = true) ?: return null
        val provider = analysis.facts.providerOf(RelationKind.SLOT, name)?.takeUnless { it.platformResolves } ?: return null
        val relation = analysis.facts.merged.slots[name] ?: return null
        val fallback = relation.provenance?.let { provider.navigationTarget(file, it) } ?: call
        var table: SqlTableType = SqlTableTypeBase.EMPTY_TABLE
        for (fact in relation.columns) {
            val column = ScopeColumn(
                fact.name, name, fact.origin, fact.nullability, fact.sqlType, fact.displayType, ColumnSource(name, fact, RelationKind.SLOT), provenance = fact.provenance, relationKind = RelationKind.SLOT,
            )
            val symbol = RelationColumnSymbol(file, provider, column, fallback)
            table = table.add(SqlTableTypeBase.createType(symbol, DasTypeSystemBase.UNKNOWN, fact.name, false, false, true, null, null))
        }
        return table
    }

    /**
     * The table type of [call], a table function in a FROM position, for a plugin's function-call element to
     * return: a slot's as [of] gives it; else, where the analysis knows the call's columns (call facts, such
     * as [TableFunctionFacts], a structure argument or a query the dialect reads them from), those columns
     * with their SQL types; else the empty table type, which leaves its columns open, where the platform would
     * invent one named after the call. Null outside a FROM position.
     */
    public fun ofTableFunction(call: SqlFunctionCallExpression, profile: RelationScopeProfile): SqlTableType? {
        if (call.parent?.node?.elementType != SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return null
        of(call, profile)?.let { return it }
        val file = call.containingFile ?: return null
        val facts = RelationAnalysis.of(file, profile, lastKnown = true)?.facts?.merged ?: RelationFacts()
        val sql = "SELECT $ALIAS. FROM ${call.text} AS $ALIAS"
        val completion = RelationScope.complete(sql, profile.dialect, facts, "SELECT $ALIAS.".length, checkCanceled = ProgressManager::checkCanceled)
        if (completion.knowledge != Knowledge.FULL || completion.items.isEmpty()) return SqlTableTypeBase.EMPTY_TABLE
        val types = (file.language as? SqlLanguageDialectEx)?.dbms?.let(DasTypeSystems::getTypeSystem)
        var table: SqlTableType = SqlTableTypeBase.EMPTY_TABLE
        for (item in completion.items) {
            val column = item.column ?: continue
            val type = column.sqlType?.let { sqlType -> types?.createDasType(DataTypeFactory.of(sqlType)) } ?: DasTypeSystemBase.UNKNOWN
            table = table.add(SqlTableTypeBase.createType(call, type, column.name, false, false, true, null, null))
        }
        return table
    }

    private const val ALIAS = "brikk_call"
}
