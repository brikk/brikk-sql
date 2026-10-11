package dev.brikk.house.sql.intellij.pipe

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PipeExecutionContract
import java.util.UUID

class PipeExecutionContractTest : BasePlatformTestCase() {
    private fun forEachDialect(check: PipeExecutionContract.() -> Unit) {
        for (dialect in testPipeDialects) {
            val contract = PipeExecutionContract(dialect, TEST_DRIVER) { sql ->
                myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", sql)
                myFixture.editor
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
    fun testNotifiesRefusals() = forEachDialect { notifiesRefusals() }
}
