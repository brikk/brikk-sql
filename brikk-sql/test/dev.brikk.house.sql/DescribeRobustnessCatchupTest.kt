package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Describe
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SG29-002, upstream d6e78751: rewind a dot only after consuming a kind or style. */
class DescribeRobustnessCatchupTest {
    private val dialects = listOf("", "mysql", "doris", "starrocks", "postgres", "duckdb",
        "clickhouse", "hive", "spark2", "spark", "presto", "trino", "bigquery", "datafusion", "spice")

    @Test
    fun incompleteDescribeDotRaisesPositionedErrorsRatherThanRecursing() {
        for (dialect in dialects) {
            for (sql in listOf("DESC.", "DESCRIBE.", "DESC. ;", "DESC .", "DESCRIBE . ;")) {
                for (level in listOf(ErrorLevel.RAISE, ErrorLevel.IMMEDIATE)) {
                    val reader = Dialects.forName(dialect)
                    val parser = reader.parser(level)
                    val error = assertFailsWith<ParseError>("$dialect $level: $sql") {
                        parser.parse(reader.tokenize(sql), sql)
                    }
                    val info = error.errors.first()
                    assertTrue(assertNotNull(info.start) in 0..sql.length, error.message)
                    assertTrue(assertNotNull(info.end) in info.start..sql.length, error.message)
                    assertNotNull(info.line)
                    assertNotNull(info.col)
                    // A failed edit must not poison a parser reused for the next edit.
                    val valid = "DESCRIBE tbl"
                    assertIs<Describe>(parser.parse(reader.tokenize(valid), valid).single())
                }
            }
        }
    }

    @Test
    fun permissiveErrorLevelsAlsoTerminate() {
        for (dialect in dialects) {
            val reader = Dialects.forName(dialect)
            for (level in listOf(ErrorLevel.IGNORE, ErrorLevel.WARN)) {
                reader.parser(level).parse(reader.tokenize("DESC."), "DESC.")
            }
        }
    }

    @Test
    fun kindAndStyleWordsCanStillNameADatabase() {
        for (dialect in listOf("", "spark", "mysql", "duckdb", "clickhouse")) {
            for (db in listOf("history", "formatted", "extended", "table")) {
                val tree = assertIs<Describe>(parseOne("DESCRIBE $db.tbl", dialect))
                val table = assertIs<Table>(tree.thisArg)
                assertEquals("tbl", table.name)
                assertEquals(db, (table.args["db"] as dev.brikk.house.sql.ast.Identifier).name)
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
            }
        }
        assertEquals("DESCRIBE EXTENDED tbl", parseOne("DESCRIBE EXTENDED tbl", "spark").sql("spark"))
    }

    @Test
    fun describedCallsAndDorisAllKeepTheirNativeContracts() {
        for ((sql, expected) in listOf(
            "DESCRIBE TABLE numbers(3)" to "DESCRIBE numbers(3)",
            "DESCRIBE TABLE format('TSV', 'a UInt64', '1')" to "DESCRIBE format('TSV', 'a UInt64', '1')",
            "DESCRIBE TABLE merge('db', 't')" to "DESCRIBE merge('db', 't')",
            "DESCRIBE TABLE view(SELECT 1 AS a)" to "DESCRIBE view(SELECT 1 AS a)",
        )) {
            val tree = parseOne(sql, "clickhouse")
            assertEquals(expected, tree.sql("clickhouse"))
            assertEquals(expected, Serde.loadExpression(Serde.dump(tree)).sql("clickhouse"))
        }
        assertEquals("DESCRIBE db.t ALL", parseOne("DESCRIBE db.t ALL", "doris").sql("doris"))
    }

    @Test
    fun editorDiagnosticStaysInsideTheMalformedStatementAfterSupplementaryText() {
        val prefix = "-- 😀 preceding statement\nSELECT 1;\n"
        val sql = prefix + "DESC .;"
        val diagnostic = localParseDiagnostics(sql, prefix.length until sql.length, "mysql").single()
        assertTrue(diagnostic.start >= prefix.length)
        assertTrue(diagnostic.end <= sql.length)
    }
}
