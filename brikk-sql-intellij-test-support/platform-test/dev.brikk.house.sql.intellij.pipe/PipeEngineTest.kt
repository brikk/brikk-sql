package dev.brikk.house.sql.intellij.pipe

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PipeEngineTest : BasePlatformTestCase() {
    fun testFromOnlyFirstStageIsDecidedOnTheFirstTokenAfterTrivia() {
        val fromOnly = TestPipeDialect("doris", FirstStageRule.FromOnly)
        for (stage in listOf("-- note\nFROM offline_rows", "/* note */ FROM offline_rows", "\n  FROM offline_rows")) {
            assertTrue(stage, fromOnly.lower(stage, firstStage = true) is PipeTranslation.Lowered)
        }
        assertTrue(fromOnly.lower("-- FROM\nSELECT id FROM offline_rows", firstStage = true) is PipeTranslation.Refused)
        assertTrue(TestPipeDialect("doris", FirstStageRule.AnyQuery).lower("SELECT id FROM offline_rows", firstStage = true) is PipeTranslation.Lowered)
    }
}
