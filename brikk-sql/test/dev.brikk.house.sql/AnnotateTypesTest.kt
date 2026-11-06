package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Schema-aware annotate_types assertions. Every expectation below was verified
 * against Python first:
 *
 *   annotate_types(parse_one(sql), schema={"t": {"a": "INT", "b": "VARCHAR"}})
 *
 * Columns must be table-qualified: annotate_types only resolves Columns that carry a
 * table reference (Python runs `qualify` first in the optimizer pipeline).
 */
class AnnotateTypesTest {

    private fun firstSelectType(sql: String): String {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "INT", "b" to "VARCHAR")))
        val expression = annotateTypes(Dialects.BASE.parseOne(sql), schema = schema)
        val type = expression.selects[0].type as DataType
        return Dialects.BASE.generate(type)
    }

    @Test
    fun columnPlusIntLiteral() {
        assertEquals("INT", firstSelectType("SELECT t.a + 1 FROM t"))
    }

    @Test
    fun columnPlusDoubleLiteral() {
        assertEquals("DOUBLE", firstSelectType("SELECT t.a + 1.5 FROM t"))
    }

    @Test
    fun concatIsVarchar() {
        assertEquals("VARCHAR", firstSelectType("SELECT CONCAT(t.b, 'x') FROM t"))
    }

    @Test
    fun caseBranchesCoerce() {
        assertEquals(
            "DOUBLE",
            firstSelectType("SELECT CASE WHEN t.a > 0 THEN t.a ELSE 1.5 END FROM t"),
        )
    }

    @Test
    fun arrayOfColumn() {
        assertEquals("ARRAY<INT>", firstSelectType("SELECT ARRAY(t.a) FROM t"))
    }

    @Test
    fun subqueryProjectionPropagation() {
        assertEquals(
            "INT",
            firstSelectType("SELECT x.a2 FROM (SELECT t.a AS a2 FROM t) AS x"),
        )
    }

    @Test
    fun setOperationCoercion() {
        assertEquals(
            "DOUBLE",
            firstSelectType(
                "SELECT u.c FROM (SELECT t.a AS c FROM t UNION ALL SELECT 1.5 AS c FROM t) AS u"
            ),
        )
    }

    // NULL literal -> rewritten to the dialect's DEFAULT_NULL_TYPE (base: UNKNOWN)
    @Test
    fun nullLiteralGetsDefaultNullType() {
        assertEquals("UNKNOWN", firstSelectType("SELECT NULL"))
    }

    @Test
    fun coalesceNullCoercesAway() {
        assertEquals("INT", firstSelectType("SELECT COALESCE(t.a, NULL) FROM t"))
    }

    @Test
    fun scalarSubqueryType() {
        assertEquals("VARCHAR", firstSelectType("SELECT (SELECT t.b FROM t) FROM t"))
    }

    // Unqualified columns stay UNKNOWN (annotate_types expects qualified input)
    @Test
    fun unqualifiedColumnIsUnknown() {
        assertEquals("UNKNOWN", firstSelectType("SELECT a + 1 FROM t"))
    }
}
