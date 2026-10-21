package dev.brikk.house.sql.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * STABLE ACCESSOR CONTRACT
 *
 * `brikk-sql-metadata` is the featherweight, dependency-light module external consumers
 * (e.g. the doris-intellij plugin) embed to get per-dialect function catalogs WITHOUT the
 * transpiler. Its only dependency is kotlinx-serialization-json. Keep the API surface
 * small and deliberate:
 *
 *  - [FunctionKind], [FunctionOverload], [FunctionDef], [FunctionCatalog]
 *  - the generated per-dialect catalog vals (DORIS_FUNCTION_CATALOG, ...)
 *  - versioned snapshots: [DialectMetadata], [MetadataSnapshot] and the per-dialect vals
 *    (DUCKDB_METADATA, TRINO_METADATA, ...); their parts [KeywordCatalog], [KeywordDef],
 *    [KeywordCategory], [PragmaDef], [SettingDef], [ExtensionDef], [TableFunctionInfo],
 *    [PropertyDef], [ColumnDef], [OutputSchema], [GrammarForm], [InvocationSyntax]
 *  - the completion projection: [FunctionPresentation], [presentation], [completionEntries]
 *  - the DuckDB capture shape shared with live harvests: [DuckdbCapture], the `DUCKDB_*`
 *    capture vals, the `Duckdb*Row` types, [DuckdbDump] and [toSnapshot]
 *
 * Changes here are API changes for the plugin — additive only, and prefer defaulted
 * fields (serialization stays backward-compatible for persisted JSON).
 */

/**
 * A dialect's built-in function catalog: the engine's real registered functions, as opposed
 * to sqlglot's translation registry (which only models functions that need cross-dialect
 * rewriting — ~7 Doris-specific entries vs the 800+ Doris actually registers).
 *
 * Consumers:
 *  - brikk-sql: engine-exact slot detection (a known function in table position is NOT a
 *    table-valued fragment slot), future UDF/builtin return-type inference and validation.
 *  - External tooling (e.g. the doris-intellij plugin): completion/validation source via
 *    the Kotlin API or [FunctionCatalog.toJson].
 *
 * [FunctionDef.overloads] is populated for DuckDB/Trino/StarRocks from engine registry
 * dumps (duckdb_functions(), SHOW FUNCTIONS, SHOW FULL BUILTIN FUNCTIONS), and for Doris
 * statically from each function class's SIGNATURES field
 * (tools/extract_doris_signatures.py -> vendor/data/doris-signatures.json). Doris
 * functions whose class computes signatures dynamically (all table-valued functions,
 * rank-like window functions, ...) keep empty overloads. ClickHouse's system.functions
 * dump exposes names/kinds/aliases but no signatures, so its overloads are honestly empty.
 */
enum class FunctionKind { SCALAR, AGGREGATE, WINDOW, TABLE_VALUED, TABLE_GENERATING }

/**
 * How a function's result nullability relates to its arguments — a per-(function, engine)
 * semantic fact carried by the catalog (NOT by AST nodes; see the design note in brikk-sql).
 *
 * Engine-construct mapping (the source of each verdict is static engine metadata, never a
 * guess — anything not directly extractable stays [UNKNOWN]):
 *
 *  - Doris (Nereids ComputeNullable marker interfaces, walked over each function class's
 *    implements clause + superclass chain by tools/extract_doris_signatures.py):
 *      - `PropagateNullable`    -> [STRICT] ("nullable if any child is nullable" — Doris's
 *        planning-level form of NULL-in/NULL-out propagation)
 *      - `AlwaysNullable`       -> [ALWAYS_NULLABLE]
 *      - `AlwaysNotNullable`    -> [NEVER_NULL]
 *      - a custom `nullable()` override (Coalesce, Lag, If, ...) -> [UNKNOWN] with
 *        [SemanticProfile.notes] = "doris: custom nullable() override" (the engine computes
 *        it from argument shapes at plan time; not represented by a marker)
 *      - any other `*Nullable*` marker interface (e.g. `PropagateNullableOnDateLikeV2Args`,
 *        which exists in some Doris versions but NOT at the pinned checkout) -> [UNKNOWN]
 *        with the verbatim interface name in notes. It means "nullable if any DATE-like arg
 *        is nullable" — argument-subset propagation, which [STRICT] would over-promise for
 *        the non-date args, so no dedicated member is added until the pin ships it.
 *      - table-valued / table-generating functions: no mode (row-set producers; Doris's
 *        TableValuedFunction.nullable() throws — scalar nullability is not applicable).
 *  - DuckDB: duckdb_functions() exposes no null-handling column (checked v2.0.0: only
 *    has_side_effects/stability, neither is null propagation) -> all [UNKNOWN].
 *  - Trino: SHOW FUNCTIONS exposes nothing -> all [UNKNOWN].
 *
 * [SKIPS_NULLS] (aggregate-style "NULL inputs are ignored, result non-null-from-nulls") is
 * declared for evidence-based population (e.g. the semantic hazard DB) — no engine-side
 * static source maps onto it yet.
 */
