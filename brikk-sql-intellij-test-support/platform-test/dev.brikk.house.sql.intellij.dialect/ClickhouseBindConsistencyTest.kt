package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlParameter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.configureDialect
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlScriptParameters

/** Core's ClickHouse bind discovery finds the parameters the IDE's ClickHouse grammar finds, ternaries included. */
class ClickhouseBindConsistencyTest : BasePlatformTestCase() {
    private fun ide(sql: String): List<Int> {
        myFixture.configureDialect("ClickHouse", sql)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, SqlParameter::class.java).map { it.textRange.startOffset }.sorted()
    }

    private fun core(sql: String): SqlScriptParameters = SqlScriptParameters.parse(sql, "clickhouse", positional = true)

    private fun coreStarts(sql: String) = core(sql).occurrences.filter { it.style != ParamStyle.NAMED_TYPED }.map { it.range.start }.sorted()

    fun testProvenBindsAreTheIdesParameters() {
        for (sql in listOf(
            "SELECT flag ? a : b", "SELECT flag?a:b", "SELECT ?", "SELECT :name", "SELECT a ? b : c ? d : e",
            "SELECT f(?, ?)", "SELECT 1 WHERE x = ? AND y = :n", "SELECT {name:UInt64}", "SELECT c ? 1 : 0, ?", "SELECT x ? y :z",
            "SELECT a::Int32", "SELECT ? ? 1 : 0", "SELECT * FROM t WHERE a = ? ? 1 : 2", "SELECT (x > 1) ? 'a' : 'b'",
            "SELECT ? + 1", "SELECT 1 FROM t LIMIT ?", "SELECT [?, ?]", "SELECT x ? (y ? 1 : 2) : 3", "SELECT x ? ? : ?",
            "SELECT a ? b : :c", "INSERT INTO t VALUES (?, ?)", "SELECT ?, flag ? 1 : 0",
        )) {
            assertEmpty(sql, core(sql).ambiguous)
            assertEquals(sql, ide(sql), coreStarts(sql))
        }
    }

    /** Where tokens cannot prove a `?`, core reports it as ambiguous; the IDE calls it a parameter. */
    fun testAmbiguousQuestionMarksAreTheIdesParameters() {
        for (sql in listOf("SELECT x ?", "SELECT x ?:y")) {
            val result = core(sql)
            assertEquals(sql, ide(sql), (coreStarts(sql) + result.ambiguous.map { it.start }).sorted())
        }
    }

    /** The IDE's grammar misreads a bind opening a ternary's middle: it also takes the ternary `?` for a parameter. */
    fun testTheIdesMisreadOfABindAfterATernaryIsKnown() {
        val sql = "SELECT x ? :p : ?"
        assertEquals(listOf(11, 16), coreStarts(sql))
        assertEquals(listOf(9, 11, 16), ide(sql))
    }
}
