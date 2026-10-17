package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbPsiFacade
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.SqlBundle
import com.intellij.sql.psi.SqlFile
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlExpression
import com.intellij.sql.psi.SqlJoinExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.intellij.recoverEditorOperation

/** What a data source's model says about a reference that does not resolve. */
public sealed interface ReferenceScope {
    /** The reference lies in [namespace] of [dataSource], listed but never introspected: the name may exist. */
    public data class OutOfScope(val dataSource: LocalDataSource, val namespace: NamespaceTarget) : ReferenceScope

    /** The reference's namespace was introspected and has no such name. */
    public data object Nonexistent : ReferenceScope

    /** The model cannot tell: no namespace of the profile's dbms holds the reference. */
    public data object Unknown : ReferenceScope
}

/**
 * Tells a reference that does not resolve because its namespace was never introspected from one
 * that names nothing; [classify] answers for the reference's place, so call it on references the
 * platform reports. The namespace is the nearest qualifier that resolves; for an unqualified table
 * with [CatalogProfile.usesCurrentNamespace], the console's current namespace, as far as the model
 * lists it. A column is out of scope only with the relation it reads: the one its qualifier names (an
 * alias, or an unaliased table by its name, in its query or an enclosing one), or, unqualified, every
 * relation of its own query, when all of them are out of scope. No other reference takes another's
 * status: a wrong catalog, alias or table name, a column of a loaded table, a CTE or a derived table stays. A namespace of
 * the service profile's [levels][CatalogProfile.levels] is out of scope when it has no loaded children
 * ([CatalogProfile.childrenOf]) and the service does not know it introspected: a refresh of the service
 * ran on it, or the introspection scope selects it by name. A namespace another way introspected but
 * empty still counts as out of scope.
 * Call under read access; resolution may be slow.
 */
public class OutOfScopeClassifier(private val service: AutoIntrospectService) {
    private val profile = service.profile
    private val dataSources = profile.dataSources

    public fun classify(reference: SqlReferenceExpression): ReferenceScope {
        val own = ownScope(reference)
        return if (own == ReferenceScope.Unknown && reference.referenceElementType.targetKind == ObjectKind.COLUMN) columnScope(reference) else own
    }

    /** The scope the reference's own qualifiers, or the console's namespace, give it. */
    private fun ownScope(reference: SqlReferenceExpression): ReferenceScope {
        var qualifier = reference.qualifierExpression as? SqlReferenceExpression
        while (qualifier != null) {
            qualifier.resolve()?.let { return namespaceScope(it) }
            qualifier = qualifier.qualifierExpression as? SqlReferenceExpression
        }
        if (reference.qualifierExpression != null || reference.referenceElementType.targetKind != ObjectKind.TABLE) return ReferenceScope.Unknown
        return currentNamespaceScope(reference.containingFile)
    }

    private fun namespaceScope(resolved: PsiElement): ReferenceScope {
        val element = resolved as? DbElement ?: return ReferenceScope.Unknown
        val node = element.delegate as? DasObject ?: return ReferenceScope.Unknown
        val id = element.dataSource.uniqueId
        val dataSource = LocalDataSourceManager.getInstance(resolved.project).dataSources.firstOrNull { it.uniqueId == id } ?: return ReferenceScope.Unknown
        if (node.kind !in profile.levels || !dataSources.accepts(dataSource.dbms)) return ReferenceScope.Unknown
        val path = generateSequence(node) { it.dasParent }.takeWhile { it.kind in profile.levels }.map { it.name }.toList().asReversed()
        if (profile.childrenOf(node).isNotEmpty() || service.introspected(dataSource, path)) return ReferenceScope.Nonexistent
        return ReferenceScope.OutOfScope(dataSource, NamespaceTarget(path, node))
    }

    /** The deepest listed namespace of the console's current one, when it is childless. */
    private fun currentNamespaceScope(file: PsiFile): ReferenceScope {
        if (!profile.usesCurrentNamespace) return ReferenceScope.Unknown
        val current = dataSources.consoleNamespace(file).ifEmpty { return ReferenceScope.Unknown }
        val dataSource = dataSources.resolve(file).dataSource ?: return ReferenceScope.Unknown
        val model = DbPsiFacade.getInstance(file.project).findDataSource(dataSource.uniqueId)?.model ?: return ReferenceScope.Unknown
        val resolver = profile.pathResolver
        val target = current.indices.reversed().firstNotNullOfOrNull { resolver.resolve(model.modelRoots.toList(), current.take(it + 1)) }
            ?: return ReferenceScope.Unknown
        if (target.node.kind !in profile.levels || !resolver.isChildless(target.node) || service.introspected(dataSource, target.path)) return ReferenceScope.Unknown
        return ReferenceScope.OutOfScope(dataSource, target)
    }

    /**
     * A column is out of scope with the relation it reads: the one its qualifier names (an alias, or an
     * unaliased table by its name, in its query or an enclosing one), or, unqualified, every relation of
     * its own query, when all of them are out of scope.
     */
    private fun columnScope(column: SqlReferenceExpression): ReferenceScope {
        val sources = when (val qualifier = column.qualifierExpression) {
            null -> PsiTreeUtil.getParentOfType(column, SqlQueryExpression::class.java)?.let(::relations).orEmpty()
            is SqlReferenceExpression -> listOfNotNull(relationNamed(qualifier, column))
            else -> emptyList()
        }
        val scopes = sources.map(::relationScope)
        return scopes.firstOrNull()?.takeIf { scopes.none { it == null } } ?: ReferenceScope.Unknown
    }