enum class NullPropagation {
    /** NULL in any argument makes the result NULL (equivalently: result nullable iff some argument is). */
    STRICT,

    /** NULL inputs are skipped rather than propagated (typical aggregate behavior). */
    SKIPS_NULLS,

    /** Result is nullable regardless of argument nullability (e.g. parse-style functions). */
    ALWAYS_NULLABLE,

    /** Result is never NULL (e.g. count). */
    NEVER_NULL,

    /** No engine-side evidence — the honest default. */
    UNKNOWN,
}

/**
 * Per-(function, engine) semantic facts beyond the type signature. Kept deliberately
 * minimal and extensible: every field has a default so persisted JSON stays
 * backward-compatible as facts are added (collation/timezone sensitivity flags are NOT
 * modeled yet — they need per-function evidence no engine exposes statically).
 *
 * Argument names are NOT here: DuckDB (the only engine exposing them) names parameters
 * per overload, and 122 of its 286 multi-overload functions use different names across
 * overloads (round(x), round(x, precision), ...) — so names live on
 * [FunctionOverload.argNames], the one home that can represent both engines' shapes.
 *
 * [notes] preserves extractor oddities verbatim (e.g. Doris custom `nullable()`
 * overrides, unmapped marker interfaces) — human-readable provenance, not machine API.
 */
@Serializable
data class SemanticProfile(
    @SerialName("null_propagation") val nullPropagation: NullPropagation = NullPropagation.UNKNOWN,
    val notes: String? = null,
)

/**
 * [argNames] are the engine-declared parameter names for THIS overload, in [argTypes]
 * order; null when the engine does not expose names (Doris SIGNATURES, Trino SHOW
 * FUNCTIONS, and some DuckDB rows). Names are verbatim from the engine — DuckDB includes
 * generic ones (col0, col1) and lambda shapes (`lambda(x)`).
 *
 * [positionalCount], when set, says only the first that many arguments are positional; the
 * rest are optional named parameters (DuckDB `read_csv('f.csv', header := true)`). Null when
 * every argument is positional or the engine does not say.
 */
@Serializable
data class FunctionOverload(
    @SerialName("arg_types") val argTypes: List<String>,
    @SerialName("return_type") val returnType: String,
    val variadic: Boolean = false,
    @SerialName("arg_names") val argNames: List<String>? = null,
    @SerialName("positional_count") val positionalCount: Int? = null,
)

/**
 * One catalog entry (a function and all its overloads).
 *
 * [kind] is normalized to the 5-value [FunctionKind] enum; [nativeKind] preserves the
 * engine-native type string when it doesn't map 1:1 (e.g. DuckDB "macro"/"table_macro"
 * normalize to SCALAR/TABLE_VALUED but keep their native kind here). Null when the
 * engine kind maps directly.
 *
 * [sinceVersion] is a PROVEN fact: the first engine version that ships the function (for
 * example a registry's introduced-in column). Only proven facts may exclude a def from a
 * version (see [FunctionCatalog.availableIn]). Null for every bundled dialect today: no
 * engine source the generators read carries one.
 *
 * [firstDocumentedIn] is evidence of a weaker kind: the first documentation tier that
 * describes the function (Doris: the oldest apache/doris-website version tier, coarse
 * values such as "3.x"; see tools/extract_doris_since_versions.py and vendor/README.md).
 * Documentation lags releases, so it never excludes a def. Null when the docs at the
 * pinned clone have no matching page or only the unreleased tree documents it.
 *
 * [profile] carries per-engine semantic facts (see [SemanticProfile]); null when the
 * engine exposes none for this function (all Trino/DuckDB defs currently; Doris defs
 * whose class carries no ComputeNullable marker). Null and an all-defaults profile mean
 * the same thing semantically — generators emit null to keep the catalogs lean.
 *
 * [extension] names the extension that registers the function; set only on
 * [MetadataSnapshot.extensionFunctions], never on a base-catalog def.
 */
