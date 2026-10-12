package dev.brikk.house.sql.intellij.psi

import com.intellij.database.Dbms
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.psi.SqlGroupByClause
import com.intellij.sql.psi.SqlHavingClause
import com.intellij.sql.psi.SqlOrderByClause
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension
import kotlin.reflect.KClass
import org.jetbrains.annotations.ApiStatus

/**
 * Lets unqualified references in [clauses] of a [dbms] query resolve to, and complete, the
 * query's SELECT-list aliases. The platform offers them only to a fixed list of built-in DBMS
 * families, which a plugin's own `Dbms` cannot join. Normal resolution runs first, so a column
 * of the same name still wins. Register a no-arg subclass as `com.intellij.sql.resolveExtension`.
 */
@ApiStatus.Experimental
public abstract class SelectAliasResolveExtension(
    private val dbms: Dbms,
    clauses: Set<KClass<out PsiElement>> = setOf(SqlHavingClause::class, SqlGroupByClause::class, SqlOrderByClause::class),
) : SqlResolveExtension {
    private val clauses = clauses.map { it.java }.toTypedArray()

    final override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val element = reference.element
        if ((element.language as? SqlLanguageDialectEx)?.dbms != dbms) return true
        if (PsiTreeUtil.getNonStrictParentOfType(element, SqlReferenceExpression::class.java)?.qualifierExpression != null) return true
        val query = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java) ?: return true
        val clause = PsiTreeUtil.getParentOfType(element, *clauses) ?: return true
        // A clause of an enclosing query, reached through a subquery, does not expose its aliases here.
        if (PsiTreeUtil.getParentOfType(clause, SqlQueryExpression::class.java) != query) return true
        val select = query.selectClause ?: return true
        return select.processDeclarations(processor, ResolveState.initial(), null, element)
    }
}
