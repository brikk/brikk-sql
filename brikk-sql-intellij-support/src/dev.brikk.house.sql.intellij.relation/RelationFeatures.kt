package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.database.Dbms
import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.model.DataType
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasColumnSymbol
import com.intellij.database.symbols.DasPsiSymbol
import com.intellij.database.types.DasSimpleTypeClass
import com.intellij.database.types.DasType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlParenthesizedExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.SqlSelectClause
import com.intellij.sql.psi.SqlSetOperatorExpression
import com.intellij.sql.psi.SqlStatement
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.JBIterable
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationKind
import dev.brikk.house.sql.scope.Resolution
import dev.brikk.house.sql.scope.ScopeColumn
import dev.brikk.house.sql.scope.ScopeRange
import dev.brikk.house.sql.scope.ScopeReference
import org.jetbrains.annotations.ApiStatus

/**
 * Gives the platform's references in [RelationScopeProfile.language] files light column symbols for
 * the columns the analysis resolves through a provider's relations (not those the platform resolves
 * itself), so resolve, highlighting and completion see them. Register a no-arg subclass as
 * `com.intellij.sql.resolveExtension`.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationResolveExtension(private val profile: RelationScopeProfile) : SqlResolveExtension {
    final override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val psiReference: PsiReference = reference
        val element = psiReference.element
        val file = element.containingFile ?: return true
        val analysis = RelationAnalysis.of(file, profile, lastKnown = true) ?: return true
        val start = analysis.source.textOffset(element.textRange.startOffset + psiReference.rangeInElement.startOffset)
        val scoped = analysis.referenceAt(start)?.takeIf { it.kind == ReferenceKind.COLUMN && it.start == start } ?: return true
        for (column in scoped.columns) {
            if (!analysis.manages(column, start)) continue
            val symbol = RelationColumnSymbol(file, analysis.providerOf(column), column)
            if (!processor.execute(symbol, ResolveState.initial())) return false
        }
        return true
    }
}

/**
 * Suppresses the platform's resolve, type and signature inspections only on the references the
 * analysis decides, each on the reference's own element: columns it resolves or finds ambiguous
 * directly through a provider's relations, and the provider relations SQL names (such as a slot
 * call). The resolve inspection also on every column the analysis resolves, through stars and
 * derived tables the platform may not follow, on columns it reports absent itself, and on columns it
 * cannot know (a star over a table whose columns were never introspected) in a statement whose
 * relations are all known. Every other inspection, and every other reference, stays. Register a no-arg subclass as
 * `com.intellij.sql.inspectionSuppressorDelegate`.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationInspectionSuppressor(private val profile: RelationScopeProfile) : SqlInspectionSuppressorDelegate {
    final override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val tool = toolId.removeSuffix("Inspection")
        if (tool !in TOOLS) return false
        val file = element.containingFile ?: return false
        val analysis = RelationAnalysis.of(file, profile) ?: return false
        // The element reported on: a name, or the reference or call it names. Its parents are asked too, and must not match.
        val name = when (element) {
            is SqlReferenceExpression -> element.identifier
            is SqlFunctionCallExpression -> element.nameElement?.identifier
            else -> element
        } ?: return false
        val range = name.textRange ?: return false
        val start = analysis.source.textOffset(range.startOffset)
        val end = analysis.source.textOffset(range.endOffset)
        return analysis.referencesAt(start, end).any { analysis.decides(it, file, absent = tool == "SqlResolve") }
    }

    private fun RelationAnalysis.decides(reference: ScopeReference, file: PsiFile, absent: Boolean): Boolean = when (reference.kind) {
        ReferenceKind.COLUMN -> when (reference.resolution) {
            Resolution.RESOLVED, Resolution.AMBIGUOUS ->
                reference.columns.isNotEmpty() && (absent || reference.columns.all { manages(it, reference.start) })
            // Absent where the facts prove it, the analysis reports it itself, once. Where they cannot (a star
            // over a table whose columns were never introspected), nothing proves it absent; unless a relation
            // of the statement is unknown, which is the error to fix first.
            Resolution.UNRESOLVED -> absent && (diagnoses(reference.start, reference.end) ||
                reference.knowledge != Knowledge.FULL && statementReferences(reference.start).none { it.kind != ReferenceKind.COLUMN && it.resolution != Resolution.RESOLVED })
        }
        ReferenceKind.QUALIFIER, ReferenceKind.TABLE -> reference.relation?.let(::manages) == true
    }

    private companion object {
        val TOOLS = setOf("SqlResolve", "SqlType", "SqlSignature")
    }
}

/**
 * Go to declaration for every column and relation reference the analysis resolves in the profile's
 * files, ahead of the platform's resolver: a provider's navigation target for a column with
 * provenance (through aliases, pass-through relations and merged USING or NATURAL keys, which lead to
 * the side the analysis names) and for a provider relation; else the declaration in the SQL (an
 * alias, a CTE or derived table column, a computed PIPE column); the branches' output items for a
 * set operation's output column, which has no single origin; a merged key no provider column names
 * declares itself in its own join's USING list. Only where the analysis has no answer does the platform's own
 * resolution navigate. Register a no-arg subclass as `gotoDeclarationHandler`.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationGotoDeclarationHandler(private val profile: RelationScopeProfile) : GotoDeclarationHandler {
    final override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val analysis = RelationAnalysis.of(file, profile) ?: return null
        val reference = analysis.referenceAt(analysis.source.textOffset(offset)) ?: return null
        val targets = when (reference.kind) {
            ReferenceKind.COLUMN -> reference.columns.flatMap { column ->
                column.provenance?.let { analysis.providerOf(column)?.navigationTarget(file, it) }?.let { return@flatMap listOf(it) }
                column.declaration?.let { analysis.elementAt(file, it) }?.let { return@flatMap listOf(it) }
                when (column.computedBy) {
                    in SET_OPERATIONS -> setOperationOutputs(owningSetOperation(file, analysis, column, offset), column.name)
                    else -> emptyList()
                }
            }
            ReferenceKind.QUALIFIER, ReferenceKind.TABLE -> listOfNotNull(reference.relation?.let { relation ->
                relation.provenance?.let { analysis.providerOf(relation)?.navigationTarget(file, it) }
                    ?: relation.declaration?.let { analysis.elementAt(file, it) }
            })
        }
        return targets.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * The set operation whose output [column], referred to at [offset], is: the one a derived table or
     * CTE the column is seen through declares, else the nearest one around the reference.
     */
    private fun owningSetOperation(file: PsiFile, analysis: RelationAnalysis, column: ScopeColumn, offset: Int): SqlSetOperatorExpression? {
        val owner = column.relationKind?.takeIf { it == RelationKind.SUBQUERY || it == RelationKind.CTE }?.let { kind ->
            analysis.statementReferences(analysis.source.textOffset(offset)).asSequence().filter { it.kind != ReferenceKind.COLUMN }.mapNotNull { it.relation }
                .firstOrNull { it.kind == kind && it.name == column.relation }?.declaration?.let { analysis.elementAt(file, it) } ?: return null
        }
        var element: PsiElement? = owner ?: file.findElementAt(offset)
        while (element != null && element !is PsiFile) {
            outermostSetOperation(element)?.let { return it }
            if (element is SqlStatement) return null
            element = element.parent
        }
        return null
    }

    private fun outermostSetOperation(scope: PsiElement): SqlSetOperatorExpression? = scope as? SqlSetOperatorExpression
        ?: PsiTreeUtil.findChildrenOfType(scope, SqlSetOperatorExpression::class.java)
            .firstOrNull { PsiTreeUtil.getParentOfType(it, SqlSetOperatorExpression::class.java, true, SqlStatement::class.java)?.let { outer -> !PsiTreeUtil.isAncestor(scope, outer, false) } != false }

    /** The items producing output column [name] in each branch of [operation], by its position in the first branch. */
    private fun setOperationOutputs(operation: SqlSetOperatorExpression?, name: String): List<PsiElement> {
        operation ?: return emptyList()
        val branches = branchesOf(operation).map { it.expressions }
        val position = branches.firstOrNull()?.indexOfFirst { outputName(it).equals(name, ignoreCase = true) }?.takeIf { it >= 0 } ?: return emptyList()
        return branches.mapNotNull { items -> items.getOrNull(position)?.let { (it as? SqlAsExpression)?.nameElement ?: it } }
    }

    private fun branchesOf(expression: SqlExpression): List<SqlSelectClause> = when (expression) {
        is SqlSetOperatorExpression -> expression.operands.flatMap(::branchesOf)
        is SqlQueryExpression -> listOfNotNull(expression.selectClause)
        is SqlParenthesizedExpression -> expression.expression?.let(::branchesOf).orEmpty()
        else -> emptyList()
    }

    private fun outputName(item: SqlExpression): String? = when (item) {
        is SqlAsExpression -> item.name
        is SqlReferenceExpression -> item.name
        else -> null
    }

    private companion object {
        val SET_OPERATIONS = setOf("UNION", "UNION ALL", "INTERSECT", "INTERSECT ALL", "EXCEPT", "EXCEPT ALL", "MINUS")
    }
}

