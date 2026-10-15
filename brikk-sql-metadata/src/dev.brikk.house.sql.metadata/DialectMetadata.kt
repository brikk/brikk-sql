package dev.brikk.house.sql.metadata

/**
 * What metadata knows about one engine release. Every optional part means the same thing
 * when null: not known for this dialect (not captured or not researched), never "none".
 * [extensionFunctions] are functions that loadable extensions add on top of [functions]
 * (each with [FunctionDef.extension]); they stay out of [functions], so its lookups answer
 * for the base engine only. [tableFunctions] adds call-site knowledge to table functions;
 * [grammarForms] say how the parser accepts function-shaped names (see [completionEntries]).
 */
class MetadataSnapshot(
    val engineVersion: String,
    val functions: FunctionCatalog,
    val keywords: KeywordCatalog? = null,
    val pragmas: List<PragmaDef>? = null,
    val settings: List<SettingDef>? = null,
    val extensions: List<ExtensionDef>? = null,
    val extensionFunctions: List<FunctionDef>? = null,
    val tableFunctions: List<TableFunctionInfo>? = null,
    val grammarForms: List<GrammarForm>? = null,
) {
    /** The [TableFunctionInfo] for [name], matched by the dialect's name policy. */
    fun tableFunction(name: String): TableFunctionInfo? =
        functions.nameKey(name).let { key -> tableFunctions?.find { functions.nameKey(it.name) == key } }
}

/** Every bundled snapshot of one dialect, oldest first, and where the engine's documentation lives ([docs]), if bundled. */
class DialectMetadata(
    val dialect: String,
    snapshots: List<MetadataSnapshot>,
    defaultVersion: String,
    val docs: EngineDocs? = null,
) {
    // Binary compatibility (AGENTS.md): the constructor from before docs.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(dialect: String, snapshots: List<MetadataSnapshot>, defaultVersion: String) : this(dialect, snapshots, defaultVersion, null)

    init {
        require(docs == null || docs.dialect == dialect) { "$dialect's docs are ${docs?.dialect}'s" }
    }

    val snapshots: List<MetadataSnapshot> = snapshots.sortedBy { it.version }

    /** The snapshot used when the server version is unknown. */
    val default: MetadataSnapshot = requireNotNull(snapshots.find { it.engineVersion == defaultVersion }) {
        "$dialect has no $defaultVersion snapshot"
    }

    /**
     * The snapshot for a server's reported version ("v1.5.5", "v2.0.0-alpha43143", "483"):
     * the same major.minor first (the nearest older one, else the oldest of that line), then
     * the nearest older snapshot; a server older than every snapshot gets the oldest. A missing
     * or unparsable version gets [default].
     */
    fun forServerVersion(raw: String?): Selection {
        val server = raw?.let(::parseVersion) ?: return Selection(default, exact = false)
        val line = snapshots.filter { it.version.majorMinor == server.number.majorMinor }
        val snapshot = line.lastOrNull { it.version <= server.number }
            ?: line.firstOrNull()
            ?: snapshots.lastOrNull { it.version <= server.number }
            ?: snapshots.first()
        return Selection(snapshot, exact = !server.prerelease && snapshot.version.compareTo(server.number) == 0)
    }

    /** [exact] is true only for a release server whose version equals the snapshot's. */
    data class Selection(val snapshot: MetadataSnapshot, val exact: Boolean)
}

/** The bundled metadata of the core dialect named [dialect] (`doris`, `duckdb`, ...), or null when none is bundled. */
fun bundledMetadata(dialect: String): DialectMetadata? = when (dialect) {
    "clickhouse" -> CLICKHOUSE_METADATA
    "doris" -> DORIS_METADATA
    "duckdb" -> DUCKDB_METADATA
    "starrocks" -> STARROCKS_METADATA
    "trino" -> TRINO_METADATA
    else -> null
}

/** A dialect with one bundled snapshot, which is also its default. */
internal fun singleSnapshot(dialect: String, snapshot: MetadataSnapshot, docs: EngineDocs? = null) =
    DialectMetadata(dialect, listOf(snapshot), defaultVersion = snapshot.engineVersion, docs = docs)

private val MetadataSnapshot.version: Version
    get() = requireNotNull(parseVersion(engineVersion)?.takeUnless { it.prerelease }) {
        "snapshot version '$engineVersion' is not a release version"
    }.number

internal class Version(val parts: List<Int>) : Comparable<Version> {
    val majorMinor: Pair<Int, Int> get() = part(0) to part(1)

    private fun part(index: Int) = parts.getOrElse(index) { 0 }

    override fun compareTo(other: Version): Int =
        (0 until maxOf(parts.size, other.parts.size))
            .firstNotNullOfOrNull { i -> part(i).compareTo(other.part(i)).takeIf { it != 0 } } ?: 0
}

internal class ParsedVersion(val number: Version, val prerelease: Boolean)

private val VERSION = Regex("""(\d+(?:\.\d+)*)(-)?""")

internal fun parseVersion(raw: String): ParsedVersion? = VERSION.find(raw)?.let { match ->
    val parts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    ParsedVersion(Version(parts), prerelease = match.groups[2] != null)
}
