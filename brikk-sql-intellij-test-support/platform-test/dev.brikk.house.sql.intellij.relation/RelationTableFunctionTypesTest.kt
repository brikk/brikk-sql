package dev.brikk.house.sql.intellij.relation

import com.intellij.database.types.DasTableType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlTableType
import dev.brikk.house.sql.intellij.completion.configureDialect
import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts

/** A table function call's columns as the platform's table type: known ones typed, any other call open. */
class RelationTableFunctionTypesTest : RelationTestCase() {
    private val clickhouseRelations by lazy { TestRelationProfile(checkNotNull(com.intellij.lang.Language.findLanguageByID("ClickHouse")), "clickhouse") }

    /** The table type of the call named [name] in the open file, under [profile]. */
    private fun typeOf(name: String, profile: TestRelationProfile = pgRelations): SqlTableType? =
        RelationTableTypes.ofTableFunction(PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single { it.nameElement?.name == name }, profile)

    private fun SqlTableType?.columns() = this?.let { type -> (0 until type.columnCount).map { type.getColumnName(it) to type.getColumnDasType(it).specification } }

    fun testAnUnknownCallsColumnsAreOpen() {
        open("SELECT * FROM somewhere(1, 'x') AS t")
        assertEquals(0, (typeOf("somewhere") as DasTableType).columnCount)
    }

    fun testACallTheFactsKnowHasTheirColumns() {
        pgRelations.fake.facts = RelationFacts(calls = mapOf("recent" to RelationFact(listOf(ColumnFact("a", sqlType = "integer"), ColumnFact("b", sqlType = "text")))))
        open("SELECT * FROM recent(7) AS t")
        assertEquals(listOf("a", "b"), typeOf("recent").columns()?.map { it.first })
    }

    fun testMetadataGivesATableFunctionsFixedColumns() {
        pgRelations.providers = listOf(TableFunctionFacts { CLICKHOUSE_METADATA.default })
        open("SELECT * FROM numbers(10) AS t")
        assertEquals(listOf("number"), typeOf("numbers").columns()?.map { it.first })
        open("SELECT * FROM somewhere(10) AS t")
        assertEquals(0, typeOf("somewhere")?.columnCount)
    }

    fun testAClickhouseStructureArgumentDeclaresTypedColumns() {
        clickhouseRelations.providers = emptyList()
        for (call in listOf(
            "s3('https://example.invalid/object', 'TSV', 'field_a UInt64, field_b String')",
            "s3(collection_demo, structure = 'field_a UInt64, field_b String')",
            "file('x.csv', 'CSV', 'field_a UInt64, field_b String')",
        )) {
            myFixture.configureDialect("ClickHouse", "SELECT * FROM $call AS t<caret>")
            val columns = typeOf(call.substringBefore('('), clickhouseRelations).columns()
            assertEquals(call, listOf("field_a", "field_b"), columns?.map { it.first })
            assertTrue("$call: $columns", columns.orEmpty().none { it.second.isBlank() })
        }
        myFixture.configureDialect("ClickHouse", "SELECT * FROM s3('https://example.invalid/object', 'TSV') AS t<caret>")
        assertEquals(0, typeOf("s3", clickhouseRelations)?.columnCount)
    }
}
