package dev.brikk.house.sql.metadata

/** An introspected example, never an exhaustive overload or an arity-validation rule. */
data class DatafusionSignatureSample(
    val argumentTypes: List<String>?,
    val returnType: String?,
    val argumentNames: List<String?>?,
)

/**
 * Additional registry evidence for the pinned DataFusion or Spice profile. Samples from
 * SHOW FUNCTIONS are kept outside FunctionDef.overloads: that view expands representative
 * types, not all valid calls. Null constraints/aliases mean not captured, never no arguments.
 * Native constraint text is version-specific diagnostic data, not a portable type grammar.
 */
data class DatafusionFunctionEvidence(
    val name: String,
    val kind: FunctionKind,
    val canonicalName: String?,
    val nativeSignature: String?,
    val volatility: String?,
    val descriptions: List<String>,
    val syntaxExamples: List<String>,
    val samples: List<DatafusionSignatureSample>,
)

/** Metadata remains scoped to an embedding/build, even when the numeric version matches. */
class DatafusionProfileEvidence(
    val profileId: String,
    val availability: String,
    val captureSha256: String,
    val functions: List<DatafusionFunctionEvidence>,
    val settingDefaults: Map<String, String?>,
) {
    private val byName = functions.groupBy { it.name.lowercase() }

    /** Same-named scalar stubs and table implementations remain separate entries. */
    fun functions(name: String): List<DatafusionFunctionEvidence> = byName[name.lowercase()].orEmpty()
}
