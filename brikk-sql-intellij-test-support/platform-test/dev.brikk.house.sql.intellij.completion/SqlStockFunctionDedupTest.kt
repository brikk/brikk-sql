package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.sql.dialects.BuiltinFunction
import dev.brikk.house.sql.metadata.FunctionPresentation

/** A function both metadata and the base dialect know is offered once, from metadata; the base dialect's own forms stay. */
class SqlStockFunctionDedupTest : CompletionTestCase() {
    private fun offered(dialect: String, text: String, contributor: Class<out CompletionContributor>): List<LookupElement> {
        myFixture.configureDialect(dialect, text)
        register(contributor, LoadingOrder.FIRST)
        return complete()
    }

    private fun List<LookupElement>.named(name: String) = filter { it.lookupString.equals(name, ignoreCase = true) }

    fun testClickhouseOffersAFunctionOnce() {
        val items = offered("ClickHouse", "SELECT toDateTi<caret>", ClickhouseCompletion::class.java)
        val toDateTime = items.named("toDateTime")
        assertEquals(1, toDateTime.size)
        assertInstanceOf(toDateTime.single().`object`, FunctionPresentation::class.java)
    }

    fun testClickhouseKeepsTheBaseDialectsCombinators() {
        val items = offered("ClickHouse", "SELECT sumMa<caret>", ClickhouseCompletion::class.java)
        assertTrue(items.map { it.lookupString }.toString(), items.named("sumMap").any { it.`object` is BuiltinFunction.Prototype })
    }

    private fun assertOfferedOnce(dialect: String, contributor: Class<out CompletionContributor>) {
        assertEquals(1, offered(dialect, "SELECT ab<caret>", contributor).named("abs").size)
    }

    fun testDuckdbOnPostgresOffersAFunctionOnce() = assertOfferedOnce("PostgreSQL", DuckdbCompletion::class.java)

    fun testTrinoOnPostgresOffersAFunctionOnce() = assertOfferedOnce("PostgreSQL", TrinoCompletion::class.java)

    fun testDorisOnMysqlOffersAFunctionOnce() = assertOfferedOnce("MySQL", DorisCompletion::class.java)
}
