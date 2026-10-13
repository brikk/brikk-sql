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
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.light.LightElement
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.JBIterable
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.ReferenceKind
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
        val analysis = RelationAnalysis.of(file, profile) ?: return true
        val start = analysis.source.textOffset(element.textRange.startOffset + psiReference.rangeInElement.startOffset)
        val scoped = analysis.referenceAt(start)?.takeIf { it.kind == ReferenceKind.COLUMN && it.start == start } ?: return true
        for (column in scoped.columns) {
            if (!analysis.manages(column, start, file)) continue
            val symbol = RelationColumnSymbol(file, analysis.providerOf(column, start, file), column)
            if (!processor.execute(symbol, ResolveState.initial())) return false
        }
        return true
    }
}

/**
 * Suppresses the platform's resolve, type and signature inspections only on the references the
 * analysis decides, each on the reference's own element: columns it resolves or finds ambiguous
 * directly through a provider's relations, and the provider relations SQL names (such as a slot
 * call); the resolve inspection also on columns the analysis reports absent itself. Every other
 * inspection, and every other reference, stays. Register a no-arg subclass as
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
            Resolution.RESOLVED, Resolution.AMBIGUOUS -> reference.columns.isNotEmpty() && reference.columns.all { manages(it, reference.start, file) }
            // Absent where the facts prove it: the analysis reports it itself, once.
            Resolution.UNRESOLVED -> absent && diagnoses(reference.start, reference.end)
        }
        ReferenceKind.QUALIFIER, ReferenceKind.TABLE -> reference.relation?.let(::manages) == true
    }

    private companion object {
        val TOOLS = setOf("SqlResolve", "SqlType", "SqlSignature")
    }
}

/**
 * Go to declaration for the analysis's references: a provider's navigation target for its columns
 * and relations; where the platform resolves nothing itself (PIPE stages), also a computed column's
 * or relation's declaration in the SQL. Register a no-arg subclass as `gotoDeclarationHandler`.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationGotoDeclarationHandler(private val profile: RelationScopeProfile) : GotoDeclarationHandler {
    final override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val analysis = RelationAnalysis.of(file, profile) ?: return null
        val reference = analysis.referenceAt(analysis.source.textOffset(offset)) ?: return null
        val platformResolves = file.findReferenceAt(offset)?.let { it is PsiPolyVariantReference && it.multiResolve(false).isNotEmpty() || it.resolve() != null } == true
        val targets = when (reference.kind) {
            ReferenceKind.COLUMN -> reference.columns.mapNotNull { column ->
                val provider = analysis.providerOf(column, reference.start, file)
                when {
                    analysis.manages(column, reference.start, file) -> column.provenance?.let { provider?.navigationTarget(file, it) }
                    platformResolves -> null
                    else -> column.provenance?.let { provider?.navigationTarget(file, it) } ?: column.declaration?.let { analysis.elementAt(file, it) }
                }
            }
            ReferenceKind.QUALIFIER, ReferenceKind.TABLE -> listOfNotNull(reference.relation?.let { relation ->
                val provider = analysis.providerOf(relation)
                when {
                    analysis.manages(relation) -> relation.provenance?.let { provider?.navigationTarget(file, it) }
                    platformResolves -> null
                    else -> relation.provenance?.let { provider?.navigationTarget(file, it) } ?: relation.declaration?.let { analysis.elementAt(file, it) }
                }
            })
        }
        return targets.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
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

/** The analysis's problems in [file]'s SQL (unescaped when injected), as core diagnostics; empty while facts are pending. */
@ApiStatus.Experimental
public fun relationDiagnostics(file: PsiFile, profile: RelationScopeProfile): List<SqlDiagnostic> =
    RelationAnalysis.of(file, profile)?.statements?.flatMap { (_, scope) -> scope.diagnostics.map { it.toSqlDiagnostic() } }.orEmpty()

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
