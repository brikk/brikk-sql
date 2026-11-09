package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.*

/** Finite preview controls, not general ClickHouse String -> PostgreSQL JSON equivalence. */
class ClickhouseJsonPostgresResultTest {
    @Test fun rawPreviewRetainsJsonValuesAndShowsMissingVersusJsonNullTypes() {
        EmbeddedPostgres.builder().start().use { pg -> pg.postgresDatabase.connection.use { c ->
            val doc = "'{\"obj\":{\"b\":1},\"arr\":[10,20],\"s\":\"hello\",\"n\":12,\"null\":null,\"12\":\"key\"}'"
            for ((segment, expected) in listOf("'obj'" to "{\"b\":1}", "'arr'" to "[10,20]",
                "'s'" to "\"hello\"", "'n'" to "12", "'null'" to "null", "'missing'" to null,
                "'12'" to "\"key\"", "'arr', 1" to "10")) {
                val source = "SELECT JSONExtractRaw($doc, $segment) AS v"
                assertFailsWith<UnsupportedError> { SqlFragment(source, "clickhouse").toExecutable("postgres") }
                val preview = transpile(source, "clickhouse", "postgres")
                c.createStatement().use { s -> s.executeQuery(preview).use { r ->
                    assertEquals("json", r.metaData.getColumnTypeName(1))
                    assertTrue(r.next()); assertEquals(expected, r.getString(1), preview)
                } }
            }
            c.createStatement().use { s ->
                s.executeQuery(transpile("SELECT JSONExtractString($doc, 's')", "clickhouse", "postgres")).use { r ->
                    assertEquals("text", r.metaData.getColumnTypeName(1)); assertTrue(r.next()); assertEquals("hello", r.getString(1))
                }
                s.executeQuery("SELECT JSON_EXTRACT_PATH(CAST(NULL AS JSON), 'a')").use { r ->
                    assertTrue(r.next()); assertNull(r.getString(1))
                }
            }
        } }
    }
}
