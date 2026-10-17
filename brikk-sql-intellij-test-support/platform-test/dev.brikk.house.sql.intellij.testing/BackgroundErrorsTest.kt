package dev.brikk.house.sql.intellij.testing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class BackgroundErrorsTest : BasePlatformTestCase() {
    fun testAnExceptionEscapingACoroutineFails() {
        val errors = BackgroundErrors(testRootDisposable)
        errors.newScope(testRootDisposable).launch { throw IllegalStateException("escaped") }
        errors.awaitScopes()
        assertEquals("escaped", errors.errors.single().message)
        assertThrows(AssertionError::class.java) { errors.assertNone() }
    }

    fun testAnErrorLoggedOnAPooledThreadFails() {
        val errors = BackgroundErrors(testRootDisposable)
        val logged = ApplicationManager.getApplication().executeOnPooledThread(::logAnError)
        // The platform's test logger also rethrows the error on the logging thread.
        try {
            logged.get(10, TimeUnit.SECONDS)
        } catch (_: ExecutionException) {
        }
        assertTrue(errors.errors.toString(), errors.errors.any { it.message == "logged elsewhere" })
        assertThrows(AssertionError::class.java) { errors.assertNone() }
    }

    fun testNothingCollectedPasses() {
        val errors = BackgroundErrors(testRootDisposable)
        errors.newScope(testRootDisposable).launch { }
        errors.awaitScopes()
        errors.assertNone()
    }

    private fun logAnError() = logger<BackgroundErrorsTest>().error("logged elsewhere")
}
