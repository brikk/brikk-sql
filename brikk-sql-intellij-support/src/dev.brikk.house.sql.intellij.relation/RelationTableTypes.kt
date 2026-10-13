package dev.brikk.house.sql.intellij.relation

import com.intellij.database.types.DasTypeSystemBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlTableTypeBase
import com.intellij.util.IncorrectOperationException
import dev.brikk.house.sql.scope.ColumnFact
import org.jetbrains.annotations.ApiStatus

/**
 * The optional native adapter: a slot call's columns as the platform's own table type, so ordinary
 * SQL over it gets the platform's resolve, rename and find usages without light symbols. A plugin
 * returns [of] from its function-call element's type computation (an element factory override);
 * PIPE stages, which have no such PSI, keep the analysis.
 */
@ApiStatus.Experimental
public object RelationTableTypes {
    /**
     * The table type of [call] when it is a slot (an unqualified call without arguments in a FROM
     * position) of a provider's relation in [profile]; null otherwise, and while facts are pending,
     * so the platform's own typing applies. Column types stay unknown: a display type proves no SQL type.
     */
    public fun of(call: SqlFunctionCallExpression, profile: RelationScopeProfile): SqlTableType? {
        if (call.parent?.node?.elementType != SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return null
        if (!call.parameterList?.expressionList.isNullOrEmpty() || call.nameElement?.qualifierExpression != null) return null
        val name = call.nameElement?.name ?: return null
        val file = call.containingFile ?: return null
        val analysis = RelationAnalysis.of(file, profile) ?: return null
        val provider = analysis.facts.providerOf(name)?.takeUnless { it.platformResolves } ?: return null
        val relation = analysis.facts.merged.slots[name] ?: return null
        var table: SqlTableType = SqlTableTypeBase.EMPTY_TABLE
        for (column in relation.columns) {
            val anchor = ColumnAnchor(call, file, column, provider, relation.provenance)
            table = table.add(SqlTableTypeBase.createType(anchor, DasTypeSystemBase.UNKNOWN, column.name, false, false, true, null, null))
        }
        return table
    }

    /** A slot column's declaration for the platform: it navigates to the provider's target for the column, else the relation, else the call. */
    private class ColumnAnchor(
        private val call: SqlFunctionCallExpression,
        private val file: PsiFile,
        private val column: ColumnFact,
        private val provider: ExternalRelationFacts,
        private val relation: String?,
    ) : LightElement(call.manager, call.language), PsiNamedElement {
        override fun getName(): String = column.name

        override fun setName(name: String): PsiElement = throw IncorrectOperationException("Rename the column where its relation is declared")

        override fun getContainingFile(): PsiFile = file

        override fun getNavigationElement(): PsiElement =
            column.provenance?.let { provider.navigationTarget(file, it) } ?: relation?.let { provider.navigationTarget(file, it) } ?: call

        override fun toString(): String = "Relation column ${column.name}"
    }
}
