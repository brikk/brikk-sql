package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mysql.model.MysqlRoot
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.LanguageAnnotators
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.TestProfile
import dev.brikk.house.sql.intellij.testing.RealSqlContract
import dev.brikk.house.sql.intellij.testing.RealSqlFeature
import dev.brikk.house.sql.intellij.testing.RealSqlModel
import dev.brikk.house.sql.intellij.testing.RealSqlTarget
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

/**
 * The real-SQL contract on the library's substrates: PostgreSQL and MySQL files attached to a data source
 * holding [RealSqlModel.STANDARD] (and, on PostgreSQL, which has catalogs, each of [RealSqlModel.STATES]),
 * with the platform's resolve inspection, the shared relation features over the data source's tables and
 * the native-error annotator's local parse.
 */
class RealSqlContractTest : RelationTestCase() {
    private val sources = mutableListOf<LocalDataSource>()

    override fun tearDown() {
        RunAll(
            { sources.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource) },
            { DbPsiFacade.getInstance(project).clearCaches() },
            { super.tearDown() },
        ).run()
    }

    /** A data source of [dbms] holding [content]; MySQL has no catalogs, so its paths drop the first part. */
    private fun dataSource(dbms: Dbms, driver: String, content: RealSqlModel = RealSqlModel.STANDARD): LocalDataSource {
        val model = ModelFactory.BLACK_HOLE.createModel(dbms)
        val pg = model.root is PgRoot
        /** [path] as the model's levels see it. */
        fun levels(path: String) = path.split('.').let { if (pg) it else it.drop(1) }
        fun table(path: String) = levels(path).let { names ->
            when (val root = model.root) {
                is PgRoot -> root.databases.createOrGet(names[0]).schemas.createOrGet(names[1]).tables.createOrGet(names[2])
                is MysqlRoot -> root.schemas.createOrGet(names[0]).tables.createOrGet(names[1])
                else -> error("No model for $dbms")
            }
        }
        for ((path, columns) in content.tables) {
            val table = table(path)
            for (column in columns) table.columns.createOrGet(column)
        }
        for (path in content.bare) table(path)
        for (namespace in content.namespaces) {
            val names = levels("$namespace.x").dropLast(1)
            when (val root = model.root) {
                is PgRoot -> root.databases.createOrGet(names[0]).also { database -> names.getOrNull(1)?.let(database.schemas::createOrGet) }
                is MysqlRoot -> names.firstOrNull()?.let(root.schemas::createOrGet)
            }
        }
        val source = LocalDataSource().apply {
            name = "real sql ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
            url = "jdbc:offline:real-sql"
            isAutoSynchronize = false
            this.model = model
            // The platform resolves only what the introspection scope selects, as after a real introspection.
            val kinds = if (pg) listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA) else listOf(ObjectKind.SCHEMA)
            val namespaces = ((content.tables.keys + content.bare).map { levels(it).dropLast(1) } +
                content.namespaces.map { levels("$it.x").dropLast(1) }).filter { it.isNotEmpty() }.distinct()
            setIntrospectionScope(namespaces.map { CatalogScopes.select(kinds, it) }.reduce(CatalogScopes::widen))
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        sources += source
        return source
    }

    private inner class Target(
        override val dialect: String,
        override val features: Set<RealSqlFeature>,
        private val language: Language,
        private val dbms: Dbms,
        private val driver: String,
    ) : RealSqlTarget {
        private var source = dataSource(dbms, driver)

        override fun install(model: RealSqlModel) {
            console?.close()
            console = null
            LocalDataSourceManager.getInstance(project).removeDataSource(source)
            sources -= source
            source = dataSource(dbms, driver, model)
        }

        override val fixture: CodeInsightTestFixture get() = myFixture

        /** The open file's console, on the current namespace a user would pick. */
        private var console: RecordingConsole? = null

        override fun open(sql: String) {
            console?.close()
            val file = myFixture.configureByText("real-${UUID.randomUUID()}.sql", sql).virtualFile
            setSqlDialectMapping(project, file, language as SqlLanguageDialect, testRootDisposable)
            myFixture.configureFromExistingVirtualFile(file)
            console = RecordingConsole(project, file, source).also { recording ->
                val path = if (language == postgres) ObjectPath.create("shop", ObjectKind.DATABASE).append("sales", ObjectKind.SCHEMA)
                else ObjectPath.create("sales", ObjectKind.SCHEMA)
                recording.console.setSearchPath(SearchPath.of(path))
            }
        }

        fun close() {
            console?.close()
        }
    }

    /** The product's registrations for [profile]: relation features, local parse diagnostics and the resolve inspection. */
    private fun register(profile: TestRelationProfile, dbms: Dbms) {
        profile.providers = listOf(DasRelationFacts(DialectDataSourceResolver(dbms)))
        SqlResolveExtension.EP_NAME.point.registerExtension(object : ExternalRelationResolveExtension(profile) {}, testRootDisposable)
        SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(object : ExternalRelationInspectionSuppressor(profile) {}, testRootDisposable)
        GotoDeclarationHandler.EP_NAME.point.registerExtension(object : ExternalRelationGotoDeclarationHandler(profile) {}, testRootDisposable)
        // The daemon instantiates annotators by their class: these have no-arg constructors.
        val postgresql = profile.language == postgres
        LanguageAnnotators.INSTANCE.addExplicitExtension(profile.language, if (postgresql) PgRelationAnnotator() else MysqlRelationAnnotator(), testRootDisposable)
        ExternalLanguageAnnotators.INSTANCE.addExplicitExtension(
            profile.language, if (postgresql) PgLocalParseAnnotator() else MysqlLocalParseAnnotator(), testRootDisposable,
        )
        enableResolveInspection()
    }

    fun testPostgresql() {
        register(pgRelations, Dbms.POSTGRES)
        val features = setOf(
            RealSqlFeature.NATURAL_JOIN, RealSqlFeature.FULL_JOIN, RealSqlFeature.INTERSECT_EXCEPT, RealSqlFeature.NAMED_WINDOW,
            RealSqlFeature.UNNEST, RealSqlFeature.LATERAL_SUBQUERY, RealSqlFeature.VALUES_COLUMN_ALIASES, RealSqlFeature.CREATE_TABLE_AS,
            RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.DOUBLE_QUOTED_IDENTIFIERS,
        )
        val target = Target("postgres", features, postgres, Dbms.POSTGRES, "postgresql")
        try {
            RealSqlContract(target).check()
            RealSqlContract(target).checkModelStates()
        } finally {
            target.close()
        }
    }

    /** Whether `nope` in [sql] opened through [target] is an error. */
    private fun nopeReported(target: Target, sql: String): Boolean {
        target.open(sql)
        return reports(HighlightSeverity.ERROR).any { it.first == "nope" }
    }

    fun testThePlatformsMissesStandWhereItKnowsEverySourceAndNoneWhereItDoesNot() {
        // The analysis sees no data source; the platform resolves its tables.
        register(pgRelations, Dbms.POSTGRES)
        pgRelations.providers = emptyList()
        val target = Target("postgres", emptySet(), postgres, Dbms.POSTGRES, "postgresql")
        try {
            for (sql in listOf(
                "SELECT nope FROM orders",
                "SELECT o.nope FROM orders o JOIN users u ON u.user_id = o.user_id",
                "SELECT nope FROM (SELECT * FROM orders) d",
                "WITH c AS (SELECT * FROM orders) SELECT nope FROM c",
                // A table function the platform types.
                "$TYPED_FUNCTION SELECT r.nope, r.amount FROM recent_orders() r",
                "$TYPED_FUNCTION SELECT nope FROM (SELECT * FROM recent_orders()) d",
            )) assertTrue(sql, nopeReported(target, sql))
            // A source neither knows keeps every column quiet: listed without columns, absent, an untyped call, or mixed in.
            for (sql in listOf(
                "SELECT nope FROM archive",
                "SELECT nope FROM (SELECT * FROM archive) d",
                "SELECT nope FROM orders o, archive a",
                "WITH c AS (SELECT * FROM ghost) SELECT nope FROM c",
                "SELECT nope FROM orders o, events() e",
                "$TYPED_FUNCTION SELECT nope FROM recent_orders() r, archive a",
            )) assertFalse(sql, nopeReported(target, sql))
        } finally {
            target.close()
        }
    }

    fun testMysql() {
        register(mysqlRelations, Dbms.MYSQL)
        val features = setOf(
            RealSqlFeature.GROUP_BY_ALIAS, RealSqlFeature.NATURAL_JOIN, RealSqlFeature.INTERSECT_EXCEPT, RealSqlFeature.NAMED_WINDOW,
            RealSqlFeature.LATERAL_SUBQUERY, RealSqlFeature.CREATE_TABLE_AS, RealSqlFeature.BACKTICK_IDENTIFIERS,
        )
        val target = Target("mysql", features, mysql, Dbms.MYSQL, "mysql.8")
        try {
            RealSqlContract(target).check()
        } finally {
            target.close()
        }
    }
}

private const val TYPED_FUNCTION = "CREATE FUNCTION recent_orders() RETURNS TABLE (order_id int, amount int) AS $$ SELECT 1, 2 $$ LANGUAGE sql;\n"

private fun localParse(language: Language, dialect: String) = TestProfile(language, dialect, "Local parse").apply {
    validator = null
    localParser = true
}

private val pgLocalParse = localParse(postgres, "postgres")
private val mysqlLocalParse = localParse(mysql, "mysql")

class PgLocalParseAnnotator : NativeErrorAnnotator(pgLocalParse)

class MysqlLocalParseAnnotator : NativeErrorAnnotator(mysqlLocalParse)
