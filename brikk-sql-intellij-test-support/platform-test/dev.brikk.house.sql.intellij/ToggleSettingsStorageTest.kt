package dev.brikk.house.sql.intellij

import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@State(name = "BrikkWorkspaceToggle", storages = [Storage(ToggleSettings.STORAGE)])
class WorkspaceToggle(project: Project) : ToggleSettings(project, SqlLanguage.INSTANCE)

@State(name = "BrikkSharedToggle", storages = [Storage("brikk-toggle.xml")])
class SharedFileToggle(project: Project) : ToggleSettings(project, SqlLanguage.INSTANCE)

/** Every plugin's switch is stored where the library says: the project's workspace file. */
class ToggleSettingsStorageTest : BasePlatformTestCase() {
    fun testTheStorageIsTheWorkspaceFile() {
        assertEquals(StoragePathMacros.WORKSPACE_FILE, ToggleSettings.STORAGE)
        WorkspaceToggle(project).initializeComponent()
    }

    fun testAnotherStorageIsReported() {
        val error = LoggedErrorProcessor.executeAndReturnLoggedError { SharedFileToggle(project).initializeComponent() }
        assertTrue(error.toString(), error.message.orEmpty().contains("ToggleSettings.STORAGE"))
    }
}
