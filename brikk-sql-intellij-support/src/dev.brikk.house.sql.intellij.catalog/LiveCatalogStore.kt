package dev.brikk.house.sql.intellij.catalog

import com.intellij.openapi.diagnostic.logger
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.KeywordDef
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Data source files written by an earlier, data-source-keyed format. On first use under a connection
 * identity, a data source's file is read, rewritten under that identity and deleted.
 */
public interface LegacyCatalogFiles {
    /** The old file of [dataSourceId], whether or not it exists. */
    public fun file(dataSourceId: String): Path

    /** The complete inventory in [file]; null when it does not parse. */
    public fun read(file: Path): LiveInventory?
}

/**
 * Live catalogs per data source and connection identity, selected by one ordered rule:
 *  1. a complete harvest from the current identity taken this IDE session ([CatalogSource.Live]);
 *  2. otherwise a complete harvest on disk stored under the current identity ([CatalogSource.Disk]);
 *  3. otherwise none, and the caller serves the bundled snapshot ([CatalogSource.Bundled]).
 *
 * Only complete harvests are stored, so a failed or partial refresh never replaces one. Another
 * identity's entries are never selected, and storing a harvest deletes the data source's files of other
 * identities. Session facts are kept per session key (held weakly) in memory only, and served only to
 * that session under the identity they were read from.
 *
 * With a [directory], every stored harvest is also written there, one file per data source and
 * identity, read lazily on first selection after a restart; without, the store is memory only.
 */
