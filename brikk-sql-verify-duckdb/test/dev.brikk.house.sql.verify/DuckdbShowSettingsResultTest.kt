package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbShowSettingsResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun Connection.exec(sql:String)=createStatement().use { it.execute(sql);Unit }
    private fun Connection.rows(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun Connection.check(sql:String,expected:List<List<String?>>) {
        assertEquals(expected,rows(sql),sql);assertEquals(expected,rows(render(sql)),render(sql))
    }
    @Test fun autoTableAndSettingModesPreserveCollisionsNamesAndErrors() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=2");c.exec("CREATE TABLE my_table(i INTEGER)")
            for(sql in listOf("SHOW threads","SHOW THREADS","SHOW \"threads\"","SHOW 'threads'"))c.check(sql,listOf(listOf("2")))
            c.createStatement().use { s -> s.executeQuery("SHOW threads").use { r ->
                assertEquals("threads",r.metaData.getColumnLabel(1));assertEquals(1,r.metaData.columnCount)
            } }
            val description=listOf(listOf("i","INTEGER","YES",null,null,null))
            c.check("SHOW my_table",description);c.check("SHOW main.my_table",description)
            c.check("SHOW (SELECT i FROM my_table)",description)
            assertFailsWith<SQLException> { c.rows("DESCRIBE threads") }
            c.exec("CREATE TABLE threads(x VARCHAR)")
            val collision=listOf(listOf("x","VARCHAR","YES",null,null,null))
            c.check("SHOW threads",collision)
            c.exec("SET show_behavior='setting'")
            c.check("SHOW threads",listOf(listOf("2")))
            for(sql in listOf("SHOW my_table","SHOW main.my_table","SHOW missing")) {
                val error=assertFailsWith<SQLException> { c.rows(render(sql)) }
                assertTrue("Setting with name" in error.message.orEmpty(),error.message)
            }
            c.check("SHOW (SELECT i FROM my_table)",description)
            c.exec("SET show_behavior='table'")
            c.check("SHOW threads",collision);c.check("SHOW my_table",description)
            c.exec("DROP TABLE threads")
            assertTrue("Table with name threads" in assertFailsWith<SQLException> { c.rows(render("SHOW threads")) }.message.orEmpty())
            assertFailsWith<SQLException> { c.exec("SET show_behavior='bogus'") }
            c.exec("SET show_behavior='auto'")
            assertFailsWith<SQLException> { c.rows(render("SHOW main.threads")) }
            assertFailsWith<SQLException> { c.rows(render("SHOW ''")) }
            c.check("SHOW threads",listOf(listOf("2")))
        }
    }
    @Test fun preparedShowCapturesItsBindTimeValueAndQueryParametersKeepTheirOrder() {
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=2")
            c.prepareStatement(if(generated)render("SHOW threads")else "SHOW threads").use { p ->
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals("2",r.getString(1)) }
                c.exec("SET threads=3")
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals("2",r.getString(1)) }
            }
            c.check("SHOW threads",listOf(listOf("3")))
            c.prepareStatement(render("SHOW (SELECT CAST(? AS INTEGER) AS x,CAST(? AS VARCHAR) AS y)")).use { p ->
                assertEquals(2,p.parameterMetaData.parameterCount);p.setInt(1,7);p.setString(2,"text")
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals("x",r.getString(1));assertTrue(r.next());assertEquals("y",r.getString(1)) }
            }
        }
    }
    @Test fun parserValidationNeverBindsTheDescribedQueryOrResolvesSettings() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            c.exec("CREATE SEQUENCE audit_seq")
            val sql="SHOW (SELECT * FROM range(nextval('audit_seq')))"
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            for(text in listOf("SHOW missing_setting",sql)) {
                val result=verifier.verify(render(text))
                assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            }
            assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
        }
    }
}
