package dev.brikk.house.sql.intellij.completion

import com.intellij.util.ThreeState
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax

class DuckdbConfidence : SqlCompletionConfidence(duckdbProfile)

class SqlFunctionGateTest : CompletionTestCase() {
    private val namespaced = FunctionPresentation("ab_routine", false, FunctionKind.SCALAR, null, "", null, 1, null, setOf(InvocationSyntax.CALL))

    private fun offeredAfterQualifier(rule: QualifierRule): List<String> {
        duckdbProfile.functionGates = SqlFunctionGates(qualifier = rule)
        configure("SELECT t.ab<caret> FROM t", DuckdbCompletion::class.java)
        return functionItems().map { it.name }
    }

    fun testWithoutAQualifierGateFunctionsFollowAQualifier() {
        assertContainsElements(offeredAfterQualifier(QualifierRule.Unrestricted), "abs")
    }

    fun testADialectWithoutQualifiedFunctionsOffersNoneAfterAQualifier() {
        assertEmpty(offeredAfterQualifier(QualifierRule.Unqualified))
    }

    fun testANamespacedDialectOffersTheQualifiersFunctions() {
        var asked: String? = null
        val offered = offeredAfterQualifier(QualifierRule.Namespaced { qualifier, _ -> asked = qualifier; listOf(namespaced) })
        assertEquals(listOf("ab_routine"), offered)
        assertEquals("t", asked)
    }

    fun testTheQualifierGateLeavesUnqualifiedPositionsAlone() {
        duckdbProfile.functionGates = SqlFunctionGates(qualifier = QualifierRule.Unqualified)
        configure("SELECT ab<caret> FROM t", DuckdbCompletion::class.java)
        assertContainsElements(functionItems().map { it.name }, "abs")
    }

    private fun offeredAfterADigit(digitPrefix: Boolean): List<FunctionPresentation> {
        duckdbProfile.functionGates = SqlFunctionGates(digitPrefix = digitPrefix)
        configure("SELECT a FROM t GROUP BY 1<caret>", DuckdbCompletion::class.java)
        return functionItems()
    }

    fun testADigitLedPrefixOffersFunctionsOnlyWithoutTheGate() {
        assertNotEmpty(offeredAfterADigit(digitPrefix = false))
        assertEmpty(offeredAfterADigit(digitPrefix = true))
    }

    private fun autopopupOffers(text: String, gate: ExpressionPositions?): Boolean {
        duckdbProfile.functionGates = SqlFunctionGates(autopopup = gate)
        configure(text, DuckdbCompletion::class.java)
        return functionItems(invocationCount = 0).isNotEmpty()
    }

    fun testTheAutopopupGateOffersFunctionsOnlyInExpressionPositions() {
        val gate = ExpressionPositions.STANDARD
        assertTrue(autopopupOffers("SELECT a FROM t WHERE a<caret>", gate))
        assertTrue(autopopupOffers("SELECT a FROM t WHERE a = /* c */ a<caret>", gate))
        assertFalse(autopopupOffers("SELECT a AS a<caret> FROM t", gate))
        assertTrue(autopopupOffers("SELECT a AS a<caret> FROM t", null))
    }

    fun testExplicitCompletionIgnoresTheAutopopupGate() {
        duckdbProfile.functionGates = SqlFunctionGates(autopopup = ExpressionPositions.STANDARD)
        configure("SELECT a AS a<caret> FROM t", DuckdbCompletion::class.java)
        assertNotEmpty(functionItems())
    }

    private fun confidenceAfter(text: String): ThreeState {
        myFixture.configureByText("confidence.sql", text.replace("<caret>", ""))
        val offset = text.indexOf("<caret>")
        return DuckdbConfidence().shouldSkipAutopopup(myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset)
    }

    fun testTheDigitGateSkipsTheAutopopupForEveryContributor() {
        duckdbProfile.functionGates = SqlFunctionGates(digitPrefix = true)
        assertEquals(ThreeState.YES, confidenceAfter("SELECT a FROM t GROUP BY 1<caret>"))
        assertEquals(ThreeState.UNSURE, confidenceAfter("SELECT a FROM t GROUP BY a<caret>"))
        duckdbProfile.functionGates = SqlFunctionGates.OFF
        assertEquals(ThreeState.UNSURE, confidenceAfter("SELECT a FROM t GROUP BY 1<caret>"))
    }
}
