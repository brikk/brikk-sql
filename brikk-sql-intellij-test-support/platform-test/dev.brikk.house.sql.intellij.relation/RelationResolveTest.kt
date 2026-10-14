package dev.brikk.house.sql.intellij.relation

import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate

import com.intellij.database.symbols.DasColumnSymbol
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlSelectClause

/** Light column symbols for provider columns, and resolve-inspection suppression only where the analysis decides. */
class RelationResolveTest : RelationTestCase() {
    private fun resolved(text: String, name: String): Any? {
        open(text)
        return myFixture.file.findReferenceAt(myFixture.file.text.indexOf(name))?.resolve()
    }

    fun testAProviderColumnResolvesToALightSymbol() {
        val sql = "SELECT e.id FROM events() e"
        assertNull(resolved(sql, "id"))
        registerPgRelations(testRootDisposable)
        val symbol = resolved(sql, "id") as DasColumnSymbol
        assertEquals("id", symbol.name)
        assertEquals(symbol, myFixture.file.findReferenceAt(sql.indexOf("id"))?.resolve())
    }

    fun testPendingAndPlatformResolvedFactsGiveNoSymbols() {
        registerPgRelations(testRootDisposable)
        pgRelations.fake.pending = true
        assertNull(resolved("SELECT e.id FROM events() e", "id"))
        pgRelations.fake.pending = false
        pgRelations.fake.platformResolves = true
        assertNull(resolved("SELECT e.id FROM events() e", "id"))
    }

    fun testOnlyReferencesTheAnalysisDecidesLoseTheResolveInspection() {
        enableResolveInspection()
        val sql = "SELECT e.id, e.nope, x.y FROM events() e, nowhere x;"
        open(sql)
        val platform = reports().filter { it.second?.startsWith("Un") == true }.map { it.first }
        assertSameElements(platform, "id", "nope", "y", "events", "nowhere")
        registerPgRelations(testRootDisposable)
        open(sql)
        // The slot, its resolved column, the missing column the analysis reports and the column of the
        // unknown table are decided; the unknown table stays.
        val reported = reports()
        assertSameElements(reported.map { it.first }, "nowhere", "nope")
        assertEquals("only the analysis reports the missing column", 1, reported.count { it.first == "nope" })
    }

    /** What the platform resolves the [nth] [word] of the open file to. */
    private fun targets(word: String, nth: Int = 0): List<PsiElement?> {
        val offset = Regex("\\b$word\\b").findAll(myFixture.file.text).elementAt(nth).range.first
        val reference = myFixture.file.findReferenceAt(offset) as PsiPolyVariantReference
        return reference.multiResolve(false).map { it.element }
    }

    /** The platform follows the CTE or derived table to the inner reference's symbol; the extension adds none of its own. */
    fun testAColumnPassedOnByACteOrDerivedTableGetsNoSecondSymbol() {
        registerPgRelations(testRootDisposable)
        for (sql in listOf("WITH c AS (SELECT e.id FROM events() e) SELECT c.id FROM c", "SELECT t.id FROM (SELECT e.id FROM events() e) t", "SELECT id FROM (SELECT e.id FROM events() e) t")) {
            open(sql)
            val outer = if (sql.startsWith("SELECT t") || sql.startsWith("SELECT id")) 0 else 1
            val inner = 1 - outer
            assertTrue(sql, targets("id", inner).single() is DasColumnSymbol)
            assertTrue("$sql: ${targets("id", outer)}", targets("id", outer).size <= 1)
        }
    }

    fun testOneProviderColumnIsOneSymbolWhateverItsAlias() {
        registerPgRelations(testRootDisposable)
        open("SELECT a.id, b.id FROM events() a CROSS JOIN events() b")
        assertEquals(targets("id", 0), targets("id", 1))
    }

    fun testTypeAndSignatureInspectionsAreSuppressedOnlyOnDecidedReferencesOwnElements() {
        registerPgRelations(testRootDisposable)
        val sql = "SELECT e.id, e.nope, lower('a') FROM events() e"
        open(sql)
        val suppressor = PgRelationSuppressor()
        fun at(word: String) = checkNotNull(myFixture.file.findElementAt(sql.indexOf(word)))
        val slot = PsiTreeUtil.getParentOfType(at("events"), SqlFunctionCallExpression::class.java)
        val other = PsiTreeUtil.getParentOfType(at("lower"), SqlFunctionCallExpression::class.java)
        for (tool in listOf("SqlTypeInspection", "SqlSignatureInspection", "SqlResolveInspection")) {
            assertTrue(tool, suppressor.isSuppressedFor(at("id"), tool))
            assertTrue(tool, suppressor.isSuppressedFor(checkNotNull(slot), tool))
            assertFalse(tool, suppressor.isSuppressedFor(checkNotNull(other), tool))
            assertFalse(tool, suppressor.isSuppressedFor(checkNotNull(PsiTreeUtil.getParentOfType(at("id"), SqlSelectClause::class.java)), tool))
        }
        // An absent column is the analysis's to report for resolve only.
        assertTrue(suppressor.isSuppressedFor(at("nope"), "SqlResolveInspection"))
        assertFalse(suppressor.isSuppressedFor(at("nope"), "SqlTypeInspection"))
        assertFalse(suppressor.isSuppressedFor(at("id"), "SqlNoDataSourceInspection"))
    }