@Serializable
data class FunctionDef(
    val name: String,
    val kind: FunctionKind,
    val aliases: List<String> = emptyList(),
    val overloads: List<FunctionOverload> = emptyList(),
    @SerialName("native_kind") val nativeKind: String? = null,
    @SerialName("since_version") val sinceVersion: String? = null,
    val profile: SemanticProfile? = null,
    val extension: String? = null,
    @SerialName("first_documented_in") val firstDocumentedIn: String? = null,
)

/**
 * [grammarBuiltins] are function-shaped names the engine parses at the GRAMMAR (or
 * parser-special-form) level rather than registering in its function registry — e.g.
 * Trino's COALESCE/CAST/EXTRACT (absent from `SHOW FUNCTIONS`; dedicated SqlBase.g4
 * alternatives or AstBuilder special forms). They are engine-grammar knowledge, not
 * registry entries: uppercase names, NOT part of [functions] and NOT serialized by
 * [toJson] (persisted catalogs stay pure registry dumps). Use [isKnown] when asking
 * "would this name reach the engine unrecognized?" — [contains]/[get] stay
 * registry-only.
 *
 * [caseSensitive] is the dialect's name policy (ClickHouse: names differ by case). [getAll]
 * and the snapshot projections follow it; [get], [contains] and [isKnown] always ignore case.
 */
class FunctionCatalog(
    val functions: List<FunctionDef>,
    val grammarBuiltins: Set<String> = emptySet(),
    val caseSensitive: Boolean = false,
) {

    private val byName: Map<String, FunctionDef> = buildMap {
        for (def in functions) {
            put(def.name.uppercase(), def)
            for (alias in def.aliases) put(alias.uppercase(), def)
        }
    }

    val size: Int get() = functions.size

    /**
     * Every name this catalog recognizes: each registered function's primary name and
     * aliases, plus the [grammarBuiltins]. Use this to enumerate/list recognized names
     * (e.g. completion) — unlike [functions] / [toJson], which stay a pure registry dump
     * and therefore omit grammar-level forms (CAST, EXTRACT, ...). Returned in source
     * casing (registry entries as registered; grammar builtins uppercase).
     */
    val knownNames: Set<String> by lazy {
        buildSet {
            for (def in functions) {
                add(def.name)
                addAll(def.aliases)
            }
            addAll(grammarBuiltins)
        }
    }

    private val allByName: Map<String, List<FunctionDef>> by lazy {
        buildMap<String, MutableList<FunctionDef>> {
            for (def in functions) {
                for (key in (listOf(def.name) + def.aliases).map(::nameKey).distinct()) getOrPut(key, ::mutableListOf) += def
            }
        }
    }

    /**
     * Case-insensitive lookup by primary name or alias. A name defined under several kinds
     * (DuckDB `range` as scalar and table function) returns the LAST such def; use [getAll]
     * to see every one.
     */
    operator fun get(name: String): FunctionDef? = byName[name.uppercase()]

    /** Every def registered under [name] (primary name or alias), across kinds, by [caseSensitive]. */
    fun getAll(name: String): List<FunctionDef> = allByName[nameKey(name)].orEmpty()

    /** [name] as the dialect compares names: unchanged when [caseSensitive], else uppercased. */
    internal fun nameKey(name: String): String = if (caseSensitive) name else name.uppercase()

    operator fun contains(name: String): Boolean = byName.containsKey(name.uppercase())

    /**
     * True when the engine recognizes [name] at all: registered in the function
     * registry ([contains], aliases included) OR parsed at the grammar level
     * ([grammarBuiltins]). Case-insensitive.
     */
    fun isKnown(name: String): Boolean =
        contains(name) || name.uppercase() in grammarBuiltins

    /**
     * The defs a server at [version] can have: every def except those whose proven
     * [FunctionDef.sinceVersion] is newer. [FunctionDef.firstDocumentedIn] never excludes.
     * An unparsable [version] or since-version keeps the def.
     */
    fun availableIn(version: String): List<FunctionDef> {
        val server = parseVersion(version)?.number ?: return functions
        return functions.filter { def -> def.sinceVersion?.let(::parseVersion)?.let { it.number <= server } ?: true }
    }

    /** True when [name] is a registered table-valued / table-generating function. */
    fun isTableFunction(name: String): Boolean =
        get(name)?.kind in TABLE_KINDS

    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY else PLAIN).encodeToString(ListSerializer(FunctionDef.serializer()), functions)

    private companion object {
        val TABLE_KINDS = setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING)
        val PLAIN = Json
        val PRETTY = Json { prettyPrint = true }
    }
}
