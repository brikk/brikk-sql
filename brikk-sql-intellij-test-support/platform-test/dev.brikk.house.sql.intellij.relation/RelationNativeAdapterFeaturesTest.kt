package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import java.util.UUID

/**
 * The shared features beside the native table-type adapter, as Doris wires them: completion decorates the
 * platform's items for the adapter's columns and hides those the analysis does not see; navigation follows
 * the analysis where the platform resolves a USING key to the other side.
 */
class RelationNativeAdapterFeaturesTest : RelationTestCase() {
    private val slots = RelationFacts(
        slots = mapOf(
            "src" to RelationFact(listOf(
                ColumnFact("id", displayType = "Long", nullability = Nullability.NON_NULL, provenance = "src.id"),
                ColumnFact("kind", displayType = "Kind", provenance = "src.kind"),
            ), provenance = "src"),
            "rhs" to RelationFact(listOf(
                ColumnFact("id", displayType = "Long", nullability = Nullability.NON_NULL, provenance = "rhs.id"),
                ColumnFact("code", displayType = "String", provenance = "rhs.code"),
            ), provenance = "rhs"),
        ),
        tables = mapOf("physical" to RelationFact(listOf(
            ColumnFact("id", "INTEGER", nullability = Nullability.NON_NULL, origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "physical.id"),
            ColumnFact("label", "VARCHAR", origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "physical.label"),
        ))),
    )

    private lateinit var declarations: Map<String, PsiFile>

    override fun setUp() {
        super.setUp()
        installNativeSlotTyping(testRootDisposable)
        registerPgRelations(testRootDisposable)
        pgRelations.fake.facts = slots
        declarations = listOf("src.id", "src.kind", "rhs.id").associateWith { myFixture.addFileToProject("$it.txt", it) }
        pgRelations.fake.targets = { _, provenance -> declarations[provenance] }
    }

    /** Every item named [names] offered at the caret in [text], as name and tail. */
    private fun offered(text: String, vararg names: String): List<Pair<String, String?>> {
        open(text)
        return myFixture.completeBasic().orEmpty().filter { it.lookupString in names }.map { it.lookupString to LookupElementPresentation.renderElement(it).tailText }
    }

    fun testASelfJoinsColumnsAreOfferedOncePerAlias() {
        assertSameElements(offered("SELECT i<caret> FROM src() e JOIN src() f ON e.id = f.id", "id"),
            "id" to "  declared column (e)", "id" to "  declared column (f)")
    }

    fun testAShadowedAliasOffersOnlyTheInnerRelationsColumns() {
        val items = offered("SELECT id FROM src() e WHERE EXISTS (SELECT 1 FROM physical e WHERE e.<caret>label IS NULL)", "id", "kind", "label")
        assertSameElements(items, "id" to "  column (physical)", "label" to "  column (physical)")
    }

    fun testColumnsASetOperationOrUsingMergesAreOfferedAsTheirOwn() {
        assertEquals(listOf("id" to "  computed column (UNION ALL)"), offered("SELECT id FROM src() UNION ALL SELECT id FROM rhs() ORDER BY i<caret>", "id"))
        assertEquals(listOf("id" to "  computed column (USING)"), offered("SELECT q.<caret> FROM (SELECT id FROM src() FULL JOIN rhs() USING (id)) q", "id"))
        assertEmpty(offered("SELECT id FROM src() UNION ALL SELECT id, code FROM rhs() ORDER BY i<caret>", "id"))
    }

    /** MySQL, which Doris's PSI is, with the adapter, the resolve extension and [mysqlRelations] over [slots]. */
    private fun mysqlAdapter() {
        installNativeSlotTyping(testRootDisposable, mysqlRelations)
        mysqlRelations.fake.facts = slots
        mysqlRelations.fake.targets = pgRelations.fake.targets
        SqlResolveExtension.EP_NAME.point.registerExtension(object : ExternalRelationResolveExtension(mysqlRelations) {}, testRootDisposable)
    }

    private fun openMysql(sql: String) {
        val file = myFixture.addFileToProject("mysql-${UUID.randomUUID()}.sql", sql).virtualFile
        setSqlDialectMapping(project, file, mysql as SqlLanguageDialect, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file)
        check(myFixture.file.language == mysql)
    }

    fun testALeftJoinsUsingKeyLeadsToTheLeftSide() {
        // MySQL, which Doris's PSI is, resolves the merged key to the right side's column (in 2026.1 and 2026.2).
        mysqlAdapter()
        val handler = object : ExternalRelationGotoDeclarationHandler(mysqlRelations) {}
        for (sql in listOf("SELECT id FROM src() e LEFT JOIN rhs() r USING (id) WHERE id > 0", "SELECT * FROM src() e LEFT JOIN rhs() r USING (id) WHERE id > 0")) {
            openMysql(sql)
            val offset = sql.lastIndexOf("id >")
            // The handler's targets lead; without any, the platform's resolution does.
            val targets = handler.getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)?.toList()
                ?: listOfNotNull(myFixture.file.findReferenceAt(offset)?.resolve()?.navigationElement)
            assertEquals(sql, listOf(declarations["src.id"]), targets)
        }
    }


    /** Where the handler leads from the [nth] [word] of MySQL [sql], as declaration file names or `sql@offset`; the platform does not answer. */
    private fun navigation(sql: String, word: String, nth: Int = 0): List<String> {
        openMysql(sql)
        val offset = Regex("\\b$word\\b").findAll(sql).elementAt(nth).range.first
        val handler = object : ExternalRelationGotoDeclarationHandler(mysqlRelations) {}
        return handler.getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor).orEmpty()
            .map { if (it is PsiFile) it.name.removeSuffix(".txt") else "sql@" + it.textRange.startOffset }
    }

    fun testNavigationFollowsTheAnalysisNotTheNativeResolver() {
        mysqlAdapter()
        // A NATURAL JOIN's merged key leads to the left side, as the analysis names it.
        assertEquals(listOf("src.id"), navigation("SELECT * FROM src() e NATURAL JOIN rhs() r WHERE id > 0", "id"))
        // A grouping alias leads through the alias to the column it names.
        assertEquals(listOf("src.kind"), navigation("SELECT kind AS k, count(*) AS n FROM src() GROUP BY k", "k", 1))
        // INTERSECT's named output is the first branch's column.
        assertEquals(listOf("src.id"), navigation("SELECT id AS first_name FROM src() INTERSECT SELECT id AS other_name FROM rhs() ORDER BY first_name", "first_name", 1))
        // UNION ALL's output has no single origin: both branches' items.
        val union = "SELECT id FROM src() UNION ALL SELECT id FROM rhs() ORDER BY id"
        assertEquals(listOf("sql@7", "sql@${union.indexOf("id", 30)}"), navigation(union, "id", 2))
        // A FULL JOIN's merged key, which no provider column names, leads to the USING key.
        val full = "SELECT q.id FROM (SELECT id FROM src() FULL JOIN rhs() USING (id)) q"
        assertEquals(listOf("sql@${full.lastIndexOf("id")}"), navigation(full, "id"))
        // A CTE's computed column leads to its declaration.
        val cte = "WITH c AS (SELECT 1 AS total) SELECT total FROM c"
        assertEquals(listOf("sql@${cte.indexOf("total")}"), navigation(cte, "total", 1))
    }
}
