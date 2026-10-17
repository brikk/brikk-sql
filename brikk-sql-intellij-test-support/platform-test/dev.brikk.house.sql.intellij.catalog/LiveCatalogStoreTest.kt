package dev.brikk.house.sql.intellij.catalog

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.KeywordDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.days

class LiveCatalogStoreTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val a = ConnectionIdentity.of(listOf("POSTGRES", "jdbc:duckdb:/a.db", "me"))
    private val b = ConnectionIdentity.of(listOf("POSTGRES", "jdbc:duckdb:/b.db", "me"))
    private val shared = inventory(liveFunction("abs", "BIGINT", returnType = "BIGINT"), keywords = listOf(KeywordDef("SELECT", KeywordCategory.RESERVED)))

    override fun setUp() {
        super.setUp()
        dir = Path.of(PathManager.getSystemPath(), "live-catalog-store", UUID.randomUUID().toString())
    }

    override fun tearDown() {
        try {
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testNothingStoredSelectsTheBundledSnapshot() {
        assertEquals(LiveCatalogSelection.BUNDLED, LiveCatalogStore(dir).select("ds", a))
    }

    fun testAHarvestThisSessionIsLiveAndRestartsFromDisk() {
        val live = LiveCatalogStore(dir).also { it.store("ds", a, shared) }.select("ds", a)
        assertInstanceOf(live.source, CatalogSource.Live::class.java)
        val restarted = LiveCatalogStore(dir).select("ds", a)
        assertInstanceOf(restarted.source, CatalogSource.Disk::class.java)
        assertEquals("the file keeps the whole inventory", shared, restarted.inventory)
        assertFalse(LiveCatalogStore(dir).hasLive("ds", a))
    }

    fun testAChangedIdentityIgnoresAndReplacesTheOldEntry() {
        LiveCatalogStore(dir).store("ds", a, shared)
        val restarted = LiveCatalogStore(dir)
        assertEquals(CatalogSource.Bundled, restarted.select("ds", b).source)
        restarted.store("ds", b, inventory(liveFunction("other")))
        assertEquals("the other identity's file is deleted", 1, dir.listDirectoryEntries().size)
        assertEquals(CatalogSource.Bundled, LiveCatalogStore(dir).select("ds", a).source)
    }

    fun testSessionFactsServeOnlyTheirSessionAndNeverPersist() {
        val store = LiveCatalogStore(dir)
        store.store("ds", a, shared)
        val first = Any()
        val second = Any()
        store.storeSession(first, "ds", a, SessionFacts(listOf(liveFunction("m1"))))
        store.storeSession(second, "ds", a, SessionFacts(listOf(liveFunction("m2"))))
        assertEquals(listOf("m1"), store.select("ds", a, first).session.functions.map { it.def.name })
        assertEquals(listOf("m2"), store.select("ds", a, second).session.functions.map { it.def.name })
        assertEquals(SessionFacts.NONE, store.select("ds", a).session)
        assertEquals("not under another identity", SessionFacts.NONE, store.select("ds", b, first).session)
        val restarted = LiveCatalogStore(dir).select("ds", a, first)
        assertEquals(shared, restarted.inventory)
        assertEquals("a restart restores no session facts", SessionFacts.NONE, restarted.session)
    }

    fun testAnOldDataSourceFileIsRewrittenUnderTheIdentity() {
        val legacy = TestLegacyFiles(dir.resolve("legacy"))
        Files.createDirectories(legacy.root)
        Files.writeString(legacy.file("ds"), "abs\nlower\n")
        val selection = LiveCatalogStore(dir, legacy).select("ds", a)
        assertInstanceOf(selection.source, CatalogSource.Disk::class.java)
        assertEquals(listOf("abs", "lower"), selection.inventory?.functions?.map { it.def.name })
        assertFalse("the old file is deleted", Files.exists(legacy.file("ds")))
        assertEquals("and read from the new one after a restart", selection.inventory, LiveCatalogStore(dir).select("ds", a).inventory)
    }

    fun testACorruptOrForeignFileIsIgnored() {
        LiveCatalogStore(dir).store("ds", a, shared)
        val file = dir.listDirectoryEntries().single()
        Files.writeString(file, Files.readString(file).replace("\"abs\"", "\"abs"))
        assertEquals(CatalogSource.Bundled, LiveCatalogStore(dir).select("ds", a).source)
        LiveCatalogStore(dir).store("ds", a, shared)
        Files.move(dir.listDirectoryEntries().single(), file.resolveSibling(file.fileName.toString().replace("ds~", "dt~")))
        assertEquals("another data source's file", CatalogSource.Bundled, LiveCatalogStore(dir).select("dt", a).source)
    }

    fun testForgetDeletesTheDataSourcesFilesAndFacts() {
        val store = LiveCatalogStore(dir)
        store.store("ds", a, shared)
        store.store("keep", a, shared)
        val session = Any()
        store.storeSession(session, "ds", a, SessionFacts(listOf(liveFunction("m1"))))
        store.forget("ds")
        assertEquals(LiveCatalogSelection.BUNDLED, store.select("ds", a, session))
        assertEquals(1, dir.listDirectoryEntries().size)
        assertEquals(CatalogSource.Bundled, LiveCatalogStore(dir).select("ds", a).source)
    }

    fun testSweepDeletesFilesNotWrittenForTheMaximumAge() {
        val store = LiveCatalogStore(dir)
        store.store("old", a, shared)
        store.store("new", a, shared)
        val old = dir.listDirectoryEntries().single { it.fileName.toString().startsWith("old~") }
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(100L * 24 * 3600)))
        assertEquals("a restarted store sweeps it", 1, LiveCatalogStore(dir).sweep(90.days))
        assertEquals(listOf("new"), dir.listDirectoryEntries().map { it.fileName.toString().substringBefore('~') })
    }

    fun testSweepKeepsWhatThisSessionSelected() {
        LiveCatalogStore(dir).store("old", a, shared)
        val file = dir.listDirectoryEntries().single()
        val longAgo = FileTime.from(Instant.now().minusSeconds(100L * 24 * 3600))
        Files.setLastModifiedTime(file, longAgo)
        val restarted = LiveCatalogStore(dir)
        assertInstanceOf(restarted.select("old", a).source, CatalogSource.Disk::class.java)
        assertEquals("a selected file is marked as used", 0, LiveCatalogStore(dir).sweep(90.days))
        Files.setLastModifiedTime(file, longAgo)
        assertEquals("an entry selected this session stays", 0, restarted.sweep(90.days))
        assertTrue(Files.exists(file))
    }

    fun testAMemoryOnlyStoreWritesNothing() {
        val store = LiveCatalogStore(null)
        store.store("ds", a, shared)
        assertInstanceOf(store.select("ds", a).source, CatalogSource.Live::class.java)
        assertFalse(Files.exists(dir))
    }

    /** One function name per line, in a file named after the data source. */
    class TestLegacyFiles(val root: Path) : LegacyCatalogFiles {
        override fun file(dataSourceId: String): Path = root.resolve("$dataSourceId.tsv")

        override fun read(file: Path): LiveInventory? =
            Files.readAllLines(file).filter(String::isNotBlank).takeIf { it.isNotEmpty() }
                ?.let { names -> LiveInventory(null, names.map { liveFunction(it) }, signatures = false) }
    }
}
