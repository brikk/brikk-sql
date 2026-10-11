package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.TimeSlice
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StarrocksTimeSliceCatchupTest {
    @Test
    fun intervalAmountsAndBoundariesHaveDedicatedAstAndStableNativeSpelling() {
        for ((suffix, kind) in listOf("" to null, ", FLOOR" to "START", ", CEIL" to "END")) {
            val source = "SELECT TIME_SLICE(dt, INTERVAL 5 MINUTE$suffix)"
            val tree = Dialects.STARROCKS.parseOne(source)
            val slice = tree.findAll<TimeSlice>().single()
            assertEquals("5", (slice.expressionArg as Literal).name)
            assertTrue(!(slice.expressionArg as Literal).isString)
            assertEquals("MINUTE", (slice.args["unit"] as dev.brikk.house.sql.ast.Expression).name)
            assertEquals(kind, (slice.args["kind"] as? Literal)?.name)
            assertEquals(source, tree.sql("starrocks"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
        assertEquals("SELECT TIME_SLICE(dt, INTERVAL 1 DAY, CEIL)",
            Dialects.STARROCKS.parseOne("SELECT TIME_SLICE(dt, 1, 'DAY', 'END')").sql("starrocks"))
        assertFailsWith<ParseError> {
            Dialects.STARROCKS.parseOne("SELECT TIME_SLICE(dt, INTERVAL 1 DAY, something_else)")
        }
    }

    @Test
    fun starrocksTranslationUsesItsOwnBucketOriginWithoutChangingOtherSourceContexts() {
        val fragment = SqlFragment("SELECT TIME_SLICE(dt, INTERVAL 3 DAY, FLOOR)", "starrocks")
        val result = fragment.transpileTo("duckdb")
        assertTrue("0001-01-01 00:00:00" in result.sql, result.sql)
        assertTrue(result.unsupportedMessages.isEmpty(), result.toString())
        val generic = fragment.ast.sql("duckdb")
        assertEquals("SELECT TIME_BUCKET(INTERVAL 3 DAY, dt)", generic)
        val ceil = SqlFragment("SELECT TIME_SLICE(dt, INTERVAL 1 DAY, CEIL)", "starrocks").transpileTo("duckdb")
        assertTrue(ceil.sql.endsWith("+ INTERVAL 1 DAY"), ceil.sql)
    }
}
