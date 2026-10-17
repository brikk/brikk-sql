package dev.brikk.house.sql.intellij

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.PlaceProvider
import javax.swing.JPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Two plugins' wrappers on the same real action id chain: each claims only its own events. */
class CooperativeActionTest : BasePlatformTestCase() {
    private val owner = DataKey.create<String>("brikk.test.cooperative.owner")
    private val performed = mutableListOf<String>()

    private inner class Stock : AnAction("Stock text") {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = false
        }

        override fun actionPerformed(e: AnActionEvent) {
            performed += "stock"
        }
    }

    private inner class Plugin(private val name: String, override val actionIds: List<String>, private val runStockFirst: Boolean = false) :
        CooperativeActionConfiguration() {
        override val interceptor = object : ActionInterceptor {
            override fun update(event: AnActionEvent): Boolean? = if (event.getData(owner) == name) true else null

            override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
                if (event.getData(owner) != name) return ExecutionClaim.DECLINED
                if (runStockFirst) delegate()
                performed += name
                return ExecutionClaim.HANDLED
            }
        }
    }

    private val id = "Brikk.Test.CooperativeAction"
    private val manager get() = ActionManager.getInstance()

    override fun setUp() {
        super.setUp()
        manager.registerAction(id, Stock())
    }

    override fun tearDown() {
        try {
            manager.unregisterAction(id)
        } finally {
            super.tearDown()
        }
    }

    private fun event(action: AnAction, claimant: String?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).apply { claimant?.let { add(owner, it) } }.build()
        return TestActionEvent.createTestEvent(action, context)
    }

    private fun perform(claimant: String?) {
        val action = checkNotNull(manager.getAction(id))
        val event = event(action, claimant)
        action.update(event)
        action.actionPerformed(event)
    }

    private fun visible(claimant: String?): Boolean {
        val action = checkNotNull(manager.getAction(id))
        return event(action, claimant).also(action::update).presentation.isEnabledAndVisible
    }

    fun testTwoRegistrationsChainAndEachClaimsOnlyItsOwnEvents() {
        val stock = manager.getAction(id)
        val a = Plugin("a", listOf(id))
        val b = Plugin("b", listOf(id))
        a.registerActions(manager)
        b.registerActions(manager)
        val outer = manager.getAction(id) as CooperativeAction
        assertSame(stock, (outer.delegate as CooperativeAction).delegate)
        assertEquals("the stock presentation", "Stock text", outer.templatePresentation.text)
        perform("a")
        perform("b")
        perform(null)
        assertEquals(listOf("a", "b", "stock"), performed)
        assertTrue(visible("a"))
        assertTrue(visible("b"))
        assertFalse("others get the stock update", visible(null))
        b.unregisterActions(manager)
        a.unregisterActions(manager)
        assertSame(stock, manager.getAction(id))
    }

    fun testAClaimMayRunTheWrappedActionFirst() {
        Plugin("a", listOf(id), runStockFirst = true).registerActions(manager)
        perform("a")
        assertEquals(listOf("stock", "a"), performed)
    }

    fun testUnloadingBeneathAPeerOnlyDetaches() {
        val a = Plugin("a", listOf(id))
        val b = Plugin("b", listOf(id))
        a.registerActions(manager)
        b.registerActions(manager)
        a.unregisterActions(manager)
        perform("a")
        assertEquals("a detached wrapper passes through", listOf("stock"), performed)
        perform("b")
        assertEquals(listOf("stock", "b"), performed)
        b.unregisterActions(manager)
        assertTrue((manager.getAction(id) as CooperativeAction).detached)
    }

    fun testIdsTheIdeLacksAreSkipped() {
        val plugin = Plugin("a", listOf("Brikk.Test.Missing", id))
        plugin.registerActions(manager)
        assertNull(manager.getAction("Brikk.Test.Missing"))
        assertInstanceOf(manager.getAction(id), CooperativeAction::class.java)
        plugin.unregisterActions(manager)
    }

    /** The stock Explain Plan and Cancel keep their presentation and shortcuts, and come back on unloading. */
    fun testStockDatabaseActionsAreWrappedInPlace() {
        val explain = checkNotNull(manager.getAction(CooperativeActionConfiguration.EXPLAIN_PLAN))
        val cancel = checkNotNull(manager.getAction(CooperativeActionConfiguration.CANCEL))
        val plugin = Plugin("a", listOf(CooperativeActionConfiguration.EXPLAIN_PLAN, CooperativeActionConfiguration.CANCEL))
        plugin.registerActions(manager)
        try {
            val wrappedExplain = manager.getAction(CooperativeActionConfiguration.EXPLAIN_PLAN) as CooperativeAction
            val wrappedCancel = manager.getAction(CooperativeActionConfiguration.CANCEL) as CooperativeAction
            assertSame(explain, wrappedExplain.delegate)
            assertSame(cancel, wrappedCancel.delegate)
            assertEquals(explain.templatePresentation.text, wrappedExplain.templatePresentation.text)
            assertEquals(explain.templatePresentation.icon, wrappedExplain.templatePresentation.icon)
            assertEquals(cancel.shortcutSet.shortcuts.toList(), wrappedCancel.shortcutSet.shortcuts.toList())
        } finally {
            plugin.unregisterActions(manager)
        }
        assertSame(explain, manager.getAction(CooperativeActionConfiguration.EXPLAIN_PLAN))
        assertSame(cancel, manager.getAction(CooperativeActionConfiguration.CANCEL))
    }

    /** The database promoter ranks a wrapped database action as the action it wraps: here the stock Execute in the structure popup. */
    fun testThePromoterRanksAWrapperAsTheActionItWraps() {
        val stock = checkNotNull(manager.getAction(CooperativeExecuteConfiguration.IDS.first()))
        val wrapped = CooperativeAction(stock, object : ActionInterceptor {
            override fun perform(event: AnActionEvent, delegate: () -> Unit) = ExecutionClaim.DECLINED
        })
        val other = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {}
        }
        val popup = object : JPanel(), PlaceProvider {
            override fun getPlace(): String = "StructureViewPopup"
        }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, popup).build()
        val database = ActionPromoter.EP_NAME.extensionList.single { it.javaClass.name == "com.intellij.database.actions.DatabaseActionPromoter" }
        assertEquals("the stock action is promoted", listOf(stock), database.promote(listOf(other, stock), context).orEmpty())
        assertEquals(listOf(wrapped), CooperativeExecutePromoter().promote(listOf(other, wrapped), context))
    }
}
