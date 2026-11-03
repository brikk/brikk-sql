package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import org.duckdb.DuckDBConnection
import kotlin.test.*

internal class DuckdbCompatibilityModeResultTest {
    private fun connect()=DriverManager.getConnection("jdbc:duckdb:",Properties().apply {
        setProperty("enable_external_access","false");setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
    }) as DuckDBConnection
    private fun Connection.execute(sql:String)=createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql:String):String=createStatement().use { s -> s.executeQuery(sql).use { r -> assertTrue(r.next());r.getString(1) } }
    @Test fun sameGrammarAndParseTreeDoNotProveTheSameLambdaIndexSemanticsOrMatchingInstance() {
        connect().use { owner -> owner.duplicate().use { sibling ->
            val sql="SELECT list_transform([10,20],lambda x,i:i) AS indices"
            val filter="SELECT list_filter([10,20],lambda x,i:i=1) AS chosen"
            val static=parseDuckdbNativeOne(sql)
            val parsed=assertIs<SerializedSql.Parsed>(owner.serializeSql(sql))
            val grammar=owner.scalar("SELECT current_setting('active_grammar_extensions')")
            assertEquals("NONE",owner.scalar("SELECT current_setting('dialect_compatibility_mode')").uppercase())
            assertEquals("[1, 2]",owner.scalar(sql));assertEquals("[10]",owner.scalar(filter))
            val setting="SET dialect_compatibility_mode='spark'"
            assertEquals("not implemented",assertIs<SerializedSql.Failed>(owner.serializeSql(setting)).type)
            owner.execute(parseDuckdbNativeOne(setting).sql("duckdb"))
            assertEquals("NONE",sibling.scalar("SELECT current_setting('dialect_compatibility_mode')").uppercase())
            assertEquals(grammar,owner.scalar("SELECT current_setting('active_grammar_extensions')"))
            assertEquals(parsed,owner.serializeSql(sql),"The selected mode changes semantics, not this parse tree")
            assertEquals(static,parseDuckdbNativeOne(sql),"No process-global static dialect mutation")
            for(text in listOf(sql,static.copy().sql("duckdb"))) {
                assertEquals("[0, 1]",owner.scalar(text))
                owner.createStatement().use { s -> s.executeQuery(text).use { r -> assertEquals("BIGINT[]",r.metaData.getColumnTypeName(1)) } }
            }
            assertEquals("[20]",owner.scalar(filter))
            assertEquals("[1, 2]",sibling.scalar(sql))
            connect().use { fresh ->
                fresh.execute("SET active_grammar_extensions=[]")
                assertEquals(grammar,fresh.scalar("SELECT current_setting('active_grammar_extensions')"))
                assertEquals("NONE",fresh.scalar("SELECT current_setting('dialect_compatibility_mode')").uppercase())
                assertEquals(parsed,fresh.serializeSql(sql))
                assertEquals("[1, 2]",fresh.scalar(sql),"A fresh helper with the same grammar is not the same semantic context")
            }
            assertFailsWith<SQLException> { owner.execute("SET dialect_compatibility_mode='not_a_proved_mode'") }
            assertEquals("SPARK",owner.scalar("SELECT current_setting('dialect_compatibility_mode')").uppercase())
            owner.duplicate().use { late ->
                assertEquals("SPARK",late.scalar("SELECT current_setting('dialect_compatibility_mode')").uppercase())
                assertEquals("[0, 1]",late.scalar(sql))
                owner.execute("RESET dialect_compatibility_mode")
                assertEquals("[0, 1]",late.scalar(sql),"Later changes do not resynchronize a duplicate's copied variables")
            }
            assertEquals("[1, 2]",owner.scalar(sql));assertEquals("[10]",owner.scalar(filter))
            assertEquals(grammar,owner.scalar("SELECT current_setting('active_grammar_extensions')"))
        } }
    }
}
