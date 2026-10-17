package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionOverload
import dev.brikk.house.sql.metadata.TRINO_METADATA

/**
 * Trino's `SHOW FUNCTIONS`: every function of the session path (built-ins, plugin functions, SQL
 * routines), with the same modeling as the bundled Trino catalog, which is generated from the same
 * command: one def per name and kind, one overload per argument and return types, types verbatim,
 * no variadic flags and no argument names (the command shows neither). Rows of a function type it does
 * not know, and names that are not identifiers, are left out.
 */
public object TrinoFunctionHarvester : LiveCatalogHarvester {
    public const val SQL_VERSION: String = "SELECT version()"
    public const val SQL_FUNCTIONS: String = "SHOW FUNCTIONS"

    override fun harvest(rows: CatalogRowSource): Harvest? {
        var version: String? = null
        rows.forEachRow(SQL_VERSION) { row -> version = version ?: row.string(1)?.trim() }
        val overloads = LinkedHashMap<Pair<String, FunctionKind>, MutableSet<FunctionOverload>>()
        rows.forEachRow(SQL_FUNCTIONS) { row ->
            val name = row.string(1)?.takeIf(IDENTIFIER::matches) ?: return@forEachRow
            val kind = KINDS[row.string(4)?.trim()] ?: return@forEachRow
            val overload = FunctionOverload(splitTypes(row.string(3).orEmpty()), row.string(2)?.trim().orEmpty())
            overloads.getOrPut(name to kind, ::LinkedHashSet) += overload
        }
        if (overloads.isEmpty()) return null
        val functions = overloads.map { (key, signatures) ->
            LiveFunction(FunctionDef(key.first, key.second, overloads = signatures.sortedWith(ORDER)))
        }
        return Harvest(LiveInventory(version?.takeIf(String::isNotEmpty), functions, signatures = true))
    }

    /** Splits an argument type list on its top-level commas: `map(K, V), decimal(p,s)` is two types. */
    private fun splitTypes(types: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in types) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
            }
            if (c == ',' && depth == 0) {
                result += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private val KINDS = mapOf(
        "scalar" to FunctionKind.SCALAR,
        "aggregate" to FunctionKind.AGGREGATE,
        "window" to FunctionKind.WINDOW,
        "table" to FunctionKind.TABLE_VALUED,
    )
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val ORDER = compareBy<FunctionOverload>({ it.argTypes.joinToString("\u0000") }, { it.returnType })
}

/**
 * A Trino plugin's live catalog: [TrinoFunctionHarvester] once per IDE session, replacing the bundled
 * registry, in memory. Off ([enabled] false) until the plugin turns it on.
 */
public interface TrinoLiveCatalogProfile : LiveCatalogProfile {
    override val productLabel: String get() = "Trino"
    override val metadata: DialectMetadata get() = TRINO_METADATA
    override val harvester: LiveCatalogHarvester get() = TrinoFunctionHarvester
    override val enabled: Boolean get() = false
}
