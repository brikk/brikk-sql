package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StringEscapeResultTest {
    @Test
    fun nativeDuckdbEscapeStringsHaveTheSameValueAfterGeneration() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((literal, expected) in listOf(
                    "e'\\a\\v\\x41\\101\\541'" to "avAAa",
                    "e'\\x4Z\\xzz\\z'" to "\u0004Zxzzz",
                    "e'\\\\n\\\\x41'" to "\\n\\x41",
                    "e'it\\'s\\\\path'" to "it's\\path",
                    "e'a\\nb\\r\\tc\\b\\f'" to "a\nb\r\tc\u0008\u000C",
                    "e'\\x07\\x0b\\x7f'" to "\u0007\u000B\u007F",
                    "'\\n\\x41'" to "\\n\\x41",
                    "\$\$\\n\\x41\$\$" to "\\n\\x41",
                )) {
                    val source = "SELECT $literal"
                    val output = SqlFragment(source, "duckdb").transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    for (sql in listOf(source, output.sql)) {
                        statement.executeQuery(sql).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, rows.getString(1), sql)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nativeUtf8ByteEscapesAndRejectedNulEscapesNormalizeToExecutableText() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val nativeUtf8 = "SELECT e'\\xc3\\xa9 \\360\\237\\230\\200'"
            connection.createStatement().use { statement -> statement.executeQuery(nativeUtf8).use { rows ->
                assertTrue(rows.next())
                assertEquals("é 😀", rows.getString(1), nativeUtf8)
            } }
            for ((body, expected) in listOf("a\\x00b" to "a\u0000b", "\\0000" to "\u00000")) {
                val source = "SELECT e'$body'"
                val failure = assertFailsWith<SQLException>(source) {
                    connection.createStatement().use { it.executeQuery(source).close() }
                }
                assertTrue("Null character not permitted" in failure.message.orEmpty(), failure.message)
                val output = SqlFragment(source, "duckdb").transpileTo("duckdb")
                assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                connection.createStatement().use { statement ->
                    statement.executeQuery(output.sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1), output.sql)
                    }
                }
            }
        }
    }

    @Test
    fun postgresEscapesAndSparkRawLiteralsTranslateToTheExpectedDuckdbText() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((source, dialect, expected) in listOf(
                    Triple("SELECT e'\\uD83D\\uDE00'", "postgres", "😀"),
                    Triple("SELECT e'\\U0001f600\\xC3\\xA9'", "postgres", "😀é"),
                    Triple("SELECT e'\\v\\a'", "postgres", "\u000Ba"),
                    Triple("SELECT r'\\n\\x41'", "spark", "\\n\\x41"),
                    Triple("SELECT r'a\u0000\\n'", "spark", "a\u0000\\n"),
                    Triple("SELECT '\\N\\e\\xc3\\xa9'", "clickhouse", "\u001Bé"),
                )) {
                    val output = SqlFragment(source, dialect).transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    statement.executeQuery(output.sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1), output.sql)
                    }
                }
                // Constructing a text AST with embedded NUL must not place a NUL in SQL source.
                val text = dev.brikk.house.sql.ast.ByteString(dev.brikk.house.sql.ast.args(
                    "this" to "a\u0000b", "is_bytes" to false))
                val output = "SELECT ${text.sql("duckdb")}"
                assertTrue('\u0000' !in output)
                statement.executeQuery(output).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("a\u0000b", rows.getString(1))
                }
            }
        }
    }
}
