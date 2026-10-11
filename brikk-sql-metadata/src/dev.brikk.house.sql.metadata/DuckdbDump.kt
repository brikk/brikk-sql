package dev.brikk.house.sql.metadata

/**
 * The rows one DuckDB engine reports about itself, as captured by [DUCKDB_FUNCTIONS] and its
 * siblings. Bundled releases are generated from the vendored dumps; a live harvest builds one
 * from the user's engine. Either way the same functions below turn rows into metadata.
 */
class DuckdbDump(
    val engineVersion: String,
    val functions: List<DuckdbFunctionRow>,
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
 *
 * Fails on a function type it does not know, so a new engine kind is mapped deliberately.
 */
fun DuckdbDump.toSnapshot(): MetadataSnapshot = MetadataSnapshot(
    engineVersion,
    FunctionCatalog(functionDefs(functions), grammarBuiltins = DUCKDB_GRAMMAR_BUILTINS),
)

private fun functionDefs(rows: List<DuckdbFunctionRow>): List<FunctionDef> =
    rows.filter { it.type != PRAGMA && IDENTIFIER.matches(it.name) }
        .groupBy { it.name to duckdbKind(it).kind }
        .map { (key, group) ->
            val (name, kind) = key
            FunctionDef(
                name = name,
                kind = kind,
                overloads = group.map { it.overload() }
                    .distinctBy { Triple(it.argTypes, it.returnType, it.variadic) }
                    .sortedWith(OVERLOAD_ORDER),
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

private fun DuckdbFunctionRow.overload() = FunctionOverload(
    argTypes = parameterTypes.map { it ?: ANY },
    returnType = returnType ?: ANY,
    variadic = varargs != null,
    argNames = parameters.ifEmpty { null },
)

private val OVERLOAD_ORDER: Comparator<FunctionOverload> =
    Comparator<FunctionOverload> { a, b -> compareLists(a.argTypes, b.argTypes) }
        .thenBy { it.returnType }
        .thenBy { it.variadic }

private fun compareLists(a: List<String>, b: List<String>): Int =
    a.zip(b).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: a.size.compareTo(b.size)

private const val PRAGMA = "pragma"
private const val ANY = "ANY"
private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
