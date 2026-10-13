package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [sql] over this connection and returns at most [maxRows] rows, each by lower-case column label in
 * column order: strings, and lists of strings for [listColumns]. [onStatement] sees the statement while
 * it runs, so a caller can cancel it; a statement created once [isCancelled] holds is closed, never run.
 * Failures throw.
 */
internal fun DatabaseConnectionCore.queryRows(
    sql: String,
    maxRows: Int,
    queryTimeoutSeconds: Int = 0,
    listColumns: Set<String> = emptySet(),
    onStatement: (RemoteStatement?) -> Unit = {},
    isCancelled: () -> Boolean = { false },
): List<Map<String, Any?>> {
    val statement = JdbcNativeUtil.computeRemote { remoteConnection.createStatement() } ?: error("No statement")
    onStatement(statement)
    try {
        if (isCancelled()) throw CancellationException("Cancelled before $sql")
        if (queryTimeoutSeconds > 0) JdbcNativeUtil.performSafe { statement.setQueryTimeout(queryTimeoutSeconds) }
        val result = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: error("No result for $sql")
        try {
            val meta = result.metaData
            val labels = (1..meta.columnCount).map { meta.getColumnLabel(it).orEmpty().lowercase() }
            return buildList {
                while (size < maxRows && result.next()) {
                    add(LinkedHashMap<String, Any?>().apply {
                        labels.forEachIndexed { index, label ->
                            val value: Any? = if (label in listColumns) {
                                (result.getArray(index + 1)?.array as? Array<*>)?.map { it?.toString() }
                            } else {
                                result.getString(index + 1)
                            }
                            putIfAbsent(label, value)
                        }
                    })
                }
            }
        } finally {
            JdbcNativeUtil.performSafe { result.close() }
        }
    } finally {
        onStatement(null)
        JdbcNativeUtil.closeRemoteStatementSafe(statement)
    }
}

/** Runs [sql], which returns no rows (such as `USE`), over this connection; [onStatement] and [isCancelled] as for [queryRows]. Failures throw. */
internal fun DatabaseConnectionCore.executeStatement(
    sql: String,
    queryTimeoutSeconds: Int = 0,
    onStatement: (RemoteStatement?) -> Unit = {},
    isCancelled: () -> Boolean = { false },
) {
    val statement = JdbcNativeUtil.computeRemote { remoteConnection.createStatement() } ?: error("No statement")
    onStatement(statement)
    try {
        if (isCancelled()) throw CancellationException("Cancelled before $sql")
        if (queryTimeoutSeconds > 0) JdbcNativeUtil.performSafe { statement.setQueryTimeout(queryTimeoutSeconds) }
        JdbcNativeUtil.computeRemote { statement.execute(sql) }
    } finally {
        onStatement(null)
        JdbcNativeUtil.closeRemoteStatementSafe(statement)
    }
}
