package dev.brikk.house.sql.intellij.parser

import com.intellij.database.script.ScriptModel
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlPsiFacade
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.LightVirtualFile
import dev.brikk.house.sql.intellij.fixture.BoundaryCase
import dev.brikk.house.sql.intellij.fixture.CHOUSE_BOUNDARY_CORPUS
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.fixture.OWNER_S3_EXPORT
import dev.brikk.house.sql.parser.SqlScriptSnapshot

class CHouseStatementBoundaryTest : CHouseFixtureTestCase() {
    /** Each statement's range, without its `;`, as the editor's statements should have it. */
    private fun BoundaryCase.expectedRanges(): List<TextRange> {
        var from = 0
        return statements.map { statement ->
            val start = sql.indexOf(statement, from)
            from = start + statement.length
            TextRange(start, start + statement.removeSuffix(";").trimEnd().length)
        }
    }

    private fun stockStatements(text: String): List<SqlStatement> {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("stock-${System.nanoTime()}.sql", CHouseDialect.INSTANCE, text))
        return statements()
    }

    /** What Execute runs at a caret at [offset]; the model also lists the whole file, which is left out. */
    private fun caretStatements(file: PsiFile, offset: Int): List<String> =
        SqlPsiFacade.getInstance(project).createScriptModel(file).subModel(ScriptModel.PositionRange(offset)).statements()
            .map { it.query() }.filter { it != file.text }.map { it.trim() }.toList()

    fun testCoreRangesAreTheCorpusStatements() {
        for (case in CHOUSE_BOUNDARY_CORPUS) {
            val ranges = SqlScriptSnapshot.parse(case.sql, "clickhouse").statements.filter { it.hasSql }
            assertEquals(case.name, case.statements, ranges.map { case.sql.substring(it.start, it.end).trim() })
            assertTrue(case.name, ranges.all { it.boundaryError == null })
        }
    }

    fun testEveryCorpusStatementIsOnePsiStatement() {
        for (case in CHOUSE_BOUNDARY_CORPUS) {
            configureCHouse(case.sql)
            assertEquals(case.name, case.expectedRanges(), statements().map { it.textRange })
        }
    }

    /** The platform ClickHouse dialect splits these; the family keeps each whole. */
    fun testTheStockDialectSplitsTheOwnersExport() {
        val split = CHOUSE_BOUNDARY_CORPUS.filter { case -> stockStatements(case.sql).map { it.textRange } != case.expectedRanges() }.map { it.name }
        for (name in listOf("owner s3 export", "owner s3 export as a table function", "owner s3 export without settings",
            "partitioned insert with comments", "partitioned file and remote functions")) {
            assertTrue("$name in $split", name in split)
        }
        // The owner's export splits before its SELECT line.
        val stock = stockStatements(OWNER_S3_EXPORT)
        assertEquals(listOf(TextRange(0, OWNER_S3_EXPORT.indexOf("\nSELECT")), TextRange(OWNER_S3_EXPORT.indexOf("SELECT *"), OWNER_S3_EXPORT.length - 1)),
            stock.map { it.textRange })
    }

    fun testTheOwnersExportIsOneLenientStatementWithoutErrors() {
        configureCHouse(OWNER_S3_EXPORT + "\nSELECT 1 FROM t;")
        val (export, next) = statements()
        assertEquals(OWNER_S3_EXPORT.removeSuffix(";"), export.text)
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, export.node.elementType)
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        // The next statement keeps the grammar's PSI.
        assertEquals("SELECT 1 FROM t", next.text)
        assertNotSame(SqlCompositeElementTypes.SQL_STATEMENT, next.node.elementType)
    }

    fun testStatementsTheGrammarParsesKeepTheirPsi() {
        configureCHouse("INSERT INTO FUNCTION null('id UInt64') SELECT id FROM events;\nSELECT 1 /* ; */ FROM t;;\nSELECT 2")
        assertEquals(listOf("INSERT INTO FUNCTION null('id UInt64') SELECT id FROM events", "SELECT 1 /* ; */ FROM t", "SELECT 2"), statements().map { it.text })
        assertNotNull(PsiTreeUtil.findChildOfType(statements().first(), SqlInsertDmlInstruction::class.java))
    }

    fun testExecuteAtCaretTakesTheWholeStatement() {
        val text = "SELECT 0;\n$OWNER_S3_EXPORT\nSELECT 1;"
        val export = OWNER_S3_EXPORT.removeSuffix(";")
        configureCHouse(text)
        for (caret in listOf("FUNCTION s3", "PARTITION BY", "SELECT *", "max_threads", "parallel_encoding")) {
            assertEquals(caret, listOf(export), caretStatements(myFixture.file, text.indexOf(caret)))
        }
        assertEquals(listOf("SELECT 0", export, "SELECT 1"),
            SqlPsiFacade.getInstance(project).createScriptModel(myFixture.file).statements().map { it.query().trim() }.toList())
        // Stock runs the SELECT part on its own.
        stockStatements(text)
        assertEquals(listOf(export.substring(export.indexOf("SELECT *"))), caretStatements(myFixture.file, text.indexOf("max_threads")))
    }

    /**
     * Statements the grammar reads cleanly without a `;` between them stay apart, as in the stock
     * dialect, and Execute at the caret runs each. Stock ClickHouse itself reads `SELECT 1\nSELECT 2`
     * as one statement (`SELECT` aliasing `1`), and so does the family.
     */
    fun testCleanStatementsWithoutASemicolonStayAsInStock() {
        val split = listOf("DROP TABLE t\nSELECT 1", "SELECT 1 FROM t LIMIT 1\nSELECT 2;", "TRUNCATE TABLE t\nINSERT INTO t VALUES (1)",
            "SELECT 1 FROM t ORDER BY a\nSELECT 2 FROM u;\nSELECT 3")
        for (text in split + "SELECT 1\nSELECT 2") {
            val stock = stockStatements(text).map { it.text }
            val stockCaret = caretStatements(myFixture.file, text.length - 1)
            configureCHouse(text)
            assertEquals(text, stock, statements().map { it.text })
            assertEquals(text, stockCaret, caretStatements(myFixture.file, text.length - 1))
            if (text in split) assertEquals(text, stock.last(), stockCaret.first())
            assertEquals(text, text in split, stock.size > 1)
        }
    }

    /**
     * A grammar error before the split means the grammar cannot be trusted to have ended the
     * statement there, so the core range, which the server also reads as one query, is one
     * statement: the clean SELECT after it joins it.
     */
    fun testAnErroneousStatementWithoutASemicolonAbsorbsTheCleanOneAfterIt() {
        val text = "SELECT 1 FROM t WHERE a = = 1\nSELECT 2;\nSELECT 3;"
        assertEquals(listOf("SELECT 1 FROM t WHERE a = = 1", "SELECT 2", "SELECT 3"), stockStatements(text).map { it.text })
        configureCHouse(text)
        assertEquals(listOf("SELECT 1 FROM t WHERE a = = 1\nSELECT 2", "SELECT 3"), statements().map { it.text })
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, statements().first().node.elementType)
    }

    fun testABoundaryCoreCannotProveKeepsTheGrammarsStatements() {
        // An unclosed parenthesis while typing: core cannot place the boundary, so the grammar's statements stay.
        val text = "SELECT (1;\nSELECT 2;"
        val stock = stockStatements(text).map { it.textRange }
        configureCHouse(text)
        assertEquals(stock, statements().map { it.textRange })
        assertEquals(2, stock.size)
    }
}
