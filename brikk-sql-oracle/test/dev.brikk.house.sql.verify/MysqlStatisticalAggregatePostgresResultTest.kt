package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.math.sqrt
import kotlin.test.*

/** SG29-008: private PostgreSQL result/type controls for MySQL-family source aliases. */
class MysqlStatisticalAggregatePostgresResultTest {
    @Test fun aliasesHavePopulationValuesIncludingEmptyNullAndSingletonInputs() {
        EmbeddedPostgres.builder().start().use { pg -> pg.postgresDatabase.connection.use { c ->
            for ((input, expected) in listOf(
                "SELECT CAST(NULL AS DOUBLE) x WHERE FALSE" to listOf(null, null, null, null),
                "SELECT CAST(NULL AS DOUBLE) x" to listOf(null, null, null, null),
                "SELECT CAST(2 AS DOUBLE) x" to listOf(0.0, null, 0.0, null),
                "SELECT CAST(0 AS DOUBLE) x UNION ALL SELECT CAST(2 AS DOUBLE)" to listOf(1.0, sqrt(2.0), 1.0, 2.0),
            )) for (source in listOf("mysql", "doris", "starrocks")) {
                val sql = "SELECT STDDEV(x), STDDEV_SAMP(x), VARIANCE(x), VAR_SAMP(x) FROM ($input) t"
                val output = SqlFragment(sql, source).toExecutable("postgres").sql
                c.createStatement().use { s -> s.executeQuery(output).use { r ->
                    assertTrue(r.next())
                    for (i in 1..4) {
                        assertEquals("float8", r.metaData.getColumnTypeName(i))
                        val value = r.getDouble(i)
                        if (expected[i - 1] == null) assertTrue(r.wasNull(), output)
                        else assertEquals(expected[i - 1]!!, value, 1e-12, output)
                    }
                    assertFalse(r.next())
                } }
            }
        } }
    }
}
