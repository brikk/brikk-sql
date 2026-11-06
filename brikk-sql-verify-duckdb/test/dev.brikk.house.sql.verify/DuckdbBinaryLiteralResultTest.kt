package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/** Pinned 2.0.0 d54cbbe3df hex_string_literal/literal_round_trip/adjacent_string_literal tests. */
internal class DuckdbBinaryLiteralResultTest {
    private fun forms(sql: String) = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
        assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    @Test fun blobAndBitTypesValuesCastsEmptyLiteralsAndContinuationsMatchWithoutTextPrefixes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT typeof(X'FF'),typeof(B'00101'),hex(X'0041ff'),X'48656C6C6F'::VARCHAR,octet_length(X'DEADBEEF'),B'00101'::VARCHAR,bit_length(B'00101'),hex(X''),B''::VARCHAR,X'4'\n'142'::VARCHAR,B'00'\n'10'::VARCHAR"
            val expected = listOf(listOf("BLOB", "BIT", "0041FF", "Hello", "4", "00101", "5", "", "0", "AB", "0010"))
            for (form in forms(sql)) assertEquals(expected, c.rows(form), form)
            for (form in forms("SELECT X'0001FF' AS bytes,B'00101' AS bits")) c.createStatement().use { s -> s.executeQuery(form).use { r ->
                assertEquals("BLOB", r.metaData.getColumnTypeName(1))
                    assertEquals("BIT", r.metaData.getColumnTypeName(2))
                    assertTrue(r.next())
                assertContentEquals(byteArrayOf(0, 1, -1), r.getBytes(1))
                    assertEquals("00101", r.getString(2))
            } }
        }
    }
    @Test fun invalidDigitsOddHexAndIllegalAdjacentFormsAreNativeParserRejections() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (literal in listOf("X'F'", "X'gg'", "B'012'", "X'4\\x31'", "X'4''1'", "X'41' '42'", "X'41'\nX'42'")) {
                val sql = "SELECT $literal"
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type, sql)
                assertFails { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
            }
        }
    }
    @Test fun preparedBindingsAndPersistentViewDefinitionsKeepBinaryValuesAndNativeTypes() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT hex(CAST(? AS BLOB)),X'0041' = CAST(? AS BLOB),B'00101'::VARCHAR"
            c.prepareStatement(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql).use { p ->
                assertEquals(2, p.parameterMetaData.parameterCount)
                p.setBytes(1, byteArrayOf(0, 65))
                p.setBytes(2, byteArrayOf(0, 65))
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals("0041", r.getString(1))
                    assertTrue(r.getBoolean(2))
                    assertEquals("00101", r.getString(3))
                }
            }
            val view = "CREATE VIEW binary_literals AS SELECT X'0041' AS bytes,B'00101' AS bits"
            c.createStatement().use { it.execute(if (generated) SqlFragment(view, "duckdb").toExecutable("duckdb").sql else view) }
            assertEquals(listOf(listOf("BLOB", "BIT", "0041", "00101")), c.rows("SELECT typeof(bytes),typeof(bits),hex(bytes),bits::VARCHAR FROM binary_literals"))
        }
    }
}
