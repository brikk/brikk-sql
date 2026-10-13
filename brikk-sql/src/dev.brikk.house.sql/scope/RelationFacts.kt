package dev.brikk.house.sql.scope

import kotlinx.serialization.Serializable

/** How much of a relation's columns is known. */
@Serializable
enum class Knowledge {
    /** Every column is known: a name that is not listed does not exist. */
    FULL,

    /** The listed columns exist, and others may. */
    PARTIAL,

    /** Nothing is known, not even that the listed columns are all. */
    UNKNOWN,
}

@Serializable
enum class Nullability { NON_NULL, NULLABLE, UNKNOWN }

/** Where a column comes from. */
@Serializable
enum class ColumnOrigin {
    /** Declared one by one by the provider, such as the properties of a host-language row type. */
    DECLARED,

    /** Known only through a relation-level contract; navigation leads to the relation, not the column. */
    RELATION_CONTRACT,

    /** A column of a physical table in the catalog. */
    PHYSICAL_TABLE,

    /** Computed by the SQL itself. Facts never carry it; the analysis assigns it. */
    COMPUTED,
}

/**
 * One column a provider knows. [sqlType] is the exact SQL type when known, never inferred from
 * [displayType], which is the provider's own type text (a Kotlin type, for example).
 * [provenance] is an opaque id the provider can navigate to.
 */
@Serializable
data class ColumnFact(
    val name: String,
    val sqlType: String? = null,
    val displayType: String? = null,
    val nullability: Nullability = Nullability.UNKNOWN,
    val origin: ColumnOrigin = ColumnOrigin.DECLARED,
    val provenance: String? = null,
)

/** The columns of one relation, in order. [provenance] is an opaque navigation id for the relation itself. */
@Serializable
data class RelationFact(
    val columns: List<ColumnFact> = emptyList(),
    val knowledge: Knowledge = Knowledge.FULL,
    val provenance: String? = null,
)

/** Whether the provider's tables are the whole catalog. */
@Serializable
enum class CatalogKnowledge {
    /** Other tables may exist: an unlisted table is not an error. */
    OPEN,

    /** The listed tables are all: an unlisted one is reported. */
    CLOSED,
}

/**
 * What the analysis knows about the relations SQL can name, copied from any provider.
 *
 * - [slots]: relations SQL reaches through a slot, an unqualified call with no arguments, `src()`.
 *   Keys match the call's name exactly, case included: they are provider keys, not SQL names.
 * - [tables]: physical tables by name, dotted when qualified (`db.events`), matched by the
 *   dialect's identifier rules, the last parts of a qualified name included.
 * - [calls]: the outputs of table functions, matched exactly like slots, called with any
 *   arguments, in `FROM` or a pipe `CALL`.
 * - [searchPath]: the namespaces a partly qualified table name is looked up in, in order, each a
 *   dotted path as stored (`one.sales`), such as a console's current schema; it supplies the leading
 *   components the name leaves out (`sales.orders` in `one.sales` is `one.sales.orders`). A name
 *   several tables match takes the one in the first namespace holding exactly one; when none does,
 *   the table is ambiguous: unknown, never the first match and never reported as missing.
 */
@Serializable
data class RelationFacts(
    val slots: Map<String, RelationFact> = emptyMap(),
    val tables: Map<String, RelationFact> = emptyMap(),
    val calls: Map<String, RelationFact> = emptyMap(),
    val catalog: CatalogKnowledge = CatalogKnowledge.OPEN,
    val searchPath: List<String> = emptyList(),
)
