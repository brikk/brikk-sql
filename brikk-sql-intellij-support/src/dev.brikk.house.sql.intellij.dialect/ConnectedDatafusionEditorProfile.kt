package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.*
import dev.brikk.house.sql.intellij.relation.*
import dev.brikk.house.sql.metadata.*
import java.util.concurrent.atomic.AtomicLong

/** Cache-only editor hooks. The consumer registers the service, interceptor, observer and refresh action. */
public open class ConnectedDatafusionEditorProfile(
    language: DatafusionFamilyDialect,
    private val service: () -> LiveCatalogService,
    additionalProviders: List<ExternalRelationFacts> = emptyList(),
) : DatafusionEditorProfile(language) {
    private val revision = AtomicLong()
    private val facts = object : ExternalRelationFacts {
        private var previous: Pair<NativeCatalog, List<String>>? = null
        private var copied: RelationFactsResult.Available? = null
        @Synchronized override fun facts(file: PsiFile): RelationFactsResult? {
            val selection = service().completion(file).selection
            val native = selection.inventory?.nativeCatalog?.takeIf { it.product == dialect } ?: return null
            val key = native to selection.session.namespace
            if (key != previous) {
                copied = RelationFactsResult.Available(native.relationFacts(key.second), revision.incrementAndGet())
                previous = key
            }
            return copied
        }
    }
    override val providers: List<ExternalRelationFacts> = additionalProviders + facts
    override fun serverVersion(file: PsiFile): String? = service().completion(file).selection.inventory?.serverVersion
    override fun parserSettings(file: PsiFile): Map<String, String?> = service().completion(file).selection.inventory?.nativeCatalog?.settings.orEmpty()
    private var lastInventory: LiveInventory? = null
    private var liveSnapshot: MetadataSnapshot? = null
    @Synchronized override fun snapshot(file: PsiFile): MetadataSnapshot {
        val completion = service().completion(file)
        val inventory = completion.selection.inventory ?: return completion.snapshot
        if (lastInventory !== inventory) {
            val bundled = completion.snapshot
            liveSnapshot = MetadataSnapshot(inventory.serverVersion ?: "unknown", FunctionCatalog(inventory.functions.map { it.def }),
                bundled.keywords, tableFunctions = bundled.tableFunctions, grammarForms = bundled.grammarForms, types = bundled.types)
            lastInventory = inventory
        }
        return requireNotNull(liveSnapshot)
    }
    override fun functions(file: PsiFile): List<FunctionPresentation> {
        val live = service().completion(file)
        if (live.selection.inventory == null) return super.functions(file)
        return live.functions.filterNot { it.nativeKind == "SCALAR_STUB" }.map { entry ->
            if (dialect == "spice" && live.selection.inventory.serverVersion == "2.4.0")
                entry.copy(namedParameters = SPICE_NAMED_ARGUMENTS[entry.name].orEmpty()) else entry
        }
    }
    override fun builtinFunctionFilter(file: PsiFile): (String) -> Boolean {
        val names = functions(file).mapTo(HashSet()) { it.name.lowercase() }
        return { it.lowercase() in names }
    }
    override fun catalogProvenance(file: PsiFile): String = when (service().completion(file).selection.source) {
        is CatalogSource.Live -> "live connection"
        is CatalogSource.Disk -> "cached connection"
        CatalogSource.Bundled -> "bundled fallback"
    }
}
