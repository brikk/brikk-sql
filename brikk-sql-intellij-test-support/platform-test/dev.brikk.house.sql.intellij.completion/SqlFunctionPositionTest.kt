package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionKind.SCALAR
import dev.brikk.house.sql.metadata.FunctionKind.TABLE_VALUED

/** Table positions offer only table functions, other known positions hide them, unknown ones offer everything. */
class SqlFunctionPositionTest : CompletionTestCase() {
    private val everything = setOf("range" to TABLE_VALUED, "range" to SCALAR, "random" to SCALAR)
    private val table = setOf("range" to TABLE_VALUED)
    private val expression = setOf("range" to SCALAR, "random" to SCALAR)

    /** The `range` and `random` items DuckDB offers at the caret in [text], with their kinds. */
    private fun offered(text: String): Set<Pair<String, FunctionKind>> {
        configure(text, DuckdbCompletion::class.java)
        return functionItems().filter { it.name == "range" || it.name == "random" }.mapTo(HashSet()) { it.name to it.kind }
    }

    private fun assertOffers(expected: Set<Pair<String, FunctionKind>>, vararg texts: String) {
        for (text in texts) assertEquals(text, expected, offered(text))
    }

    fun testAfterFromOnlyTheTableFormIsOffered() = assertOffers(table, "SELECT * FROM ra<caret>", "FROM ra<caret>")

    fun testAnExpressionHidesTheTableForm() = assertOffers(
        expression,
        "SELECT ra<caret>", "SELECT a, ra<caret> FROM t", "SELECT * FROM t WHERE a = ra<caret>", "SELECT abs(ra<caret>)",
    )

    fun testJoinsAreTablePositions() = assertOffers(
        table,
        "SELECT * FROM t JOIN ra<caret>", "SELECT * FROM t LEFT OUTER JOIN ra<caret>", "SELECT * FROM t CROSS JOIN ra<caret>",
        "SELECT * FROM t ASOF JOIN ra<caret>",
    )

    fun testACommaContinuesTheFromList() = assertOffers(
        table,
        "SELECT * FROM t, ra<caret>", "SELECT * FROM range(3), ra<caret>", "SELECT * FROM t JOIN u ON t.a = u.a, ra<caret>",
    )

    fun testACommaInsideACallInTheFromListIsAnArgument() = assertOffers(expression, "SELECT * FROM range(1, ra<caret>")

    fun testUnknownPositionsOfferEverything() = assertOffers(
        everything,
        "ra<caret>", "SELECT * FROM (ra<caret>", "SELECT a FROM t WHERE b = 1) ra<caret>",
    )

    fun testPipePositions() {
        assertOffers(table, "FROM t |> JOIN ra<caret>", "FROM t |> LEFT JOIN ra<caret>", "FROM t |> CALL ra<caret>")
        assertOffers(expression, "FROM t |> WHERE ra<caret>", "FROM t |> SELECT a, ra<caret>", "FROM t |> EXTEND ra<caret>")
        assertOffers(everything, "FROM t |> TABLESAMPLE ra<caret>")
    }

    fun testAStageHeadOffersNoFunctions() = assertOffers(emptySet(), "FROM t |> ra<caret>", "FROM t |> LEFT ra<caret>")

    fun testTrinoTableArgumentsAreTablePositions() {
        trinoProfile.tablePositions = TablePositions.STANDARD.copy(parenthesized = setOf("TABLE"))
        fun sequence(text: String): Set<FunctionKind> {
            configure(text, TrinoCompletion::class.java)
            return functionItems().filter { it.name == "sequence" }.mapTo(HashSet()) { it.kind }
        }
        assertEquals(setOf(TABLE_VALUED), sequence("SELECT * FROM TABLE(seq<caret>"))
        assertEquals(setOf(SCALAR), sequence("SELECT seq<caret>"))
        assertEquals(setOf(SCALAR), sequence("SELECT * FROM TABLE(sequence(1, seq<caret>"))
    }
}
