package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbRegexAdditionsResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun Connection.rows(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun Connection.check(sql:String,expected:List<List<String?>>) { assertEquals(expected,rows(sql),sql);assertEquals(expected,rows(render(sql)),render(sql)) }
    @Test fun optionsGroupsNullsNoMatchAndKeepOptionsPreserveNativeResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT regexp_extract('ABC','abc','i'),regexp_extract('abc','(a)(b)',2),regexp_extract('abc','z'),regexp_extract('abc','z','k')",listOf(listOf("ABC","b","","abc")))
            c.check("SELECT regexp_extract(NULL,'a'),regexp_extract('a',NULL),regexp_extract('a','a',NULL),regexp_extract_all('a','a',NULL)",listOf(listOf(null,null,"",null)))
            c.check("SELECT regexp_extract('A.B','a.b','il'),regexp_extract('x','z',0,'ik'),regexp_extract('abc','(a)',1,'c')",listOf(listOf("A.B","x","a")))
            c.check("SELECT to_json(regexp_extract_all('Aa aA','(a)',1,'i')),to_json(regexp_extract_all('x','z'))",listOf(listOf("[\"A\",\"a\",\"a\",\"A\"]","[]")))
        }
    }
    @Test fun namedCapturesBindStructAndListOfStructTypesAndKeepOptionalGroupNullsDistinct() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT to_json(regexp_extract('a','(?P<original>a)(b)?',['letter','digit'])),to_json(regexp_extract_all('a1 b','(\\w)(\\d)?',['letter','digit']))",
                listOf(listOf("{\"letter\":\"a\",\"digit\":\"\"}","[{\"letter\":\"a\",\"digit\":\"1\"},{\"letter\":\"b\",\"digit\":null}]")))
            c.check("SELECT to_json(regexp_extract('x','(a)',['g'])),to_json(regexp_extract_all('x','(a)',['g']))",listOf(listOf("{\"g\":\"\"}","[]")))
            val sql=render("SELECT regexp_extract(NULL,'(a)',['x']) AS scalar,regexp_extract_all(NULL,'(a)',['x']) AS all_rows")
            c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                assertEquals("STRUCT(x VARCHAR)",r.metaData.getColumnTypeName(1));assertEquals("STRUCT(x VARCHAR)[]",r.metaData.getColumnTypeName(2));assertTrue(r.next());assertNull(r.getObject(1));assertNull(r.getObject(2))
            } }
        }
    }
    @Test fun replacementFirstGlobalBackreferencesUnicodeAndInvalidInputsStayNative() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT regexp_replace('aaa','a','x'),regexp_replace('aaa','a','x','g'),regexp_replace('ab','(a)(b)','\\2\\1'),regexp_replace('🦆🦆','.','x','g')",listOf(listOf("xaa","xxx","ba","xx")))
            for(sql in listOf("SELECT regexp_extract('a','a',0,'e')","SELECT regexp_extract('a','a','g')","SELECT regexp_extract_all('a','a','i')",
                "SELECT regexp_extract_all('a','(a)',[])","SELECT regexp_extract_all('a','(a)',['x','x'])","SELECT regexp_extract('a','(a)',['x','y'])",
                "SELECT regexp_replace('a','a','x','k')","SELECT regexp_extract('a','(',0)","SELECT regexp_extract('a','a',0,'i',99)","SELECT regexp_replace('a','a','x','g',99)")) {
                val original=assertFailsWith<SQLException>(sql) { c.rows(sql) }
                val generated=assertFailsWith<SQLException>(sql) { c.rows(render(sql)) }
                assertEquals(original.message!!.substringBefore(':').trim(),generated.message!!.substringBefore(':').trim(),sql)
            }
            // RE2 invalid rewrites are checked from actual native results/errors, not a host regex engine.
            for(replacement in listOf("\\9","\\q","\\")) {
                val sql="SELECT regexp_replace('a','(a)','${replacement.replace("'","''")}')"
                val original=runCatching { c.rows(sql) };val generated=runCatching { c.rows(render(sql)) }
                assertEquals(original.isSuccess,generated.isSuccess,sql)
                if(original.isSuccess)assertEquals(original.getOrThrow(),generated.getOrThrow(),sql)
            }
        }
    }
    @Test fun preparedOverloadBindingsAndVolatileSubjectsKeepTheirNativeEvaluationCount() {
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql="SELECT regexp_extract(?,?,CAST(? AS VARCHAR))"
            c.prepareStatement(if(generated)render(sql)else sql).use { p ->
                assertEquals(3,p.parameterMetaData.parameterCount);p.setString(1,"ABC");p.setString(2,"abc");p.setString(3,"i")
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals("ABC",r.getString(1)) }
                p.setString(3,"c");p.executeQuery().use { r -> assertTrue(r.next());assertEquals("",r.getString(1)) }
            }
            c.createStatement().use { it.execute("CREATE SEQUENCE audit_seq") }
            val once="SELECT regexp_extract(CAST(nextval('audit_seq') AS VARCHAR),'[0-9]+','i')"
            assertEquals(listOf(listOf("1")),c.rows(if(generated)render(once)else once));assertEquals(listOf(listOf("1")),c.rows("SELECT currval('audit_seq')"))
        }
    }
    @Test fun nativeRegistryMatchesTheUnrefreshedCapturesDespiteBindingDependentResultTypes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for(name in listOf("regexp_extract","regexp_extract_all","regexp_replace")) {
                c.createStatement().use { s -> s.executeQuery("SELECT parameter_types,return_type FROM duckdb_functions() WHERE function_name='$name' AND internal AND database_name='system'").use { r ->
                    val rows=buildSet { while(r.next())add((r.getArray(1).array as Array<*>).map { it.toString() } to r.getString(2)) }
                    assertEquals(DUCKDB_METADATA.default.functions[name]!!.overloads.map { it.argTypes to it.returnType }.toSet(),rows)
                } }
            }
        }
    }
}
