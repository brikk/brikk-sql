package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.HelperRequestor
import dev.brikk.house.sql.intellij.catalog.queryRows
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.verify.SqlVerifier
import java.lang.ref.WeakReference
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.util.Collections
import java.util.Objects
import java.util.Properties
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
    private val validators: MutableMap<LocalDataSource, NativeSqlValidator> = Collections.synchronizedMap(WeakHashMap())

    /** The validator for [file]'s data source; null when none serves it, so only local checks run. */
    public fun validator(file: PsiFile): NativeSqlValidator? {
        val dataSource = resolver.resolve(file).dataSource ?: return null
        val project = WeakReference(file.project)
        val source = WeakReference(dataSource)
        return validators.getOrPut(dataSource) {
            NativeSqlValidator.of(verifierFor { openHelper(project.get(), source.get()) }, timing)
        }
    }

    /** A stamp of what besides the text decides [file]'s verdicts: its data source and whether it is connected. */
    public fun modificationCount(file: PsiFile): Long {
        val dataSource = resolver.resolve(file).dataSource ?: return 0
        return Objects.hash(dataSource.uniqueId, dataSource.url, dataSource.username, isUserConnected(dataSource)).toLong()
    }

    private fun openHelper(project: Project?, dataSource: LocalDataSource?): DataSourceQueryConnection? {
        if (project == null || project.isDisposed || dataSource == null || !isUserConnected(dataSource)) return null
        val builder = DatabaseConnectionManager.getInstance().build(project, VerificationPoint(dataSource, connectionProperties(dataSource)))
            .setRequestor(HelperRequestor()).setAskPassword(false)
        // Verifiers open connections on their own workers, outside any progress indicator; an interrupt cancels.
        val ref = runBlocking { builder.create() } ?: return null
        return DataSourceQueryConnection(ref)
    }
}

/**
 * [point] as a verification connects to it: without its startup script (the data source's and its
 * driver's), which is for the user's sessions and would run again on every verification connection,
 * and with [properties] over its driver properties. Its data source stays [point]'s, unchanged.
 */
private class VerificationPoint(point: DatabaseConnectionPoint, private val properties: Map<String, String>) : DatabaseConnectionPoint.Adapter(point) {
    override fun getInitScript(): String? = null

    override fun getConnectionProperties(): Properties = Properties().also { shaped ->
        super.getConnectionProperties()?.let(shaped::putAll)
        shaped.putAll(properties)
    }
}

/** Whether the user (not a helper) has a connection to [dataSource] open. */
internal fun isUserConnected(dataSource: LocalDataSource): Boolean =
    DatabaseConnectionManager.getInstance().activeConnections.any { it.isUsers() && it.connectionPoint.dataSource == dataSource }

private fun DatabaseConnection.isUsers(): Boolean = requestor !is HelperRequestor

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
