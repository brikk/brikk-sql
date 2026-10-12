package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.postgres.model.PgDatabase
import com.intellij.database.dialects.postgres.model.PgLocalTable
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.Casing
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.database.util.TreePattern
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A PostgreSQL data source whose model lists the catalogs `hive` and `memory` without children and
 * `loaded` with the schema `web` (in the scope, so the model shows its tables), whose table `orders` has no columns. The real platform sync manager runs every refresh; only the database
 * boundary is replaced, by an executor that waits for the test and then plays the introspection's
 * effect on the model.
 */
abstract class AutoIntrospectTestCase : BasePlatformTestCase() {
    class Profile(
        override val autoIntrospect: Boolean = true,
        override val usesCurrentNamespace: Boolean = false,
        override val maxDeepenDepth: Int = 2,
    ) : CatalogProfile {
        override val dbms: Dbms = Dbms.POSTGRES
        override val productLabel = "Fixture"
        override val quoteChars = "\""
        override val levels = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)
    }

    class Service(project: Project, scope: CoroutineScope, override val profile: CatalogProfile, private val time: TestTimeSource) :
        AutoIntrospectService(project, scope) {
        override fun createEngine() = AutoIntrospectEngine(1.minutes, time)
    }

    /** A refresh the executor received; [finish] lets it end, running the effect, which must be idempotent, first. */
    class Refresh(val context: LoaderContext) {
        val done = CompletableFuture<() -> Unit>()
        fun finish(effect: () -> Unit = {}) = done.complete(effect)
    }

    /** Reads of its introspection scope can be held until a second read arrives, to expose unserialized updates. */
    class GatedDataSource : LocalDataSource() {
        @Volatile var gate: CountDownLatch? = null

        override fun getIntrospectionScope(): TreePattern {
            gate?.let { latch ->
                latch.countDown()
                latch.await(1, TimeUnit.SECONDS)
            }
            return super.getIntrospectionScope()
        }
    }

    protected val time = TestTimeSource()
    /** Throwables that escaped the service's coroutines, and errors logged on any thread. */
    private val backgroundErrors = CopyOnWriteArrayList<Throwable>()
    protected lateinit var serviceScope: CoroutineScope
    protected val refreshes = LinkedBlockingQueue<Refresh>()
    protected lateinit var service: Service
    protected lateinit var source: GatedDataSource
    protected lateinit var hive: PgDatabase
    protected lateinit var memory: PgDatabase
    protected lateinit var orders: PgLocalTable
    protected lateinit var quoted: PgDatabase

    override fun setUp() {
        super.setUp()
        val errors = object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                backgroundErrors += t ?: AssertionError(message)
                return super.processError(category, message, details, t)
            }
        }
        val logging = LoggedErrorProcessor.executeWith(errors)
        Disposer.register(testRootDisposable) { logging.finish() }
        useService(Profile())
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        val databases = (model.root as PgRoot).databases
        hive = databases.createOrGet("hive")
        memory = databases.createOrGet("memory")
        orders = databases.createOrGet("loaded").schemas.createOrGet("web").tables.createOrGet("orders")
        quoted = databases.createOrGet("q\"t")
        source = GatedDataSource().apply {
            name = "auto-introspect ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("postgresql"))
            url = "jdbc:offline:auto-introspect"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
            setIntrospectionScope(CatalogScopes.widen(CatalogScopes.select(levels(), listOf("memory")), CatalogScopes.select(levels(), listOf("loaded", "web"))))
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        // A refresh may reach the database more than once; each of its calls waits for the same answer.
        val byContext = ConcurrentHashMap<LoaderContext, Refresh>()
        DataSourceSyncManager.withDefaultExecutor(testRootDisposable) { task, _, _, _ ->
            val refresh = byContext.computeIfAbsent(task.context) { Refresh(it).also(refreshes::put) }
            refresh.done.get(30, TimeUnit.SECONDS)()
        }
    }

    override fun tearDown() {
        RunAll(
            { refreshes.forEach { it.finish() } },
            { PlatformTestUtil.waitWithEventsDispatching("the service's coroutines end", { serviceScope.coroutineContext.job.children.none(Job::isActive) }, 10) },
            { assertEmpty("background errors", backgroundErrors) },
            { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
            { DbPsiFacade.getInstance(project).clearCaches() },
            { super.tearDown() },
        ).run()
    }

    protected fun useService(profile: CatalogProfile) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> backgroundErrors += e })
        Disposer.register(testRootDisposable) { scope.cancel() }
        serviceScope = scope
        service = Service(project, scope, profile, time)
        project.registerOrReplaceServiceInstance(Service::class.java, service, testRootDisposable)
    }

    protected fun levels() = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)

    protected fun pending(): String? = service.pending(myFixture.file.virtualFile)

    /** The test runs on the EDT, which the refresh needs too, so it dispatches events while it waits. */
    protected fun nextRefresh(): Refresh {
        PlatformTestUtil.waitWithEventsDispatching("a refresh reaches the database", { refreshes.isNotEmpty() }, 10)
        return refreshes.remove()
    }

    protected fun assertNoRefresh() {
        assertNull(refreshes.poll(200, TimeUnit.MILLISECONDS))
    }

    protected fun awaitIdle() {
        PlatformTestUtil.waitWithEventsDispatching("introspection finishes", { pending() == null && refreshes.isEmpty() }, 10)
    }

    protected fun scopeNode(path: List<String>) = path.withIndex().fold(source.introspectionScope.root) { node, (level, name) ->
        node?.getGroup(levels()[level])?.children?.firstOrNull { it.naming.matches(ObjectName.plain(name), Casing.EXACT) }
    }

    protected fun selects(path: List<String>) = scopeNode(path) != null
}
