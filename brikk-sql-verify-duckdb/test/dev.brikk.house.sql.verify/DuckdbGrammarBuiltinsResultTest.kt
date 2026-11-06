package dev.brikk.house.sql.verify

import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbGrammarBuiltinsResultTest {
    private fun connection() = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
    })
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    @Test fun actualRegistryAndPegSpecialFormsAgreeWithoutTreatingIfAsRegistryAbsent() {
        connection().use { c ->
            assertEquals(listOf(listOf("v2.0.0", "d54cbbe3df")), c.rows("SELECT library_version,source_id FROM pragma_version()"))
            val registry = c.rows("SELECT DISTINCT upper(function_name) FROM duckdb_functions()").map { it.single() }.toSet()
            for (name in DUCKDB_GRAMMAR_BUILTINS) assertFalse(name in registry, name)
            assertEquals(listOf(listOf("macro")), c.rows("SELECT DISTINCT function_type FROM duckdb_functions() WHERE function_name='if'"))
            val sql = "SELECT COALESCE(NULL,7),IF(true,1,2),TRY(CAST('bad' AS INTEGER)),CAST('3' AS INTEGER),TRY_CAST('bad' AS INTEGER),EXTRACT(YEAR FROM DATE '2024-01-01'),POSITION('b' IN 'abc'),TRIM(BOTH ' ' FROM ' x '),SUBSTRING('abc' FROM 2 FOR 1)"
            for (form in listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql)) {
                assertIs<SerializedSql.Parsed>(c.serializeSql(form))
                assertEquals(listOf(listOf("7", "1", null, "3", null, "2024", "2", "x", "b")), c.rows(form), form)
            }
            assertIs<VerifyResult.Rejected>(c.serializeSql("SELECT POSITION('b','abc')").toParseOnlyResult("SELECT POSITION('b','abc')"))
            // Qualification bypasses the special syntax. The registered scalar's
            // argument order is haystack, needle, not the special form's order.
            assertEquals(listOf(listOf("2")), c.rows("SELECT main.position('abc','b')"))
        }
    }
    @Test fun everyDeclaredBareAndCallPresentationHasNativeExecutionEvidence() {
        connection().use { c ->
            val bareOnly = listOf("CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIME", "LOCALTIMESTAMP")
            val both = listOf("CURRENT_DATE", "CURRENT_CATALOG", "CURRENT_SCHEMA", "CURRENT_USER", "CURRENT_ROLE", "SESSION_USER", "USER")
            for (name in bareOnly+both) {
                assertIs<SerializedSql.Parsed>(c.serializeSql("SELECT $name"))
                assertNotNull(c.rows("SELECT $name").single().single(), name)
                assertIs<SerializedSql.Parsed>(c.serializeSql("SELECT $name()"), "generic call syntax is not registry resolution")
                if (name in bareOnly) assertTrue(assertFailsWith<SQLException> { c.rows("SELECT $name()") }.message.orEmpty().contains("Catalog Error"), name)
                else assertNotNull(c.rows("SELECT $name()").single().single(), name)
            }
        }
    }
    @Test fun groupingIsParseAcceptedButRequiresGroupedBindingAndPreservesRollupBits() {
        connection().use { c ->
            for (name in listOf("GROUPING", "GROUPING_ID")) {
                val invalid = "SELECT $name(1)"
                assertIs<SerializedSql.Parsed>(c.serializeSql(invalid))
                assertTrue(assertFailsWith<SQLException> { c.rows(invalid) }.message.orEmpty().contains("cannot be used without groups"))
                val sql = "SELECT x,$name(x) AS bits FROM (VALUES(1),(2)) t(x) GROUP BY ROLLUP(x) ORDER BY ALL"
                for (form in listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql))
                    assertEquals(listOf(listOf("1", "0"), listOf("2", "0"), listOf(null, "1")), c.rows(form))
            }
        }
    }
}
