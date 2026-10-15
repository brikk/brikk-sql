package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasPredefinedSymbol
import com.intellij.database.symbols.DasSymbol
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlExpressionList
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.SqlTokens
import com.intellij.sql.psi.SqlTypeElement
import com.intellij.sql.psi.impl.SqlNamedParameterValueExpression
import com.intellij.sql.psi.impl.SqlTableTypeBase
import com.intellij.sql.psi.impl.SqlTableTypeBase.UnresolvedRefType
import dev.brikk.house.sql.intellij.catalog.dasRelationFact
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.relation.RelationAnalysis
import dev.brikk.house.sql.intellij.relation.RelationScopeProfile
import dev.brikk.house.sql.intellij.relation.RelationTableTypes
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus

/**
 * The engine's own functions and types for the platform's resolution and typing of a family dialect's files,
 * as a fallback where nothing native binds a name ([PgFamilyDialect.builtins], [PgFamilyResolveExtension]):
 *  - an unqualified call of a function [completion] offers in the file resolves: a table function in a FROM
 *    position, any other kind elsewhere. A name the platform dialect's builtins have keeps their resolution,
 *    as does one a definition in the file or a routine of the data source binds.
 *  - the label of a named argument of a call that fallback binds resolves to the parameter of that name, which
 *    one of the function's overloads in the file's snapshot takes by name (after its positional ones): with
 *    `:=` in any such call ([PgFamilyDialect.processNamedArgumentCandidates]), with `=` only in a table
 *    function's call in a FROM position, where SQL reads `name = value` as a named argument; anywhere else `=`
 *    compares. An unknown name stays unresolved.
 *  - an unqualified type name the file's snapshot knows ([dev.brikk.house.sql.metadata.MetadataSnapshot.type])
 *    resolves where no native type does.
 *  - a table function's call bound by that fallback, in a FROM position, has the columns [relations] knows
 *    ([RelationTableTypes.ofTableFunction]), else the platform's open type of a relation it cannot know, never
 *    one column named after the call ([PgFamilyElementFactory]). A natively bound call keeps its native type.
 *
 * Everything else, unknown names included, keeps the platform's resolution and diagnostics.
 */
