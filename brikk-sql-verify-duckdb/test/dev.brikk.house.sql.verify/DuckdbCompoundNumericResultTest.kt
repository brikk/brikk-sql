package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbCompoundNumericResultTest {
    @Test fun reviewedSumSignaturesMatchNativeMetadataAndPreserveNullEmptyDistinctAndWindowCases() {
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply { setProperty("enable_external_access", "false") }).use { c ->
            for (input in listOf("BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE", "DECIMAL(1,0)", "DECIMAL(9,2)", "DECIMAL(38,38)")) {
                val value = if (input == "BOOLEAN") "true" else "0"
                for (body in listOf("sum(x)", "sum(DISTINCT x)", "sum(x) OVER()")) {
                    val sql = "SELECT $body AS n FROM (SELECT $value::$input AS x)t"
                    val shape = SqlFragment(sql, "duckdb").outputShape().columns.single().type!!
                    for (text in listOf(sql, parseDuckdbNativeOne(sql).copy().sql("duckdb"))) c.createStatement().use { s -> s.executeQuery(text).use { r ->
                        val native = dataTypeFromStr(r.metaData.getColumnTypeName(1), nativeDuckdbAnalysisDialect(), udt = true).sql("duckdb")
                        assertEquals(dataTypeFromStr(shape, nativeDuckdbAnalysisDialect(), udt = true).sql("duckdb"), native, text)
                        assertTrue(r.next())
                    } }
                }
            }
            for (sql in listOf("SELECT sum(x) FROM (SELECT 1::INTEGER x WHERE false)t", "SELECT sum(NULL)"))
                c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    assertTrue(r.next())
                    assertNull(r.getObject(1))
                    assertEquals("HUGEINT", r.metaData.getColumnTypeName(1))
                }
            }
        }
    }
    @Test fun nativeTypeAndMacroBindingBoundariesAreNotConfusedWithSyntaxOrHostNumericCoercion() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fun execute(sql: String) = c.createStatement().use { it.execute(sql) }
            for (sql in listOf("SELECT typeof({}),typeof(struct_pack()),typeof(NULL::STRUCT)",
                "SELECT INTEGER '42',DECIMAL(9,2) '1.25'")) {
                val tree = parseDuckdbNativeOne(sql)
                for (text in listOf(sql, assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb")))
                    c.createStatement().use { s ->
                    s.executeQuery(text).use { r ->
                        assertTrue(r.next())
                        assertNotNull(r.getObject(1))
                    }
                }
            }
            for (sql in listOf("SELECT 1::DECIMAL(4.0,2)", "SELECT 1::DECIMAL(4,2.0)")) {
                assertIs<SerializedSql.Parsed>(c.serializeSql(sql))
                val error = assertFailsWith<SQLException> { execute(sql) }
                assertTrue("Binder Error" in error.message.orEmpty())
            }
            assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql("SELECT NULL::STRUCT()")).type)
            // Internal TUPLE is accepted by the engine's dynamic type binder, but a
            // NULL cast is no proof of a public value/construction/typing contract.
            assertIs<SerializedSql.Parsed>(c.serializeSql("SELECT NULL::TUPLE(INTEGER)"))
            execute("CREATE MACRO audit_tiny(x TINYINT := 127) AS x")
            execute(parseDuckdbNativeOne("CREATE MACRO audit_generated(x TINYINT := 127) AS x").sql("duckdb"))
            c.createStatement().use { s ->
                s.executeQuery("SELECT audit_tiny(),typeof(audit_tiny())").use { r ->
                    assertTrue(r.next())
                    assertEquals(127, r.getInt(1))
                    assertEquals("TINYINT", r.getString(2))
                }
            }
            assertFailsWith<SQLException> { execute("CREATE MACRO audit_bad(x TINYINT := 128) AS x") }
        }
    }
}
