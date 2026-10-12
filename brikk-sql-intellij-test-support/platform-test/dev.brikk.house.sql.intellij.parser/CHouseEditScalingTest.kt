package dev.brikk.house.sql.intellij.parser

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.testFramework.LightVirtualFile
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase

class CHouseEditScalingTest : CHouseFixtureTestCase() {
    private val script = buildString {
        var row = 0
        while (length < 1_000_000) {
            append("SELECT a, b, toDate('2026-01-01') FROM db.t WHERE x = ").append(row++).append(";\n")
            if (row % 500 == 0) append("INSERT INTO t FORMAT CSV\n1,O'Brien\n2,\"x;y\"\n\n")
        }
    }

    /** Milliseconds to type a character in the middle of the open file and commit it. */
    private fun timeEdit(): Long {
        val document = myFixture.editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        myFixture.file.node.text
        val offset = document.text.indexOf("WHERE x = 16000") + 6
        val started = System.nanoTime()
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "y") }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        myFixture.file.node.text
        return (System.nanoTime() - started) / 1_000_000
    }

    /** Core's ranges are read once per parse, a window at a time; a 1 MB script edits about as fast as in stock. */
    fun testEditingAMegabyteScriptCostsAboutWhatStockDoes() {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("stock.sql", CHouseDialect.INSTANCE, script))
        val stock = (1..3).minOf { timeEdit() }
        configureCHouse(script)
        val family = (1..3).minOf { timeEdit() }
        assertTrue("stock $stock ms, family $family ms", family <= 3 * stock + 2_000)
    }
}
