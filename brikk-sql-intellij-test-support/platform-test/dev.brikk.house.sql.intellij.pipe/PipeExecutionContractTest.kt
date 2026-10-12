package dev.brikk.house.sql.intellij.pipe

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PipeExecutionContract

class PipeExecutionContractTest : BasePlatformTestCase() {
    private fun forEachDialect(check: PipeExecutionContract.() -> Unit) {
        for (dialect in testPipeDialects) {
            val contract = PipeExecutionContract(dialect, TEST_DRIVER, FOREIGN_DRIVER, { dialect.enabled = it }) { sql ->
                myFixture.openPipeSql(sql, testRootDisposable)
            }
            try {
                contract.check()
            } catch (failure: AssertionError) {
                throw AssertionError("${dialect.dialect}: ${failure.message}", failure)
            }
        }
    }

    fun testClaimsOnlyPipes() = forEachDialect { claimsOnlyPipes() }
    fun testRefusesStaleInput() = forEachDialect { refusesStaleInput() }
    fun testPropagatesCancellation() = forEachDialect { propagatesCancellation() }
    fun testLowersNestedOnlyPipelines() = forEachDialect { lowersNestedOnlyPipelines() }
    fun testRunsTopLevelStagesToTheCaret() = forEachDialect { runsTopLevelStagesToTheCaret() }
    fun testRefusesDestinationChangesDuringPrompting() = forEachDialect { refusesDestinationChangesDuringPrompting() }
    fun testRefusesNamespaceChangesDuringPrompting() = forEachDialect { refusesNamespaceChangesDuringPrompting() }
    fun testNotifiesRefusals() = forEachDialect { notifiesRefusals() }
    fun testDeclinesWhenNotOwned() = forEachDialect { declinesWhenNotOwned() }
    fun testSelectsStatementsLikeStockExecute() = forEachDialect { selectsStatementsLikeStockExecute() }
    fun testRefusesWholeScriptsItCannotRun() = forEachDialect { refusesWholeScriptsItCannotRun() }
}
