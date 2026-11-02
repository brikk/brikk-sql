package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbCompoundNumericTest {
    @Test fun nativeSumPromotionsReachShapesScopesAndDerivedCompletionWithoutChangingParity() {
        for((input,expected)in listOf("INTEGER" to "INT128","UBIGINT" to "INT128","BOOLEAN" to "INT128",
            "UHUGEINT" to "DOUBLE","FLOAT" to "DOUBLE","DECIMAL(9,2)" to "DECIMAL(38, 2)")) {
            val facts=RelationFacts(tables=mapOf("t" to RelationFact(listOf(ColumnFact("x",input)))))
            val sql="SELECT sum(x) AS total FROM t"
            assertEquals(expected,RelationScope.analyze(sql,"duckdb",facts).output!!.columns.single().sqlType,input)
            val outer="WITH q AS ($sql) SELECT q. FROM q"
            assertEquals(expected,RelationScope.complete(outer,"duckdb",facts,outer.indexOf("q.")+2).items.single { it.name=="total" }.type)
        }
        assertEquals("INT128",SqlFragment("SELECT sum(1) AS n","duckdb").outputShape().columns.single().type)
        val parity=annotateTypes(Dialects.DUCKDB.parseOne("SELECT sum(1)"),dialect=Dialects.DUCKDB)
        assertTrue((parity.expressionsArg.single() as Expression).isType(DType.BIGINT))
        assertEquals(listOf("BIGINT","INT"),SqlFragment("SELECT count(1) AS n,max(1) AS m","duckdb").outputShape().columns.map { it.type })
        for(body in listOf("sum(x)","sum(1.25)","sum(99999999999999999999999999999999999999999999)"))
            assertEquals("UNKNOWN",SqlFragment("SELECT $body AS n","duckdb").outputShape().columns.single().type)
    }
    @Test fun emptyStructAndTypedLiteralsKeepTheirExistingAstWithoutInventingTupleSyntax() {
        for(sql in listOf("SELECT {} AS s","SELECT struct_pack() AS s","SELECT NULL::STRUCT AS s",
            "SELECT INTEGER '42' AS n","SELECT DECIMAL(9,2) '1.25' AS n",
            "SELECT 1::DECIMAL(4.0,2)","CREATE MACRO audit_tiny(x TINYINT := 127) AS x")) {
            val tree=parseDuckdbNativeOne(sql)
            assertEquals(tree,Serde.load(Serde.dump(tree)),sql)
            val rendered=tree.copy().sql("duckdb")
            assertEquals(rendered,parseDuckdbNativeOne(rendered).sql("duckdb"),sql)
        }
        assertFalse(DType.entries.any { it.name=="TUPLE" },"Internal tuple types are not a new public SQL type enum")
    }
}
