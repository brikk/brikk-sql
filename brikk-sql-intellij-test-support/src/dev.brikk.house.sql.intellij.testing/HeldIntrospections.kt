package dev.brikk.house.sql.intellij.testing

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Replaces, until [until] ends, only the database boundary of the real `DataSourceSyncManager`: every
 * introspection it runs waits for the test, which plays the introspection's effect on the model with
 * [Refresh.finish]. Scope handling, task preparation, queueing and model updates stay the platform's.
 * A refresh still waiting after [answerTimeoutSeconds] fails inside the sync (collect that with
 * [BackgroundErrors]). When [until] ends, waiting refreshes finish without an effect.
 *
 * Uses `DataSourceSyncManager.withDefaultExecutor`, a Database Tools implementation seam.
 */
public class HeldIntrospections(until: Disposable, private val answerTimeoutSeconds: Long = 30) {
    /** One introspection the sync manager started; [context] says what it refreshes. */
    public class Refresh internal constructor(public val context: LoaderContext) {
        internal val answer = CompletableFuture<() -> Unit>()

        /**
         * Lets the introspection end after running [effect] on the model. A refresh may reach the database
         * more than once, and each call runs [effect], so it must be idempotent. Only the first call counts.
         */
        public fun finish(effect: () -> Unit = {}) {
            answer.complete(effect)
        }
    }

    private val started = LinkedBlockingQueue<Refresh>()
    private val all = ConcurrentHashMap<LoaderContext, Refresh>()

    init {
        DataSourceSyncManager.withDefaultExecutor(until) { task, _, _, _ ->
            val refresh = all.computeIfAbsent(task.context) { Refresh(it).also(started::put) }
            refresh.answer.get(answerTimeoutSeconds, TimeUnit.SECONDS)()
        }
        Disposer.register(until) { finishAll() }
    }

    /** Whether no started refresh is waiting to be taken by [next]. */
    public val isEmpty: Boolean get() = started.isEmpty()

    /** The oldest refresh not yet taken, waiting for one while dispatching events (the sync needs the EDT too). */
    public fun next(timeoutSeconds: Int = 10): Refresh {
        PlatformTestUtil.waitWithEventsDispatching("an introspection reaches the database", { started.isNotEmpty() }, timeoutSeconds)
        return started.remove()
    }

    /** Fails if a refresh starts within [millis]. */
    public fun assertNone(millis: Long = 200) {
        val refresh = started.poll(millis, TimeUnit.MILLISECONDS)
        if (refresh != null) throw AssertionError("Unexpected introspection of ${refresh.context.dataSource.name}")
    }

    /** Finishes, without an effect, every refresh that has started, taken or not. */
    public fun finishAll() {
        all.values.forEach { it.finish() }
    }
}
