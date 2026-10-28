package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.postgres.model.PgLocalTable
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.types.DasSimpleTypeClass
import com.intellij.database.types.DasType
import com.intellij.database.util.DasUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.RunAll
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.util.UUID

/** Physical tables of a PostgreSQL data source's model as relation facts: PIPE completion, folding, model changes. */
class DasRelationFactsTest : RelationTestCase() {
    private lateinit var source: LocalDataSource
    private lateinit var orders: PgLocalTable
    private val extra = mutableListOf<LocalDataSource>()

    private fun type(name: String) = object : DasType {
        override fun getTypeClass() = DasSimpleTypeClass(name)
        override fun getDescription() = name
        override fun getSpecification() = name
    }

    override fun setUp() {
        super.setUp()
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        val schema = (model.root as PgRoot).databases.createOrGet("shop").schemas.createOrGet("public")
        orders = schema.tables.createOrGet("orders")
        orders.columns.createOrGet("id").apply { storedType = type("integer"); setNotNull(true) }
        orders.columns.createOrGet("total").apply { storedType = type("numeric") }
        schema.tables.createOrGet("Mixed").columns.createOrGet("quoted_only").apply { storedType = type("text") }
        schema.tables.createOrGet("bare")
        source = LocalDataSource().apply {
            name = "relations ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("postgresql"))
            url = "jdbc:offline:relations"
            isAutoSynchronize = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        pgRelations.providers = listOf(DasRelationFacts(DialectDataSourceResolver(Dbms.POSTGRES)))
        registerPgRelations(testRootDisposable)
    }

    override fun tearDown() {
        RunAll(
            { myFixture.file?.virtualFile?.let { SqlResolveMappings.getInstance(project).setMapping(it, null) } },
            { (extra + source).forEach(LocalDataSourceManager.getInstance(project)::removeDataSource) },
            { DbPsiFacade.getInstance(project).clearCaches() },
            { super.tearDown() },
        ).run()
    }

    private fun offered(text: String, vararg names: String): List<Triple<String, String?, String?>> {
        open(text)
        return myFixture.completeBasic().orEmpty().filter { it.lookupString in names }
            .map { val p = LookupElementPresentation.renderElement(it); Triple(it.lookupString, p.tailText, p.typeText) }
    }

    fun testPhysicalColumnsFeedPipeCompletionWithTheirSqlTypes() {
        assertSameElements(offered("FROM shop.public.orders |> SELECT <caret>", "id", "total"),
            Triple("id", "  column (orders)", "integer"), Triple("total", "  column (orders)", "numeric"))
        assertEquals(2, offered("FROM orders |> WHERE <caret>", "id", "total").size)
    }

    fun testStoredNamesFoldAsPostgresqlFoldsThem() {
        assertEmpty(offered("FROM mixed |> SELECT <caret>", "quoted_only"))
        assertEquals(listOf("quoted_only"), offered("FROM \"Mixed\" |> SELECT <caret>", "quoted_only").map { it.first })
    }

    fun testDottedSchemaAndTableNamesKeepTheirComponentBoundaries() {
        val shop = checkNotNull(orders.parent?.parent)
        shop.schemas.createOrGet("sales.reports").tables.createOrGet("q.3").columns.createOrGet("revenue")
            .apply { storedType = type("integer") }
        DbPsiFacade.getInstance(project).clearCaches()
        assertEquals(listOf("revenue"), offered("FROM shop.\"sales.reports\".\"q.3\" |> SELECT <caret>", "revenue").map { it.first })
        open("SELECT revenue FROM shop.\"sales.reports\".\"q.3\"")
        val facts = DasRelationFacts(DialectDataSourceResolver(Dbms.POSTGRES)).facts(myFixture.file) as RelationFactsResult.Available
        assertTrue(facts.facts.tables.containsKey("shop.\"sales.reports\".\"q.3\""))
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("shop", ObjectKind.DATABASE).append("sales.reports", ObjectKind.SCHEMA)))
            val current = DasRelationFacts(DialectDataSourceResolver(Dbms.POSTGRES)).facts(myFixture.file) as RelationFactsResult.Available
            assertEquals(listOf("shop.\"sales.reports\""), current.facts.searchPath)
        }
    }

    fun testAColumnIsOfferedOnceInOrdinarySql() {
        assertEquals(1, offered("SELECT o.i<caret> FROM orders o", "id").size)
    }

    fun testADataSourceTableIsLeftToThePlatformsCompletion() {
        open("SELECT * FROM ord<caret>")
        assertTrue(myFixture.completeBasic().orEmpty().count { it.lookupString == "orders" } <= 1)
    }

    fun testTheFactsFollowTheModel() {
        assertEmpty(offered("FROM orders |> SELECT <caret>", "added"))
        orders.columns.createOrGet("added").apply { storedType = type("text") }
        DbPsiFacade.getInstance(project).clearCaches()
        assertEquals(listOf("added"), offered("FROM orders |> SELECT <caret>", "added").map { it.first })
    }

    fun testAFileWithoutADataSourceHasNoFacts() {
        LocalDataSourceManager.getInstance(project).removeDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        assertEmpty(offered("FROM orders |> SELECT <caret>", "id", "total"))
        LocalDataSourceManager.getInstance(project).addDataSource(source)
    }

    fun testATableWithoutIntrospectedColumnsProvesNoColumnAbsent() {
        open("SELECT b.id FROM bare b")
        assertEmpty(relationDiagnostics(myFixture.file, pgRelations))
        open("SELECT o.nope FROM orders o")
        assertEquals(1, relationDiagnostics(myFixture.file, pgRelations).size)
    }

    fun testSwitchingTheFilesDataSourceInvalidatesWithoutAnEdit() {
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        (model.root as PgRoot).databases.createOrGet("shop").schemas.createOrGet("public").tables.createOrGet("orders").columns.createOrGet("fresh")
        val second = LocalDataSource().apply {
            name = "relations ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("postgresql"))
            url = "jdbc:offline:relations"
            isAutoSynchronize = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(second)
        extra += second
        DbPsiFacade.getInstance(project).clearCaches()
        open("SELECT o.fresh FROM orders o")
        fun mapTo(dataSource: LocalDataSource) = SqlResolveMappings.getInstance(project)
            .setMapping(myFixture.file.virtualFile, TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(dataSource.uniqueId)))))
        mapTo(source)
        assertEquals(1, relationDiagnostics(myFixture.file, pgRelations).size)
        mapTo(second)
        assertEmpty(relationDiagnostics(myFixture.file, pgRelations))
    }

    fun testAnUnqualifiedTableIsTheConsoleSchemasOne() {
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        val shop = (model.root as PgRoot).databases.createOrGet("shop")
        for ((schema, only) in listOf("a_db" to "a_only", "b_db" to "b_only")) {
            val table = shop.schemas.createOrGet(schema).tables.createOrGet("orders")
            table.columns.createOrGet("id").apply { storedType = type("integer") }
            table.columns.createOrGet(only).apply { storedType = type("integer") }
        }
        val schemas = LocalDataSource().apply {
            name = "relations ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("postgresql"))
            url = "jdbc:offline:schemas"
            isAutoSynchronize = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(schemas)
        extra += schemas
        DbPsiFacade.getInstance(project).clearCaches()
        val sql = "SELECT b_only, a_only FROM orders"
        open(sql)
        fun reported(schema: String?): List<String> = RecordingConsole(project, myFixture.file.virtualFile, schemas).use { recording ->
            schema?.let { recording.console.setSearchPath(SearchPath.of(ObjectPath.create("shop", ObjectKind.DATABASE).append(it, ObjectKind.SCHEMA))) }
            relationDiagnostics(myFixture.file, pgRelations).map { sql.substring(it.start, it.end) }
        }
        assertEquals(listOf("a_only"), reported("b_db"))
        assertEquals(listOf("b_only"), reported("a_db"))
        // Without a current schema the table is ambiguous: nothing is reported, nothing is taken from the first schema.
        assertEmpty(reported(null))
    }

    fun testOnlyTheSameTablesColumnIsDecoratedAndAnotherSchemasIsDropped() {
        val other = checkNotNull(orders.parent?.parent).schemas.createOrGet("other").tables.createOrGet("orders").columns.createOrGet("id")
        DbPsiFacade.getInstance(project).clearCaches()
        open("FROM shop.public.orders |> SELECT <caret>")
        OtherCompletion.offer(myFixture.file, LookupElementBuilder.create(other, "id").withTailText(" other schema"), testRootDisposable)
        // The scope is fully known and reads only shop.public.orders: the other schema's table's column is not visible.
        val tails = myFixture.completeBasic().orEmpty().filter { it.lookupString == "id" }.map { LookupElementPresentation.renderElement(it).tailText }
        assertEquals(listOf("  column (orders)"), tails)
        open("FROM shop.public.orders |> SELECT <caret>")
        val same = DasUtil.getColumns(orders).single { it.name == "id" }
        OtherCompletion.offer(myFixture.file, LookupElementBuilder.create(same, "id").withTailText(" same table"), testRootDisposable)
        assertEquals(listOf("  column (orders)"), myFixture.completeBasic().orEmpty().filter { it.lookupString == "id" }.map { LookupElementPresentation.renderElement(it).tailText })
    }
}
