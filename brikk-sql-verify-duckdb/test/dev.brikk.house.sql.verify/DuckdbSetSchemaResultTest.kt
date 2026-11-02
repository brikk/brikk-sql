package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.ast.*
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbSetSchemaResultTest {
    @Test fun nativeNamespaceSwitchingPreservesQuotesAndRejectsNestedPathsWithoutChangingState() {
        DriverManager.getConnection("jdbc:duckdb:",Properties().apply {
            setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
        }).use { c ->
            fun execute(sql:String)=c.createStatement().use { it.execute(sql) }
            fun row(sql:String)=c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                assertTrue(r.next());(1..r.metaData.columnCount).map { r.getString(it) }
            } }
            execute("CREATE SCHEMA finance");execute("CREATE SCHEMA ops");execute("CREATE SCHEMA finance.reports")
            execute("CREATE SCHEMA \"finance.reports\"");execute("CREATE SCHEMA \"a'b\"")
            execute("ATTACH ':memory:' AS \"la\"\"ke\"");execute("CREATE SCHEMA \"la\"\"ke\".web")
            execute("CREATE TABLE finance.t AS SELECT 1 AS n");execute("CREATE TABLE ops.t AS SELECT 2 AS n")
            execute("SET search_path='finance,ops'")
            assertEquals(listOf("finance","finance,ops","1"),row("SELECT current_schema(),current_setting('search_path'),n FROM t"))
            for(generated in listOf(false,true)) {
                for((target,catalog,schema)in listOf(Triple("memory.ops","memory","ops"),Triple("memory.finance","memory","finance"),
                    Triple("memory.\"finance.reports\"","memory","finance.reports"),Triple("memory.\"a'b\"","memory","a'b"),
                    Triple("\"la\"\"ke\".web","la\"ke","web"))) {
                    val original="SET SCHEMA '${target.replace("'","''")}'"
                    val sql=if(generated)assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(original)))).sql("duckdb") else original
                    // Unsupported SELECT serialization is unverified, never a bind fallback.
                    assertEquals("not implemented",assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                    execute(sql)
                    assertEquals(listOf(catalog,schema),row("SELECT current_database(),current_schema()"),sql)
                }
            }
            // Bare schema names are resolved inside the current catalog, not guessed
            // against another attached database that happens to contain that name.
            assertFailsWith<SQLException> { execute("SET SCHEMA 'ops'") }
            assertEquals(listOf("la\"ke","web"),row("SELECT current_database(),current_schema()"))
            execute("USE memory.finance")
            assertEquals(listOf("1"),row("SELECT n FROM t"))
            for(sql in listOf("SET SCHEMA 'memory.finance.reports'","SET SCHEMA 'finance.reports'","USE memory.finance.reports",
                "SET SCHEMA 'finance,ops'","SET SCHEMA ''","SET SCHEMA 'missing'","SET SCHEMA finance","SET SCHEMA \"finance\"")) {
                assertFailsWith<SQLException>(sql) { execute(sql) }
                assertEquals(listOf("memory","finance"),row("SELECT current_database(),current_schema()"),sql)
            }
            execute("SET SCHEMA 'ops'")
            assertEquals(listOf("ops","memory.ops","2"),row("SELECT current_schema(),current_setting('search_path'),n FROM t"))
            // Ordinary SET/USE syntax remains valid and does not infer a catalog from a schema prefix.
            execute("SET SCHEMA = 'finance'");execute("SET SCHEMA TO 'ops'")
            assertEquals(listOf("2"),row("SELECT n FROM t"))
        }
    }
}
