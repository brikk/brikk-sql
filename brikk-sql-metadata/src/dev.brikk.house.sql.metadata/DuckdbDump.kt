package dev.brikk.house.sql.metadata

/**
 * The rows one DuckDB engine reports about itself, as captured by [DUCKDB_FUNCTIONS] and its
 * siblings; null when not captured. Bundled releases are generated from the vendored dumps; a
 * live harvest builds one from the user's engine. Either way [toSnapshot] turns rows into
 * metadata.
 */
class DuckdbDump(
    val engineVersion: String,
    val functions: List<DuckdbFunctionRow>,
    val keywords: List<DuckdbKeywordRow>? = null,
    val settings: List<DuckdbSettingRow>? = null,
    val extensions: List<DuckdbExtensionRow>? = null,
    val extensionFunctions: List<DuckdbExtensionFunctionRow> = emptyList(),
)

/**
 * This release's metadata. [MetadataSnapshot.functions] holds the registered functions, one
 * [FunctionDef] per (name, normalized kind):
 *  - `macro`/`table_macro` normalize to SCALAR/TABLE_VALUED and keep [FunctionDef.nativeKind]
 *    when every row of the def is that macro kind (a scalar+macro name stays a plain SCALAR).
 *  - `pragma` rows are not functions; operator rows (`%`, `||`, `~~`) are grammar, not names.
 *  - aliases are not folded: DuckDB lists every alias as a full row set of its own.
 *  - NULL parameter and return types read as "ANY"; `variadic` mirrors `varargs`, and
 *    [FunctionOverload.argTypes] lists only the fixed parameters.
 *  - [FunctionOverload.argNames] are the row's parameter names (null when it lists none).
 *  - a `table` function lists its positional arguments as `col0`, `col1`, ... and then its
 *    named parameters, so [FunctionOverload.positionalCount] counts the leading `colN` names
 *    when named parameters follow.
 *
 * Fails on a function type it does not know, so a new engine kind is mapped deliberately.
 *
 * [MetadataSnapshot.pragmas] are the `pragma` rows, one [PragmaDef] per name with the same
 * overload shape, linked to a `pragma_<name>` table function when one exists. Extension rows
 * map the same way per extension, into [MetadataSnapshot.extensionFunctions] and pragmas
 * carrying their extension. A keyword category it does not know reads as
 * [KeywordCategory.UNKNOWN].
 */
fun DuckdbDump.toSnapshot(): MetadataSnapshot {
    val byExtension = extensionFunctions.groupBy({ it.extension }, { it.function }).toSortedMap()
    val functionCatalog = FunctionCatalog(functionDefs(functions), grammarBuiltins = DUCKDB_GRAMMAR_BUILTINS)
    return MetadataSnapshot(
        engineVersion,
        functionCatalog,
        keywords = keywords?.let { rows -> KeywordCatalog(rows.map { KeywordDef(it.name, keywordCategory(it.category)) }) },
        pragmas = (pragmaDefs(functions, functionCatalog) +
            byExtension.flatMap { (extension, rows) -> pragmaDefs(rows, functionCatalog, extension) })
            .sortedWith(compareBy({ it.name }, { it.extension })),
        settings = settings?.map { SettingDef(it.name, it.inputType, it.scope, it.aliases, it.description) },
        extensions = extensions?.map { ExtensionDef(it.name, it.staticallyLinked, it.aliases, it.description) },
        extensionFunctions = byExtension.flatMap { (extension, rows) -> functionDefs(rows).map { it.copy(extension = extension) } },
        grammarForms = DUCKDB_GRAMMAR_FORMS,
    )
}

private fun pragmaDefs(rows: List<DuckdbFunctionRow>, functions: FunctionCatalog, extension: String? = null): List<PragmaDef> =
    rows.filter { it.type == PRAGMA }
        .groupBy { it.name }
        .map { (name, group) ->
            PragmaDef(
                name = name,
                overloads = group.overloads(),
                tableFunction = "pragma_$name".takeIf(functions::isTableFunction),
                extension = extension,
            )
        }

private fun keywordCategory(category: String): KeywordCategory = when (category) {
    "reserved" -> KeywordCategory.RESERVED
    "unreserved" -> KeywordCategory.UNRESERVED
    "type_function" -> KeywordCategory.TYPE_FUNCTION
    "column_name" -> KeywordCategory.COLUMN_NAME
    else -> KeywordCategory.UNKNOWN
}

private fun functionDefs(rows: List<DuckdbFunctionRow>): List<FunctionDef> =
    rows.filter { it.type != PRAGMA && IDENTIFIER.matches(it.name) }
        .groupBy { it.name to duckdbKind(it).kind }
        .map { (key, group) ->
            val (name, kind) = key
            FunctionDef(
                name = name,
                kind = kind,
                overloads = group.overloads(),
                nativeKind = group.map { duckdbKind(it).native }.distinct().singleOrNull(),
            )
        }
        .sortedWith(compareBy<FunctionDef> { it.kind }.thenBy { it.name })

private data class DuckdbKind(val kind: FunctionKind, val native: String? = null)

private fun duckdbKind(row: DuckdbFunctionRow): DuckdbKind = when (row.type) {
    "scalar" -> DuckdbKind(FunctionKind.SCALAR)
    "aggregate" -> DuckdbKind(FunctionKind.AGGREGATE)
    "window" -> DuckdbKind(FunctionKind.WINDOW)
    "table" -> DuckdbKind(FunctionKind.TABLE_VALUED)
    "macro" -> DuckdbKind(FunctionKind.SCALAR, "macro")
    "table_macro" -> DuckdbKind(FunctionKind.TABLE_VALUED, "table_macro")
    else -> throw IllegalArgumentException(
        "unknown DuckDB function_type '${row.type}' (function ${row.name}); map it in duckdbKind",
    )
}

private fun List<DuckdbFunctionRow>.overloads(): List<FunctionOverload> =
    map { it.overload() }.distinctBy { Triple(it.argTypes, it.returnType, it.variadic) }.sortedWith(OVERLOAD_ORDER)

private fun DuckdbFunctionRow.overload() = FunctionOverload(
    argTypes = parameterTypes.map { it ?: ANY },
    returnType = returnType ?: ANY,
    variadic = varargs != null,
    argNames = parameters.ifEmpty { null },
    positionalCount = parameters.takeIf { type == "table" }
        ?.takeWhile(POSITIONAL_NAME::matches)?.size
        ?.takeIf { it < parameters.size },
)

private val OVERLOAD_ORDER: Comparator<FunctionOverload> =
    Comparator<FunctionOverload> { a, b -> compareLists(a.argTypes, b.argTypes) }
        .thenBy { it.returnType }
        .thenBy { it.variadic }

private fun compareLists(a: List<String>, b: List<String>): Int =
    a.zip(b).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: a.size.compareTo(b.size)

private const val PRAGMA = "pragma"
private const val ANY = "ANY"
private val POSITIONAL_NAME = Regex("col[0-9]+")
private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
