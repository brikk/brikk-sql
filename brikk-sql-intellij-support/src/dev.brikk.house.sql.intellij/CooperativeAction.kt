package dev.brikk.house.sql.intellij

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.ActionWrapperUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PerformWithDocumentsCommitted
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import org.jetbrains.annotations.ApiStatus

/** Claims an action in its plugin's own contexts and leaves every other one to the action it wraps. */
@ApiStatus.Experimental
public interface ActionInterceptor {
    /** For [event]: true shows and enables the action, false hides it, null (the default) leaves it to the wrapped action. */
    public fun update(event: AnActionEvent): Boolean? = null

    /**
     * Performs [event]. DECLINED runs the wrapped action instead. A claim returns HANDLED; it may run the
     * wrapped action itself through [delegate], before, after or instead of its own work (Doris cancels on
     * the client and kills the query on the server, and runs the stock cancel only as a fallback). Call
     * [delegate] at most once and only during this call, on its thread: it performs the wrapped action with
     * [event], whose data context is not valid afterwards.
     */
    public fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim
}

/**
 * Wraps [previous] (the stock action or another plugin's wrapper) with [interceptor]. Presentation,
 * shortcuts and update thread are the wrapped action's; once [detach]ed, it only passes through.
 */
@ApiStatus.Experimental
public open class CooperativeAction(private val previous: AnAction, private val interceptor: ActionInterceptor) :
    AnAction(), ActionWithDelegate<AnAction>, PerformWithDocumentsCommitted {
    init {
        copyFrom(previous)
    }

    @Volatile public var detached: Boolean = false
        private set

    public fun detach() {
        detached = true
    }

    override fun getDelegate(): AnAction = previous

    override fun getActionUpdateThread(): ActionUpdateThread = previous.actionUpdateThread

    override fun isDumbAware(): Boolean = previous.isDumbAware

    override fun isInInjectedContext(): Boolean = previous.isInInjectedContext

    override fun isPerformWithDocumentsCommitted(): Boolean = PerformWithDocumentsCommitted.isPerformWithDocumentsCommitted(previous)

    override fun update(e: AnActionEvent) {
        val claimed = if (detached) null else interceptor.update(e)
        if (claimed == null) previous.update(e) else e.presentation.isEnabledAndVisible = claimed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val delegate = { ActionWrapperUtil.actionPerformed(e, this, previous) }
        if (detached || interceptor.perform(e, delegate) == ExecutionClaim.DECLINED) delegate()
    }
}

/**
 * Wraps the actions [actionIds] with [interceptor], around whatever is registered under each id, so
 * plugins chain instead of replacing one another (`overrides="true"` would). Register a subclass as
 * `<dynamicActionConfigurationCustomizer>`. An id the IDE does not have is skipped. Unloading puts the
 * wrapped action back when this wrapper is still the registered one, and otherwise leaves the wrapper
 * in its chain, passing through.
 */
@ApiStatus.Experimental
public abstract class CooperativeActionConfiguration : DynamicActionConfigurationCustomizer {
    private val installed = LinkedHashMap<String, CooperativeAction>()

    protected abstract val actionIds: List<String>

    protected abstract val interceptor: ActionInterceptor

    override fun registerActions(actionManager: ActionManager) {
        if (installed.isNotEmpty()) return
        for (id in actionIds) {
            val previous = actionManager.getAction(id) ?: continue
            val action = CooperativeAction(previous, interceptor)
            actionManager.replaceAction(id, action)
            installed[id] = action
        }
    }

    override fun unregisterActions(actionManager: ActionManager) {
        installed.forEach { (id, action) ->
            action.detach()
            if (actionManager.getAction(id) === action) actionManager.replaceAction(id, action.delegate)
        }
        installed.clear()
    }

    public companion object {
        public const val EXPLAIN_PLAN: String = "Console.Jdbc.ExplainPlan"
        public const val CANCEL: String = "Console.Jdbc.Cancel"
    }
}
