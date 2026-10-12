package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.database.Dbms
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.psi.SqlIdentifier
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

/** Unresolved references under the fixture model's childless catalogs `hive` and `memory`, from a PostgreSQL file attached to its console. */
class OutOfScopeTest : AutoIntrospectTestCase() {
    class Filter : OutOfScopeReferenceFilter(Service::class.java)
    class Intention : IntrospectNamespaceIntention(Service::class.java, "Fixture")

    private lateinit var recording: RecordingConsole

    override fun setUp() {
        super.setUp()
        useService(Profile(autoIntrospect = false, outOfScopeReferences = true))
    }

    override fun tearDown() {
        try {
            if (::recording.isInitialized) recording.close()
        } finally {
            super.tearDown()
        }
    }

    /** Opens [text] (with `<caret>` where wanted) as a PostgreSQL file attached to the data source's console. */
    private fun open(text: String, current: List<String> = emptyList()) {
        if (::recording.isInitialized) recording.close()
        myFixture.configureByText("scope-${UUID.randomUUID()}.sql", text)
        setSqlDialectMapping(project, myFixture.file.virtualFile, PgDialect.INSTANCE, testRootDisposable)
        recording = RecordingConsole(project, myFixture.file.virtualFile, source)
        if (current.isNotEmpty()) {
            val path = current.drop(1).fold(ObjectPath.create(current[0], ObjectKind.DATABASE)) { parent, name -> parent.append(name, ObjectKind.SCHEMA) }
            recording.console.setSearchPath(SearchPath.of(path))
        }
    }

    private fun classify(name: String, profile: CatalogProfile = service.profile): ReferenceScope {
        val offset = myFixture.file.text.indexOf(name).also { check(it >= 0) { "No $name" } }
        val reference = checkNotNull(PsiTreeUtil.getNonStrictParentOfType(myFixture.file.findElementAt(offset), SqlReferenceExpression::class.java))
        return OutOfScopeClassifier(profile).classify(reference)
    }

    private fun assertOutOfScope(expected: List<String>, actual: ReferenceScope) = assertOutOfScope("", expected, actual)

    private fun assertOutOfScope(message: String, expected: List<String>, actual: ReferenceScope) {
        val scope = actual as? ReferenceScope.OutOfScope ?: error("$message: $actual")
        assertSame(source, scope.dataSource)
        assertEquals(expected, scope.namespace.path)
    }

    fun testANameUnderAChildlessCatalogIsOutOfScope() {
        open("SELECT * FROM hive.web.orders")
        assertOutOfScope(listOf("hive"), classify("web"))
        assertOutOfScope(listOf("hive"), classify("orders"))
    }

    fun testANameUnderAnIntrospectedNamespaceDoesNotExist() {
        open("SELECT * FROM loaded.nope.orders")
        assertEquals(ReferenceScope.Nonexistent, classify("nope"))
        open("SELECT * FROM loaded.web.nope")
        assertEquals(ReferenceScope.Nonexistent, classify("nope"))
    }

    fun testWithoutAResolvingQualifierTheModelCannotTell() {
        open("SELECT * FROM nothere.web.orders")
        assertEquals(ReferenceScope.Unknown, classify("nothere"))
        assertEquals(ReferenceScope.Unknown, classify("web"))
    }

    fun testColumnsOfAnOutOfScopeTableAreOutOfScope() {
        open("SELECT o.x, y FROM hive.web.orders o")
        assertOutOfScope(listOf("hive"), classify("x"))
        assertOutOfScope(listOf("hive"), classify("y"))
        open("SELECT o.x FROM hive.web.orders o, loaded.web.orders l")
        assertOutOfScope("through its alias", listOf("hive"), classify("x"))
        open("SELECT orders.y FROM hive.web.orders")
        assertOutOfScope("through its table's name", listOf("hive"), classify("y"))
        open("SELECT (SELECT o.x FROM loaded.web.orders) FROM hive.web.orders o")
        assertOutOfScope("from an enclosing query", listOf("hive"), classify("x"))
    }

    /** Another relation's status is never taken: each of these is reported with the setting off, and stays. */
    fun testOtherReferencesDoNotTakeAnOutOfScopeTablesStatus() {
        val taken = listOf(
            "SELECT * FROM hive.web.orders, nothere.web.t" to "nothere",
            "SELECT zz.q FROM hive.web.orders o" to "zz",
            "SELECT zz.q FROM hive.web.orders o" to "q",
            "SELECT l.nope FROM loaded.web.orders l, hive.web.orders o" to "nope",
            "SELECT nope FROM loaded.web.orders, hive.web.orders" to "nope",
            "WITH c AS (SELECT 1 a) SELECT c.nope FROM c, hive.web.orders" to "nope",
            "SELECT d.nope FROM (SELECT 1 a) d, hive.web.orders" to "nope",
            "SELECT (SELECT nope FROM loaded.web.orders) FROM hive.web.orders" to "nope",
            "SELECT y FROM loaded.web.orders" to "y",
        ).filter { (text, name) ->
            open(text)
            classify(name) != ReferenceScope.Unknown
        }
        assertEmpty(taken)
    }

