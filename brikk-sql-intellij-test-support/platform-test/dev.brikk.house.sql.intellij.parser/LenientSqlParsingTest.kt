package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.sql.psi.SqlStatement
import dev.brikk.house.sql.intellij.fixture.FixtureLexer
import dev.brikk.house.sql.intellij.fixture.FixtureParserDefinition
import dev.brikk.house.sql.intellij.fixture.FixturePipeDialect
import dev.brikk.house.sql.intellij.fixture.FixtureTestCase

class LenientSqlParsingTest : FixtureTestCase() {
    private fun builder(text: String): PsiBuilder = PsiBuilderFactory.getInstance().createBuilder(FixtureParserDefinition(), FixtureLexer(), text)

    /** Runs [probe] and checks that it consumed nothing. */
    private fun <T> PsiBuilder.peek(probe: PsiBuilder.() -> T): T {
        val offset = currentOffset
        return probe().also { assertEquals("consumed input", offset, currentOffset) }
    }

    private fun statements(): List<String> =
        PsiTreeUtil.getChildrenOfTypeAsList(myFixture.file, SqlStatement::class.java).map { it.text }

    fun testWordAtCountsLetterLedTokensWithinTheStatement() {
        val builder = builder("SELECT a, 1, b FROM t; SELECT c")
        assertEquals("SELECT", builder.peek { wordAt(0) })
        assertEquals("B", builder.peek { wordAt(2) })
        assertNull(builder.peek { wordAt(5) })
    }

    fun testScansStopAtTheStatementEnd() {
        val builder = builder("ALTER t SET (x = 1); CREATE TABLE u AS WITH")
        assertTrue(builder.peek { containsAny("SET", "NONE") })
        assertFalse(builder.peek { containsAny("WITH") })
        assertEquals("SET", builder.peek { firstOf("X", "SET") })
        assertNull(builder.peek { firstOf("TABLE") })
    }

    fun testWordThenWordAndWordThenTokenDiffer() {
        val alter = builder("ALTER t SET (x = 1)")
        assertTrue(alter.peek { wordThenToken("set", "(") })
        assertFalse(alter.peek { wordThenWord("SET", "X") })
        val create = builder("CREATE TABLE u AS /* c */ WITH")
        assertTrue(create.peek { wordThenWord("AS", "WITH") })
        assertFalse(create.peek { wordThenToken("AS", "with") })
    }

    fun testScansReadAtMostTheirLimit() {
        val builder = builder("SELECT " + "a, ".repeat(MAX_LOOKAHEAD) + "needle")
        assertFalse(builder.peek { containsAny("NEEDLE") })
        assertNotNull(builder.peek { scanStatement(Int.MAX_VALUE) { _, text -> text.takeIf { it == "needle" } } })
    }

    fun testLenientStatementEndsBeforeTheSemicolon() {
        configureFixture("SHOW a ( b; SELECT 1 FROM t")
        assertEquals(listOf("SHOW a ( b", "SELECT 1 FROM t"), statements())
    }

    fun testColonParameterNeedsAnAdjacentName() {
        val named = builder(":name + 1")
        val root = named.mark()
        assertTrue(named.parseColonParameter())
        assertEquals("+", named.tokenText)
        while (!named.eof()) named.advanceLexer()
        root.done(SqlCompositeElementTypes.SQL_STATEMENT)
        assertEquals(SqlElementTypes.SQL_PARAMETER_REFERENCE, named.treeBuilt.firstChildNode?.elementType)
        assertFalse(builder(": name").peek { parseColonParameter() })
        assertFalse(builder(":1").peek { parseColonParameter() })
    }

    fun testPipeStatementIsOneNodeBeforeItsSemicolon() {
        configureFixture("FROM t |> WHERE a = (1 |> 2);\nSELECT b FROM u")
        assertEquals(listOf("FROM t |> WHERE a = (1 |> 2)", "SELECT b FROM u"), statements())
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, PsiTreeUtil.getChildOfType(myFixture.file, SqlStatement::class.java)?.node?.elementType)
    }

    fun testPipeInsideALiteralOrCommentIsNoPipe() {
        configureFixture("SELECT '|>' FROM t /* |> */")
        val statement = PsiTreeUtil.getChildOfType(myFixture.file, SqlStatement::class.java)
        assertNotSame(SqlCompositeElementTypes.SQL_STATEMENT, statement?.node?.elementType)
    }

    fun testPipeStatementsAreParsedByTheGrammarWhenPipeIsOff() {
        FixturePipeDialect.enabled = false
        configureFixture("FROM t |> WHERE a; SELECT 1")
        assertTrue(PsiTreeUtil.hasErrorElements(myFixture.file))
    }

    fun testClaimedStatementWithoutPipeEndsAtItsOwnRange() {
        FixturePipeDialect.claimFromFirst = true
        configureFixture("FROM t WHERE a = 1;\nSELECT b FROM u;")
        assertEquals(listOf("FROM t WHERE a = 1", "SELECT b FROM u"), statements())
    }

    fun testStatementWithABoundaryErrorKeepsItsWholeRange() {
        configureFixture("FROM t |> WHERE (a; SELECT 1")
        assertEquals(listOf("FROM t |> WHERE (a; SELECT 1"), statements())
        assertNull(PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
    }
}
