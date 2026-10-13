package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.expressionNullability
import kotlin.test.Test
import kotlin.test.assertEquals

/** The nullability rules over an expression whose columns the caller resolves. */
class ExpressionNullabilityTest {
    private val verdicts = mapOf("a" to false, "b" to true)

    private fun nullable(sql: String) =
        expressionNullability(Dialects.DUCKDB.parseOne("SELECT $sql").expressionsArg.single() as dev.brikk.house.sql.ast.Expression, Dialects.DUCKDB) {
            verdicts[it.name]
        }

    @Test
    fun columnsTakeTheCallersVerdict() {
        assertEquals(false, nullable("a + 1"))
        assertEquals(true, nullable("a + b"))
        assertEquals(null, nullable("a + c"))
    }

    @Test
    fun theUsualRulesStillApply() {
        assertEquals(false, nullable("COALESCE(b, a)"))
        assertEquals(false, nullable("COUNT(b)"))
        assertEquals(true, nullable("SUM(a)"))
        assertEquals(false, nullable("b IS NULL"))
    }
}