public class LiveCatalogStore(
    private val directory: Path?,
    private val legacy: LegacyCatalogFiles? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Key(val dataSourceId: String, val identity: ConnectionIdentity)

    private class Entry(val inventory: LiveInventory, val source: CatalogSource)

    private class SessionEntry(val key: Key, val facts: SessionFacts)

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** Keys whose file was read, found or not, so a miss costs no IO per completion. */
    private val probed = ConcurrentHashMap.newKeySet<Key>()

    private val sessions: MutableMap<Any, SessionEntry> = Collections.synchronizedMap(WeakHashMap())

    /** The catalog for [dataSourceId] under [identity], with [session]'s facts when it has some under that identity. */
    public fun select(dataSourceId: String, identity: ConnectionIdentity, session: Any? = null): LiveCatalogSelection {
        val key = Key(dataSourceId, identity)
        val facts = session?.let { sessions[it] }?.takeIf { it.key == key }?.facts ?: SessionFacts.NONE
        val entry = entries[key] ?: loadOnce(key)
        return entry?.let { LiveCatalogSelection(it.source, it.inventory, facts) } ?: LiveCatalogSelection(CatalogSource.Bundled, null, facts)
    }

    /** Whether a complete harvest from [identity] was stored this IDE session. */
    public fun hasLive(dataSourceId: String, identity: ConnectionIdentity): Boolean =
        entries[Key(dataSourceId, identity)]?.source is CatalogSource.Live

    /** Stores a complete [inventory] as this session's live catalog of [dataSourceId] under [identity]. */
    public fun store(dataSourceId: String, identity: ConnectionIdentity, inventory: LiveInventory) {
        val key = Key(dataSourceId, identity)
        val harvestedAt = clock.instant()
        entries[key] = Entry(inventory, CatalogSource.Live(harvestedAt))
        probed += key
        entries.keys.removeIf { it.dataSourceId == dataSourceId && it != key }
        val dir = directory ?: return
        try {
            write(dir, key, inventory, harvestedAt)
            filesOf(dir, dataSourceId).filter { it != fileOf(dir, key) }.forEach(Files::deleteIfExists)
        } catch (e: IOException) {
            LOG.warn("The live catalog of $dataSourceId was not persisted; the in-memory copy stays", e)
        }
    }

    /** Replaces the facts of [session], read from [dataSourceId] under [identity]. */
    public fun storeSession(session: Any, dataSourceId: String, identity: ConnectionIdentity, facts: SessionFacts) {
        sessions[session] = SessionEntry(Key(dataSourceId, identity), facts)
    }

    /** Forgets everything about [dataSourceId], on disk too, for example after the data source was deleted. */
    public fun forget(dataSourceId: String) {
        entries.keys.removeIf { it.dataSourceId == dataSourceId }
        probed.removeIf { it.dataSourceId == dataSourceId }
        synchronized(sessions) { sessions.values.removeIf { it.key.dataSourceId == dataSourceId } }
        try {
            directory?.let { dir -> filesOf(dir, dataSourceId).forEach(Files::deleteIfExists) }
            legacy?.file(dataSourceId)?.let(Files::deleteIfExists)
        } catch (e: IOException) {
            LOG.warn("The live catalog files of $dataSourceId were not deleted", e)
        }
    }

    /**
     * Deletes the files neither written nor selected within [maxAge], which data sources deleted while the
     * store was not listening leave behind. Entries selected or stored this session stay.
     */
    public fun sweep(maxAge: Duration): Int {
        val dir = directory?.takeIf(Files::isDirectory) ?: return 0
        val oldest = clock.instant().minusMillis(maxAge.inWholeMilliseconds)
        val inUse = entries.keys.mapTo(HashSet()) { fileOf(dir, it) }
        return try {
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().endsWith(SUFFIX) || it.fileName.toString().endsWith(TEMP_SUFFIX) }
                    .filter { it !in inUse }
                    .filter { Files.getLastModifiedTime(it).toInstant() < oldest }
                    .toList()
            }.count(Files::deleteIfExists)
        } catch (e: IOException) {
            LOG.warn("Sweeping old live catalog files failed", e)
            0
        }
    }

    private fun loadOnce(key: Key): Entry? {
        if (directory == null || !probed.add(key)) return null
        val loaded = read(directory, key)?.also { touch(fileOf(directory, key)) } ?: migrate(directory, key) ?: return null
        // A harvest stored meanwhile is newer than the file.
        return entries.putIfAbsent(key, loaded) ?: loaded
    }

    /** Marks a selected file as used, so [sweep] keeps it. */
    private fun touch(file: Path) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()))
        } catch (e: IOException) {
            LOG.info("The live catalog file $file was not marked as used: ${e.message}")
        }
    }

    private fun migrate(dir: Path, key: Key): Entry? {
        val files = legacy ?: return null
        val old = files.file(key.dataSourceId)
        if (!Files.isRegularFile(old)) return null
        return try {
            val inventory = files.read(old)
            val harvestedAt = Files.getLastModifiedTime(old).toInstant()
            if (inventory != null) write(dir, key, inventory, harvestedAt)
            Files.deleteIfExists(old)
            inventory?.let { Entry(it, CatalogSource.Disk(harvestedAt)) }
        } catch (e: IOException) {
            LOG.warn("The old live catalog file $old was not migrated", e)
            null
        }
    }

    private fun write(dir: Path, key: Key, inventory: LiveInventory, harvestedAt: Instant) {
        Files.createDirectories(dir)
        val text = buildString {
            appendLine(HEADER)
            appendLine("ds\t${json.encodeToString(String.serializer(), key.dataSourceId)}")
            appendLine("identity\t${key.identity.hash}")
            appendLine("harvested\t${harvestedAt.toEpochMilli()}")
            appendLine("server\t${json.encodeToString(String.serializer().nullable, inventory.serverVersion)}")
            appendLine("signatures\t${inventory.signatures}")
            appendLine("extensions\t${json.encodeToString(names, inventory.extensions)}")
            inventory.keywords?.let { appendLine("keywords\t${json.encodeToString(keywords, it)}") }
            for (function in inventory.functions) {
                appendLine("fn\t${json.encodeToString(names, function.namespace)}\t${json.encodeToString(FunctionDef.serializer(), function.def)}")
            }
        }
        val file = fileOf(dir, key)
        val temp = file.resolveSibling(file.fileName.toString() + TEMP_SUFFIX)
        Files.writeString(temp, text)
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Null for a missing, corrupt or foreign file: a bad cache falls back to the bundled snapshot. */
    private fun read(dir: Path, key: Key): Entry? {
        val file = fileOf(dir, key)
        if (!Files.isRegularFile(file)) return null
        return try {
            val lines = Files.readAllLines(file)
            if (lines.firstOrNull() != HEADER) return null
            val fields = HashMap<String, String>()
            val functions = ArrayList<LiveFunction>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts[0] == "fn" && parts.size == 3) {
                    functions += LiveFunction(json.decodeFromString(FunctionDef.serializer(), parts[2]), json.decodeFromString(names, parts[1]))
                } else if (parts.size == 2 && fields.put(parts[0], parts[1]) == null) {
                    continue
                } else {
                    return null
                }
            }
            if (fields["ds"]?.let { json.decodeFromString(String.serializer(), it) } != key.dataSourceId || fields["identity"] != key.identity.hash) return null
            val harvestedAt = Instant.ofEpochMilli(fields["harvested"]?.toLongOrNull() ?: return null)
            val inventory = LiveInventory(
                serverVersion = json.decodeFromString(String.serializer().nullable, fields["server"] ?: return null),
                functions = functions,
                signatures = fields["signatures"]?.toBooleanStrictOrNull() ?: return null,
                keywords = fields["keywords"]?.let { json.decodeFromString(keywords, it) },
                extensions = json.decodeFromString(names, fields["extensions"] ?: return null),
            )
            Entry(inventory, CatalogSource.Disk(harvestedAt))
        } catch (e: IOException) {
            LOG.info("The live catalog file $file was not read: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            LOG.info("The live catalog file $file is corrupt: ${e.message}")
            null
        }
    }

    private fun fileOf(dir: Path, key: Key): Path = dir.resolve("${sanitize(key.dataSourceId)}~${key.identity.hash}$SUFFIX")

    private fun filesOf(dir: Path, dataSourceId: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val prefix = sanitize(dataSourceId) + "~"
        return Files.list(dir).use { files -> files.filter { it.fileName.toString().let { name -> name.startsWith(prefix) && name.endsWith(SUFFIX) } }.toList() }
    }

    private companion object {
        val LOG = logger<LiveCatalogStore>()
        const val HEADER = "# brikk live catalog 1"
        const val SUFFIX = ".catalog"
        const val TEMP_SUFFIX = ".tmp"
        val json = Json { ignoreUnknownKeys = true }
        val names = ListSerializer(String.serializer())
        val keywords = ListSerializer(KeywordDef.serializer())

        /** Data source ids are UUIDs in practice; the file header holds the exact id. */
        fun sanitize(id: String): String = id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(120).ifEmpty { "ds" }
    }
}
