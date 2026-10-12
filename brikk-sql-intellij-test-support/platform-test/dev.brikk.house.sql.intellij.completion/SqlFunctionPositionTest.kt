package dev.brikk.house.sql.intellij.completion

import com.intellij.openapi.extensions.LoadingOrder
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionKind.SCALAR
import dev.brikk.house.sql.metadata.FunctionKind.TABLE_GENERATING
import dev.brikk.house.sql.metadata.FunctionKind.TABLE_VALUED
import dev.brikk.house.sql.metadata.TRINO_METADATA

/** Table positions offer only table functions, other known positions hide them, unknown ones offer everything. */
class SqlFunctionPositionTest : CompletionTestCase() {
    private val everything = setOf("range" to TABLE_VALUED, "range" to SCALAR, "random" to SCALAR)
    private val table = setOf("range" to TABLE_VALUED)
    private val expression = setOf("range" to SCALAR, "random" to SCALAR)

    /** The `range` and `random` items DuckDB offers at the caret in [text], with their kinds. */
    private fun offered(text: String, dialect: String? = null): Set<Pair<String, FunctionKind>> {
        if (dialect == null) {
            configure(text, DuckdbCompletion::class.java)
        } else {
            myFixture.configureDialect(dialect, text)
            register(DuckdbCompletion::class.java, LoadingOrder.FIRST)
        }
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

    fun testFromInsideAScalarCallIsAnArgument() {
        assertOffers(
            expression,
            "SELECT EXTRACT(YEAR FROM ra<caret>", "SELECT SUBSTRING(x FROM ra<caret>", "SELECT TRIM(BOTH ' ' FROM ra<caret>",
            "SELECT SUBSTRING(x FROM 1, ra<caret>",
        )
        assertOffers(table, "SELECT * FROM t WHERE a IN (SELECT b FROM ra<caret>", "SELECT * FROM t WHERE a IN (FROM ra<caret>")
    }

    fun testDistinctFromIsNotATablePosition() =
        assertOffers(everything, "SELECT * FROM t WHERE a IS DISTINCT FROM ra<caret>", "SELECT * FROM t WHERE a IS NOT DISTINCT FROM ra<caret>")

    fun testKeywordsNotNamesDecide() {
        // The generic SQL parser does not take LATERAL; PostgreSQL's does.
        val lateral = "SELECT * FROM t JOIN LATERAL (ra<caret>) AS x ON true"
        assertEquals(everything, offered(lateral, "PostgreSQL"))
        assertOffers(table, "SELECT * FROM aggregate, ra<caret>", "SELECT * FROM extend, ra<caret>")
    }

    fun testDuckdbUnnestIsOfferedInExpressionsToo() {
        fun unnest(text: String): Set<FunctionKind> {
            configure(text, DuckdbCompletion::class.java)
            return functionItems().filter { it.name == "unnest" }.mapTo(HashSet()) { it.kind }
        }
        assertEquals(setOf(TABLE_VALUED), unnest("SELECT unn<caret>"))
        assertEquals(setOf(TABLE_VALUED), unnest("SELECT * FROM unn<caret>"))
    }

    fun testStageHeadsFollowTheProfilesStagePhrases() {
        // Trino has no NATURAL JOIN stage, so after `|> NATURAL` a function may stand.
        configure("FROM t |> NATURAL seq<caret>", TrinoCompletion::class.java)
        assertTrue(functionItems().any { it.name == "sequence" })
        configure("FROM t |> LEFT seq<caret>", TrinoCompletion::class.java)
        assertTrue(functionItems().none { it.name == "sequence" })
    }

    private fun sequence(text: String): Set<FunctionKind> {
        configure(text, TrinoCompletion::class.java)
        return functionItems().filter { it.name == "sequence" }.mapTo(HashSet()) { it.kind }
    }

    fun testTrinoTableArgumentsAreTablePositions() {
        assertEquals(setOf(TABLE_VALUED), sequence("SELECT * FROM TABLE(seq<caret>"))
        assertEquals(setOf(SCALAR), sequence("SELECT seq<caret>"))
        assertEquals(setOf(SCALAR), sequence("SELECT * FROM TABLE(sequence(1, seq<caret>"))
    }

    private fun acceptTrinoTableFunction(text: String, name: String): String {
        val entry = TRINO_METADATA.default.completableFunctions().single { it.name.equals(name, ignoreCase = true) && it.kind == TABLE_VALUED }
        configure(text, TrinoCompletion::class.java)
        accept(entry)
        return resultWithCaret()
    }

    fun testTrinoTableFunctionsAreWrappedWhereTheyStandBare() {
        assertEquals("SELECT * FROM TABLE(sequence(<caret>))", acceptTrinoTableFunction("SELECT * FROM seq<caret>", "sequence"))
        assertEquals("SELECT * FROM t, TABLE(sequence(<caret>))", acceptTrinoTableFunction("SELECT * FROM t, seq<caret>", "sequence"))
        assertEquals("SELECT * FROM TABLE(sequence(<caret>)", acceptTrinoTableFunction("SELECT * FROM TABLE(seq<caret>", "sequence"))
        assertEquals("SELECT * FROM UNNEST(<caret>)", acceptTrinoTableFunction("SELECT * FROM unn<caret>", "unnest"))
    }

    fun testDorisGeneratorsAreNotHiddenInExpressions() {
        fun kinds(text: String, name: String): Set<FunctionKind> {
            configure(text, DorisCompletion::class.java)
            return functionItems().filter { it.name.equals(name, ignoreCase = true) }.mapTo(HashSet()) { it.kind }
        }
        assertEquals(setOf(TABLE_GENERATING), kinds("SELECT expl<caret>", "explode"))
        assertEquals(setOf(TABLE_GENERATING), kinds("SELECT * FROM t LATERAL VIEW expl<caret>", "explode"))
        assertEquals(setOf(TABLE_GENERATING), kinds("SELECT * FROM expl<caret>", "explode"))
        assertEquals(emptySet<FunctionKind>(), kinds("SELECT numb<caret>", "numbers"))
        assertEquals(setOf(TABLE_VALUED), kinds("SELECT * FROM numb<caret>", "numbers"))
    }
}
