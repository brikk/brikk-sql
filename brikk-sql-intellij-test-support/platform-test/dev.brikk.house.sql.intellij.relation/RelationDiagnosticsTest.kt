package dev.brikk.house.sql.intellij.relation

import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.HighlightSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts

/** The analysis's problems at exact ranges, in plain files and injected fragments, per revision. */
class RelationDiagnosticsTest : RelationTestCase() {
    fun testTrinoIntervalTypeDoesNotCrashHighlighting() {
        trinoRelations.reset()
        LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, TrinoRelationAnnotator(), testRootDisposable)
        open("SELECT CAST(NULL AS INTERVAL DAY(3) TO SECOND(12)) AS v")
        assertEmpty(relationDiagnostics(myFixture.file, trinoRelations))
        myFixture.doHighlighting(HighlightSeverity.WARNING)
    }

    private fun marks(): List<Pair<String, HighlightSeverity>> {
        val document = myFixture.editor.document
        // A fresh pass, as a provider's restart gives when its facts change without an edit.
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        return myFixture.doHighlighting(HighlightSeverity.WARNING)
            .filter { it.description?.contains("column", ignoreCase = true) == true || it.description?.contains("qualifier", ignoreCase = true) == true }
            .map { document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) to it.severity }
    }

    fun testProblemsAreMarkedAtTheirNames() {
        registerPgRelations(testRootDisposable, resolve = false, suppressor = false)
        open("SELECT e.nope, p.other FROM events() e, partial() p;\nFROM events() |> SELECT missing")
        val marks = marks().filter { (text, _) -> text in setOf("nope", "other", "missing") }
        assertSameElements(marks, "nope" to HighlightSeverity.ERROR, "other" to HighlightSeverity.WARNING, "missing" to HighlightSeverity.ERROR)
    }

    fun testTheDiagnosticsAreCoreDataForOtherConsumers() {
        open("SELECT e.nope FROM events() e")
        val diagnostic = relationDiagnostics(myFixture.file, pgRelations).single()
        assertEquals("nope", myFixture.file.text.substring(diagnostic.start, diagnostic.end))
        assertEquals(DiagnosticSource.SCOPE, diagnostic.source)
    }

    fun testAnInjectedFragmentIsMarkedAtItsHostText() {
        LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, PgRelationAnnotator(), testRootDisposable)
        // Doubled quotes before the error shift unescaped offsets.
        openInjected("SELECT 'SELECT ''x'', e.nope FROM events() e'")
        assertEquals(listOf("nope" to HighlightSeverity.ERROR), marks())
    }

    fun testAResultIsReusedUntilAProviderRevisionChanges() {
        val sql = "SELECT e.later FROM events() e"
        open(sql)
        assertEquals(listOf("later"), relationDiagnostics(myFixture.file, pgRelations).map { sql.substring(it.start, it.end) })
        val withLater = RelationFacts(slots = mapOf("events" to RelationFact(listOf(ColumnFact("later")))))
        pgRelations.fake.facts = withLater
        assertEquals("the same revision reuses the analysis", 1, relationDiagnostics(myFixture.file, pgRelations).size)
        pgRelations.fake.revision++
        assertEmpty(relationDiagnostics(myFixture.file, pgRelations))
    }

    fun testPendingFactsAreNeitherAnalyzedNorCached() {
        open("SELECT e.nope FROM events() e")
        pgRelations.fake.pending = true
        assertEmpty(relationDiagnostics(myFixture.file, pgRelations))
        pgRelations.fake.pending = false
        assertEquals(1, relationDiagnostics(myFixture.file, pgRelations).size)
    }

    fun testEachConsumerActsOnlyOnItsOwnLanguage() {
        LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, PgRelationAnnotator(), testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(mysql, MysqlRelationAnnotator(), testRootDisposable)
        // A PostgreSQL file whose string literal holds MySQL: each profile marks only its own SQL.
        openInjected("SELECT e.pgnope, 'SELECT e.mynope FROM events() e' FROM events() e", host = "PostgreSQL", injected = mysql)
        assertSameElements(marks().map { it.first }, "pgnope", "mynope")
        mysqlRelations.fake.pending = true
        assertEquals(listOf("pgnope"), marks().map { it.first })
    }

    fun testAnExplicitlyClosedEmptyCatalogStillReportsUnknownTables() {
        pgRelations.fake.facts = RelationFacts(catalog = CatalogKnowledge.CLOSED)
        open("SELECT * FROM misspelled")
        assertEquals(listOf("misspelled"), relationDiagnostics(myFixture.file, pgRelations).map { myFixture.file.text.substring(it.start, it.end) })
    }

    fun testReplacingAProviderWithoutAnEditInvalidates() {
        open("SELECT e.later FROM events() e")
        assertEquals(1, relationDiagnostics(myFixture.file, pgRelations).size)
        pgRelations.providers = listOf(FakeFacts(RelationFacts(slots = mapOf("events" to RelationFact(listOf(ColumnFact("later")))))))
        assertEmpty(relationDiagnostics(myFixture.file, pgRelations))
    }
}
