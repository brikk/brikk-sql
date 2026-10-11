package dev.brikk.house.sql.intellij

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import java.util.Collections
import java.util.IdentityHashMap

/** Only DECLINED permits delegation. A claimed failure is handled or throws. */
public enum class ExecutionClaim { DECLINED, HANDLED }

public fun interface ExecuteInterceptor {
    public fun execute(event: AnActionEvent, option: DatabaseSettings.ExecOption): ExecutionClaim
}

/** One fixed stock Selection option, independent of the user's three variants. */
public fun selectionExecuteOption(): DatabaseSettings.ExecOption = DatabaseSettings.ExecOption().apply { execSelection = 1 }

public open class CooperativeExecuteAction(
    index: Int,
    private val previous: AnAction,
    private val interceptor: ExecuteInterceptor,
    private val selection: Boolean = false,
) : RunQueryAction(index), ActionWithDelegate<AnAction>, PerformWithDocumentsCommitted {
    init { copyFrom(previous) }
    private val selectionOption = selectionExecuteOption()
    @Volatile public var detached: Boolean = false
        private set
    public fun detach() { detached = true }
    override fun getDelegate(): AnAction = previous
    override fun getExecOption(): DatabaseSettings.ExecOption = if (selection) selectionOption else super.getExecOption()
    override fun update(e: AnActionEvent): Unit = previous.update(e)
    override fun getActionUpdateThread(): ActionUpdateThread = previous.actionUpdateThread
    override fun isDumbAware(): Boolean = previous.isDumbAware
    override fun isInInjectedContext(): Boolean = previous.isInInjectedContext
    override fun isPerformWithDocumentsCommitted(): Boolean = PerformWithDocumentsCommitted.isPerformWithDocumentsCommitted(previous)
    override fun actionPerformed(e: AnActionEvent) {
        if (detached || interceptor.execute(e, getExecOption()) == ExecutionClaim.DECLINED)
            ActionWrapperUtil.actionPerformed(e, this, previous)
    }
}

/** Register once; project participation belongs to the interceptor, not registration. */
public abstract class CooperativeExecuteConfiguration : DynamicActionConfigurationCustomizer {
    private val installed = LinkedHashMap<String, CooperativeExecuteAction>()
    protected abstract val interceptor: ExecuteInterceptor
    override fun registerActions(actionManager: ActionManager) {
        if (installed.isNotEmpty()) return
        for ((index, id) in IDS.withIndex()) {
            val previous = requireNotNull(actionManager.getAction(id)) { "Missing SQL Execute action: $id" }
            val action = CooperativeExecuteAction(if (index == 3) -1 else index + 1, previous, interceptor, index == 3)
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
        public val IDS: List<String> = listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")
    }
}

/** Foreign helper copies are traversed through the platform contract, never cast. */
public open class CooperativeExecutePromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        var ours = false
        val delegates = actions.map { action ->
            var current = action
            var containsUs = current is CooperativeExecuteAction
            val seen = Collections.newSetFromMap(IdentityHashMap<AnAction, Boolean>())
            while (seen.add(current)) {
                val next = (current as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
                if (next in seen) break
                current = next
                containsUs = containsUs || current is CooperativeExecuteAction
            }
            ours = ours || containsUs
            if (containsUs) current else action
        }
        if (!ours) return emptyList()
        val database = ActionPromoter.EP_NAME.extensionList.firstOrNull {
            it.javaClass.name == "com.intellij.database.actions.DatabaseActionPromoter"
        } ?: return emptyList()
        return database.promote(delegates, context).orEmpty().flatMap { promoted ->
            actions.indices.filter { delegates[it] === promoted }.map { actions[it] }
        }
    }
}
