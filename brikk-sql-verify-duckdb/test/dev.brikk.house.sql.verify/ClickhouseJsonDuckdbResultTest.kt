package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.*

internal class ClickhouseJsonDuckdbResultTest {
    @Test fun rawPreviewKeepsObjectsArraysAndNumericKeysButDoesNotQualifyMissingValues() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val doc = "'{\"obj\":{\"b\":1},\"arr\":[10,20],\"s\":\"hello\",\"null\":null,\"12\":\"key\"}'"
            for ((segment, expected) in listOf("'obj'" to "{\"b\":1}", "'arr'" to "[10,20]",
                "'s'" to "\"hello\"", "'null'" to "null", "'missing'" to null,
                "'12'" to "\"key\"", "'arr', 1" to "10")) {
                val source = "SELECT JSONExtractRaw($doc, $segment) AS v"
                assertFailsWith<UnsupportedError> { SqlFragment(source, "clickhouse").toExecutable("duckdb") }
                val preview = transpile(source, "clickhouse", "duckdb")
                c.createStatement().use { s -> s.executeQuery(preview).use { r ->
                    assertEquals("JSON", r.metaData.getColumnTypeName(1))
                    assertTrue(r.next()); assertEquals(expected, r.getString(1), preview)
                } }
            }
        }
    }
}
