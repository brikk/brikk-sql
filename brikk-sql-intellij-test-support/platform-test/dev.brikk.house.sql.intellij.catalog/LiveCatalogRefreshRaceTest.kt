package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/** SDK-opened helpers and native DuckDB collection, gated after collection, not simulated connections. */
class LiveCatalogRefreshRaceTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var directory: Path
    private lateinit var source: LocalDataSource
    private lateinit var service: LiveService
    private lateinit var harvester: CountingHarvester
    private var budget: Duration = 30.seconds

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        directory = Path.of(PathManager.getSystemPath(), "catalog-refresh-races", UUID.randomUUID().toString())
        source = realDuckdbDataSource(project, testRootDisposable)
        harvester = CountingHarvester()
        service = LiveService(errors.newScope(testRootDisposable), object : LiveCatalogProfile by LiveProfile(harvester, dbms = source.dbms) {
            override val deadline: Duration get() = budget
        }, directory)
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { errors.assertNone() },
        { directory.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    private fun refresh(point: LocalDataSource = source): Deferred<RefreshOutcome> =
        errors.newScope(testRootDisposable).async(Dispatchers.IO) { service.refresh(project, point) }

    private fun await(result: Deferred<RefreshOutcome>): RefreshOutcome {
        PlatformTestUtil.waitWithEventsDispatching("helper refresh finishes", { result.isCompleted }, 30)
        return runBlocking { result.await() }
    }

    private fun captured(gate: CountDownLatch) =
        PlatformTestUtil.waitWithEventsDispatching("native inventory was collected", { gate.count == 0L }, 20)

    private fun marked(rows: CatalogRowSource, marker: String): Harvest = checkNotNull(DuckdbTestHarvester.harvest(rows)).let {
        it.copy(shared = it.shared.copy(functions = it.shared.functions + liveFunction(marker)))
    }

    private fun assertInventory(marker: String, point: LocalDataSource = source) {
        assertTrue(marker, service.selection(point).inventory!!.functions.any { it.def.name == marker })
        val disk = LiveCatalogStore(directory).select(point.uniqueId, service.profile.identity(point))
        assertTrue("persisted $marker", disk.inventory!!.functions.any { it.def.name == marker })
    }

    fun testForgetInvalidatesAnAlreadyCollectedHelperResultInMemoryAndOnDisk() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        harvester.answer = { rows ->
            marked(rows, "forgotten").also { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        }
        val old = refresh()
        try {
            captured(entered)
            service.forget(source.uniqueId)
            assertNull(service.selection(source).inventory)
        } finally { release.countDown() }
        assertEquals(RefreshOutcome.Superseded, await(old))
        assertNull(service.selection(source).inventory)
        assertNull(LiveCatalogStore(directory).select(source.uniqueId, service.profile.identity(source)).inventory)
        assertFalse(Files.exists(directory) && Files.list(directory).use { it.anyMatch { p -> p.toString().endsWith(".catalog") } })
        harvester.answer = { marked(it, "after_forget") }
        assertInstanceOf(await(refresh()), RefreshOutcome.Refreshed::class.java)
        assertInventory("after_forget")
    }

    fun testOlderSameIdentityHelperCannotReplaceANewerAcceptedCompleteResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sequence = AtomicInteger()
        harvester.answer = { rows ->
            if (sequence.incrementAndGet() == 1) marked(rows, "older").also {
                entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
            } else marked(rows, "newer")
        }
        val old = refresh()
        try {
            captured(entered)
            assertInstanceOf(await(refresh()), RefreshOutcome.Refreshed::class.java)
            assertInventory("newer")
        } finally { release.countDown() }
        assertEquals(RefreshOutcome.Superseded, await(old))
        assertInventory("newer")
    }

    fun testNewerFailurePreservesLastGoodAndDoesNotFenceOutAnOlderSuccess() = newerUnsuccessful("failed")
    fun testNewerPartialPreservesLastGoodAndDoesNotFenceOutAnOlderSuccess() = newerUnsuccessful("partial")
    fun testNewerTimeoutPreservesLastGoodAndDoesNotFenceOutAnOlderSuccess() = newerUnsuccessful("timeout")
    fun testNewerCancellationPreservesLastGoodAndDoesNotFenceOutAnOlderSuccess() = newerUnsuccessful("cancelled")

    private fun newerUnsuccessful(kind: String) {
        harvester.answer = { marked(it, "last_good") }
        assertInstanceOf(await(refresh()), RefreshOutcome.Refreshed::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val sequence = AtomicInteger()
        harvester.answer = { rows ->
            val result = marked(rows, "older_success")
            if (sequence.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                result
            } else when (kind) {
                "failed" -> error("newer collection failed")
                "partial" -> null
                else -> { secondEntered.countDown(); secondRelease.await(20, TimeUnit.SECONDS); result }
            }
        }
        val old = refresh()
        try {
            captured(entered)
            if (kind == "timeout") budget = 100.milliseconds
            val newer = refresh()
            if (kind == "cancelled") {
                captured(secondEntered)
                newer.cancel()
                PlatformTestUtil.waitWithEventsDispatching("cancelled helper ends", { newer.isCompleted }, 20)
                assertTrue(newer.isCancelled)
            } else {
                val failure = await(newer) as RefreshOutcome.HarvestFailed
                when (kind) {
                    "failed" -> assertInstanceOf(failure.outcome, HarvestOutcome.Failed::class.java)
                    "partial" -> assertEquals(HarvestOutcome.Incomplete, failure.outcome)
                    "timeout" -> assertEquals(HarvestOutcome.TimedOut, failure.outcome)
                }
            }
            assertInventory("last_good")
        } finally {
            secondRelease.countDown()
            release.countDown()
        }
        assertInstanceOf(await(old), RefreshOutcome.Refreshed::class.java)
        assertInventory("older_success")
    }

    fun testStaleIdentityCannotDeleteTheNewIdentitysPersistedResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sequence = AtomicInteger()
        harvester.answer = { rows ->
            if (sequence.incrementAndGet() == 1) marked(rows, "old_identity").also {
                entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
            } else marked(rows, "new_identity")
        }
        val old = refresh()
        try {
            captured(entered)
            source.username = "another-identity"
            assertInstanceOf(await(refresh()), RefreshOutcome.Refreshed::class.java)
            assertInventory("new_identity")
        } finally { release.countDown() }
        assertEquals(RefreshOutcome.Superseded, await(old))
        assertInventory("new_identity")
    }

    fun testAnotherSourceCanRefreshAndSurvivesForgetWhileTheFirstIsHarvesting() {
        val other = realDuckdbDataSource(project, testRootDisposable)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sequence = AtomicInteger()
        harvester.answer = { rows ->
            if (sequence.incrementAndGet() == 1) marked(rows, "first_source").also {
                entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
            } else marked(rows, "other_source")
        }
        val old = refresh()
        try {
            captured(entered)
            assertInstanceOf(await(refresh(other)), RefreshOutcome.Refreshed::class.java)
            service.forget(source.uniqueId)
            assertInventory("other_source", other)
        } finally { release.countDown() }
        assertEquals(RefreshOutcome.Superseded, await(old))
        assertInventory("other_source", other)
    }

    fun testForgetInvalidatesABackgroundRefreshRequestedButNotYetStarted() {
        val pending = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        service = LiveService(CoroutineScope(errors.newScope(testRootDisposable).coroutineContext + dispatcher), service.profile, directory)
        service.refreshInBackground(project, source)
        service.forget(source.uniqueId)
        PlatformTestUtil.waitWithEventsDispatching("queued refresh is discarded", {
            while (true) (pending.poll() ?: break).run()
            service.refreshed.isNotEmpty()
        }, 20)
        assertEquals(RefreshOutcome.Superseded, service.refreshed.single())
        assertEquals("no helper harvest after forget", 0, harvester.harvests.get())
        assertNull(service.selection(source).inventory)
    }
}
