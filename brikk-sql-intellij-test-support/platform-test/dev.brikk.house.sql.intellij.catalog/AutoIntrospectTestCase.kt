package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
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
import com.intellij.openapi.project.Project
import com.intellij.database.util.TreePattern
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope

/**
 * A PostgreSQL data source whose model lists the catalogs `hive` and `memory` without children and
 * `loaded` with the schema `web` (in the scope, so the model shows its tables), whose table `orders` has no columns. The real platform sync manager runs every refresh; only the database
 * boundary is replaced ([HeldIntrospections]).
 */
abstract class AutoIntrospectTestCase : BasePlatformTestCase() {
    class Profile(
        override val autoIntrospect: Boolean = true,
        override val usesCurrentNamespace: Boolean = false,
        override val maxDeepenDepth: Int = 2,
        override val outOfScopeReferences: Boolean = false,
        override val dbms: Dbms = Dbms.POSTGRES,
    ) : CatalogProfile {
        override val productLabel = "Fixture"
        override val quoteChars = "\""
        override val levels = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)
    }

    class Service(
        project: Project,
        scope: CoroutineScope,
        override val profile: CatalogProfile,
        private val time: TestTimeSource,
        private val listing: ((LocalDataSource) -> Boolean)? = null,
    ) : AutoIntrospectService(project, scope) {
        override fun createEngine() = AutoIntrospectEngine(1.minutes, time)

        override suspend fun listNamespaces(dataSource: LocalDataSource): Boolean = listing?.invoke(dataSource) ?: super.listNamespaces(dataSource)
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
    private lateinit var backgroundErrors: BackgroundErrors
    protected lateinit var serviceScope: CoroutineScope
    protected lateinit var refreshes: HeldIntrospections
    protected lateinit var service: Service
    protected lateinit var source: GatedDataSource
    protected lateinit var hive: PgDatabase
    protected lateinit var memory: PgDatabase
    protected lateinit var orders: PgLocalTable
    protected lateinit var quoted: PgDatabase

    override fun setUp() {
        super.setUp()
        backgroundErrors = BackgroundErrors(testRootDisposable)
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
        refreshes = HeldIntrospections(testRootDisposable)
    }

    override fun tearDown() {
        RunAll(
            { refreshes.finishAll() },
            { backgroundErrors.awaitScopes() },
            { backgroundErrors.assertNone() },
            { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
            { DbPsiFacade.getInstance(project).clearCaches() },
            { super.tearDown() },
        ).run()
    }

    protected fun useService(profile: CatalogProfile, listing: ((LocalDataSource) -> Boolean)? = null) {
        val scope = backgroundErrors.newScope(testRootDisposable)
        serviceScope = scope
        service = Service(project, scope, profile, time, listing)
        project.registerOrReplaceServiceInstance(Service::class.java, service, testRootDisposable)
    }

    protected fun levels() = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)

    protected fun pending(): String? = service.pending(myFixture.file.virtualFile)

    /** The test runs on the EDT, which the refresh needs too, so it dispatches events while it waits. */
    protected fun nextRefresh(): HeldIntrospections.Refresh = refreshes.next()

    protected fun assertNoRefresh() = refreshes.assertNone()

    protected fun awaitIdle() {
        PlatformTestUtil.waitWithEventsDispatching("introspection finishes", { pending() == null && refreshes.isEmpty }, 10)
    }

    protected fun scopeNode(path: List<String>) = path.withIndex().fold(source.introspectionScope.root) { node, (level, name) ->
        node?.getGroup(levels()[level])?.children?.firstOrNull { it.naming.matches(ObjectName.plain(name), Casing.EXACT) }
    }

    protected fun selects(path: List<String>) = scopeNode(path) != null
}