    private fun relationNamed(qualifier: SqlReferenceExpression, column: SqlReferenceExpression): SqlExpression? {
        if (qualifier.qualifierExpression != null) return null
        qualifier.resolve()?.let { alias ->
            return PsiTreeUtil.getParentOfType(alias, SqlQueryExpression::class.java)?.let(::relations)?.firstOrNull { it == alias }
        }
        return generateSequence(PsiTreeUtil.getParentOfType(column, SqlQueryExpression::class.java)) { PsiTreeUtil.getParentOfType(it, SqlQueryExpression::class.java) }
            .firstNotNullOfOrNull { query ->
                relations(query).filter { it is SqlReferenceExpression && it.name.equals(qualifier.name, ignoreCase = true) }.singleOrNull()
            }
    }

    /** The relations [query] reads: its FROM items, each a table, an aliased source, a derived table or a function. */
    private fun relations(query: SqlQueryExpression): List<SqlExpression> {
        fun items(element: PsiElement?): List<SqlExpression> = when (element) {
            is SqlJoinExpression -> items(element.lOperand) + items(element.rOperand)
            is SqlExpression -> listOf(element)
            else -> emptyList()
        }
        return query.tableExpression?.fromClause?.children.orEmpty().flatMap(::items)
    }

    /** The scope of an out-of-scope table [relation] reads; null for a relation whose columns are known, or which is wrong itself. */
    private fun relationScope(relation: SqlExpression): ReferenceScope.OutOfScope? {
        val source = (relation as? SqlAsExpression)?.expression ?: relation
        if (source !is SqlReferenceExpression || source.referenceElementType.targetKind != ObjectKind.TABLE || source.resolve() != null) return null
        return ownScope(source) as? ReferenceScope.OutOfScope
    }
}

/**
 * Hides the platform's "Unable to resolve" reports of references that are out of scope
 * ([OutOfScopeClassifier]) when the profile's [outOfScopeReferences][CatalogProfile.outOfScopeReferences]
 * is on: the names may exist, the model just never loaded them. Only those reports are hidden, matched by
 * the platform's own message in the IDE's language, on exactly a reference's name; annotators', server
 * and other inspections' reports stay, as do names under introspected namespaces. Register a no-arg
 * subclass naming the plugin's service class as `daemon.highlightInfoFilter`.
 */
public abstract class OutOfScopeReferenceFilter(private val service: Class<out AutoIntrospectService>) : HighlightInfoFilter {
    final override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file !is SqlFile || !isUnresolvedReport(highlightInfo.description)) return true
        val requests = file.project.getService(service)?.takeIf { it.profile.outOfScopeReferences } ?: return true
        return recoverEditorOperation {
            val reference = PsiTreeUtil.getNonStrictParentOfType(file.findElementAt(highlightInfo.startOffset), SqlReferenceExpression::class.java)
            val reported = reference?.identifier?.textRange?.let { it.startOffset == highlightInfo.startOffset && it.endOffset == highlightInfo.endOffset } == true
            !reported || OutOfScopeClassifier(requests).classify(reference) !is ReferenceScope.OutOfScope
        }.getOrElse { e ->
            LOG.warn("Classifying an unresolved reference failed", e)
            true
        }
    }

    private companion object {
        val LOG = logger<OutOfScopeReferenceFilter>()

        /** The platform's unresolved-reference messages ("Unable to resolve symbol 'x'", "Unable to resolve table 'x'"), any arguments. */
        val UNRESOLVED: List<Regex> by lazy {
            val first = "\u0000first\u0000"
            val second = "\u0000second\u0000"
            listOf(SqlBundle.message("unable.to.resolve.symbol.0", first), SqlBundle.message("unable.to.resolve.symbol.0.1", first, second)).map { template ->
                Regex(template.split(first, second).joinToString("(.+)") { Regex.escape(it) })
            }
        }

        fun isUnresolvedReport(description: String?): Boolean = description != null && UNRESOLVED.any { it.matches(description) }
    }
}

/**
 * Alt+Enter on an out-of-scope reference ([OutOfScopeClassifier]): "Introspect '<namespace>'" loads
 * that namespace's children now through the plugin's [AutoIntrospectService.introspect], with its
 * banner, even where automatic introspection is off. Available when the profile's
 * [outOfScopeReferences][CatalogProfile.outOfScopeReferences] is on and the namespace is not being
 * introspected already. Register a no-arg subclass naming the plugin's service class and product label
 * (which names its family in the intention settings) as `intentionAction` for the dialect's language.
 */
public abstract class IntrospectNamespaceIntention(
    private val service: Class<out AutoIntrospectService>,
    private val productLabel: String,
) : PsiElementBaseIntentionAction() {
    final override fun getFamilyName(): String = "$productLabel: introspect namespace"

    final override fun startInWriteAction(): Boolean = false

    final override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val target = target(project, element) ?: return false
        text = "${target.first.profile.productLabel}: introspect '${target.second.namespace.path.joinToString(".")}'"
        return true
    }

    final override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val (requests, scope) = target(project, element) ?: return
        requests.introspect(element.containingFile, scope.dataSource, scope.namespace)
    }

    final override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    private fun target(project: Project, element: PsiElement): Pair<AutoIntrospectService, ReferenceScope.OutOfScope>? {
        val requests = project.getService(service)?.takeIf { it.profile.outOfScopeReferences } ?: return null
        val reference = PsiTreeUtil.getNonStrictParentOfType(element, SqlReferenceExpression::class.java) ?: return null
        val scope = OutOfScopeClassifier(requests).classify(reference) as? ReferenceScope.OutOfScope ?: return null
        val key = AutoIntrospectEngine.Key(scope.dataSource.uniqueId, scope.namespace.path)
        return (requests to scope).takeIf { requests.engine.state(key) != AutoIntrospectEngine.State.InFlight }
    }
}
