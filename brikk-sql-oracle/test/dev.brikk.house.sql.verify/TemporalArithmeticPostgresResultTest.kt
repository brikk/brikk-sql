package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.*

class TemporalArithmeticPostgresResultTest {
    @Test fun temporalGroupingAndTimestampaddHaveActualPostgresValuesAndTypes() {
        EmbeddedPostgres.builder().start().use { pg -> pg.postgresDatabase.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("SET TIME ZONE 'UTC'")
                for ((source, dialect, reference) in listOf(
                    Triple("SELECT TIMESTAMP '2024-01-03' - DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY)", "duckdb", "SELECT INTERVAL '1 day'"),
                    Triple("SELECT DATE_ADD(TIMESTAMP '2024-01-03', INTERVAL 1 DAY) - DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY)", "duckdb", "SELECT INTERVAL '2 days'"),
                    Triple("SELECT TIMESTAMPADD(SQL_TSI_MONTH, 1, '2024-01-31 12:00:00')", "mysql", "SELECT TIMESTAMP '2024-02-29 12:00:00'"),
                    Triple("SELECT TIMESTAMPADD(SQL_TSI_SECOND, -30, CAST('2024-01-01 12:00:00' AS DATETIME))", "mysql", "SELECT TIMESTAMP '2024-01-01 11:59:30'"),
                    Triple("SELECT TIMESTAMPADD(SQL_TSI_DAY, n + 1, CAST('2024-01-01 12:00:00' AS DATETIME)) FROM (SELECT 1 n UNION ALL SELECT NULL) t", "mysql", "SELECT TIMESTAMP '2024-01-03 12:00:00' UNION ALL SELECT NULL::TIMESTAMP"),
                    Triple("WITH t AS (SELECT CAST('2020-01-10' AS DATE) AS col, 5 AS num_days) SELECT DATE_ADD(col, INTERVAL num_days DAY) FROM t", "mysql", "SELECT TIMESTAMP '2020-01-15 00:00:00'"),
                    Triple("SELECT DATE_ADD(CAST(NULL AS TIMESTAMP), INTERVAL 1 DAY)", "duckdb", "SELECT NULL::TIMESTAMP"),
                )) {
                    fun observe(sql: String): Pair<String, List<String?>> = s.executeQuery(sql).use { r ->
                        r.metaData.getColumnTypeName(1) to buildList { while (r.next()) add(r.getString(1)) }
                    }
                    val output = SqlFragment(source, dialect).toExecutable("postgres").sql
                    assertEquals(observe(reference), observe(output), output)
                }
                s.execute("SET TIME ZONE 'America/New_York'")
                val source = "SELECT DATE_ADD(TIMESTAMPTZ '2024-03-09 12:00:00-05', INTERVAL 1 DAY)"
                // A finite typed-timestamptz control, not arbitrary zone equivalence.
                val output = SqlFragment(source, "duckdb").toExecutable("postgres").sql
                s.executeQuery(output).use { r -> assertTrue(r.next()); assertEquals("2024-03-10 12:00:00-04", r.getString(1)) }
                val zoneSource = "SELECT DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY) AT TIME ZONE 'UTC'"
                assertFailsWith<UnsupportedError> { SqlFragment(zoneSource, "duckdb").toExecutable("postgres") }
                s.executeQuery(transpile(zoneSource, "duckdb", "postgres")).use { r ->
                    assertTrue(r.next()); assertEquals("timestamptz", r.metaData.getColumnTypeName(1))
                    assertEquals("2024-01-01 19:00:00-05", r.getString(1))
                }
            }
        } }
    }
}
