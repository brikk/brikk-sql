package dev.brikk.house.sql.intellij

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import java.util.UUID

class TestToggleSettings(project: Project) : ToggleSettings(project, SqlLanguage.INSTANCE) {
    val changes = mutableListOf<Boolean>()
    override fun onChanged(enabled: Boolean) {
        changes += enabled
    }
}

class PlatformHelpersTest : BasePlatformTestCase() {
    fun testToggleReparsesOnlyFilesOfItsLanguage() {
        val files = listOf("sql", "txt").map { myFixture.addFileToProject("toggle-${UUID.randomUUID()}.$it", "SELECT 1").virtualFile }
        val settings = TestToggleSettings(project).apply { initializeComponent() }
        val (sql, text) = files.map { checkNotNull(PsiManager.getInstance(project).findFile(it)) }

        settings.enabled = true
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf(true), settings.changes)
        assertTrue(settings.enabled)
        assertFalse("The SQL file must be reparsed", sql.isValid)
        assertTrue("Other languages keep their PSI", text.isValid)
    }

    fun testLoadedStateRefreshesOnlyAfterInitialization() {
        val settings = TestToggleSettings(project)
        settings.loadState(ToggleSettings.Options(enabled = true))
        assertEmpty(settings.changes)
        settings.initializeComponent()
        settings.loadState(ToggleSettings.Options(enabled = false))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(false), settings.changes)
    }

    fun testConfigurableAppliesTheCheckboxAndHonorsTheVeto() {
        val property = "brikk.test.toggle.${UUID.randomUUID()}"
        val toggle = TestToggleSettings(project)
        val configurable = object : ToggleConfigurable("test.toggle", "Test toggle", "Enable", vetoProperty = property) {
            override val settings: ToggleSettings = toggle
        }
        try {
            val checkbox = UIUtil.findComponentOfType(configurable.createComponent(), JBCheckBox::class.java)!!
            assertTrue(checkbox.isEnabled)
            assertFalse(configurable.isModified)
            checkbox.isSelected = true
            assertTrue(configurable.isModified)
            configurable.apply()
            assertTrue(toggle.enabled)
            configurable.disposeUIResources()

            System.setProperty(property, "false")
            assertFalse(UIUtil.findComponentOfType(configurable.createComponent(), JBCheckBox::class.java)!!.isEnabled)
        } finally {
            configurable.disposeUIResources()
            System.clearProperty(property)
        }
    }

    fun testBannerMessagesBelongToTheirProject() {
        val file = myFixture.configureByText("banner-${UUID.randomUUID()}.sql", "SELECT 1").virtualFile
        val banner = FileBanner("brikk.test.banner.${UUID.randomUUID()}")
        val provider = object : FileBannerProvider(banner) {}
        val other = ProjectManager.getInstance().defaultProject

        banner.show(project, file, "Introspecting")
        assertEquals("Introspecting", banner.message(project, file))
        assertNotNull(provider.collectNotificationData(project, file))
        assertNull(banner.message(other, file))
        assertNull(provider.collectNotificationData(other, file))

        banner.clear(project, file)
        assertNull(provider.collectNotificationData(project, file))
    }

    fun testNotificationsShowTextNotMarkup() {
        val group = "brikk.test.${UUID.randomUUID()}"
        val received = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == group) received += notification.apply { setSuppressShowingPopup(true) }
            }
        })
        notifyEscaped(project, group, "<i>Title</i>", "a < b\r\nnext")
        val notification = received.single()
        assertEquals("&lt;i&gt;Title&lt;/i&gt;", notification.title)
        assertEquals("a &lt; b<br>next", notification.content)
        notification.expire()
    }
}
