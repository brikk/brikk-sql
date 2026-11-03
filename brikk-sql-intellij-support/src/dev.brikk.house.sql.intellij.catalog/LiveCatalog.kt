package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.KeywordDef
import java.security.MessageDigest
import java.time.Instant

/** A function a server reports, with the namespace it lives in (empty for built-ins). */
public data class LiveFunction(val def: FunctionDef, val namespace: List<String> = emptyList())

/**
 * What one complete harvest read from a server, shared by every connection with the same identity.
 * With [signatures] the functions' overloads are the engine's own; without, the inventory lists names
 * only and proves availability, not overloads. [keywords] is null when the harvest reads none.
 * [extensions] are the loaded extensions or plugins the server reports, for display.
 */
public data class LiveInventory(
    val serverVersion: String?,
    val functions: List<LiveFunction>,
    val signatures: Boolean,
    val keywords: List<KeywordDef>? = null,
    val extensions: List<String> = emptyList(),
    val nativeCatalog: NativeCatalog? = null,
) {
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(serverVersion: String?, functions: List<LiveFunction>, signatures: Boolean,
        keywords: List<KeywordDef>? = null, extensions: List<String> = emptyList()) :
        this(serverVersion, functions, signatures, keywords, extensions, null)

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public fun copy(serverVersion: String? = this.serverVersion, functions: List<LiveFunction> = this.functions,
        signatures: Boolean = this.signatures, keywords: List<KeywordDef>? = this.keywords,
        extensions: List<String> = this.extensions): LiveInventory =
        copy(serverVersion, functions, signatures, keywords, extensions, nativeCatalog)
}

/**
 * Facts that belong to one database session, such as temporary macros, extensions the session loaded
 * or catalogs it attached. They are kept per session in memory and never persisted.
 */
public data class SessionFacts(val functions: List<LiveFunction> = emptyList(), val extensions: List<String> = emptyList(),
    val inventory: LiveInventory? = null, val namespace: List<String> = emptyList()) {
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(functions: List<LiveFunction> = emptyList(), extensions: List<String> = emptyList()) : this(functions, extensions, null, emptyList())

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public fun copy(functions: List<LiveFunction> = this.functions, extensions: List<String> = this.extensions): SessionFacts =
        copy(functions, extensions, inventory, namespace)
    public companion object {
        public val NONE: SessionFacts = SessionFacts()
    }
}

/** One harvest: the server's [shared] inventory and the [session] facts of the connection it ran on. */
public data class Harvest(val shared: LiveInventory, val session: SessionFacts = SessionFacts.NONE)

/** One row of a catalog query; columns are 1-based. */
public fun interface CatalogRow {
    public fun string(column: Int): String?
}

/** Runs catalog queries, wherever their rows come from: a remote IDE connection, or plain JDBC in tests. */
public fun interface CatalogRowSource {
    /** Runs [sql] and calls [onRow] once per row; failures propagate. */
    public fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit)
}

/** A dialect's harvest: its SQL and how it reads the rows. */
public fun interface LiveCatalogHarvester {
    /**
     * Reads one harvest over [rows]; null when what it read is incomplete (an empty inventory, a missing
     * part). Failures propagate; a partial or failed harvest never replaces a complete one.
     */
    public fun harvest(rows: CatalogRowSource): Harvest?
}

/**
 * Which server a data source's connections reach, as a hash: the live catalog cache key. A data
 * source whose URL or user changes gets another identity, so what was harvested before is not used.
 */
@JvmInline
public value class ConnectionIdentity(public val hash: String) {
    public companion object {
        /** The identity of [parts] (for example dbms, URL and user), in order; nulls count as empty. */
        public fun of(parts: List<String?>): ConnectionIdentity {
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000") { it.orEmpty() }.toByteArray())
            return ConnectionIdentity(digest.take(16).joinToString("") { "%02x".format(it) })
        }
    }
}

/** Where the inventory serving a data source came from. */
public sealed interface CatalogSource {
    /** A complete harvest from the current connection identity, taken this IDE session. */
    public data class Live(val harvestedAt: Instant) : CatalogSource

    /** A complete harvest from an earlier IDE session, on disk under the current connection identity. */
    public data class Disk(val harvestedAt: Instant) : CatalogSource

    /** No harvest: the bundled snapshot for the server version. */
    public data object Bundled : CatalogSource
}

/**
 * The live catalog selected for a data source: [inventory] is null when [source] is [CatalogSource.Bundled].
 * [session] holds the facts of the asking session, if any.
 */
public data class LiveCatalogSelection(
    val source: CatalogSource,
    val inventory: LiveInventory?,
    val session: SessionFacts = SessionFacts.NONE,
) {
    public companion object {
        public val BUNDLED: LiveCatalogSelection = LiveCatalogSelection(CatalogSource.Bundled, null)
    }
}
