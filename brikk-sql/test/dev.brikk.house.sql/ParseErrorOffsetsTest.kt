package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.ParseErrorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ParseErrorOffsetsTest {
    private fun ParseErrorInfo.text(sql: String): String =
        sql.substring(assertNotNull(start), assertNotNull(end))

    @Test
    fun offsetsAddressTheOffendingTokenAfterSupplementaryCharactersAndCrlf() {
        val sql = "SELECT '\uD83D\uDE00\uD83D\uDE00' AS x,\r\n  CAST(a AS) FROM t"
        val error = assertFailsWith<ParseError> { Dialects.DUCKDB.parse(sql) }.errors.single()
        assertEquals(sql.indexOf(')'), error.start)
        assertEquals(")", error.text(sql))
    }

    @Test
    fun offsetsAreAbsoluteInMultiStatementScripts() {
        val sql = "SELECT a FROM t;\r\nSELECT '\uD83D\uDE00', FROM"
        val error = assertFailsWith<ParseError> { Dialects.TRINO.parse(sql) }.errors.single()
        assertEquals(sql.lastIndexOf("FROM"), error.start)
        assertEquals("FROM", error.text(sql))
    }

    @Test
    fun collectedErrorsKeepTheirOffsets() {
        val sql = "SELECT '\uD83D\uDE00' AS x,\r\n  CAST(a AS) FROM t"
        val parser = Dialects.DORIS.parser(ErrorLevel.RAISE)
        val errors = assertFailsWith<ParseError> { parser.parse(Dialects.DORIS.tokenize(sql), sql) }.errors
        assertEquals(sql.indexOf(')'), errors.first().start)
        assertEquals(errors.map { it.highlight }, errors.map { it.text(sql) })
    }
}
