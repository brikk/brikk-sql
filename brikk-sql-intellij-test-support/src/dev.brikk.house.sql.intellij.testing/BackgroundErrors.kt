package dev.brikk.house.sql.intellij.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/**
 * Collects, until [until] ends, errors logged on any thread and throwables that escape the coroutines
 * of its [newScope]s, so a test of background work fails on them although the work's own thread
 * never reports to the test. Create it in `setUp`; in `tearDown`, end the work, then call
 * [awaitScopes] and [assertNone].
 */
public class BackgroundErrors(until: Disposable) {
    private val captured = CopyOnWriteArrayList<Throwable>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()

    init {
        val processor = object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                captured += t ?: AssertionError(message)
                return super.processError(category, message, details, t)
            }
        }
        val logging = LoggedErrorProcessor.executeWith(processor)
        Disposer.register(until) { logging.finish() }
    }

    /** Collects what reaches it, for scopes the test builds itself. */
    public val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> captured += e }

    /** What was collected so far, oldest first. */
    public val errors: List<Throwable> get() = captured.toList()

    /**
     * A supervisor scope on [Dispatchers.Default] whose escaping throwables are collected, for example
     * the scope of a project service built by the test. It is cancelled when [until] ends.
     */
    public fun newScope(until: Disposable): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).also { scope ->
            scopes += scope
            Disposer.register(until) { scope.cancel() }
        }

    /** Waits, dispatching events, until no coroutine of any [newScope] is active. */
    public fun awaitScopes(timeoutSeconds: Int = 10) {
        PlatformTestUtil.waitWithEventsDispatching("background coroutines end", {
            scopes.none { scope -> scope.coroutineContext.job.children.any(Job::isActive) }
        }, timeoutSeconds)
    }

    /** Fails with every collected throwable as a suppressed cause. */
    public fun assertNone() {
        val found = errors
        if (found.isEmpty()) return
        throw AssertionError("${found.size} background error(s); the first: ${found.first()}").apply { found.forEach(::addSuppressed) }
    }
}
