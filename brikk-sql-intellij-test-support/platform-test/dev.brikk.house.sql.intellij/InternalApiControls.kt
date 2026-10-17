package dev.brikk.house.sql.intellij

import com.intellij.database.console.ConnectionTimerLauncher
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import java.util.function.Predicate
import kotlin.reflect.KClass

// One internal reference each, in a place only a full class visit reaches; read by InternalApiContainmentTest.
// ConnectionTimerLauncher is internal and top-level, so no InnerClasses entry names it.

internal class BodyClassLiteral {
    fun use(): Any = ConnectionTimerLauncher::class.java
}

internal class BodyCast {
    fun use(value: Any): Any = value as ConnectionTimerLauncher
}

internal class BodyCall {
    fun use(): Int = ApplicationManager.getApplication().extensionArea.nameToPointMap.size
}

internal class BodyFieldAccess {
    fun use(): Any = TreeStructureProvider.EP
}

internal class LambdaBody {
    fun use(): Any = { value: Any -> value is ConnectionTimerLauncher }
}

/** Compiles to invokedynamic whose handle targets a synthetic method that calls the internal one. */
internal class MethodReference {
    fun use(): Any = Predicate(ConnectionTimerLauncher::destroyConnection)
}

internal annotation class Refers(val type: KClass<*>)

internal class AnnotationValue {
    @Refers(ConnectionTimerLauncher::class)
    fun use() {}
}

internal class ControlAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {}
}

/** The call names the subclass; the internal member is declared by its platform superclass. */
internal class CallThroughSubclass {
    fun use(action: ControlAction): Boolean = action.isDefaultIcon
}
