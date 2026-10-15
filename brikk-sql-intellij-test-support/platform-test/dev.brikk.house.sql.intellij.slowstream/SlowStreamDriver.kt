package dev.brikk.house.sql.intellij.slowstream

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.logging.Logger

/**
 * DuckDB, except that a query naming [MARKER] streams [ROWS] rows, one per [ROW_MILLIS], as ClickHouse
 * JDBC 0.10 streams a running query's results: `cancel` stops the stream unless the statement is already
 * closed (then it does nothing), and closing a stream that was not cancelled first reads it to its end.
 * Every step of such a statement is appended, after the word following [MARKER] in its query, to the file
 * `-Dbrikk.slowstream.log` names. Cancelling the query tagged [HELD] returns only once the file named
 * as the log plus `.release` exists, and logs that it returns. Runs in the
 * IDE's remote JDBC process; `jdbc:brikk-slow:<file>` is DuckDB's `jdbc:duckdb:<file>`.
 */
class SlowStreamDriver : Driver {
    private val duckdb = Class.forName("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance() as Driver

    override fun connect(url: String, info: Properties): Connection? =
        if (acceptsURL(url)) proxy(duckdb.connect("jdbc:duckdb:" + url.removePrefix(PREFIX), info), Connection::class.java) { real, method, args ->
            val result = method.invoke(real, *args)
            if (method.name == "createStatement" && result is Statement) proxy(result, Statement::class.java, SlowStatement(result)) else result
        } else null

    override fun acceptsURL(url: String): Boolean = url.startsWith(PREFIX)
    override fun getPropertyInfo(url: String, info: Properties): Array<DriverPropertyInfo> = emptyArray()
    override fun getMajorVersion(): Int = 1
    override fun getMinorVersion(): Int = 0
    override fun jdbcCompliant(): Boolean = false
    override fun getParentLogger(): Logger = Logger.getLogger("brikk.slowstream")

    private class SlowStatement(private val real: Statement) : (Statement, Method, Array<Any?>) -> Any? {
        @Volatile private var closed = false
        @Volatile private var rows: SlowRows? = null
        @Volatile private var tag = ""

        private fun log(step: String) = Companion.log("$tag $step")

        override fun invoke(statement: Statement, method: Method, args: Array<Any?>): Any? = when (method.name) {
            "execute", "executeQuery" -> {
                val sql = args.firstOrNull() as? String
                if (sql == null || MARKER !in sql) method.invoke(real, *args) else {
                    tag = sql.substringAfter(MARKER).trim().takeWhile(Char::isLetterOrDigit)
                    val stream = SlowRows(statement, real.executeQuery("SELECT range AS n FROM range($ROWS)"), ::log)
                    rows = stream
                    log("execute")
                    if (method.name == "execute") true else stream.proxy
                }
            }
            "getResultSet" -> rows?.proxy ?: real.resultSet
            "getMoreResults" -> rows?.let { rows = null; it.close(); false } ?: method.invoke(real, *args)
            "getUpdateCount" -> if (rows != null) -1 else method.invoke(real, *args)
            "cancel" -> {
                if (closed) log("cancel ignored: closed") else rows?.let {
                    log("cancel")
                    it.cancelled = true
                    if (tag == HELD) {
                        awaitRelease()
                        log("cancel returns")
                    }
                }
                method.invoke(real, *args)
            }
            "close" -> {
                closed = true
                rows?.close()
                rows?.let { log("close") }
                method.invoke(real, *args)
            }
            "isClosed" -> closed || real.isClosed
            else -> method.invoke(real, *args)
        }
    }

    private class SlowRows(private val statement: Statement, private val real: ResultSet, private val log: (String) -> Unit) : (ResultSet, Method, Array<Any?>) -> Any? {
        @Volatile var cancelled = false
        @Volatile private var read = 0
        @Volatile private var open = true
        val proxy: ResultSet = proxy(real, ResultSet::class.java, this)

        override fun invoke(results: ResultSet, method: Method, args: Array<Any?>): Any? = when (method.name) {
            "next" -> {
                if (cancelled) throw SQLException("Query was cancelled")
                Thread.sleep(ROW_MILLIS)
                if (cancelled) throw SQLException("Query was cancelled")
                real.next().also { if (it) read++ else if (read == ROWS) log("end") }
            }
            "close" -> close()
            "getStatement" -> statement
            "isClosed" -> !open
            else -> method.invoke(real, *args)
        }

        fun close() {
            if (!open) return
            open = false
            if (!cancelled && read < ROWS) {
                log("drain")
                while (!cancelled && read < ROWS) {
                    Thread.sleep(ROW_MILLIS)
                    read++
                }
            }
            real.close()
        }
    }

    companion object {
        const val PREFIX: String = "jdbc:brikk-slow:"
        const val MARKER: String = "brikk_slow_stream"
        const val ROWS: Int = 200
        const val ROW_MILLIS: Long = 50
        const val LOG_PROPERTY: String = "brikk.slowstream.log"
        const val HELD: String = "held"

        private fun awaitRelease() {
            val release = Path.of(System.getProperty(LOG_PROPERTY) + ".release")
            val started = System.nanoTime()
            while (!Files.exists(release) && System.nanoTime() - started < 30_000_000_000) Thread.sleep(20)
        }

        private fun log(step: String) {
            val file = System.getProperty(LOG_PROPERTY) ?: return
            synchronized(this) {
                Files.writeString(Path.of(file), "$step\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> proxy(real: T, type: Class<T>, call: (T, Method, Array<Any?>) -> Any?): T {
            lateinit var self: T
            self = Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { _, method, args ->
                try {
                    call(if (call is SlowStatement || call is SlowRows) self else real, method, arrayOf(*(args ?: emptyArray())))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }) as T
            return self
        }
    }
}
