package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbVariablesResultTest {
    private fun text(sql: String, generated: Boolean) = if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql
    private fun Connection.execute(sql: String, generated: Boolean) = createStatement().use { statement ->
        if (statement.execute(text(sql, generated))) statement.resultSet.use { while (it.next()) { /* drain once */ } }
    }
    private fun Connection.rows(sql: String, generated: Boolean = false): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(text(sql, generated)).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }

    @Test
    fun directNamedParametersUseSessionDefaultsButGetvariableHasDifferentMissingBehavior() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET VARIABLE threshold=30", generated)
            assertEquals(listOf(listOf("50", "true")), c.rows("SELECT 20+\$threshold,\$threshold=getvariable('threshold')", generated))
            c.execute("SET VARIABLE threshold=100", generated)
            assertEquals(listOf(listOf("120")), c.rows("SELECT 20+\$threshold", generated))
            c.execute("RESET VARIABLE threshold", generated)
            assertEquals(listOf(listOf(null)), c.rows("SELECT getvariable('threshold')", generated))
            val error = assertFailsWith<SQLException> { c.rows("SELECT \$threshold", generated) }
            assertTrue("Values were not provided" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun quotedNamesCaseTypesAndSessionIsolationArePreserved() {
        for (generated in listOf(false, true)) (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            user.execute("SET VARIABLE \"my var\"=9", generated)
            user.execute("SET VARIABLE CaseName=12", generated)
            user.execute("SET VARIABLE names=['a','b']", generated)
            assertEquals(listOf(listOf("9", "12", "12", "[a, b]")), user.rows("SELECT \$\"my var\",\$casename,\$CASENAME,\$names", generated))
            user.duplicate().use { other ->
                // JDBC duplicate copies current session variables, but subsequent changes are local.
                assertEquals(listOf(listOf("12")), other.rows("SELECT getvariable('CaseName')", generated))
                other.execute("SET VARIABLE CaseName=99", generated)
                assertEquals(listOf(listOf("12")), user.rows("SELECT \$casename", generated))
                user.execute("SET VARIABLE user_only=1", generated)
                assertEquals(listOf(listOf(null)), other.rows("SELECT getvariable('user_only')", generated))
            }
            // These are not supported forms of SET VARIABLE. SET GLOBAL configuration is separate.
            for (sql in listOf("SET SESSION VARIABLE x=1", "SET GLOBAL VARIABLE x=1")) {
                assertFailsWith<SQLException>(sql) { user.execute(sql, false) }
            }
        }
    }

    @Test
    fun prepareDefaultsAreExecutionTimeFallbackAndExplicitArgumentsOverrideOnlyTheirNames() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET VARIABLE multiplier=10", generated)
            c.execute("SET VARIABLE x=42", generated)
            c.execute("PREPARE compute AS SELECT \$multiplier * \$x AS result", generated)
            assertEquals(listOf(listOf("420")), c.rows("EXECUTE compute", generated))
            assertEquals(listOf(listOf("126")), c.rows("EXECUTE compute(multiplier:=3)", generated))
            assertEquals(listOf(listOf("8")), c.rows("EXECUTE compute(x:=4,multiplier:=2)", generated))
            c.execute("SET VARIABLE multiplier=100", generated)
            assertEquals(listOf(listOf("4200")), c.rows("EXECUTE compute", generated))
            c.execute("RESET VARIABLE x", generated)
            assertFailsWith<SQLException> { c.rows("EXECUTE compute", generated) }
            assertEquals(listOf(listOf("200")), c.rows("EXECUTE compute(x:=2)", generated))
            assertFailsWith<SQLException> { c.rows("EXECUTE compute(x:=2,extra:=3)", generated) }
            c.execute("DEALLOCATE PREPARE compute", generated)
            assertFailsWith<SQLException> { c.rows("EXECUTE compute", generated) }
        }
    }

    @Test
    fun numericAndAnonymousParametersNeverUseNumericNamedVariablesAsDefaults() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET VARIABLE \"1\"=99", generated)
            for ((name, parameter) in listOf("numbered_stmt" to "\$1", "quoted_number" to "\$\"1\"", "auto_stmt" to "?")) {
                c.execute("PREPARE $name AS SELECT $parameter AS result", generated)
                assertFailsWith<SQLException> { c.rows("EXECUTE $name", generated) }
                assertEquals(listOf(listOf("7")), c.rows("EXECUTE $name(7)", generated))
            }
        }
    }

    @Test
    fun preparedWriteDefaultsDoNotBindOrExecuteDuringEditorAnalysis() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE writes(n INT)", generated)
            c.execute("SET VARIABLE n=1", generated)
            val prepare = "PREPARE write_stmt AS INSERT INTO writes VALUES (\$n) RETURNING n"
            val tree = Dialects.DUCKDB.parseOne(prepare)
            tree.sql("duckdb")
            dev.brikk.house.sql.scope.RelationScope.analyze(prepare, "duckdb", dev.brikk.house.sql.scope.RelationFacts())
            assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM writes"))
            c.execute(prepare, generated)
            assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM writes"))
            assertEquals(listOf(listOf("1")), c.rows("EXECUTE write_stmt", generated))
            c.execute("SET VARIABLE n=2", generated)
            assertEquals(listOf(listOf("2")), c.rows("EXECUTE write_stmt", generated))
            assertEquals(listOf(listOf("3")), c.rows("EXECUTE write_stmt(n:=3)", generated))
            assertEquals(listOf(listOf("1"), listOf("2"), listOf("3")), c.rows("SELECT n FROM writes ORDER BY n"))
        }
    }

    @Test
    fun jdbcDirectPreparedStatementRequiresExplicitBindingsRatherThanSqlExecuteFallback() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET VARIABLE x=5", false)
            c.prepareStatement("SELECT \$x").use { unbound ->
                val error = assertFailsWith<SQLException> { unbound.executeQuery() }
                assertTrue("Parameter count mismatch" in error.message.orEmpty(), error.message)
            }
            // A separate explicit-binding control, not a retry of a potentially executed statement.
            c.prepareStatement("SELECT ?").use { bound ->
                bound.setInt(1, 8)
                bound.executeQuery().use { r -> assertTrue(r.next()); assertEquals(8, r.getInt(1)) }
            }
            c.prepareStatement("SELECT \$x").use { named ->
                named.setInt(1, 8)
                val error = assertFailsWith<SQLException> { named.executeQuery() }
                assertTrue("excess parameters: 1" in error.message.orEmpty(), error.message)
            }
        }
    }
}
