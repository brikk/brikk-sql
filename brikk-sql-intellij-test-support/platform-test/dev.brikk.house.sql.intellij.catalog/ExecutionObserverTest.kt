package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope

/** A request as a console submits it, finished by [producer], its session's engine. */
class FinishedQuery(owner: JdbcConsole, sql: String) : DataRequest.QueryRequest(owner, sql, DataRequest.newConstraints(), null)

fun finished(console: JdbcConsole, producer: DataProducer, sql: String) =
    DataRequest.Context(producer, DataRequest.newOutputHandler(), FinishedQuery(console, sql), DataRequest.AUTO_COMMIT)

/** Namespace statements: reloads through the auto-introspection fixture's real sync manager. */
class ExecutionObserverTest : AutoIntrospectTestCase() {
    class Profile(
        override val dbms: Dbms = Dbms.POSTGRES,
        override val liveCatalog: Class<out LiveCatalogService>? = null,
        override val autoIntrospect: Class<out AutoIntrospectService>? = Service::class.java,
        override val namespaceRefreshDelay: Duration = 50.milliseconds,
        rules: List<StatementRule> = StatementRules.TRINO,
    ) : ExecutionObserverProfile {
        override val statements = StatementHeadDetector(rules)
    }

    class Observer(project: Project, scope: CoroutineScope, override val profile: ExecutionObserverProfile) : ExecutionObserver(project, scope)

    private lateinit var recording: RecordingConsole
    private lateinit var observer: Observer
    private val engine = LiveCatalogServiceTest.Engine()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("observe-${UUID.randomUUID()}.sql", "SELECT 1")
        recording = RecordingConsole(project, myFixture.file.virtualFile, source)
        observer = Observer(project, serviceScope, Profile())
    }

    override fun tearDown() {
        try {
            recording.close()
        } finally {
            super.tearDown()
        }
    }

    private fun run(sql: String) = observer.requestFinished(finished(recording.console, engine, sql))

    fun testCreatingASchemaReloadsItsLoadedCatalogWithoutWideningTheScope() {
        val scope = source.introspectionScope
        run("CREATE SCHEMA loaded.extra")
        val refresh = nextRefresh()
        assertEquals("only the catalog is reloaded", 1, refresh.context.tasks.size)
        refresh.finish()
        awaitIdle()
        assertSame(scope, source.introspectionScope)
        assertNull("not a claimed request", service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf("loaded"))))
        run("CREATE SCHEMA loaded.again")
        nextRefresh().finish()
        awaitIdle()
    }

    fun testCreatingACatalogReloadsTheTopLevel() {
        run("CREATE CATALOG fresh USING memory")
        val refresh = nextRefresh()
        assertEquals(1, refresh.context.tasks.size)
        refresh.finish()
        awaitIdle()
    }

    fun testANamespaceNeverLoadedIsNotReloaded() {
        run("CREATE SCHEMA hive.web")
        run("DROP SCHEMA nowhere.web")
        assertNoRefresh()
    }

    fun testAnUnqualifiedSchemaBelongsToTheConsolesCatalog() {
        run("CREATE SCHEMA extra")
        run("CREATE SCHEMA loaded")
        assertNoRefresh()
        recording.console.setSearchPath(SearchPath.of(ObjectPath.create("loaded", ObjectKind.DATABASE)))
        run("CREATE SCHEMA extra")
        nextRefresh().finish()
        awaitIdle()
    }

    fun testStatementsChangingOneNamespaceTogetherReloadItOnce() {
        run("CREATE SCHEMA loaded.a; CREATE SCHEMA loaded.b")
        run("DROP SCHEMA loaded.c")
        nextRefresh().finish()
        awaitIdle()
        assertNoRefresh()
    }

    fun testOtherDbmsAndOtherStatementsAreIgnored() {
        observer = Observer(project, serviceScope, Profile(dbms = Dbms.MYSQL))
        run("CREATE SCHEMA loaded.extra")
        observer = Observer(project, serviceScope, Profile())
        run("SELECT 'CREATE SCHEMA loaded.extra'")
        run("INSERT INTO loaded.web.orders VALUES (1)")
        assertNoRefresh()
    }
}