    fun testSuppressionStaysInItsStatementAndOffMisspelledRelationNames() {
        enableResolveInspection()
        registerPgRelations(testRootDisposable)
        open("SELECT e.nope FROM events() e; SELECT z.bad FROM eventz() z; SELECT q.missing FROM unknown_table q")
        val marks = reports().map { it.first }
        assertTrue(marks.toString(), marks.containsAll(listOf("nope", "eventz", "unknown_table")))
        // Columns of an unknown relation are not known absent: only its name is reported.
        assertTrue(marks.toString(), marks.none { it == "bad" || it == "missing" })
    }

    fun testAQualifierNamingARelationOfItsQueryIsQuietAndAnyOtherStays() {
        enableResolveInspection()
        registerPgRelations(testRootDisposable)
        val marks = unresolvedIn("SELECT unknown_table.c FROM unknown_table; SELECT stray.c FROM unknown_table")
        // The table's name is reported where it is named, not again where it qualifies a column.
        assertEquals(marks.toString(), 2, marks.count { it == "unknown_table" })
        assertTrue(marks.toString(), "stray" in marks)
    }

    fun testARowFieldOverAnUnknownRelationIsQuietWhereADotReadsAField() {
        enableResolveInspection()
        // Analyzed as DuckDB, where r.member may read a field of a ROW column of the unknown table.
        val duckdb = TestRelationProfile(postgres, "duckdb")
        SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(object : ExternalRelationInspectionSuppressor(duckdb) {}, testRootDisposable)
        val marks = unresolvedIn("SELECT r.member FROM unknown_table")
        assertTrue(marks.toString(), marks.none { it == "r" || it == "member" })
        assertTrue(marks.toString(), "unknown_table" in marks)
    }

    fun testColumnsOfAStatementCoreCannotReadAreQuietOverUnknownRelations() {
        enableResolveInspection()
        // Analyzed as MySQL, which cannot read PostgreSQL's ~ operator: no analysis covers the statement.
        val mysqlRead = TestRelationProfile(postgres, "mysql")
        SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(object : ExternalRelationInspectionSuppressor(mysqlRead) {}, testRootDisposable)
        val marks = unresolvedIn("SELECT x FROM unknown_table WHERE x ~ 'a'")
        assertTrue(marks.toString(), "x" !in marks)
        assertTrue(marks.toString(), "unknown_table" in marks)
    }

    fun testATablesOwnClausesNamingAnUndeclaredColumnStayReported() {
        enableResolveInspection()
        registerPgRelations(testRootDisposable)
        val marks = unresolvedIn("CREATE TABLE t2 (k BIGINT, v INT, PRIMARY KEY (nope), UNIQUE (v, missing))")
        assertTrue(marks.toString(), marks.containsAll(listOf("nope", "missing")))
        assertTrue(marks.toString(), marks.none { it == "k" || it == "v" })
    }

    /** What the resolve inspection reports in [sql] once the relation features are on. */
    private fun unresolvedIn(sql: String): List<String> {
        open(sql)
        return reports().filter { it.second?.startsWith("Un") == true }.map { it.first }
    }

    fun testUnqualifiedColumnsOverAnAliasedSlotResolveAndLoseTheResolveInspection() {
        enableResolveInspection()
        registerPgRelations(testRootDisposable)
        for (sql in listOf("SELECT id FROM events() e", "SELECT id FROM events() AS e", "SELECT * FROM events() e WHERE kind IS NULL")) {
            assertEmpty(sql, unresolvedIn(sql))
            val word = if ("kind" in sql) "kind" else "id"
            assertTrue(sql, targets(word).single() is DasColumnSymbol)
        }
        // Ambiguous between two aliases: both symbols, and the analysis reports it, not the platform.
        assertEmpty(unresolvedIn("SELECT id FROM events() a, events() b"))
        assertTrue(targets("id").isNotEmpty() && targets("id").all { it is DasColumnSymbol })
    }

    fun testColumnsASelectStarPassesOnFromASlotResolve() {
        enableResolveInspection()
        registerPgRelations(testRootDisposable)
        for ((sql, nth) in listOf(
            "SELECT s.id FROM (SELECT * FROM events()) s" to 0,
            "WITH c AS (SELECT * FROM events()) SELECT c.id FROM c" to 0,
            "WITH c AS (SELECT * FROM events()) SELECT id FROM c" to 0,
        )) {
            assertEmpty(sql, unresolvedIn(sql))
            assertTrue(sql, targets("id", nth).single() is DasColumnSymbol)
        }
    }
}
