package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.database.util.Casing
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.testFramework.LightVirtualFile
import dev.brikk.house.sql.intellij.fixture.FixtureDialect
import dev.brikk.house.sql.intellij.fixture.FixtureHighlighterFactory
import dev.brikk.house.sql.intellij.fixture.FixtureParserDefinition
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

/** Over a model whose top level is catalogs, does `catalog.schema.table` resolve from a file of each dialect family? */
class CatalogImportsTest : AutoIntrospectTestCase() {
    override fun setUp() {
        super.setUp()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FixtureDialect, FixtureParserDefinition(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(FixtureDialect, FixtureHighlighterFactory(), testRootDisposable)
    }

    /** What `loaded.web.orders` resolves to from a file of [dialect] attached to the data source's console, the console in [current]. */
    private fun resolved(dialect: SqlLanguageDialect, current: String? = null): String? {
        val text = "SELECT * FROM loaded.web.orders"
        if (dialect == FixtureDialect) {
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("imports-${UUID.randomUUID()}.sql", dialect, text))
        } else {
            myFixture.configureByText("imports-${UUID.randomUUID()}.sql", text)
            setSqlDialectMapping(project, myFixture.file.virtualFile, dialect, testRootDisposable)
        }
        return RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            current?.let { recording.console.setSearchPath(SearchPath.of(ObjectPath.create(it, ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA))) }
            myFixture.file.findReferenceAt(myFixture.file.text.indexOf("orders") + 1)?.resolve()?.toString()
        }
    }

    /**
     * The gap, behaviorally: a MySQL-family file does not resolve a catalog head. (Doris's own model, which
     * MySQL resolution walks, proves the fix in Doris's suite; this PostgreSQL-family model does not.)
     */
    fun testMysqlFamilyDoesNotResolveCatalogHeads() {
        assertNull(resolved(MysqlDialect.INSTANCE))
    }

    /** Every catalog of the attached data sources becomes importable; the base imports stay. */
    fun testAllCatalogsImportsEveryCatalogUnderTheDataSources() {
        val base = CatalogScopes.select(levels(), listOf("loaded", "web"))
        val imports = CatalogImports.allCatalogs(base, arrayOf(ObjectName.plain("one"), ObjectName.plain("two")))
        val dataSources = checkNotNull(imports.root.getGroup(SqlImportUtil.DATA_SOURCE)?.children)
        assertEquals(listOf("one", "two"), dataSources.flatMap { node -> node.naming.names.map { it.name } })
        for (dataSource in dataSources) {
            val catalogs = checkNotNull(dataSource.getGroup(ObjectKind.DATABASE)?.children).single()
            assertTrue("any catalog", catalogs.naming.matches(ObjectName.plain("whatever"), Casing.EXACT))
            assertNull("but not its schemas", catalogs.getGroup(ObjectKind.SCHEMA))
        }
        val kept = checkNotNull(imports.root.getGroup(ObjectKind.DATABASE)?.children).single()
        assertTrue("the base imports stay", kept.naming.matches(ObjectName.plain("loaded"), Casing.EXACT) && kept.getGroup(ObjectKind.SCHEMA) != null)
    }

    /** PostgreSQL-family dialects (Trino's and DuckDB's are) need no catalog imports. */
    fun testPostgresFamilyAlreadyResolvesCatalogHeads() {
        for (dialect in listOf(PgDialect.INSTANCE, FixtureDialect)) {
            assertEquals(dialect.id, "orders: table", resolved(dialect))
            assertEquals(dialect.id, "orders: table", resolved(dialect, current = "memory"))
        }
    }

    fun testWithoutDataSourceNamesTheBaseImportsStay() {
        val base = CatalogScopes.select(levels(), listOf("loaded"))
        assertSame(base, CatalogImports.allCatalogs(base, emptyArray()))
        assertSame(base, CatalogImports.allCatalogs(base, arrayOf<ObjectName?>(null)))
        assertSame(base, CatalogImports.allCatalogs(base, null))
    }
}
