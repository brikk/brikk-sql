package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import kotlin.test.*

internal class DuckdbSetvalResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun Connection.exec(sql:String)=createStatement().use { it.execute(sql);Unit }
    private fun Connection.scalar(sql:String):String?=createStatement().use { s -> s.executeQuery(sql).use { r -> assertTrue(r.next());r.getString(1) } }
    @Test fun defaultsFalseCurrvalIncrementsBoundsAndRollbackPreserveNativeState() {
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fun q(sql:String)=c.scalar(if(generated)render(sql)else sql)
            c.exec("CREATE SEQUENCE s")
            assertFailsWith<SQLException> { q("SELECT currval('s')") }
            assertEquals("42",q("SELECT setval('s',42,false)"))
            assertFailsWith<SQLException> { q("SELECT currval('s')") }
            assertEquals("42",q("SELECT nextval('s')"));assertEquals("43",q("SELECT nextval('s')"))
            assertEquals("100",q("SELECT setval('s',100,false)"));assertEquals("43",q("SELECT currval('s')"))
            assertEquals("100",q("SELECT nextval('s')"))
            assertEquals("42",q("SELECT setval('s',42)"));assertEquals("42",q("SELECT currval('s')"));assertEquals("43",q("SELECT nextval('s')"))
            assertEquals("77",q("SELECT setval(value:=77,sequence_name:='s',is_called:=true)"));assertEquals("78",q("SELECT nextval('s')"))
            c.exec("BEGIN");assertEquals("90",q("SELECT setval('s',90,true)"));c.exec("ROLLBACK")
            assertEquals("91",q("SELECT nextval('s')"))
            c.exec("CREATE SCHEMA custom");c.exec("CREATE SEQUENCE custom.inc INCREMENT BY 5 MINVALUE 10 MAXVALUE 30 START 10")
            assertEquals("20",q("SELECT setval('custom.inc',20)"));assertEquals("25",q("SELECT nextval('custom.inc')"))
            assertEquals("30",q("SELECT setval('custom.inc',30,false)"));assertEquals("30",q("SELECT nextval('custom.inc')"))
            assertFailsWith<SQLException> { q("SELECT nextval('custom.inc')") }
            assertFailsWith<SQLException> { q("SELECT setval('custom.inc',31,false)") }
            assertEquals("10",q("SELECT setval('custom.inc',10,false)"));assertEquals("10",q("SELECT nextval('custom.inc')"))
            c.exec("CREATE SEQUENCE down_seq INCREMENT -3 MINVALUE -20 MAXVALUE -1 START -1")
            assertEquals("-5",q("SELECT setval('down_seq',-5)"));assertEquals("-8",q("SELECT nextval('down_seq')"))
        }
    }
    @Test fun nullOperandsSkipMutationAndPreparedExecutionDoesNotDuplicateOrReorderOperands() {
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("CREATE SEQUENCE s");c.exec("CREATE SEQUENCE operand_seq")
            for(call in listOf("setval(NULL,42)","setval('s',NULL)","setval('s',42,NULL)","setval(NULL,NULL,NULL)"))
                assertNull(c.scalar(if(generated)render("SELECT $call")else "SELECT $call"))
            assertEquals("1",c.scalar("SELECT nextval('s')"))
            val sql="SELECT setval(?,?,?)"
            c.prepareStatement(if(generated)render(sql)else sql).use { p ->
                assertEquals(3,p.parameterMetaData.parameterCount)
                p.setString(1,"s");p.setLong(2,50);p.setBoolean(3,false)
                p.executeQuery().use { r -> assertEquals("BIGINT",r.metaData.getColumnTypeName(1));assertTrue(r.next());assertEquals(50,r.getLong(1)) }
                assertEquals("50",c.scalar("SELECT nextval('s')"))
                p.setLong(2,70);p.setBoolean(3,true)
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals(70,r.getLong(1)) }
                assertEquals("71",c.scalar("SELECT nextval('s')"))
                p.setNull(2,Types.BIGINT);p.executeQuery().use { r -> assertTrue(r.next());assertNull(r.getString(1)) }
                assertEquals("72",c.scalar("SELECT nextval('s')"))
            }
            val once="SELECT setval('s',nextval('operand_seq'),false)"
            assertEquals("1",c.scalar(if(generated)render(once)else once));assertEquals("1",c.scalar("SELECT currval('operand_seq')"))
            assertEquals("1",c.scalar("SELECT nextval('s')"))
        }
    }
    @Test fun nativeOverloadsErrorsAndQualifiedUdfsStaySeparateFromSequenceContracts() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT parameter_types,return_type FROM duckdb_functions() WHERE function_name='setval' AND database_name='system' AND internal").use { r ->
                val signatures=buildSet { while(r.next()) { assertEquals("BIGINT",r.getString(2));add((r.getArray(1).array as Array<*>).map { it.toString() }) } }
                assertEquals(DUCKDB_METADATA.default.functions["setval"]!!.overloads.map { it.argTypes }.toSet(),signatures)
            } }
            for(sql in listOf("SELECT setval()","SELECT setval('s')","SELECT setval('s',1,true,false)","SELECT setval('missing',1)")) {
                assertIs<SerializedSql.Parsed>(c.serializeSql(sql));assertFailsWith<SQLException> { c.scalar(render(sql)) }
            }
            c.exec("CREATE SCHEMA custom");c.exec("CREATE MACRO custom.setval(a,b) AS 'udf'")
            assertEquals("udf",c.scalar(render("SELECT custom.setval('s',42)")))
            assertEquals("UNKNOWN",SqlFragment("SELECT custom.setval('s',42) AS x","duckdb").outputShape().columns.single().type)
        }
    }
    @Test fun duplicateConnectionsShareCatalogSequenceStateRatherThanPostgresSessionCurrval() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { first ->
            first.exec("CREATE SEQUENCE s")
            first.duplicate().use { second ->
                assertFailsWith<SQLException> { second.scalar("SELECT currval('s')") }
                assertEquals("42",first.scalar(render("SELECT setval('s',42,true)")))
                assertEquals("42",second.scalar("SELECT currval('s')"))
                assertEquals("43",second.scalar("SELECT nextval('s')"))
                assertEquals("43",first.scalar("SELECT currval('s')"))
                assertEquals("90",first.scalar(render("SELECT setval('s',90,false)")))
                assertEquals("43",second.scalar("SELECT currval('s')"))
                assertEquals("90",second.scalar("SELECT nextval('s')"))
            }
        }
    }
    @Test fun staticFactsAndNativeParserValidationNeverInvokeSetvalEvenInsideBindingArguments() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            c.exec("CREATE SEQUENCE s")
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            for(sql in listOf("SELECT setval('s',42)","SELECT * FROM range(setval('s',42))")) {
                SqlFragment(sql,"duckdb").toExecutable("duckdb")
                RelationScope.analyze(sql,"duckdb",RelationFacts())
                val result=verifier.verify(sql)
                assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            }
            SqlFragment("SELECT setval('s',42) AS x","duckdb").outputShape()
            assertEquals("1",c.scalar("SELECT nextval('s')"))
        }
    }
}
