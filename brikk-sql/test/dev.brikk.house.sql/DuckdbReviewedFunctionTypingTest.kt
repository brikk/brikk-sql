package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbReviewedFunctionTypingTest {
    @Test fun nativeExactSignaturesGiveTypesWithoutChangingTheHistoricalAnnotator() {
        for ((body, expected) in listOf("binom(5,2)" to "INT128", "decimal_average(1.23::DECIMAL(9,2))" to "DECIMAL(9, 2)",
            "vertex_extract('POINT(1 2)'::GEOMETRY,'x')" to "DOUBLE")) {
            val sql = "SELECT $body AS result"
            assertEquals(expected, SqlFragment(sql, "duckdb").outputShape().columns.single().type, body)
            assertEquals(expected, RelationScope.analyze(sql, "duckdb", RelationFacts()).output!!.columns.single().sqlType, body)
            val native = annotateTypes(parseDuckdbNativeOne(sql), dialect = nativeDuckdbAnalysisDialect())
            assertFalse(((native.expressionsArg.single() as Expression).type as DataType).isType(DType.UNKNOWN))
            val old = annotateTypes(Dialects.DUCKDB.parseOne(sql), dialect = Dialects.DUCKDB)
            assertTrue(((old.expressionsArg.single() as Expression).type as DataType).isType(DType.UNKNOWN))
            assertEquals(native, Serde.load(Serde.dump(native)))
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("trino") }
        }
    }
    @Test fun structuredLttbResultFlowsThroughDerivedScopeAndCompletion() {
        val sql = "SELECT lttb(x,y,3) AS points FROM t"
        val facts = RelationFacts(tables = mapOf("t" to RelationFact(listOf(ColumnFact("x", "DOUBLE"), ColumnFact("y", "FLOAT")))))
        val type = RelationScope.analyze(sql, "duckdb", facts).output!!.columns.single().sqlType!!
        assertTrue("STRUCT" in type && "x DOUBLE" in type && "y FLOAT" in type, type)
        val outer = "WITH q AS ($sql) SELECT q. FROM q"
        val item = RelationScope.complete(outer, "duckdb", facts, outer.lastIndexOf("q.")+2).items.single { it.name == "points" }
        assertEquals(type, item.type)
    }
    @Test fun missingTypesWrongAritiesQualifiedUdfsAndDynamicMacroResultsStayUnknown() {
        for (body in listOf("binom(x,y)", "binom(1)", "custom.binom(5,2)", "lttb(x,y,n)", "invoke(LAMBDA x:x+1,2)", "assert_true(true)", "vertex_extract(g,'x')",
            "variant_type(1::VARIANT,[1])")) {
            val type = SqlFragment("SELECT $body AS result", "duckdb").outputShape().columns.single().type
            assertTrue(type == null || type == "UNKNOWN", "$body -> $type")
        }
    }
}
