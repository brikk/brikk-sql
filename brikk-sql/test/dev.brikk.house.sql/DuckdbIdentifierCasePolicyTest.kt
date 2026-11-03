package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.metadata.DuckdbIdentifierCase
import dev.brikk.house.sql.optimizer.normalizeIdentifiers
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbIdentifierCasePolicyTest {
    @Test fun explicitModeChangesOnlyUnquotedIdentifierSpellingAndLeavesTheSourceTreeAlone() {
        val sql="SELECT Mix AS OutName,\"QuotedCol\" AS \"QuotedAlias\",'NoSuch/Mixed/File.csv' AS Path FROM MySchema.MixedTable"
        val original=parseDuckdbNativeOne(sql)
        val originalSql=original.sql("duckdb")
        for(mode in DuckdbIdentifierCase.entries) {
            val folded=original.withDuckdbIdentifierCase(mode)
            val rendered=folded.sql("duckdb")
            assertEquals(originalSql,original.sql("duckdb"))
            assertEquals(listOf(mode.foldUnquoted("OutName"),"QuotedAlias",mode.foldUnquoted("Path")),folded.expressionsArg.filterIsInstance<Expression>().map { it.aliasOrName })
            assertTrue("'NoSuch/Mixed/File.csv'" in rendered)
            assertTrue("\"QuotedCol\"" in rendered && "\"QuotedAlias\"" in rendered)
            assertEquals(folded,Serde.load(Serde.dump(folded)))
        }
        assertEquals("MiXeD",SqlFragment("SELECT 1 AS MiXeD","duckdb").ast.expressionsArg.filterIsInstance<Expression>().single().aliasOrName)
        val quoted=Identifier(args("this" to "QuOtEdÄ", "quoted" to true))
        assertEquals("quotedÄ",assertIs<Identifier>(normalizeIdentifiers(quoted,dialect=Dialects.DUCKDB)).name)
    }
    @Test fun modeAdjustedAliasesReferencesAndLambdaBindingsResolveAgainstActualCatalogSpelling() {
        for(mode in DuckdbIdentifierCase.entries) {
            val schema=mode.foldUnquoted("MySchema");val table=mode.foldUnquoted("MixedTable");val column=mode.foldUnquoted("ArG")
            val facts=RelationFacts(tables=mapOf("$schema.$table" to RelationFact(listOf(ColumnFact(column,"INTEGER"),ColumnFact("QuotedCol","INTEGER")))))
            val sql=parseDuckdbNativeOne("SELECT m.aRg AS MiXeDAlias,m.\"quotedcol\" AS \"QuotedAlias\" FROM MySchema.MixedTable AS M")
                .withDuckdbIdentifierCase(mode).sql("duckdb")
            val scope=RelationScope.analyze(sql,"duckdb",facts)
            assertTrue(scope.diagnostics.isEmpty(),scope.diagnostics.toString())
            assertEquals(listOf(mode.foldUnquoted("MiXeDAlias"),"QuotedAlias"),scope.output!!.columns.map { it.name })
            val lambda=parseDuckdbNativeOne("SELECT list_transform([1,2],LAMBDA ArG: aRg + 1) AS MiXeD").withDuckdbIdentifierCase(mode)
            assertTrue(RelationScope.analyze(lambda.sql("duckdb"),"duckdb",RelationFacts()).diagnostics.isEmpty())
        }
    }
}
