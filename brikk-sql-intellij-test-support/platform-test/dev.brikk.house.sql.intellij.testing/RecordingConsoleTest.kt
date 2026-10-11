package dev.brikk.house.sql.intellij.testing

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.GridDataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RecordingConsoleTest : BasePlatformTestCase() {
    private fun recording(sql: String) =
        RecordingConsole(project, myFixture.configureByText("recording-${UUID.randomUUID()}.sql", sql).virtualFile, "h2.unified")

    private fun RecordingConsole.executeScript() = console.executeQueries(myFixture.editor, console.scriptModel, DatabaseSettings.ExecOption())

    fun testConsoleRequestsAreRecordedAsFinishedSessionWork() {
        recording("SELECT 1;\nSELECT 2;").use { recording ->
            val state = recording.console.session.state
            assertEmpty(recording.requests)
            assertTrue(state.isEmpty)

            recording.executeScript()
            (recording.requests.last() as ConsoleDataRequest).onFinished()

            val requests = recording.requests
            assertEquals(listOf("SELECT 1", "SELECT 2"), requests.map { (it as DataRequest.QueryRequest).query.trim() })
            assertTrue(requests.all { it.owner === recording.console })
            assertEquals(requests, state.work.map { it.request })
            assertTrue(state.work.all { it.state == DatabaseSession.State.WorkState.FINISHED })
            for (request in requests) assertSame(request, state.getWorkFor(request as GridDataRequest)?.request)
            assertSame(requests.last(), state.getMostRecentWork(requests)?.request)
        }
    }

    fun testFailedSubmissionIsRecordedAndPropagates() {
        recording("SELECT 1").use { recording ->
            val canceled = ProcessCanceledException()
            recording.onSubmit = { throw canceled }
            assertSame(canceled, runCatching { recording.executeScript() }.exceptionOrNull())
            assertSame(recording.requests.single(), recording.console.session.state.work.single().request)
        }
    }

    fun testSessionAccessHookObservesAndCanRefuseAccess() {
        recording("SELECT 1").use { recording ->
            val session = recording.console.session
            // The IDE also reads the session from background threads.
            val testThread = Thread.currentThread()
            val calls = CopyOnWriteArrayList<String>()
            recording.beforeSessionCall = { if (Thread.currentThread() === testThread) calls += it }
            session.state
            assertEquals(session, session)
            assertEquals(listOf("getState", "equals"), calls)

            val refused = ProcessCanceledException()
            recording.beforeSessionCall = { if (Thread.currentThread() === testThread) throw refused }
            assertSame(refused, runCatching { session.state }.exceptionOrNull())
        }
    }

    fun testAuditorHookRunsAtRegistrationOfAnAuditorThatSeesSubmissions() {
        recording("SELECT 1").use { recording ->
            val registered = CopyOnWriteArrayList<DataAuditor>()
            val audited = CopyOnWriteArrayList<DataRequest>()
            recording.onAuditorAdded = { registered += it }
            val auditor = object : DataAuditor {
                override fun jobSubmitted(request: DataRequest, producer: DataProducer) {
                    audited += request
                }
            }
            recording.console.session.messageBus.addAuditor(auditor)
            assertSame(auditor, registered.single())
            recording.executeScript()
            assertEquals(recording.requests, audited)

            val canceled = ProcessCanceledException()
            recording.onAuditorAdded = { throw canceled }
            assertSame(canceled, runCatching { recording.console.session.messageBus.addAuditor(object : DataAuditor {}) }.exceptionOrNull())
        }
    }

    fun testReplacementsAreVisibleToConsoleLookupAndShareTheRecording() {
        val recording = recording("SELECT 1")
        val dataSources = recording.use {
            val first = recording.console
            val second = recording.replaceSession()
            assertFalse(first.isValid)
            assertNotSame(first.session, second.session)
            assertSame(first.dataSource, second.dataSource)
            assertSame(second, JdbcConsoleProvider.getValidConsole(project, myFixture.file.virtualFile))

            val third = recording.replaceDataSource()
            assertNotSame(second.dataSource, third.dataSource)
            assertSame(third, JdbcConsoleProvider.getValidConsole(project, myFixture.file.virtualFile))
            recording.executeScript()
            assertSame(third, recording.requests.single().owner)
            listOf(first.dataSource, third.dataSource)
        }
        assertDoesntContain(LocalDataSourceManager.getInstance(project).dataSources, dataSources)
    }
}
