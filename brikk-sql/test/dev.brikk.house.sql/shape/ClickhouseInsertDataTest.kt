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

    /** Empty data is explicit, ending where the header does; a terminator line's indentation is never data. */
    @Test fun aSemicolonEndingTheFormatLineOrItsOwnLineMeansEmptyData() {
        for (sql in listOf("INSERT INTO t FORMAT TSV;\nSELECT 2;", "INSERT INTO t FORMAT TSV ;  \nSELECT 2;", "INSERT INTO t FORMAT TSV\n;\nSELECT 2;",
            "INSERT INTO t FORMAT TSV\n  ;  \nSELECT 2;", "INSERT INTO t FORMAT TSV\n\t;\t\nSELECT 2;")) {
            val range = SqlScriptSnapshot.parse(sql, "clickhouse").statements.first()
            assertEquals(2, statements(sql).size, sql)
            assertEquals(listOf(""), data(sql), sql)
            assertEquals(sql.indexOf("TSV") + 3, range.dataEnd, sql)
            assertEquals(sql.indexOf(';'), range.terminator, sql)
        }
    }

    /** Header words are keywords: string literals and quoted names never count. */
    @Test fun stringsInTheHeaderAreNotKeywords() {
        for (value in listOf("'VALUES'", "'SELECT'", "'INFILE'", "\"VALUES\"")) {
            val sql = "INSERT INTO t SETTINGS insert_deduplication_token = $value FORMAT TSV\na;b\n\nSELECT 2;"
            assertEquals(listOf("a;b"), data(sql), sql)
        }
        assertEquals(listOf("a;b"), data("INSERT INTO t SELECT s FROM input('s String') WHERE s != 'FORMAT' FORMAT TSV\na;b\n\nSELECT 2;"))
    }

    @Test fun settingsAfterTheFormatMayHoldCommentsAndBlankLines() {
        for (settings in listOf("max_threads = 1 /* c */,\n\ninput_format_skip_unknown_fields = 1", "max_threads = 1 -- c\n, b = 2",
            "max_threads = 1\n\n, input_format_skip_unknown_fields = 1", "a = /* c */ 1 # c\n, b = 2")) {
            val sql = "INSERT INTO t FORMAT TSV SETTINGS $settings\na;b\n\nSELECT 2;"
            assertEquals(listOf("a;b"), data(sql), sql)
            assertEquals(2, statements(sql).size, sql)
        }
    }

    @Test fun aBlankLineRightAfterTheFormatEndsTheStatementWithoutData() {
        val sql = "INSERT INTO t FORMAT TSV\n\nSELECT 2;"
        assertEquals(listOf("INSERT INTO t FORMAT TSV", "SELECT 2;"), statements(sql))
        assertEquals(listOf(""), data(sql))
    }

    @Test fun consecutiveInsertsEachHaveTheirData() {
        val sql = "INSERT INTO t FORMAT TSV\na\n\nINSERT INTO t FORMAT TSV\nb;c\n\nSELECT 2;"
        assertEquals(listOf("a", "b;c"), data(sql))
        assertEquals(listOf("INSERT INTO t FORMAT TSV\na", "INSERT INTO t FORMAT TSV\nb;c", "SELECT 2;"), statements(sql))
    }

    @Test fun anInsertSelectReadingInputHasInlineData() {
        val sql = "INSERT INTO t SELECT s FROM input('s String') FORMAT TSV\na;b\n\nSELECT 2;"
        assertEquals(listOf("a;b"), data(sql))
        assertEquals(2, statements(sql).size)
    }

    @Test fun settingsAfterTheFormatComeBeforeData() {
        val sql = "INSERT INTO t FORMAT JSONEachRow SETTINGS input_format_skip_unknown_fields = 1,\n\nmax_threads = 1\n{\"s\":\"a\"}\n\nSELECT 2;"
        assertEquals(listOf("{\"s\":\"a\"}"), data(sql))
        assertEquals(2, statements(sql).size)
        assertEquals(listOf("1;x"), data("INSERT INTO t FORMAT TSV SETTINGS a = -1, b = 'x;y', c = [1, 2] 1;x\n\nSELECT 2;"))
        // Only on the format name's line: on the next line SETTINGS is data.
        assertEquals(listOf("SETTINGS a = 1"), data("INSERT INTO t FORMAT TSV\nSETTINGS a = 1\n\nSELECT 2;"))
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