/**
 * Marks the analysis's problems (missing, ambiguous and partial-contract columns, unknown
 * qualifiers and tables, mismatched set-operation branches, PIPE stages execution cannot lower) at
 * their exact ranges, injected fragments included. Register a no-arg subclass as the language's
 * `annotator`, or feed [relationDiagnostics] to a `NativeErrorProfile.fileDiagnostics` instead.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationAnnotator(private val profile: RelationScopeProfile) : Annotator {
    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        val analysis = RelationAnalysis.of(file, profile) ?: return
        for (diagnostic in relationDiagnostics(file, profile)) {
            val range = analysis.source.fileRange(diagnostic.start, diagnostic.end) ?: continue
            val severity = if (diagnostic.severity == DiagnosticSeverity.WARNING) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, diagnostic.message).range(range).create()
        }
    }
}

/**
 * The analysis's problems in [file]'s SQL (unescaped when injected), as core diagnostics; empty while
 * facts are pending, a provider's or the host string's ([RelationScopeProfile.hostSql]).
 */
@ApiStatus.Experimental
public fun relationDiagnostics(file: PsiFile, profile: RelationScopeProfile): List<SqlDiagnostic> =
    if (profile.hostSql?.isPending(file) == true) emptyList() else RelationAnalysis.of(file, profile)?.statements?.flatMap { (_, scope) -> scope.diagnostics.map { it.toSqlDiagnostic() } }.orEmpty()

