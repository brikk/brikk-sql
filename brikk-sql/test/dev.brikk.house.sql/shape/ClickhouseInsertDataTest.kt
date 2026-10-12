package dev.brikk.house.sql.shape

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/brikk-extensions.md #55: ClickHouse inline INSERT data framing. */
class ClickhouseInsertDataTest {
    private fun statements(sql: String, dialect: String = "clickhouse"): List<String> =
        SqlScriptSnapshot.parse(sql, dialect).statements.filter { it.hasSql }.map { sql.substring(it.start, it.end).trim() }

    private fun data(sql: String): List<String> =
        Tokenizer(Dialects.forName("clickhouse").tokenizerConfig).tokenizeForEditor(sql).tokens
            .filter { it.tokenType == TokenType.INSERT_DATA }.map { it.text }

    @Test fun aSemicolonAloneOnItsLineEndsData() {
        val sql = "INSERT INTO t FORMAT TSV\na;b\n;\nSELECT 2;"
        assertEquals(listOf("INSERT INTO t FORMAT TSV\na;b\n;", "SELECT 2;"), statements(sql))
        assertEquals(listOf("a;b"), data(sql))
        assertEquals(sql.indexOf("\n;") + 1, SqlScriptSnapshot.parse(sql, "clickhouse").statements.first().terminator)
    }

    @Test fun aBlankLineEndsDataAndItsStatement() {
        val sql = "INSERT INTO t FORMAT CSV\n1,O'Brien\n2,\"x;y\"\n\nSELECT 2;"
        val ranges = SqlScriptSnapshot.parse(sql, "clickhouse").statements
        assertEquals(listOf("INSERT INTO t FORMAT CSV\n1,O'Brien\n2,\"x;y\"", "SELECT 2;"), statements(sql))
        assertTrue(ranges.all { it.boundaryError == null })
        assertEquals(null, ranges.first().terminator)
    }

    @Test fun dataOnTheFormatLineEndsAtASemicolonEndingThatLine() {
        assertEquals(listOf("INSERT INTO t FORMAT JSONEachRow {\"id\":1};", "SELECT 2;"),
            statements("INSERT INTO t FORMAT JSONEachRow {\"id\":1};\nSELECT 2;"))
        assertEquals(listOf("{\"id\":1}"), data("INSERT INTO t FORMAT JSONEachRow {\"id\":1};\nSELECT 2;"))
    }

    /** As in clickhouse-client and the platform editor, a `;` ending a data line is data. */
    @Test fun aSemicolonEndingALaterDataLineIsData() {
        val sql = "INSERT INTO t FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;"
        assertEquals(listOf(sql), statements(sql))
        assertEquals(listOf("{\"id\":1};\nSELECT 2"), data(sql))
    }

    @Test fun aFinalSemicolonEndsTheStatement() {
        val sql = "INSERT INTO t FORMAT JSONEachRow\n{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2};\n"
        assertEquals(sql.lastIndexOf(';') + 1, SqlScriptSnapshot.parse(sql, "clickhouse").statements.first().end)
        assertEquals(listOf("{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2}"), data(sql))
    }

    @Test fun valuesAndInsertSelectAreSql() {
        assertEquals(listOf("INSERT INTO t FORMAT Values (1, 'a;b');", "SELECT 2;"), statements("INSERT INTO t FORMAT Values (1, 'a;b');\nSELECT 2;"))
        for (sql in listOf("INSERT INTO t VALUES (1, ';');\nSELECT 2;", "INSERT INTO t SELECT * FROM s FORMAT TSV;\nSELECT 2;",
            "INSERT INTO t FROM INFILE 'a;b.csv' FORMAT CSV;\nSELECT 2;", "INSERT INTO t FORMAT CSV;\nSELECT 2;")) {
            assertEquals(2, statements(sql).size, sql)
            assertTrue(data(sql).isEmpty(), sql)
        }
    }

    @Test fun columnsSettingsAndTableFunctionsKeepTheRule() {
        for (head in listOf("INSERT INTO t (a, b)", "INSERT INTO db.t SETTINGS async_insert = 1", "INSERT INTO FUNCTION s3('x', 'CSV') PARTITION BY a")) {
            val sql = "$head FORMAT CSV\n1,'\n;\nSELECT 2;"
            assertEquals(listOf("$head FORMAT CSV\n1,'\n;", "SELECT 2;"), statements(sql), head)
        }
    }

    @Test fun onlyClickhouseEditorTokenizationFramesData() {
        val sql = "INSERT INTO t FORMAT CSV\nO'Brien\n\nSELECT 2;"
        assertNotNull(SqlScriptSnapshot.parse(sql, "mysql").statements.single().boundaryError)
        assertFalse(Tokenizer(Dialects.forName("clickhouse").tokenizerConfig).tokenize("INSERT INTO t FORMAT TSV\n1\t2")
            .any { it.tokenType == TokenType.INSERT_DATA })
    }
}
