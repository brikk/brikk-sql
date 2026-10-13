package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.HelperRequestor
import dev.brikk.house.sql.intellij.catalog.isHelperRequestor
import dev.brikk.house.sql.intellij.catalog.queryRows
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.verify.SqlVerifier
import java.lang.ref.WeakReference
import java.sql.SQLException
import java.util.Objects
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import org.jetbrains.annotations.ApiStatus

/**
 * Native verification against the data source that serves a file ([resolver]), for a
 * [NativeErrorProfile]: [validator] and [modificationCount] are its two methods.
 *
 * Each data source gets one validator over the verifier [verifierFor] makes, given a supplier of
 * [DataSourceQueryConnection]s. The supplier opens a separate, short-lived helper connection: never
 * the console's session (whose transaction, settings and namespace stay untouched), running no
 * startup script, with the driver properties [connectionProperties] adds, never asking for a password, and only while the user has the data source connected; otherwise it gives null,
 * which a verifier reports as unavailable. Introspection harvests skip helper connections.
 *
 * [modificationCount] changes with the file's data source, its URL and user, and whether it is
 * connected. Register a [VerificationConnectionListener] so that connecting and disconnecting
 * restart highlighting.
 */
@ApiStatus.Experimental
public class DataSourceVerification(
    private val resolver: DialectDataSourceResolver,
    private val timing: ValidationTiming = ValidationTiming.Lowering,
    /**
     * Driver properties a helper connection to a data source sets over the data source's own, such
     * as turning off a driver's response compression; the data source is not changed.
     */
    private val connectionProperties: (LocalDataSource) -> Map<String, String> = { emptyMap() },
    private val verifierFor: (connect: () -> DataSourceQueryConnection?) -> SqlVerifier,
) {
    /** Each data source's validator, with the login and address it was made for. */
    private val validators = WeakHashMap<LocalDataSource, Pair<Int, NativeSqlValidator>>()

    /**
     * The validator for [file]'s data source; null when none serves it, so only local checks run. A
     * changed URL, user or added properties gets a new one, since a verifier may remember what the
     * server allowed (such as query settings).
     */
    public fun validator(file: PsiFile): NativeSqlValidator? {
        val dataSource = resolver.resolve(file).dataSource ?: return null
        val properties = connectionProperties(dataSource)
        val login = Objects.hash(dataSource.url, dataSource.username, properties)
        val project = WeakReference(file.project)
        val source = WeakReference(dataSource)
        return synchronized(validators) {
            validators[dataSource]?.takeIf { it.first == login }?.second
                ?: NativeSqlValidator.of(verifierFor { openHelper(project.get(), source.get()) }, timing)
                    .also { validators[dataSource] = login to it }
        }
    }

    /** A stamp of what besides the text decides [file]'s verdicts: its data source and whether it is connected. */
    public fun modificationCount(file: PsiFile): Long {
        val dataSource = resolver.resolve(file).dataSource ?: return 0
        return Objects.hash(dataSource.uniqueId, dataSource.url, dataSource.username, isUserConnected(dataSource)).toLong()
    }

    private fun openHelper(project: Project?, dataSource: LocalDataSource?): DataSourceQueryConnection? {
        if (project == null || project.isDisposed || dataSource == null || !isUserConnected(dataSource)) return null
        val builder = DatabaseConnectionManager.getInstance().build(project, HelperConnectionPoint(dataSource, connectionProperties(dataSource)))
            .setRequestor(HelperRequestor()).setAskPassword(false)
        // Verifiers open connections on their own workers, usually outside any progress indicator; one there cancels.
        val ref = runBlockingMaybeCancellable { builder.create() } ?: return null
        return DataSourceQueryConnection(ref)
    }
}

/** Whether the user (not a helper) has a connection to [dataSource] open. */
internal fun isUserConnected(dataSource: LocalDataSource): Boolean =
    DatabaseConnectionManager.getInstance().activeConnections.any { it.isUsers() && it.connectionPoint.dataSource == dataSource }

// Another plugin's copy of this library opens helpers too: they are recognized by key, never by class.
private fun DatabaseConnection.isUsers(): Boolean = !isHelperRequestor(requestor)

/**
 * A helper connection a [DataSourceVerification] opened for one verification. A verifier runs its
 * query with [queryString], may [cancel] it from another thread, and closes it.
 */
@ApiStatus.Experimental
public class DataSourceQueryConnection internal constructor(private val ref: GuardedRef<DatabaseConnection>) : AutoCloseable {
    @Volatile
    private var running: RemoteStatement? = null

    /**
     * Runs [sql], a query of one column, and returns its first row's value as a string. Any failure
     * but cancellation is an `SQLException` carrying the driver's message.
     */
    public fun queryString(sql: String, timeoutSeconds: Int): String? = try {
        ref.get().queryRows(sql, 1, timeoutSeconds, onStatement = { running = it }).firstOrNull()?.values?.firstOrNull()?.toString()
    } catch (e: SQLException) {
        throw e
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException || e is InterruptedException) throw e
        throw SQLException(e.message ?: e.toString(), e)
    }

    /** Cancels the running query, if any. */
    public fun cancel() {
        running?.let { JdbcNativeUtil.performSafe { it.cancel() } }
    }

    override fun close() {
        ref.close()
    }
}

/**
 * Restarts highlighting in the projects that hold a data source when the user connects to it or
 * disconnects, so a [DataSourceVerification]'s changed [DataSourceVerification.modificationCount]
 * is seen. Helper connections are ignored. Register it as an application listener of
 * `com.intellij.database.dataSource.DatabaseConnectionManager$Listener`.
 */
@ApiStatus.Experimental
public open class VerificationConnectionListener : DatabaseConnectionManager.Listener {
    override fun connectionChanged(connection: DatabaseConnection, added: Boolean) {
        if (!connection.isUsers()) return
        val dataSource = connection.connectionPoint.dataSource
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed || dataSource !in LocalDataSourceManager.getInstance(project).dataSources) continue
            restart(project)
        }
    }

    /** Restarts [project]'s highlighting. */
    protected open fun restart(project: Project) {
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