/** The element at the start of [range] of the analysis's text in [file]. */
private fun RelationAnalysis.elementAt(file: PsiFile, range: ScopeRange): PsiElement? =
    source.fileRange(range.start, range.end)?.let { file.findElementAt(it.startOffset) }

/**
 * A column the analysis resolves through a provider's relation, as the platform's resolve sees it.
 * Equal symbols name the same provider column in the same file, whatever alias shows it: `a.id` and
 * `b.id` of a self-join are one column. It is equivalent to the provider's navigation target for
 * the column, so find usages and rename of that declaration include the SQL referring to it;
 * without one it navigates to [fallback].
 */
internal class RelationColumnSymbol(
    private val file: PsiFile,
    private val provider: ExternalRelationFacts?,
    val column: ScopeColumn,
    private val fallback: PsiElement? = null,
) : LightElement(file.manager, file.language), DasPsiSymbol, DasColumnSymbol {
    private val target: PsiElement? by lazy { column.provenance?.let { provider?.navigationTarget(file.originalFile, it) } }

    private val identity = listOf(column.source?.relation, column.name, column.provenance)

    override fun equals(other: Any?): Boolean = other is RelationColumnSymbol && file.originalFile === other.file.originalFile && identity == other.identity

    override fun hashCode(): Int = 31 * System.identityHashCode(file.originalFile) + identity.hashCode()

    override fun getName(): String = column.name

    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Rename the column where its relation is declared")

    override fun isQuoted(): Boolean = false

    override fun getKind(): ObjectKind = ObjectKind.COLUMN

    override fun getDbms(): Dbms = (file.language as? SqlLanguageDialect)?.dbms ?: Dbms.UNKNOWN

    override fun getDasObject(): DasColumn = RelationColumn(column)

    override fun getContainingFile(): PsiFile = file

    override fun getContextElement(): PsiElement = file

    override fun getNavigationElement(): PsiElement = target ?: fallback ?: this

    override fun isEquivalentTo(another: PsiElement?): Boolean = super.isEquivalentTo(another) || another != null && another == target

    override fun getText(): String = column.name

    override fun toString(): String = "Relation column ${column.relation}.${column.name}"

    private class RelationTable(private val name: String) : DasTable {
        override fun getName(): String = name
        override fun getKind(): ObjectKind = ObjectKind.TABLE
        override fun isQuoted(): Boolean = false
        override fun isSystem(): Boolean = false
        override fun isTemporary(): Boolean = false
        override fun getColumnAttrs(column: DasColumn?): Set<DasColumn.Attribute> = emptySet()
        override fun getDasChildren(kind: ObjectKind?): JBIterable<out DasObject> = JBIterable.empty()
    }

    private class RelationColumn(private val column: ScopeColumn) : DasColumn {
        override fun getName(): String = column.name
        override fun getKind(): ObjectKind = ObjectKind.COLUMN
        override fun isQuoted(): Boolean = false
        override fun getTable(): DasTable = RelationTable(column.source?.relation ?: column.relation)
        override fun getDasParent(): DasObject = table
        // A provider's columns have no SQL ordinal.
        override fun getPosition(): Short = -1
        override fun isNotNull(): Boolean = column.nullability == Nullability.NON_NULL
        override fun getDefault(): String? = null
        // A display type (a Kotlin type, say) proves no SQL type; an exact one is only text here.
        override fun getDasType(): DasType = object : DasType {
            override fun getTypeClass() = DasSimpleTypeClass(column.sqlType ?: "UNKNOWN")
            override fun getDescription() = column.sqlType ?: "relation column (SQL type unknown)"
            override fun toDataType(): DataType = DataType.UNKNOWN
        }
    }
}
