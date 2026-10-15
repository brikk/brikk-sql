package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.database.types.DasTypeSystemBase
import com.intellij.icons.AllIcons
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.lang.PsiParser
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.psi.SqlCreateStatement
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlTableTypeBase
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.fixture.FixtureLexer
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.fixture.FixtureParser
import dev.brikk.house.sql.intellij.relation.TestRelationProfile
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import java.util.UUID

/** DuckDB's shape: a PostgreSQL-family dialect whose dbms falls back to PostgreSQL, with DuckDB's builtins. */
object BuiltinsFixtureDialect : PgFamilyDialect("BrikkBuiltinsFixtureSQL") {
    override fun getDbms(): Dbms = BuiltinsFixtureDbms.DBMS

    override val builtins by lazy { EngineBuiltins(BuiltinsFixtureCompletion, builtinsRelations) }
}

object BuiltinsFixtureCompletion : SqlCompletionProfile {
    override val language get() = BuiltinsFixtureDialect
    override val metadata = DUCKDB_METADATA
}

/** The relation analysis of fixture files, whose call facts stand for table-function layouts a plugin knows. */
val builtinsRelations = TestRelationProfile(BuiltinsFixtureDialect, "duckdb")

object BuiltinsFixtureDbms {
    val DBMS: Dbms = Dbms.create("BRIKK_BUILTINS_FIXTURE", "Brikk builtins fixture", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("brikkbuiltinsfixture"))
}

class BuiltinsFixtureParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = PgFamilyElementFactory()
    override fun createLexer(project: Project?): Lexer = FixtureLexer()
    override fun createParser(project: Project?): PsiParser = FixtureParser()
    override fun getFileNodeType(): IFileElementType = BUILTINS_FIXTURE_FILE
}

class BuiltinsFixtureHighlighterFactory : SqlSyntaxHighlighterFactory.Base(BuiltinsFixtureDialect)

private val BUILTINS_FIXTURE_FILE = SqlFileElementType("BRIKK_BUILTINS_FIXTURE_SQL_FILE", BuiltinsFixtureDialect)

/**
 * The engine's functions, types and reader rows in the platform's own resolution, typing and inspections, for a
 * PostgreSQL-family dialect: valid DuckDB SQL is clean, and unknown names and real layout mismatches still report.
 */
class PgFamilyBuiltinsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        builtinsRelations.fake.facts = RelationFacts()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(BuiltinsFixtureDialect, BuiltinsFixtureParserDefinition(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(BuiltinsFixtureDialect, BuiltinsFixtureHighlighterFactory(), testRootDisposable)
        val fallback = DbmsExtension.FallbackBean().apply {
            dbmsStr = BuiltinsFixtureDbms.DBMS.name
            fallbackDbmsStr = Dbms.POSTGRES.name
        }
        ExtensionPointName<DbmsExtension.FallbackBean>("com.intellij.database.extensionFallback").point.registerExtension(fallback, testRootDisposable)
        SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(PgFamilyInspectionSuppressor(), testRootDisposable)
        SqlResolveExtension.EP_NAME.point.registerExtension(PgFamilyResolveExtension(), testRootDisposable)
        for (name in listOf("SqlResolveInspection", "SqlTypeInspection", "SqlInsertValuesInspection")) {
            val tool = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".$name") }
            myFixture.enableInspections(tool.instantiateTool() as LocalInspectionTool)
        }
    }

    private fun reports(sql: String, severity: HighlightSeverity = HighlightSeverity.ERROR): List<String> {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("builtins-${UUID.randomUUID()}.sql", BuiltinsFixtureDialect, sql))
        check(myFixture.file.language == BuiltinsFixtureDialect)
        return myFixture.doHighlighting(severity).map { "${myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset))}: ${it.description}" }
    }

    fun testEngineTableFunctionsResolveInSelectAndCtas() {
        val calls = listOf("read_parquet('input.parquet')", "read_csv('input.csv')", "range(3)", "generate_series(1, 3)", "duckdb_tables()")
        val failures = calls.flatMap { call ->
            listOf("SELECT * FROM $call", "CREATE TABLE result_table AS SELECT * FROM $call").flatMap { sql -> reports(sql).map { "$sql -> $it" } }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    fun testEngineTypesAndAliasesResolveInDeclarationsAndCasts() {
        val failures = listOf("UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "UINT8", "UINT32", "INT8", "STRING", "INTEGER").flatMap { type ->
            listOf("CREATE TABLE type_probe (sample_value $type)", "SELECT CAST(0 AS $type)").flatMap { sql -> reports(sql).map { "$sql -> $it" } }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    fun testEngineScalarFunctionsResolve() {
        val found = reports("SELECT list_value(1, 2), abs(-1)")
        assertEmpty(found.joinToString("\n"), found)
    }

    fun testATableFunctionInAScalarPositionAndAScalarInAFromPositionStillReport() {
        assertTrue(reports("SELECT read_parquet('input.parquet')").any { it.startsWith("read_parquet:") })
        assertTrue(reports("SELECT * FROM list_value(1, 2)").any { it.startsWith("list_value:") })
    }

    /** [reports] but `LANGUAGE sql`, which the fixture's catalog lacks: the routines' languages are beside the point. */
    private fun definitionReports(sql: String) = reports(sql).filterNot { it == "sql: Unable to resolve language 'sql'" }

    /** The call of [name] in the open file. */
    private fun call(name: String) = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single { it.nameElement?.name == name }

    /** [call]'s name binds to its CREATE FUNCTION in the file alone: no engine placeholder, hidden or not. */
    private fun assertBindsToTheLocalDefinition(call: SqlFunctionCallExpression) {
        val results = checkNotNull(call.nameElement?.reference).multiResolve(false).map { it as SqlResolveResult }
        assertEquals(results.toString(), 1, results.size)
        val result = results.single()
        assertFalse(result.isHidden)
        assertFalse("${result.targetSymbol}", result.targetSymbol is BuiltinFunction)
        val definition = checkNotNull(PsiTreeUtil.getParentOfType(result.element, SqlCreateStatement::class.java, false)) { "${result.element}" }
        assertSame(myFixture.file, definition.containingFile)
        assertTrue(definition.text, definition.text.startsWith("CREATE FUNCTION ${call.nameElement?.name}("))
    }

    fun testALocalDefinitionOfAnEngineFunctionNameKeepsItsBindingAndType() {
        val sql = "CREATE FUNCTION list_value(a integer) RETURNS integer LANGUAGE sql AS 'SELECT 1';\n" +
            "CREATE FUNCTION my_local_fn(a integer) RETURNS integer LANGUAGE sql AS 'SELECT 1';\n" +
            "SELECT list_value(1), my_local_fn(1)"
        val found = definitionReports(sql)
        assertEmpty(found.joinToString("\n"), found)
        for (name in listOf("list_value", "my_local_fn")) assertBindsToTheLocalDefinition(call(name))
        val control = call("my_local_fn").dasType
        assertFalse(control.toString(), control == DasTypeSystemBase.UNKNOWN)
        assertEquals(control.specification, call("list_value").dasType.specification)
    }

    fun testALocalTableFunctionOfAnEngineFunctionNameKeepsItsRow() {
        val sql = "CREATE FUNCTION read_csv(p text) RETURNS TABLE (x integer, y text) LANGUAGE sql AS 'SELECT 1, ''a''';\n" +
            "SELECT x, y FROM read_csv('input.csv')"
        val found = definitionReports(sql)
        assertEmpty(found.joinToString("\n"), found)
        val call = call("read_csv")
        assertBindsToTheLocalDefinition(call)
        val row = call.dasType as SqlTableType
        assertEquals(listOf("x", "y"), (0 until row.columnCount).map(row::getColumnName))
        assertFalse("$row", row is SqlTableTypeBase.UnresolvedRefType)
    }

    fun testUnknownFunctionsAndTypesStillReport() {
        assertTrue(reports("SELECT * FROM not_a_builtin_reader('input')").toString(), reports("SELECT * FROM not_a_builtin_reader('input')").any { it.startsWith("not_a_builtin_reader:") })
        assertTrue(reports("SELECT not_a_builtin_scalar(1)").any { it.startsWith("not_a_builtin_scalar:") })
        assertTrue(reports("CREATE TABLE type_probe (sample_value not_a_builtin_type)").any { it.startsWith("not_a_builtin_type:") })
        assertTrue(reports("SELECT CAST(0 AS not_a_builtin_type)").any { it.startsWith("not_a_builtin_type:") })
    }

    /** The platform's INSERT value count reports in [sql]. */
    private fun countReports(sql: String): List<String> {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("builtins-${UUID.randomUUID()}.sql", BuiltinsFixtureDialect, sql))
        return myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.inspectionToolId?.removeSuffix("Inspection") == "SqlInsertValues" }
            .map { "${myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset))}: ${it.description}" }
    }

    private val destination = "CREATE TABLE dst (source_label VARCHAR, record_key UINTEGER, payload_text VARCHAR, status_text VARCHAR);"

    fun testAReaderRowStaysOpenForAnInsertSelect() {
        val sql = """
            CREATE TABLE insert_probe (source_label VARCHAR, record_key UINTEGER, payload_text VARCHAR, status_text VARCHAR);
            INSERT INTO insert_probe SELECT 'local' AS source_label, record_key::UINTEGER, * EXCLUDE(record_key) FROM read_parquet('input.parquet')
        """.trimIndent()
        val found = reports(sql, HighlightSeverity.WARNING)
        assertEmpty(found.joinToString("\n"), found)
    }

    fun testAWidthThatDependsOnAReaderRowIsNotCounted() {
        for (query in listOf(
            "SELECT 'local', r.* FROM read_parquet('input.parquet') AS r",
            "SELECT * FROM (SELECT * FROM read_parquet('input.parquet')) AS src",
            "SELECT 'local', src.* FROM (SELECT * FROM read_parquet('input.parquet')) AS src",
            "WITH src AS (SELECT * FROM read_parquet('input.parquet')) SELECT * FROM src",
        )) {
            val found = countReports("$destination INSERT INTO dst $query")
            assertEmpty("$query: $found", found)
        }
        // A known table's star within the target's width, beside an open row: still unknown.
        val withinTarget = countReports("CREATE TABLE dst (a INTEGER, b INTEGER, c INTEGER); CREATE TABLE known_src (x INTEGER, y INTEGER); INSERT INTO dst SELECT k.*, r.* FROM known_src AS k, read_parquet('input.parquet') AS r")
        assertEmpty(withinTarget.toString(), withinTarget)
        val statementWith = countReports("CREATE TABLE dst (a INTEGER, b INTEGER); WITH src AS (SELECT * FROM read_parquet('input.parquet')) INSERT INTO dst SELECT * FROM src")
        assertEmpty(statementWith.toString(), statementWith)
    }

    fun testAKnownWidthNextToAReaderStillCounts() {
        for ((sql, actual) in listOf(
            "CREATE TABLE dst (a INTEGER, b INTEGER); INSERT INTO dst SELECT * FROM (SELECT 1 AS a FROM read_parquet('synthetic.parquet')) AS src" to 1,
            "CREATE TABLE dst (a BIGINT, b BIGINT); INSERT INTO dst SELECT count(*) FROM read_parquet('synthetic.parquet')" to 1,
            "CREATE TABLE dst (a INTEGER, b INTEGER, c INTEGER); CREATE TABLE k (x INTEGER); INSERT INTO dst SELECT k.* FROM k, read_parquet('synthetic.parquet') AS r" to 1,
            "CREATE TABLE dst (a VARCHAR, b VARCHAR, c VARCHAR); INSERT INTO dst SELECT 'a', 'b' FROM read_parquet('synthetic.parquet')" to 2,
            "CREATE TABLE dst (a INTEGER, b INTEGER); WITH src AS (SELECT 1 AS a FROM read_parquet('synthetic.parquet')) INSERT INTO dst SELECT * FROM src" to 1,
            // An open star leaves the width unknown, but no less than the explicit items: two are already too many.
            "CREATE TABLE dst (a INTEGER); INSERT INTO dst SELECT 1, 2, * FROM read_parquet('synthetic.parquet')" to 2,
            // ... nor than a known table's star: known_src's two columns already overflow one target column,
            // given as the table or as an explicit target column list.
            "CREATE TABLE dst (a INTEGER); CREATE TABLE known_src (x INTEGER, y INTEGER); INSERT INTO dst SELECT k.*, r.* FROM known_src AS k, read_parquet('synthetic.parquet') AS r" to 2,
            "CREATE TABLE dst (a INTEGER, b INTEGER, c INTEGER); CREATE TABLE known_src (x INTEGER, y INTEGER); INSERT INTO dst (a) SELECT k.*, r.* FROM known_src AS k, read_parquet('synthetic.parquet') AS r" to 2,
        )) {
            val found = countReports(sql)
            assertTrue("$sql: $found", found.any { it.endsWith("got $actual") })
        }
    }

    fun testAKnownTableFunctionLayoutIsKeptAndItsMismatchReports() {
        builtinsRelations.fake.facts = RelationFacts(calls = mapOf("duckdb_tables" to RelationFact(listOf(ColumnFact("database_name", sqlType = "varchar"), ColumnFact("table_name", sqlType = "varchar")))))
        val clean = reports("CREATE TABLE names_probe (database_name VARCHAR, table_name VARCHAR); INSERT INTO names_probe SELECT * FROM duckdb_tables()", HighlightSeverity.WARNING)
        assertEmpty(clean.joinToString("\n"), clean)
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single()
        val columns = (call.dasType as SqlTableType).let { type -> (0 until type.columnCount).map(type::getColumnName) }
        assertEquals(listOf("database_name", "table_name"), columns)
        val mismatch = reports("CREATE TABLE one_probe (table_name VARCHAR); INSERT INTO one_probe SELECT * FROM duckdb_tables()", HighlightSeverity.WARNING)
        assertTrue(mismatch.toString(), mismatch.isNotEmpty())
    }

    fun testAKnownLayoutMismatchStillReports() {
        val found = reports("CREATE TABLE layout_probe (first_value INTEGER, second_value INTEGER); INSERT INTO layout_probe SELECT 1", HighlightSeverity.WARNING)
        assertTrue(found.toString(), found.isNotEmpty())
    }
}
