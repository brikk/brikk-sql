package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.*

/** SG29-009. Real DuckDB 2.0.0 values/types, not parse-only equivalence. */
internal class TemporalArithmeticResultTest {
    @Test fun nestedArithmeticAndZoneConversionMatchNativeGroupingAndTypes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.execute("SET TimeZone='UTC'")
                val expressions = listOf(
                    "DATE_ADD(TIMESTAMP '2024-01-03', INTERVAL 1 DAY) - DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY)",
                    "TIMESTAMP '2024-01-03' - DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY)",
                    "INTERVAL 1 DAY + DATE_ADD(TIMESTAMP '2024-01-30', INTERVAL 1 MONTH)",
                    "DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL 1 DAY) AT TIME ZONE 'UTC'",
                    "DATE_ADD(TIMESTAMPTZ '2024-03-09 12:00:00-05', INTERVAL 1 DAY)",
                    "DATE_ADD(CAST(NULL AS TIMESTAMP), INTERVAL 1 DAY)",
                    "DATE_ADD(TIMESTAMP '2024-01-01', INTERVAL (n + 1) DAY)",
                )
                for (expression in expressions) {
                    val source = "SELECT $expression AS v FROM (VALUES(1),(NULL)) t(n)"
                    fun observe(sql: String): Pair<String, List<String?>> = s.executeQuery(sql).use { r ->
                        r.metaData.getColumnTypeName(1) to buildList { while (r.next()) add(r.getString(1)) }
                    }
                    val expected = observe(source)
                    val ast = Dialects.DUCKDB.parseOne(source)
                    val before = Serde.dump(ast)
                    for (generated in listOf(SqlFragment(source, "duckdb").toExecutable("duckdb").sql,
                        Serde.loadExpression(before).sql("duckdb"))) {
                        assertEquals(expected, observe(generated), generated)
                    }
                    assertEquals(before, Serde.dump(ast))
                }
            }
        }
    }

    @Test fun mysqlTimestampaddUsesTimestampLiteralsAndDynamicIntegerCounts() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for ((unit, count, input, expected) in listOf(
                listOf("MONTH", "1", "2024-01-31 12:00:00", "2024-02-29 12:00:00"),
                listOf("SECOND", "30", "2024-01-01 12:00:00", "2024-01-01 12:00:30"),
                listOf("DAY", "-1", "2024-03-01 12:00:00", "2024-02-29 12:00:00"),
            )) {
                val generated = SqlFragment("SELECT TIMESTAMPADD(SQL_TSI_$unit, $count, '$input')", "mysql").toExecutable("duckdb").sql
                s.executeQuery(generated).use { r ->
                    assertEquals("TIMESTAMP", r.metaData.getColumnTypeName(1)); assertTrue(r.next())
                    assertEquals(java.time.LocalDateTime.parse(expected.replace(' ', 'T')), r.getTimestamp(1).toLocalDateTime(), generated)
                }
            }
            val source = "SELECT TIMESTAMPADD(SQL_TSI_DAY, n + 1, CAST('2024-01-01 12:00:00' AS DATETIME)) FROM (SELECT 1 n UNION ALL SELECT NULL) t"
            s.executeQuery(SqlFragment(source, "mysql").toExecutable("duckdb").sql).use { r ->
                assertTrue(r.next()); assertEquals(java.time.LocalDateTime.parse("2024-01-03T12:00:00"), r.getTimestamp(1).toLocalDateTime())
                assertTrue(r.next()); assertNull(r.getString(1))
            }
        } }
    }
}
