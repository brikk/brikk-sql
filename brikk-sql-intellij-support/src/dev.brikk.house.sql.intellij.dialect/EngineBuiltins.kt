package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasPredefinedSymbol
import com.intellij.database.symbols.DasSymbol
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.SqlTypeElement
import com.intellij.sql.psi.impl.SqlTableTypeBase
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.relation.RelationScopeProfile
import dev.brikk.house.sql.intellij.relation.RelationTableTypes
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus

/**
 * The engine's own functions and types for the platform's resolution and typing of a family dialect's files,
 * where the platform dialect it builds on lacks them ([PgFamilyDialect.builtins]):
 *  - an unqualified call of a function [completion] offers in the file resolves: a table function in a FROM
 *    position, any other kind elsewhere. A name the platform dialect's builtins have keeps their resolution.
 *  - an unqualified type name the file's snapshot knows ([dev.brikk.house.sql.metadata.MetadataSnapshot.type])
 *    resolves.
 *  - such a table function's call in a FROM position has the columns [relations] knows
 *    ([RelationTableTypes.ofTableFunction]), else the platform's open type of a relation it cannot know, never
 *    one column named after the call ([PgFamilyElementFactory]).
 *
 * Everything else, unknown names included, keeps the platform's resolution and diagnostics.
 */
@ApiStatus.Experimental
public class EngineBuiltins(
    private val completion: SqlCompletionProfile,
    private val relations: RelationScopeProfile? = null,
) {
    private val symbols = ConcurrentHashMap<String, DasSymbol>()

    /** Offers [reference]'s engine function or type to [processor]; false when the processor stops. */
    internal fun process(dialect: SqlLanguageDialectEx, processor: SqlScopeProcessor, state: ResolveState, reference: PsiReference): Boolean {
        val name = processor.referenceName ?: return true
        val element = reference.element as? SqlReferenceExpression ?: return true
        if (element.qualifierExpression != null) return true
        val file = element.containingFile ?: return true
        val call = element.parent as? SqlFunctionCallExpression
        val symbol = when {
            call != null && call.nameElement === element -> function(dialect, file, call, name)?.let { function ->
                symbols.computeIfAbsent("routine:${function.name}") { BuiltinFunction(function.name, null, emptyMap(), emptyList(), dialect.dbms) }
            }
            processor.isExpected(ObjectKind.OBJECT_TYPE) && PsiTreeUtil.getParentOfType(element, SqlTypeElement::class.java) != null ->
                completion.snapshot(file).type(name)?.let { type -> symbols.computeIfAbsent("type:${type.name}") { EngineType(type.name, dialect.dbms) } }
            else -> null
        }
        return symbol == null || processor.executeTarget(symbol, null, null, state)
    }

    /** The columns of [call], an engine table function in a FROM position; null for any other call. */
    internal fun tableType(dialect: SqlLanguageDialectEx, call: SqlFunctionCallExpression): SqlTableType? {
        val name = call.nameElement?.takeIf { it.qualifierExpression == null }?.name ?: return null
        val file = call.containingFile ?: return null
        if (function(dialect, file, call, name)?.kind != FunctionKind.TABLE_VALUED) return null
        val known = relations?.let { RelationTableTypes.ofTableFunction(call, it) }?.takeIf { it.columnCount > 0 }
        // The platform's type for a relation whose columns it cannot know, as for an unresolved table: open.
        return known ?: call.nameElement?.reference?.let(SqlTableTypeBase::createUnresolvedTableType)
    }

    /** The function [call] (named [name]) calls in [file], of a kind its position takes, unless the platform dialect has the name. */
    private fun function(dialect: SqlLanguageDialectEx, file: PsiFile, call: SqlFunctionCallExpression, name: String): FunctionPresentation? {
        if (dialect.supportedFunctions.contains(name)) return null
        val table = call.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION
        val caseSensitive = completion.snapshot(file).functions.caseSensitive
        return completion.functions(file).firstOrNull { function ->
            function.name.equals(name, ignoreCase = !caseSensitive) && (function.kind == FunctionKind.TABLE_VALUED) == table
        }
    }
}

/** A built-in type of the engine, as a resolve target. */
private class EngineType(private val name: String, private val dbms: Dbms) : DasPredefinedSymbol() {
    override fun getName(): String = name

    override fun getKind(): ObjectKind = ObjectKind.OBJECT_TYPE

    override fun isQuoted(): Boolean = false

    override fun getDbms(): Dbms = dbms

    override fun getDasObject(): DasObject = this

    override fun toString(): String = name
}
