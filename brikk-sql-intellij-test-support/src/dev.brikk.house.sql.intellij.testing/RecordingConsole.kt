package dev.brikk.house.sql.intellij.testing

import com.intellij.database.DataBus
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.DatabaseSessionClient
import com.intellij.database.console.client.DatabaseSessionClientWithFile
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataConsumer
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.GridDataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real [JdbcConsole] for [file] whose session records submitted requests instead of executing them.
 * Every request becomes finished session work, as the IDE 263 gutter renderer expects.
 * Session methods the recording does not model fail the call and [close].
 */
public class RecordingConsole private constructor(
    private val project: Project,
    private val file: VirtualFile,
    private val driverId: String,
    shared: LocalDataSource?,
) : AutoCloseable {
    /** A console on a new data source using [driverId], removed again by [close]. */
    public constructor(project: Project, file: VirtualFile, driverId: String) : this(project, file, driverId, null)

    /** A console on [dataSource], which stays when the fixture closes; another fixture may share it. */
    public constructor(project: Project, file: VirtualFile, dataSource: LocalDataSource) :
        this(project, file, dataSource.databaseDriver?.id ?: error("The data source has no driver"), dataSource)

    private val recorded = CopyOnWriteArrayList<DataRequest>()
    private val dataSources = CopyOnWriteArrayList<LocalDataSource>()
    private val unsupportedCalls = CopyOnWriteArrayList<String>()

    /** Requests submitted through any of this fixture's sessions, in submission order. */
    public val requests: List<DataRequest> get() = recorded.toList()

    /** Runs after a submission is recorded. Throw from it to fail or cancel that submission. */
    public var onSubmit: (DataRequest) -> Unit = {}

    /** Runs after an auditor is registered on a session's bus. Throw from it to fail or cancel the registration. */
    public var onAuditorAdded: (DataAuditor) -> Unit = {}

    /** Runs with the method name before every call into a session. Throw from it to refuse that access. */
    public var beforeSessionCall: (String) -> Unit = {}

    public var console: JdbcConsole = try {
        // A data source added just before may not be in the PSI view yet; console validity reads it.
        open(shared?.also { DbPsiFacade.getInstance(project).clearCaches() } ?: newDataSource())
    } catch (failure: Throwable) {
        releaseDataSources()
        throw failure
    }
        private set

    /** Replaces [console] with a console on a new session for the same file and data source, as a session switch does. */
    public fun replaceSession(): JdbcConsole = replace(console.dataSource)

    /** Replaces [console] with a console on a new session and a new data source using [driverId]. */
    public fun replaceDataSource(driverId: String = this.driverId): JdbcConsole = replace(newDataSource(driverId))

    override fun close() {
        onSubmit = {}
        onAuditorAdded = {}
        beforeSessionCall = {}
        try {
            dispose(console)
        } finally {
            releaseDataSources()
        }
        if (unsupportedCalls.isNotEmpty()) throw AssertionError("Recording session calls are not modeled: $unsupportedCalls")
    }

    private fun replace(dataSource: LocalDataSource): JdbcConsole {
        dispose(console)
        console = open(dataSource)
        return console
    }

    private fun newDataSource(driverId: String = this.driverId): LocalDataSource = LocalDataSource().apply {
        name = "Recording console ${UUID.randomUUID()}"
        databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driverId)) { "Unknown driver: $driverId" }
        // Not a reachable endpoint, so an accidental real connection fails.
        url = "jdbc:offline:recording-console"
        isAutoSynchronize = false
        isKeepAlive = false
    }.also {
        LocalDataSourceManager.getInstance(project).addDataSource(it)
        dataSources += it
        // The manager invalidates its PSI view later; console validity reads it now.
        DbPsiFacade.getInstance(project).clearCaches()
    }

    private fun releaseDataSources() {
        val manager = LocalDataSourceManager.getInstance(project)
        dataSources.forEach(manager::removeDataSource)
        DbPsiFacade.getInstance(project).clearCaches()
    }

    private fun open(dataSource: LocalDataSource): JdbcConsole =
        JdbcConsole.newConsole(project).forFile(file).fromDataSource(dataSource)
            .useSession(RecordingSession(dataSource).session).build()
            .also { check(it.isValid) { "The recording console must be valid" } }

    private fun dispose(console: JdbcConsole) {
        // A real session destroys its client views; a stale view would invalidate the file's next console.
        DatabaseSessionViewService.getInstance().destroyView(console)
        Disposer.dispose(console)
    }

    private inner class RecordingSession(private val target: LocalDataSource) {
        private val work = CopyOnWriteArrayList<DatabaseSession.State.Work>()
        private val clients = CopyOnWriteArrayList<DatabaseSessionClient>()
        private val auditors = CopyOnWriteArrayList<DataAuditor>()
        private val userData = UserDataHolderBase()

        private val producer = object : DataProducer {
            override fun processRequest(request: GridDataRequest) {
                val submitted = request as DataRequest
                recorded += submitted
                work += RecordedWork(submitted)
                auditors.forEach { it.jobSubmitted(submitted, this) }
                onSubmit(submitted)
            }
        }

        private val bus = object : DataBus.Consuming {
            override fun filterFor(owner: DataRequest.Owner): DataBus.Consuming = this
            override fun getDataProducer(): DataProducer = producer
            // Recorded requests produce no results.
            override fun addConsumer(consumer: DataConsumer) {}
            override fun addAuditor(auditor: DataAuditor) {
                auditors += auditor
                onAuditorAdded(auditor)
            }
        }

        private val state = recording<DatabaseSession.State>("Recording session state") { method, args ->
            when (method.name) {
                "isIdle", "isFinalized" -> true
                "isEmpty" -> work.isEmpty()
                "isCancelled" -> false
                "getStartTime", "getTimeSpentMs" -> 0L
                "getWork" -> work.toList()
                "getWorkFor" -> work.lastOrNull { it.request === args[0] || it.request.owner === args[0] }
                "getMostRecentWork" -> work.lastOrNull { recent -> (args[0] as List<*>).any { it === recent.request } }
                else -> unsupported(method)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val session: DatabaseSession = recording("Recording session", { beforeSessionCall(it) }) { method, args ->
            when (method.name) {
                "getTitle", "getDisplayName" -> "Recording session"
                "getConnectionPoint", "getTarget" -> target
                "getState" -> state
                "getProject" -> project
                "getMessageBus" -> bus
                "getClients" -> clients.toTypedArray()
                "getClientsWithFile" -> clients.filterIsInstance<DatabaseSessionClientWithFile>().toTypedArray()
                "attach" -> { clients += args[0] as DatabaseSessionClient; null }
                "detach" -> { clients -= args[0] as DatabaseSessionClient; null }
                "isValid", "isIdle" -> true
                "isConnected", "isInternal", "isService", "isCancelled" -> false
                "getCurrentTx" -> DataRequest.AUTO_COMMIT
                "getUserData" -> userData.getUserData(args[0] as Key<Any>)
                "putUserData" -> { userData.putUserData(args[0] as Key<Any>, args[1]); null }
                else -> unsupported(method)
            }
        }
    }

    private fun unsupported(method: Method): Nothing {
        unsupportedCalls += method.toString()
        throw UnsupportedOperationException("Recording session does not model $method")
    }
}

private class RecordedWork(override val request: DataRequest) : DatabaseSession.State.Work {
    override val timeSpentMs: Long get() = 0
    override val state: DatabaseSession.State.WorkState get() = DatabaseSession.State.WorkState.FINISHED
    override val status: DatabaseSession.State.WorkStatus get() = RecordedStatus
}

private object RecordedStatus : DatabaseSession.State.WorkStatus {
    override val type: DatabaseSession.State.WorkStatusType get() = DatabaseSession.State.WorkStatusType.UNKNOWN
    override val description: String get() = "Recorded, not executed"
    override val errorNavigator: DataRequest.CoupledWithEditor.ErrorNavigator? get() = null
}

private inline fun <reified T : Any> recording(
    description: String,
    crossinline beforeCall: (String) -> Unit = {},
    crossinline answer: (Method, Array<out Any?>) -> Any?,
): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), InvocationHandler { proxy, method, args ->
        beforeCall(method.name)
        val arguments = args.orEmpty()
        when (method.name) {
            "equals" -> proxy === arguments[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> description
            else -> answer(method, arguments)
        }
    }) as T
