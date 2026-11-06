package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.SAFE_IDENTIFIER_RE
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class DialectHelperTest {
    @Test
    fun identifierRegexSpellingsAgree() {
        val spellings = listOf(Regex("^[_a-zA-Z][\\w]*$"), Regex("^[_a-zA-Z]\\w*$"))
        val names = listOf("", "_", "aZ_09", "0a", "a\$b", "a\nb", "a\n", "é", "aé", "a😀")
        for (regex in spellings) {
            for (name in names) assertEquals(SAFE_IDENTIFIER_RE.matches(name), regex.matches(name), name)
            for (char in Char.MIN_VALUE..Char.MAX_VALUE) {
                val first = char.toString()
                val tail = "a$char"
                assertEquals(SAFE_IDENTIFIER_RE.matches(first), regex.matches(first), "first ${char.code}")
                assertEquals(SAFE_IDENTIFIER_RE.matches(tail), regex.matches(tail), "tail ${char.code}")
            }
        }
    }

    @Test
    fun mappedParserFormatsMatchTheirFixedDialect() {
        val cases = listOf(
            Triple("bigquery", "FORMAT_DATE('%F', x)", "%F"),
            Triple("presto", "DATE_FORMAT(x, '%i')", "%i"),
            Triple("hive", "DATE_FORMAT(x, 'yyyy-MM-dd')", "yyyy-MM-dd"),
            Triple("mysql", "DATE_FORMAT(x, '%i')", "%i"),
            Triple("postgres", "TO_CHAR(x, 'YYYY-MM-DD')", "YYYY-MM-DD"),
        )
        for ((name, sql, format) in cases) {
            val expected = Dialects.forName(name).formatTime(Literal.string(format))
            val actual = assertIs<Literal>(parseOne(sql, name).args["format"], name)
            assertEquals(expected?.thisArg, actual.thisArg, name)
            assertEquals(true, actual.isString, name)
        }
    }

    @Test
    fun clickhouseKeepsEmptyFormatsUnlikeTheBaseHelper() {
        val format = assertIs<Literal>(parseOne("formatDateTime(x, '')", "clickhouse").args["format"])
        assertEquals("", format.thisArg)
        assertNull(Dialects.CLICKHOUSE.formatTime(Literal.string(""))?.thisArg)
    }

    @Test
    fun baseFormatHelperPassesThroughNonStrings() {
        val number = Literal.number("1")
        assertSame(number, Dialects.MYSQL.formatTime(number))
        assertNull(Dialects.MYSQL.formatTime(null))
    }

    @Test
    fun dorisAndStarrocksDefaultBareDateDeltasToDays() {
        for (dialect in listOf("doris", "starrocks")) {
            for ((sql, expectedUnit) in listOf("DATE_ADD(x, 7)" to "DAY", "DATE_ADD(x, INTERVAL 7 HOUR)" to "HOUR")) {
                val delta = assertIs<DateAdd>(parseOne(sql, dialect), dialect)
                assertEquals(expectedUnit, assertIs<Var>(delta.args["unit"]).name, dialect)
                assertEquals("7", assertIs<Literal>(delta.args["expression"]).name, dialect)
            }
        }
    }
}
