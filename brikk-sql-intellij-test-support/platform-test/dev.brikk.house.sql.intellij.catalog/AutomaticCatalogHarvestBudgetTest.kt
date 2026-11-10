@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // The budget seam is internal, not plugin API.

package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** Real console requests share the blocking harvest path; only the automatic call gets the cap. */
class AutomaticCatalogHarvestBudgetTest : BasePlatformTestCase() {
    private class Service(scope: CoroutineScope, override val profile: LiveCatalogProfile, cap: Duration) : LiveCatalogService(scope, cap) {
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<RefreshOutcome>()
        override fun persistenceDirectory() = null
        override fun onRefreshed(project: Project, dataSource: LocalDataSource, outcome: RefreshOutcome, reason: RefreshReason) {
            outcomes += outcome
        }
    }

    private lateinit var errors: BackgroundErrors
    private lateinit var source: LocalDataSource
    private lateinit var session: DatabaseSession
    private lateinit var console: JdbcConsole
    private lateinit var engine: JdbcEngine
    private lateinit var service: Service
    private val harvester = CountingHarvester { Harvest(inventory(liveFunction("previous"))) }
    private val releases = ArrayList<CountDownLatch>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        source = realDuckdbDataSource(project, testRootDisposable)
        val file = myFixture.addFileToProject("budget-${UUID.randomUUID()}.sql", "SELECT 1")
        session = DatabaseSessionManager.openSession(project, source, "harvest budget ${UUID.randomUUID()}")
        console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(source).useSession(session).build()
        val request = FinishedQuery(console, "SELECT 1")
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("console connects", { request.promise.isDone }, 30)
        engine = DatabaseConnectionManager.getInstance().activeConnections.single { it.connectionPoint.dataSource === source }.requestor as JdbcEngine
    }

    override fun tearDown() = RunAll(
        { releases.forEach(CountDownLatch::countDown) },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { DatabaseSessionViewService.getInstance().destroyView(console); Disposer.dispose(console); Disposer.dispose(session) },
        { super.tearDown() },
    ).run()

    private fun prepare(deadline: Duration, cap: Duration = AUTOMATIC_HARVEST_CAP) {
        service = Service(errors.newScope(testRootDisposable), LiveProfile(harvester, HarvestTrigger.ON_REFRESH, deadline = deadline, dbms = source.dbms), cap)
        val connection = DatabaseConnectionManager.getInstance().activeConnections.single { it.requestor === engine }
        runBlocking { (service as LiveCatalogService).connected(connection) }
        assertInstanceOf(await(manual()), RefreshOutcome.Refreshed::class.java)
    }

    private fun manual(): CompletableFuture<RefreshOutcome> = CompletableFuture.supplyAsync { runBlocking { service.refresh(project, session) } }

    private fun automatic(): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                service.inventoryChanged(project, session, context.producer, sessionFacts = true, notify = true)
                result.complete(Unit)
            }
        }
        request.promise.onError { result.completeExceptionally(it) }
        session.messageBus.dataProducer.processRequest(request)
        return result
    }

    private fun <T> await(result: CompletableFuture<T>): T {
        PlatformTestUtil.waitWithEventsDispatching("harvest caller finishes", { result.isDone }, 15)
        return result.get(1, TimeUnit.SECONDS)
    }

    private fun hold(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        harvester.answer = {
            entered.countDown()
            release.await()
            Harvest(inventory(liveFunction("replacement")))
        }
        return entered
    }

    fun testAutomaticHarvestWithALongProfileDeadlineTimesOutAtFiveSecondsAndKeepsTheCatalog() {
        assertEquals(5.seconds, AUTOMATIC_HARVEST_CAP)
        prepare(30.seconds)
        val before = service.selection(source, session)
        val entered = hold()
        val started = TimeSource.Monotonic.markNow()
        val result = automatic()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        await(result)
        val waited = started.elapsedNow()
        assertTrue("the harvest gets its five second budget: $waited", waited >= 5.seconds)
        assertTrue("the automatic cap applies, not the 30 second deadline: $waited", waited < 10.seconds)
        assertEquals(RefreshOutcome.HarvestFailed(HarvestOutcome.TimedOut), service.outcomes.single())
        assertSame(before.inventory, service.selection(source, session).inventory)
        assertSame(before.session, service.selection(source, session).session)
    }

    fun testManualSessionRefreshOutlivesTheAutomaticCapOnTheSameProfile() {
        prepare(2.seconds, 100.milliseconds)
        var entered = hold()
        val automatic = automatic()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        await(automatic)
        assertEquals(RefreshOutcome.HarvestFailed(HarvestOutcome.TimedOut), service.outcomes.single())
        entered = hold()
        val finished = CountDownLatch(1)
        val manual = manual().whenComplete { _, _ -> finished.countDown() }
        try {
            PlatformTestUtil.waitWithEventsDispatching("manual harvest starts", { entered.count == 0L }, 5)
            assertFalse("the manual refresh is not cut off at 100 ms", finished.await(300, TimeUnit.MILLISECONDS))
        } finally { releases.last().countDown() }
        assertInstanceOf(await(manual), RefreshOutcome.Refreshed::class.java)
        assertEquals(listOf("replacement"), service.selection(source).inventory!!.functions.map { it.def.name })
    }

    fun testAProfileDeadlineBelowTheAutomaticCapStillTimesOutAtItsOwnDeadline() {
        prepare(100.milliseconds, 5.seconds)
        val before = service.selection(source, session)
        val entered = hold()
        val started = TimeSource.Monotonic.markNow()
        val result = automatic()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        await(result)
        val waited = started.elapsedNow()
        assertTrue("the harvest gets its profile budget: $waited", waited >= 100.milliseconds)
        assertTrue("the shorter profile deadline applies: $waited", waited < 3.seconds)
        assertEquals(RefreshOutcome.HarvestFailed(HarvestOutcome.TimedOut), service.outcomes.single())
        assertSame(before.inventory, service.selection(source, session).inventory)
    }

    fun testAnAutomaticTimeoutDoesNotFenceOutAnOlderSuccessfulHarvest() {
        prepare(5.seconds, 100.milliseconds)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        val sequence = AtomicInteger()
        harvester.answer = {
            if (sequence.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                Harvest(inventory(liveFunction("older_success")))
            } else {
                CountDownLatch(1).await()
                error("the timed out harvest cannot answer")
            }
        }
        val older = CompletableFuture.supplyAsync { runBlocking { service.refresh(project, source) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("older helper harvest starts", { entered.count == 0L }, 10)
            await(automatic())
            assertEquals(RefreshOutcome.HarvestFailed(HarvestOutcome.TimedOut), service.outcomes.single())
            assertEquals(listOf("previous"), service.selection(source).inventory!!.functions.map { it.def.name })
        } finally { release.countDown() }
        assertInstanceOf(await(older), RefreshOutcome.Refreshed::class.java)
        assertEquals(listOf("older_success"), service.selection(source).inventory!!.functions.map { it.def.name })
    }
}
