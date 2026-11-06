package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/** DuckDB 2.0.0 d54cbbe3df, regex_operators.test and regex_operators_v1_5_view.test. */
internal class DuckdbRegexOperatorsResultTest {
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun renderings(sql: String) = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
        assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
    private fun Connection.check(sql: String, expected: List<List<String?>>) {
        for (rendered in renderings(sql)) assertEquals(expected, rows(rendered), rendered)
    }
    @Test fun operatorsHonorBothSessionModesWhileExplicitFunctionsKeepTheirOwnSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            assertEquals(listOf(listOf("partial")), c.rows("SELECT current_setting('regex_match_operator_semantics')"))
            for (mode in listOf("partial", "full")) {
                c.createStatement().use { it.execute("SET regex_match_operator_semantics='$mode'") }
                val partial = mode == "partial"
                c.check("SELECT 'abc' ~ 'b','abc' !~ 'b','abc' ~ '^a','abc' ~ '^b','ABC' ~ 'a','ABC' ~* 'a','ABC' !~* 'a'",
                    listOf(if (partial) listOf("true", "false", "true", "false", "false", "true", "false") else listOf("false", "true", "false", "false", "false", "false", "true")))
                c.check("SELECT 'abc' ~ '^a.*','ABC' ~* 'a.*','ABC' !~* 'a.*',regexp_full_match('abc','b'),regexp_matches('abc','b'),regexp_full_match('ABC','a','i')",
                    listOf(listOf("true", "true", "false", "false", "true", "false")))
                c.check("SELECT NULL ~ 'a','abc' !~ NULL,NULL ~* NULL,'abc' !~* NULL", listOf(listOf(null, null, null, null)))
                c.check("SELECT '🦆abc' ~ 'abc','a.b' ~ 'a\\.b','abc' ~ ''", listOf(listOf(if (partial) "true" else "false", "true", if (partial) "true" else "false")))
            }
            c.createStatement().use { it.execute("RESET regex_match_operator_semantics") }
            c.check("SELECT 'abc' ~ 'b'", listOf(listOf("true")))
        }
    }
    @Test fun quantifiedNegationEmptyArraysAndElementNullsAreNotOuterNotRewrites() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (mode in listOf("partial", "full")) {
                c.createStatement().use { it.execute("SET regex_match_operator_semantics='$mode'") }
                c.check("SELECT 'abc' !~ ANY(['abc','z']),'abc' !~ ALL(['abc','z']),'ABC' !~* ANY(['ABC','z']),'ABC' !~* ALL(['ABC','z'])",
                    listOf(listOf("true", "false", "true", "false")))
                c.check("SELECT 'abc' ~ ANY(['abc','z']),'ABC' ~* ANY(['Z','ABC']),'abc' ~ ANY(['z',NULL]),'abc' ~ ALL(['abc',NULL])",
                    listOf(listOf("true", "true", null, null)))
                for (op in listOf("~", "!~", "~*", "!~*")) {
                    c.check("SELECT 'abc' $op ANY([]::VARCHAR[]),'abc' $op ALL([]::VARCHAR[]),NULL $op ANY([]::VARCHAR[]),NULL $op ALL([]::VARCHAR[])",
                        listOf(listOf("false", "true", "false", "true")))
                }
            }
        }
    }
    @Test fun columnSelectorsNativeErrorsAndStoredViewsKeepTheRuntimeOperatorContract() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE regex_columns(abc INTEGER, \"Bcd\" INTEGER, def INTEGER)")
                it.execute("INSERT INTO regex_columns VALUES(1,2,3)")
            }
            c.createStatement().use { it.execute(SqlFragment("CREATE VIEW regex_view AS SELECT 'abc' ~ 'b' AS m", "duckdb").toExecutable("duckdb").sql) }
            c.createStatement().use { it.execute("CREATE VIEW original_regex_view AS SELECT 'abc' ~ 'b' AS m") }
            for (mode in listOf("partial", "full")) {
                c.createStatement().use { it.execute("SET regex_match_operator_semantics='$mode'") }
                if (mode == "partial") c.check("SELECT * ~* 'b' FROM regex_columns", listOf(listOf("1", "2")))
                else for (sql in renderings("SELECT * ~* 'b' FROM regex_columns")) {
                    assertTrue(assertFailsWith<SQLException> { c.rows(sql) }.message!!.contains("empty set of columns"))
                }
                c.check("SELECT * ~* 'b.*' FROM regex_columns", if (mode == "partial") listOf(listOf("1", "2")) else listOf(listOf("2")))
                // Native CREATE VIEW captures the resolved regex function at parse time.
                c.check("SELECT * FROM regex_view", listOf(listOf("true")))
                assertEquals(c.rows("SELECT * FROM original_regex_view"), c.rows("SELECT * FROM regex_view"))
                for (sql in listOf("SELECT 'abc' ~ '('", "SELECT 'abc' !~* '('")) {
                    val failures = renderings(sql).map { rendered -> assertFailsWith<SQLException> { c.rows(rendered) } }
                    assertEquals(1, failures.map { it.message!!.substringBefore(':') }.toSet().size)
                }
            }
        }
    }
    @Test fun bindOrderAndVolatileOperandsArePreservedWithoutConsultingOrChangingSessionSettings() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (mode in listOf("partial", "full")) {
                c.createStatement().use { it.execute("SET regex_match_operator_semantics='$mode'") }
                val sql = "SELECT CAST(? AS VARCHAR) !~* ANY([CAST(? AS VARCHAR),CAST(? AS VARCHAR)]),CAST(? AS VARCHAR) ~ CAST(? AS VARCHAR)"
                c.prepareStatement(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql).use { p ->
                    assertEquals(5, p.parameterMetaData.parameterCount)
                    listOf("ABC", "abc", "z", "abc", "b").forEachIndexed { index, value -> p.setString(index + 1, value) }
                    p.executeQuery().use { r ->
                        assertTrue(r.next())
                        assertTrue(r.getBoolean(1))
                        assertEquals(mode == "partial", r.getBoolean(2))
                    }
                }
                assertEquals(listOf(listOf(mode)), c.rows("SELECT current_setting('regex_match_operator_semantics')"))
            }
            c.createStatement().use { it.execute("CREATE SEQUENCE operand_seq") }
            val volatile = "SELECT CAST(nextval('operand_seq') AS VARCHAR) ~ CAST(nextval('operand_seq') AS VARCHAR)"
            assertEquals(listOf(listOf("false")), c.rows(if (generated) SqlFragment(volatile, "duckdb").toExecutable("duckdb").sql else volatile))
            assertEquals(listOf(listOf("2")), c.rows("SELECT currval('operand_seq')"))
        }
    }

    @Test fun preparedStatementsKeepNativeSettingCaptureRatherThanBeingRewrittenOrReprepared() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT CAST(? AS VARCHAR) ~ CAST(? AS VARCHAR)"
            c.prepareStatement(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql).use { p ->
                p.setString(1, "abc")
                p.setString(2, "b")
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertTrue(r.getBoolean(1))
                }
                c.createStatement().use { it.execute("SET regex_match_operator_semantics='full'") }
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertTrue(r.getBoolean(1))
                }
            }
            c.check("SELECT 'abc' ~ 'b'", listOf(listOf("false")))
        }
    }
}
