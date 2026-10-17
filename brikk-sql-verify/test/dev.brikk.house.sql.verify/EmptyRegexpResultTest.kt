package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmptyRegexpResultTest {
    @Test
    fun canonicalEmptyPatternsRemainZeroWithFlagsButNullArgumentsRemainNull() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((call, expected) in listOf(
                    "REGEXP_COUNT('abc', '')" to 0L,
                    "REGEXP_COUNT('abc', '', 1, 'im')" to 0L,
                    "REGEXP_COUNT('Hello World', 'L', 1, 'im')" to 3L,
                    "REGEXP_COUNT(NULL, '', 1, 'i')" to null,
                    "REGEXP_COUNT('abc', NULL, 1, 'i')" to null,
                    "REGEXP_COUNT('abc', '', NULL, 'i')" to null,
                    "REGEXP_COUNT('abc', '', 1, NULL)" to null,
                    "REGEXP_INSTR('abc', '', 1, 1, 0, 'im')" to 0L,
                    "REGEXP_INSTR(NULL, '', 1, 1, 0, 'i')" to null,
                    "REGEXP_INSTR('abc', NULL, 1, 1, 0, 'i')" to null,
                    "REGEXP_INSTR('abc', '', NULL, 1, 0, 'i')" to null,
                    "REGEXP_INSTR('abc', '', 1, NULL, 0, 'i')" to null,
                    "REGEXP_INSTR('abc', '', 1, 1, NULL, 'i')" to null,
                    "REGEXP_INSTR('abc', '', 1, 1, 0, NULL)" to null,
                    "REGEXP_INSTR('abc', 'x')" to 0L,
                    "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 0, 'i')" to 7L,
                    "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 1, 'i')" to 10L,
                    "REGEXP_INSTR('é😀LéL', 'l', 1, 2, 0, 'i')" to 5L,
                )) {
                    val output = Dialects.BASE.parseOne("SELECT $call").sql("duckdb")
                    statement.executeQuery(output).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getObject(1), "$call\n$output")
                        assertEquals("BIGINT", rows.metaData.getColumnTypeName(1), output)
                    }
                }
                statement.executeQuery("SELECT LENGTH(REGEXP_EXTRACT_ALL('abc', '(?i)')), REGEXP_MATCHES('abc', '', 'i'), " +
                    "REGEXP_REPLACE('abc', '', '-', 'g')").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(4L, rows.getLong(1))
                    assertEquals(true, rows.getBoolean(2))
                    assertEquals("-a-b-c-", rows.getString(3))
                }
            }
        }
    }

    @Test
    fun patternColumnsAndOptionColumnsAreEvaluatedPerRowNotSpecialCasedAtCompileTime() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(s VARCHAR, p VARCHAR, pos INT, occ INT, opt INT)")
                statement.execute("INSERT INTO t VALUES ('Hello World', '', 1, 1, 0), ('Hello World', 'L', 1, 1, 0), " +
                    "('Hello World', 'L', 1, 1, 1), ('Hello World', 'L', 1, 3, 0), ('Hello World', 'L', 6, 1, 0), " +
                    "('Hello World', 'x', 1, 1, 0), (NULL, '', 1, 1, 0), ('abc', NULL, 1, 1, 0), ('abc', '', 1, 1, NULL)")
                val source = "SELECT REGEXP_COUNT(s, p, pos, 'im') AS c, REGEXP_INSTR(s, p, pos, occ, opt, 'im') AS i FROM t"
                val output = SqlFragment(source, "").transpileTo("duckdb")
                assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                statement.executeQuery(output.sql).use { rows ->
                    for ((count, position) in listOf(0L to 0L, 3L to 3L, 3L to 4L, 3L to 10L,
                        1L to 10L, 0L to 0L, null to null, null to null, 0L to null)) {
                        assertTrue(rows.next())
                        assertEquals(count, rows.getObject(1), output.sql)
                        assertEquals(position, rows.getObject(2), output.sql)
                    }
                }
            }
        }
    }

    @Test
    fun invalidSearchArgumentsAndUnrepresentableZeroWidthPositionsDoNotSucceedSilently() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (call in listOf("REGEXP_COUNT('abc', 'a', 0)", "REGEXP_INSTR('abc', 'a', 1, 0)",
                "REGEXP_INSTR('abc', 'a', 1, 1, 2)", "REGEXP_INSTR('abc', '^')")) {
                val output = SqlFragment("SELECT $call", "").transpileTo("duckdb")
                assertFailsWith<SQLException>(output.sql) {
                    connection.createStatement().use { it.executeQuery(output.sql).close() }
                }
            }
        }
    }
}
