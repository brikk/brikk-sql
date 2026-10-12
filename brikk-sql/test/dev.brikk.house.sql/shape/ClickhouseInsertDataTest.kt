package dev.brikk.house.sql.shape

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/brikk-extensions.md #55: ClickHouse inline INSERT data framing. */
class ClickhouseInsertDataTest {
    private fun statements(sql: String, dialect: String = "clickhouse"): List<String> =
        SqlScriptSnapshot.parse(sql, dialect).statements.filter { it.hasSql }.map { sql.substring(it.start, it.end).trim() }

    private fun data(sql: String): List<String> =
        Tokenizer(Dialects.forName("clickhouse").tokenizerConfig).tokenizeForEditor(sql).tokens
            .filter { it.tokenType == TokenType.INSERT_DATA }.map { it.text }

    @Test fun aSemicolonAloneOnItsLineEndsData() {
        val sql = "INSERT INTO t FORMAT TSV\na;b\n  ;\t\nSELECT 2;"
        assertEquals(listOf("INSERT INTO t FORMAT TSV\na;b\n  ;", "SELECT 2;"), statements(sql))
        assertEquals(listOf("a;b"), data(sql))
        val range = SqlScriptSnapshot.parse(sql, "clickhouse").statements.first()
        assertEquals(sql.indexOf(";\t"), range.terminator)
        assertEquals(sql.indexOf("a;b") + 3, range.dataEnd)
    }

    @Test fun aBlankLineEndsDataAndItsStatement() {
        val sql = "INSERT INTO t FORMAT CSV\n1,O'Brien\n2,\"x;y\"\n\nSELECT 2;"
        val ranges = SqlScriptSnapshot.parse(sql, "clickhouse").statements
        assertEquals(listOf("INSERT INTO t FORMAT CSV\n1,O'Brien\n2,\"x;y\"", "SELECT 2;"), statements(sql))
        assertTrue(ranges.all { it.boundaryError == null })
        assertNull(ranges.first().terminator)
    }

    @Test fun lineBreaksMayBeCrlf() {
        assertEquals(listOf("a;b"), data("INSERT INTO t FORMAT TSV\r\na;b\r\n;\r\nSELECT 2;"))
        assertEquals(2, statements("INSERT INTO t FORMAT TSV\r\na;b\r\n;\r\nSELECT 2;").size)
        assertEquals(listOf("a;b"), data("INSERT INTO t FORMAT TSV\r\na;b\r\n\r\nSELECT 2;"))
        assertEquals(2, statements("INSERT INTO t FORMAT TSV\r\na;b\r\n\r\nSELECT 2;").size)
    }

    /** As TSV and CSV store it, a `;` ending a data line is data, on the format's line too. */
    @Test fun aSemicolonEndingADataLineIsData() {
        for ((sql, payload) in listOf(
            "INSERT INTO t FORMAT TSV\na;\nSELECT 'NEXT';" to "a;\nSELECT 'NEXT';",
            "INSERT INTO t FORMAT TSV a;\nSELECT 'NEXT';" to "a;\nSELECT 'NEXT';",
            "INSERT INTO t FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;" to "{\"id\":1};\nSELECT 2;",
        )) {
            assertEquals(listOf(sql), statements(sql), sql)
            assertEquals(listOf(payload), data(sql), sql)
        }
    }

    @Test fun dataRunsToTheRealEndOfTheText() {
        val sql = "INSERT INTO t FORMAT JSONEachRow\n{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2};\n"
        val range = SqlScriptSnapshot.parse(sql, "clickhouse").statements.single()
        assertEquals(sql.length, range.end)
        assertNull(range.terminator)
        assertEquals(listOf("{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2};\n"), data(sql))
    }

    @Test fun aSemicolonEndingTheFormatLineMeansNoData() {
        for (sql in listOf("INSERT INTO t FORMAT TSV;\nSELECT 2;", "INSERT INTO t FORMAT TSV ;  \nSELECT 2;", "INSERT INTO t FORMAT TSV\n;\nSELECT 2;")) {
            assertEquals(2, statements(sql).size, sql)
            assertTrue(data(sql).isEmpty(), sql)
        }
    }

    @Test fun aBlankLineRightAfterTheFormatEndsTheStatementWithoutData() {
        val sql = "INSERT INTO t FORMAT TSV\n\nSELECT 2;"
        assertEquals(listOf("INSERT INTO t FORMAT TSV", "SELECT 2;"), statements(sql))
        assertEquals(listOf(""), data(sql))
    }

    @Test fun valuesInsertSelectAndInfileAreSql() {
        assertEquals(listOf("INSERT INTO t FORMAT Values (1, 'a;b');", "SELECT 2;"), statements("INSERT INTO t FORMAT Values (1, 'a;b');\nSELECT 2;"))
        for (sql in listOf("INSERT INTO t VALUES (1, ';');\nSELECT 2;", "INSERT INTO t SELECT * FROM s FORMAT TSV;\nSELECT 2;",
            "INSERT INTO t FROM INFILE 'a;b.csv' FORMAT CSV;\nSELECT 2;")) {
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
