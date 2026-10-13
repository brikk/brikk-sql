package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.fixture.CHOUSE_BOUNDARY_CORPUS
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDialect
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.fixture.OWNER_S3_EXPORT
import dev.brikk.house.sql.intellij.lexer.MaskingSqlLexer
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll

/** A plugin's rule for the clause the platform grammar lacks: `PARTITION BY <expr>` after `INSERT INTO [TABLE] FUNCTION`, masked as a comment. */
class PartitionMaskingLexer : MaskingSqlLexer(CHouseLexer(), keepParameters = true) {
    private var functionInsert = false

    override fun resetStatement() {
        functionInsert = false
    }

    override fun onNote(token: String) {
        if (token == "FUNCTION" && statementHead == "INSERT") functionInsert = true
    }

    override fun rule(base: Lexer, upper: String): Boolean {
        if (upper != "PARTITION" || !functionInsert || parenDepth != 0 || !wordsFollow(base, "BY")) return false
        var depth = 0
        base.advance()
        while (base.tokenType != null) {
            val text = base.tokenText
            when {
                text == "(" -> depth++
                text == ")" -> depth--
                text == ";" || depth == 0 && text.uppercase() in CLAUSES_AFTER -> break
            }
            base.advance()
        }
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
        return true
    }

    private companion object {
        private val CLAUSES_AFTER = setOf("SELECT", "WITH", "FORMAT", "VALUES", "SETTINGS")
    }
}

class MaskingCHouseParserDefinition : CHouseFamilyParserDefinition(CHouseFixtureDialect) {
    override fun createBaseLexer(): Lexer = PartitionMaskingLexer()
}

class CHouseMaskingBaseLexerTest : CHouseFixtureTestCase() {
    override fun parserDefinition(): ParserDefinition = MaskingCHouseParserDefinition()

    private fun assertParsesWithTheGrammar(name: String, statements: List<String>, sql: String) {
        configureCHouse(sql)
        assertEquals(name, statements.map { it.removeSuffix(";") }, statements().map { it.text })
        // The grammar's own statements, not lenient ones, without errors.
        assertTrue(name, statements().none { it.node.elementType == SqlCompositeElementTypes.SQL_STATEMENT })
        assertEmpty(name, PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
        assertNotNull(name, PsiTreeUtil.findChildOfType(statements().first(), SqlInsertDmlInstruction::class.java))
    }

    fun testTheMaskedOwnersExportParsesWithTheGrammar() {
        for (case in CHOUSE_BOUNDARY_CORPUS.filter { it.name.startsWith("owner") || it.name.startsWith("partitioned") && "format" !in it.name }) {
            assertParsesWithTheGrammar(case.name, case.statements, case.sql)
        }
    }

    /** On one line the platform grammar keeps its statement whole but reports an error at PARTITION; masking removes it. */
    fun testTheMaskedSingleLineExportParsesWithTheGrammar() {
        val single = OWNER_S3_EXPORT.lines().joinToString(" ") { it.trim() }
        assertParsesWithTheGrammar("single line", listOf(single, "SELECT 1;"), "$single\nSELECT 1;")
    }

    fun testAMaskingBaseLexerKeepsTheBoundedLexersRestarts() {
        val definition = MaskingCHouseParserDefinition()
        for (case in CHOUSE_BOUNDARY_CORPUS) {
            assertRestartsMatchFullLex({ definition.createLexer(project) }, case.sql)
            assertTrue(case.name, definition.createLexer(project).lexAll(case.sql).all { it.state >= 0 })
        }
    }
}
