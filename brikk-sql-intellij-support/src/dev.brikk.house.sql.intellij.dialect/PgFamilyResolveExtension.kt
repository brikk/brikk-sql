package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.PsiReference
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension

/**
 * The engine's own functions and types ([PgFamilyDialect.builtins], [EngineBuiltins]) for names in a
 * [PgFamilyDialect]'s files that nothing native binds: the platform asks resolve extensions after its builtins,
 * the file's definitions and the data source's routines and types, so those keep their binding. Register it as
 * `com.intellij.sql.resolveExtension`.
 */
public class PgFamilyResolveExtension : SqlResolveExtension {
    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val psiReference: PsiReference = reference
        val dialect = psiReference.element.containingFile?.language as? PgFamilyDialect ?: return true
        return dialect.builtins?.process(dialect, processor, psiReference) != false
    }
}