    fun testAnUnqualifiedTableIsInTheConsolesNamespace() {
        open("SELECT * FROM orders", current = listOf("hive", "web"))
        assertEquals("only where the dialect uses the console's namespace", ReferenceScope.Unknown, classify("orders"))
        val profile = Profile(usesCurrentNamespace = true, outOfScopeReferences = true)
        assertOutOfScope(listOf("hive"), classify("orders", profile))
        open("SELECT * FROM nope", current = listOf("loaded", "web"))
        assertEquals(ReferenceScope.Unknown, classify("nope", profile))
    }

    fun testOnlyNamespacesOfTheProfilesDbmsCount() {
        open("SELECT * FROM hive.web.orders")
        assertEquals(ReferenceScope.Unknown, classify("web", Profile(dbms = Dbms.MYSQL)))
    }

    private fun unresolved(): List<String> {
        // The platform's SQL resolution inspection, whose package differs between releases.
        val resolve = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
        myFixture.enableInspections(resolve.instantiateTool() as LocalInspectionTool)
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.inspectionToolId == "SqlResolveInspection" }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    fun testTheFilterHidesOnlyOutOfScopeReports() {
        HighlightInfoFilter.EXTENSION_POINT_NAME.point.registerExtension(Filter(), testRootDisposable)
        open("SELECT o.x FROM hive.web.orders o, loaded.nope.t; SELECT * FROM nothere.web.orders")
        assertEquals(listOf("nope", "nothere"), unresolved())
        useService(Profile(outOfScopeReferences = false))
        open("SELECT o.x FROM hive.web.orders o")
        assertEquals("off until the dialect enables it", listOf("x", "web"), unresolved().sorted().reversed())
    }

    /** Another tool's error exactly on an out-of-scope name, such as a server diagnostic, stays. */
    fun testTheFilterKeepsOtherReportsOnTheSameName() {
        HighlightInfoFilter.EXTENSION_POINT_NAME.point.registerExtension(Filter(), testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(PgDialect.INSTANCE, Annotator { element, holder ->
            if (element is SqlIdentifier && element.text == "orders") holder.newAnnotation(HighlightSeverity.ERROR, "Server: no table orders").create()
        }, testRootDisposable)
        open("SELECT * FROM hive.web.orders")
        val reports = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description }
        assertTrue(reports.toString(), "Server: no table orders" in reports)
        assertEquals("the unresolved report is hidden", listOf("Server: no table orders"), reports)
    }

    /** Another inspection's report on a name, here an ambiguous column next to an out-of-scope table, stays. */
    fun testTheFilterKeepsAnAmbiguousColumn() {
        HighlightInfoFilter.EXTENSION_POINT_NAME.point.registerExtension(Filter(), testRootDisposable)
        orders.columns.createOrGet("x")
        checkNotNull(orders.schema).tables.createOrGet("items").columns.createOrGet("x")
        DbPsiFacade.getInstance(project).clearCaches()
        val ambiguous = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlAmbiguousColumnInspection") }
        myFixture.enableInspections(ambiguous.instantiateTool() as LocalInspectionTool)
        open("SELECT x FROM loaded.web.orders, loaded.web.items, hive.web.t")
        val reports = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { myFixture.file.text.substring(it.startOffset, it.endOffset) == "x" }
        assertTrue(reports.map { it.description }.toString(), reports.any { it.inspectionToolId?.contains("Ambiguous") == true })
    }

    fun testTheIntentionsFamilyNamesItsPlugin() {
        assertEquals("Fixture: introspect namespace", Intention().familyName)
    }

    fun testTheIntentionIntrospectsTheNamespace() {
        open("SELECT * FROM hive.we<caret>b.orders")
        val intention = Intention()
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Fixture: introspect 'hive'", intention.text)
        intention.invoke(project, myFixture.editor, myFixture.file)
        val refresh = nextRefresh()
        assertEquals("even with automatic introspection off", "hive", pending())
        assertTrue(selects(listOf("hive")))
        assertFalse("not while in flight", intention.isAvailable(project, myFixture.editor, myFixture.file))
        refresh.finish { throw java.sql.SQLException("offline") }
        awaitIdle()
        assertTrue("again at once after a failure", intention.isAvailable(project, myFixture.editor, myFixture.file))
        intention.invoke(project, myFixture.editor, myFixture.file)
        nextRefresh().finish { hive.schemas.createOrGet("web").tables.createOrGet("orders") }
        awaitIdle()
        assertFalse("resolved now", intention.isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testTheIntentionIsOfferedOnAColumnOfAnOutOfScopeTable() {
        open("SELECT o.<caret>x FROM hive.web.orders o")
        val intention = Intention()
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Fixture: introspect 'hive'", intention.text)
    }

    fun testTheIntentionIsNotOfferedForNamesThatDoNotExist() {
        val intention = Intention()
        open("SELECT * FROM loaded.no<caret>pe.orders")
        assertFalse(intention.isAvailable(project, myFixture.editor, myFixture.file))
        open("SELECT * FROM hive.we<caret>b.orders")
        useService(Profile(outOfScopeReferences = false))
        assertFalse("off until the dialect enables it", intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertNoRefresh()
    }
}
