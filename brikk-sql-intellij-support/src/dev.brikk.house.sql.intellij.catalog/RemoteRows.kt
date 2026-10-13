package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil

/**
 * Runs [sql] over this connection and returns at most [maxRows] rows, each by lower-case column label in
 * column order. [onStatement] sees the statement while it runs, so a caller can cancel it. Failures throw.
 */
internal fun DatabaseConnectionCore.queryRows(
    sql: String,
    maxRows: Int,
    queryTimeoutSeconds: Int = 0,
    onStatement: (RemoteStatement?) -> Unit = {},
): List<Map<String, String?>> {
    val statement = JdbcNativeUtil.computeRemote { remoteConnection.createStatement() } ?: error("No statement")
    onStatement(statement)
    try {
        if (queryTimeoutSeconds > 0) JdbcNativeUtil.performSafe { statement.setQueryTimeout(queryTimeoutSeconds) }
        val result = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: error("No result for $sql")
        try {
            val meta = result.metaData
            val labels = (1..meta.columnCount).map { meta.getColumnLabel(it).orEmpty().lowercase() }
            return buildList {
                while (size < maxRows && result.next()) {
                    add(LinkedHashMap<String, String?>().apply { labels.forEachIndexed { index, label -> putIfAbsent(label, result.getString(index + 1)) } })
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

/** Runs [sql], which returns no rows (such as `USE`), over this connection; [onStatement] as for [queryRows]. Failures throw. */
internal fun DatabaseConnectionCore.executeStatement(sql: String, queryTimeoutSeconds: Int = 0, onStatement: (RemoteStatement?) -> Unit = {}) {
    val statement = JdbcNativeUtil.computeRemote { remoteConnection.createStatement() } ?: error("No statement")
    onStatement(statement)
    try {
        if (queryTimeoutSeconds > 0) JdbcNativeUtil.performSafe { statement.setQueryTimeout(queryTimeoutSeconds) }
        JdbcNativeUtil.computeRemote { statement.execute(sql) }
    } finally {
        onStatement(null)
        JdbcNativeUtil.closeRemoteStatementSafe(statement)
    }
}
