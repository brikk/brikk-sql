package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlDmlInstruction
import com.intellij.sql.psi.SqlInsertStatement
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlWithClause

/**
 * Keeps the platform's INSERT value count from counting the row of an engine table function whose columns
 * nobody knows (its row is open, [EngineBuiltins]) as no columns, in a [PgFamilyDialect] with builtins: where
 * the query's projected width depends on such a row, through stars, derived tables and CTEs, the count is
 * unknown, unless the columns known are already more than the INSERT target takes. Every other count stands:
 * explicit projections, stars over known rows, derived tables of a known width. Register it as `com.intellij.sql.inspectionSuppressorDelegate`.
 */
public class PgFamilyInspectionSuppressor : SqlInspectionSuppressorDelegate {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId.removeSuffix("Inspection") != "SqlInsertValues") return false
        val query = element as? SqlQueryExpression ?: return false
        val builtins = (query.containingFile?.language as? PgFamilyDialect)?.builtins ?: return false
        // `WITH ... INSERT INTO t SELECT ...`: the statement's WITH scopes the query.
        val with = PsiTreeUtil.getParentOfType(query, SqlInsertStatement::class.java)?.let { PsiTreeUtil.getChildOfType(it, SqlWithClause::class.java) }
        val minimum = builtins.openRowMinimumWidth(query, with) ?: return false
        // Its known columns are already more than the target takes: too wide, whatever the open row adds.
        val target = PsiTreeUtil.getParentOfType(query, SqlDmlInstruction::class.java)?.targetType ?: return false
        return minimum <= target.columnCount
    }
}
