package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.*
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationScopeProfile
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.DatafusionArgumentRole
import dev.brikk.house.sql.shape.datafusionCallSite
import dev.brikk.house.sql.intellij.pipe.PipeText
import java.util.Collections
import java.util.WeakHashMap

internal const val SCALAR_STUB_KIND: String = "SCALAR_STUB"
internal const val SPICE_DIALECT: String = "spice"
internal const val SPICE_PROFILE_VERSION: String = "2.4.0"

/** A consumer supplies stable providers and a cached version selection; these hooks perform no I/O. */
public open class DatafusionEditorProfile(
    final override val language: DatafusionFamilyDialect,
    override val providers: List<ExternalRelationFacts> = emptyList(),
) : SqlCompletionProfile, RelationScopeProfile {
    final override val dialect: String get() = language.profile.coreDialect
    final override val metadata: DialectMetadata = if (dialect == SPICE_DIALECT) SPICE_METADATA else DATAFUSION_METADATA
    override val styles: SqlLookupStyles get() = SqlLookupStyles.DEFAULT
    public open fun serverVersion(file: PsiFile): String? = null
    /** Copied parser settings from the connection catalog, when available. */
    public open fun parserSettings(file: PsiFile): Map<String, String?> = emptyMap()
    public open fun catalogProvenance(file: PsiFile): String = "bundled inventory"
    override fun snapshot(file: PsiFile): MetadataSnapshot = metadata.forServerVersion(serverVersion(file)).snapshot
    override val functionGates: SqlFunctionGates = SqlFunctionGates(qualifier = QualifierRule.Unqualified, digitPrefix = true)
    private val presentations: MutableMap<MetadataSnapshot, List<FunctionPresentation>> = Collections.synchronizedMap(WeakHashMap())
    override fun functions(file: PsiFile): List<FunctionPresentation> {
        val selected = snapshot(file)
        return presentations.getOrPut(selected) { selected.completableFunctions()
            .filterNot { it.nativeKind == SCALAR_STUB_KIND }
            .map { entry -> if (dialect == SPICE_DIALECT && selected.engineVersion == SPICE_PROFILE_VERSION) entry.copy(
                namedParameters = SPICE_NAMED_ARGUMENTS[entry.name].orEmpty(),
                returnType = entry.returnType ?: SPICE_SCALAR_RETURN_TYPES[entry.name].takeIf { entry.kind == FunctionKind.SCALAR },
            ) else entry } }
    }
    override fun builtinFunctionFilter(file: PsiFile): (String) -> Boolean {
        val snapshot = snapshot(file)
        return { name -> snapshot.functions.getAll(name).any { it.nativeKind != SCALAR_STUB_KIND } ||
            snapshot.grammarForms.orEmpty().any { it.name.equals(name, true) } }
    }

    internal fun position(file: PsiFile, offset: Int, ordinary: FunctionPosition): FunctionPosition {
        val source = PipeText.of(file)
        val at = source.textOffset(offset)
        if (datafusionTypePosition(source.text.take(at), dialect)) return FunctionPosition.STAGE_HEAD
        val site = datafusionCallSite(source.text, at, dialect) ?: return ordinary
        return when (site.role) {
            DatafusionArgumentRole.SEARCH_INPUT -> FunctionPosition.TABLE_ARGUMENT
            DatafusionArgumentRole.DATASET, DatafusionArgumentRole.COLUMN -> FunctionPosition.STAGE_HEAD // no function here
            else -> ordinary
        }
    }

    internal fun offerFilter(file: PsiFile, offset: Int): (FunctionPresentation) -> Boolean {
        val source = PipeText.of(file)
        val at = source.textOffset(offset)
        val site = datafusionCallSite(source.text, at, dialect)
        val clause = dev.brikk.house.sql.shape.SqlNameContext.of(source.text.take(at), dialect)?.tokens?.lastOrNull {
            it.tokenType in setOf(TokenType.SELECT, TokenType.WHERE,
                TokenType.ON, TokenType.GROUP_BY,
                TokenType.HAVING, TokenType.ORDER_BY)
        }?.tokenType
        return { entry ->
            entry.nativeKind != SCALAR_STUB_KIND &&
                (site?.role != DatafusionArgumentRole.SEARCH_INPUT || entry.name.lowercase() in SEARCH_CALLS) &&
                !(entry.kind in setOf(FunctionKind.AGGREGATE, FunctionKind.WINDOW) && clause in setOf(
                    TokenType.WHERE, TokenType.ON, TokenType.GROUP_BY)) &&
                !(entry.kind == FunctionKind.WINDOW && clause == TokenType.HAVING)
        }
    }

    internal fun tableCall(file: PsiFile, offset: Int): Boolean = PipeText.of(file).let {
        datafusionCallSite(it.text, it.textOffset(offset), dialect)?.role == DatafusionArgumentRole.SEARCH_INPUT
    }

    internal fun decorator(file: PsiFile, snapshot: MetadataSnapshot): (LookupElement, FunctionPresentation) -> LookupElement {
        val selection = metadata.forServerVersion(serverVersion(file))
        val version = "${metadata.dialect} ${snapshot.engineVersion}" + if (selection.exact && selection.snapshot.engineVersion == snapshot.engineVersion) "" else "; bundled fallback details ${selection.snapshot.engineVersion}"
        val provenance = catalogProvenance(file)
        return { item, entry ->
            val availability = when {
                entry.name.startsWith("ai_") || entry.name in setOf("ai", "embed") -> "; requires model configuration"
                entry.name in SEARCH_CALLS -> "; dataset/configuration dependent"
                else -> ""
            }
            val signature = if (entry.signature == null) "; signature unknown" else ""
            object : LookupElementDecorator<LookupElement>(item) {
                override fun renderElement(presentation: LookupElementPresentation) {
                    super.renderElement(presentation)
                    presentation.appendTailText(" [$version; $provenance$signature$availability]", true)
                }
            }
        }
    }

    internal companion object {
        val SEARCH_CALLS = setOf("vector_search", "text_search", "rrf", "rerank")
    }
}
