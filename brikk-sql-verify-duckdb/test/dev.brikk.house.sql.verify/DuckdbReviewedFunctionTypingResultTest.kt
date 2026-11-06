package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class DuckdbReviewedFunctionTypingResultTest {
    @Test fun exactNativeResultMetadataAgreesWithAnnotatedCopySerdeAndExecution() {
        val queries = mutableListOf("SELECT binom(5,2) AS value", "SELECT decimal_average(x) AS value FROM (VALUES(1.23::DECIMAL(9,2)),(2.34::DECIMAL(9,2))) t(x)",
            "SELECT vertex_extract('POINT(1 2)'::GEOMETRY,'x') AS value",
            "SELECT lttb(x,y,3) AS value FROM (VALUES(1.0::DOUBLE,2.0::FLOAT),(2.0::DOUBLE,3.0::FLOAT),(3.0::DOUBLE,4.0::FLOAT)) t(x,y)")
        for ((precision, scale) in listOf(1 to 0, 18 to 3, 38 to 9, 38 to 38))
            queries += "SELECT decimal_average(x) AS value FROM (VALUES(0::DECIMAL($precision,$scale))) t(x)"
        for (x in listOf("FLOAT", "DOUBLE", "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "TIMESTAMPTZ", "TIMESTAMPTZ_NS")) for (y in listOf("FLOAT", "DOUBLE")) {
            val rows = (1..3).joinToString(",") { i ->
                val value = if (x in listOf("FLOAT", "DOUBLE")) "$i" else "'2024-01-0$i 00:00:00+00'"
                "(CAST($value AS $x),CAST($i AS $y))"
            }
            queries += "SELECT lttb(x,y,3) AS value FROM (VALUES $rows) t(x,y)"
        }
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("autoinstall_known_extensions", "false")
        }).use { c ->
            for (sql in queries) {
                val tree = annotateTypes(parseDuckdbNativeOne(sql), dialect = nativeDuckdbAnalysisDialect())
                val shapeType = assertNotNull(SqlFragment(sql, "duckdb").outputShape().columns.single().type, sql)
                val expected = dataTypeFromStr(shapeType, nativeDuckdbAnalysisDialect(), udt = true)
                assertFalse(expected.isType(DType.UNKNOWN), sql)
                for (form in listOf(sql, tree.copy().sql("duckdb"), Serde.loadExpression(Serde.dump(tree)).sql("duckdb"), SqlFragment(sql, "duckdb").toExecutable("duckdb").sql))
                    c.createStatement().use { s -> s.executeQuery(form).use { r ->
                        val actual = dataTypeFromStr(r.metaData.getColumnTypeName(1), nativeDuckdbAnalysisDialect(), udt = true)
                        assertEquals(expected.sql("duckdb"), actual.sql("duckdb"), form)
                        assertTrue(r.next())
                        assertNotNull(r.getObject(1))
                    } }
            }
        }
    }
    @Test fun polymorphicAndMacroMetadataAreNotMisrepresentedAsFixedDeclaredTypes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT typeof(invoke(LAMBDA x:x+1,2)),typeof(assert_true(true))").use { r ->
                assertTrue(r.next())
                    assertEquals("INTEGER", r.getString(1))
                    assertEquals("\"NULL\"", r.getString(2))
            } }
            // Both observed examples are narrower than the unbound registry contracts.
            for (sql in listOf("SELECT invoke(LAMBDA x:x+1,2) AS v", "SELECT assert_true(true) AS v"))
                assertEquals("UNKNOWN", SqlFragment(sql, "duckdb").outputShape().columns.single().type)
        }
    }
}