@ApiStatus.Experimental
public class EngineBuiltins(
    private val completion: SqlCompletionProfile,
    private val relations: RelationScopeProfile? = null,
) {
    private val symbols = ConcurrentHashMap<String, DasSymbol>()

    /**
     * Offers [reference]'s engine function or type to [processor] when the platform's own stages bound nothing;
     * false when the processor stops.
     */
    internal fun process(dialect: SqlLanguageDialectEx, processor: SqlScopeProcessor, reference: PsiReference): Boolean {
        if (!processor.isResultEmpty) return true
        val name = processor.referenceName ?: return true
        val element = reference.element as? SqlReferenceExpression ?: return true
        if (element.qualifierExpression != null) return true
        val file = element.containingFile ?: return true
        val call = element.parent as? SqlFunctionCallExpression
        val labelled = equalsLabelledCall(element)
        val symbol = when {
            labelled != null -> (labelled.nameElement?.resolveSymbol() as? EngineFunction)?.parameter(name)
            // Made afresh from the file's snapshot: a live catalog may change the function's parameters.
            call != null && call.nameElement === element -> function(dialect, file, call, name)?.let { function ->
                EngineFunction(function.name, dialect.dbms, namedParameters(file, function))
            }
            processor.isExpected(ObjectKind.OBJECT_TYPE) && PsiTreeUtil.getParentOfType(element, SqlTypeElement::class.java) != null ->
                completion.snapshot(file).type(name)?.let { type -> symbols.computeIfAbsent("type:${type.name}") { EngineType(type.name, dialect.dbms) } }
            else -> null
        }
        return symbol == null || processor.executeTarget(symbol, null, null, ResolveState.initial())
    }

    /**
     * The table function's call in a FROM position whose `name = value` argument [element] labels: SQL reads that
     * as a named argument there (as the relation analysis does), and as a comparison anywhere else. A `:=` label
     * the platform binds itself, through [PgFamilyDialect.processNamedArgumentCandidates].
     */
    private fun equalsLabelledCall(element: SqlReferenceExpression): SqlFunctionCallExpression? {
        val argument = element.parent as? SqlBinaryExpression ?: return null
        if (argument.lOperand !== element || argument is SqlNamedParameterValueExpression || argument.opSign != SqlTokens.SQL_OP_EQ) return null
        val call = (argument.parent as? SqlExpressionList)?.parent as? SqlFunctionCallExpression ?: return null
        return call.takeIf { it.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION }
    }

    /** The parameters an overload of [function] in [file]'s snapshot takes by name (after its positional ones), as the engine spells them. */
    private fun namedParameters(file: PsiFile, function: FunctionPresentation): List<String> {
        val snapshot = completion.snapshot(file)
        val ignoreCase = !snapshot.functions.caseSensitive
        val defs = snapshot.functions.getAll(function.name) + snapshot.extensionFunctions.orEmpty().filter { it.name.equals(function.name, ignoreCase) }
        return defs.filter { it.kind == function.kind }
            .flatMap { def -> def.overloads.flatMap { overload -> overload.argNames.orEmpty().let { it.drop(overload.positionalCount ?: it.size) } } }
            .distinctBy { if (ignoreCase) it.lowercase() else it }
    }

    /** The columns of [call], an engine table function the fallback binds in a FROM position; null for any other call. */
    internal fun tableType(dialect: SqlLanguageDialectEx, call: SqlFunctionCallExpression): SqlTableType? {
        val nameElement = call.nameElement?.takeIf { it.qualifierExpression == null } ?: return null
        if (nameElement.resolveSymbol() !is EngineFunction) return null
        val name = nameElement.name ?: return null
        val file = call.containingFile ?: return null
        if (function(dialect, file, call, name)?.kind != FunctionKind.TABLE_VALUED) return null
        val known = relations?.let { RelationTableTypes.ofTableFunction(call, it) }?.takeIf { it.columnCount > 0 }
        // The platform's type for a relation whose columns it cannot know, as for an unresolved table: open.
        return known ?: call.nameElement?.reference?.let(SqlTableTypeBase::createUnresolvedTableType)
    }

    /**
     * When the width of the row [query] projects depends on an engine table function's open row ([tableType]), as
     * the shared relation analysis finds through stars, derived tables and CTEs ([withClause], the WITH of an
     * enclosing statement, scopes it): the number of its columns that are known, the least its width can be. Null
     * when its width is known or it reads no open row. The tables it names have the columns the platform resolves
     * them to, so a known table's star counts towards that least width.
     */
    internal fun openRowMinimumWidth(query: SqlQueryExpression, withClause: PsiElement?): Int? {
        val file = query.containingFile ?: return null
        val readsOpenRow = PsiTreeUtil.findChildrenOfType(query, SqlFunctionCallExpression::class.java).any { it.dasType is UnresolvedRefType } ||
            withClause != null && PsiTreeUtil.findChildrenOfType(withClause, SqlFunctionCallExpression::class.java).any { it.dasType is UnresolvedRefType }
        if (!readsOpenRow) return null
        val sql = listOfNotNull(withClause?.text, query.text).joinToString(" ")
        val base = relations?.let { RelationAnalysis.of(file, it, lastKnown = true)?.facts?.merged } ?: RelationFacts()
        // The tables the platform resolves, with the columns it knows of them (a local CREATE TABLE, the data
        // source's model); the provider facts stand where they have the table, and any other table stays unknown.
        val tables = listOfNotNull(withClause, query).flatMap { PsiTreeUtil.findChildrenOfType(it, SqlReferenceExpression::class.java) }
            .filter { it.referenceElementType.targetKind == ObjectKind.TABLE && it.parent !is SqlReferenceExpression }
            .mapNotNull { reference -> (reference.reference?.resolve() as? DasTable)?.let { reference.text to dasRelationFact(it, reference.text) } }
            .toMap()
        val facts = base.copy(tables = tables + base.tables)
        val output = RelationScope.analyze(sql, completion.metadata.dialect, facts, checkCanceled = ProgressManager::checkCanceled).output ?: return null
        return output.columns.size.takeIf { output.knowledge != Knowledge.FULL }
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

/** A built-in function of the engine, as the fallback's resolve target, with the parameters it takes by name. */
internal class EngineFunction(name: String, dbms: Dbms, parameters: List<String>) : BuiltinFunction(name, null, emptyMap(), emptyList(), dbms) {
    val parameters: List<DasSymbol> = parameters.map { EngineArgument(it, dbms) }

    /** The parameter [name] it takes by name, by the engine's spelling or another case of it. */
    fun parameter(name: String): DasSymbol? = parameters.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** A parameter an engine function takes by name, as the resolve target of a named argument's label. */
private class EngineArgument(private val name: String, private val dbms: Dbms) : DasPredefinedSymbol() {
    override fun getName(): String = name

    override fun getKind(): ObjectKind = ObjectKind.ARGUMENT

    override fun isQuoted(): Boolean = false

    override fun getDbms(): Dbms = dbms

    override fun getDasObject(): DasObject = this

    override fun toString(): String = name
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
