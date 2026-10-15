package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.impl.SqlTableTypeBase.UnresolvedRefType
import org.jetbrains.annotations.ApiStatus

/**
 * Keeps the platform's INSERT value count from counting a star over an engine table function whose columns
 * nobody knows (its row is open, [EngineBuiltins]) as no columns, in a [PgFamilyDialect] with builtins: the
 * count is unknown there. Every other count stands, a star over known columns included. Register it as
 * `com.intellij.sql.inspectionSuppressorDelegate`.
 */
@ApiStatus.Experimental
public class PgFamilyInspectionSuppressor : SqlInspectionSuppressorDelegate {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId.removeSuffix("Inspection") != "SqlInsertValues") return false
        val query = element as? SqlQueryExpression ?: return false
        if ((query.containingFile?.language as? PgFamilyDialect)?.builtins == null) return false
        val stars = PsiTreeUtil.findChildrenOfType(query.selectClause, SqlReferenceExpression::class.java).filter { it.name == "*" }
        return stars.any { star -> star.dasType is UnresolvedRefType || star.qualifierExpression == null && openSource(query) }
    }

    /** Whether a table function of [query]'s FROM clause has an open row. */
    private fun openSource(query: SqlQueryExpression): Boolean =
        PsiTreeUtil.findChildrenOfType(query.tableExpression?.fromClause, SqlFunctionCallExpression::class.java).any { it.dasType is UnresolvedRefType }
}
