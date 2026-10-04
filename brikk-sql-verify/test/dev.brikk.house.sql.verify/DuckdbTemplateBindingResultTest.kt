package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DDB-001: test portable templates without pretending :name is native DuckDB SQL. */
class DuckdbTemplateBindingResultTest {
    @Test
    fun bareTemplateProjectionsExecuteAfterParameterOnlyJdbcAdaptation() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((source, label) in listOf(
                "SELECT :n AS n" to "n",
                "SELECT :value AS result" to "result",
                "SELECT :n::INTEGER AS n" to "n",
                "SELECT (:n) AS n" to "n",
                "SELECT ((:n)) AS n" to "n",
                "SELECT CAST(:n AS INTEGER) AS n" to "n",
                "SELECT answer: :n" to "answer",
                "SELECT \"odd alias\": :n" to "odd alias",
                "SELECT current_date: :n" to "current_date",
                "SELECT /* 😀 :n */ :n AS n -- (:n)" to "n",
                "SELECT :n AS n FROM (SELECT (:n) AS nested) AS input" to "n",
            )) {
                val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
                assertEquals(source, result.sql)
                assertTrue(result.diagnostics.isEmpty())
                // Exact identity means the original occurrence intervals also index this output.
                // Only genuine marker intervals change; casts, prefix aliases and trivia do not.
                val adapted = buildString {
                    var cursor = 0
                    for (parameter in result.parameterOccurrences) {
                        append(result.sql, cursor, parameter.range.start)
                        append('?')
                        cursor = parameter.range.end
                    }
                    append(result.sql, cursor, result.sql.length)
                }
                assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(adapted).accepted, adapted)
                connection.prepareStatement(adapted).use { statement ->
                    for (value in listOf<Int?>(7, null)) {
                        for (index in result.parameterOccurrences.indices) {
                            if (value == null) statement.setNull(index + 1, Types.INTEGER)
                            else statement.setInt(index + 1, value)
                        }
                        statement.executeQuery().use { rows ->
                            assertEquals(label, rows.metaData.getColumnLabel(1), source)
                            assertTrue(rows.next(), source)
                            val actual = rows.getInt(1).let { if (rows.wasNull()) null else it }
                            assertEquals(value, actual, "$source\n$adapted")
                            assertFalse(rows.next(), source)
                        }
                    }
                }
            }
        }
    }
}
