package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParserCancellationTest {
    @Test
    fun publicCancellableEntryPointRestoresParserAfterCancellation() {
        val parser = Dialects.SPICE.parser()
        val sql = "SELECT 1"
        val tokens = Dialects.SPICE.tokenize(sql)
        val cancellation = IllegalStateException("cancel editor parse")
        assertSame(cancellation, assertFailsWith<IllegalStateException> { parser.parse(tokens, sql) { throw cancellation } })
        assertEquals(1, parser.parse(tokens, sql).size)
    }

    @Test
    fun cancellationIsCheckedDuringLongStatements() {
        val sql = "SELECT " + (1..2000).joinToString(",") { "$it AS c$it" }
        var checks = 0
        val cancellation = IllegalStateException("cancel while parsing")
        assertSame(cancellation, assertFailsWith<IllegalStateException> {
            Dialects.DATAFUSION.parser().parse(Dialects.DATAFUSION.tokenize(sql), sql) {
                if (++checks == 4) throw cancellation
            }
        })
        assertTrue(checks >= 4)
    }
}
