package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.math.sqrt
import kotlin.test.*

/** SG29-008, private DuckDB 2.0.0. No extensions, persistent tables or user data. */
internal class MysqlStatisticalAggregateResultTest {
    @Test fun populationAliasesAndSampleNamesHaveNativeValuesAndDoubleTypes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT version()").use { r -> r.next(); assertEquals("v2.0.0", r.getString(1)) }
                for ((input, expected) in listOf(
                    "SELECT CAST(NULL AS DOUBLE) x WHERE FALSE" to listOf(null, null, null, null),
                    "SELECT CAST(NULL AS DOUBLE) x" to listOf(null, null, null, null),
                    "SELECT CAST(2 AS DOUBLE) x" to listOf(0.0, null, 0.0, null),
                    "SELECT CAST(0 AS DOUBLE) x UNION ALL SELECT CAST(2 AS DOUBLE)" to listOf(1.0, sqrt(2.0), 1.0, 2.0),
                )) for (source in listOf("mysql", "doris", "starrocks")) {
                    val sql = "SELECT STDDEV(x), STDDEV_SAMP(x), VARIANCE(x), VAR_SAMP(x) FROM ($input) t"
                    val ast = Dialects.forName(source).parseOne(sql)
                    val before = Serde.dump(ast)
                    for (output in listOf(SqlFragment(sql, source).toExecutable("duckdb").sql,
                        Serde.loadExpression(before).sql("duckdb"))) {
                        s.executeQuery(output).use { r ->
                            assertTrue(r.next())
                            for (i in 1..4) {
                                assertEquals("DOUBLE", r.metaData.getColumnTypeName(i))
                                val value = r.getDouble(i)
                                if (expected[i - 1] == null) assertTrue(r.wasNull(), output)
                                else assertEquals(expected[i - 1]!!, value, 1e-12, output)
                            }
                            assertFalse(r.next())
                        }
                    }
                    assertEquals(before, Serde.dump(ast))
                }
            }
        }
    }

    @Test fun groupsAndSlidingWindowsKeepSingletonSampleNulls() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val input = "SELECT 1 g, 1 n, CAST(0 AS DOUBLE) x UNION ALL SELECT 1, 2, CAST(2 AS DOUBLE) UNION ALL SELECT 2, 3, CAST(NULL AS DOUBLE)"
            val queries = listOf(
                "SELECT g, STDDEV(x), STDDEV_SAMP(x), VARIANCE(x), VAR_SAMP(x) FROM ($input) t GROUP BY g ORDER BY g" to
                    listOf(listOf("1", "1.0", "1.4142135623730951", "1.0", "2.0"), listOf("2", null, null, null, null)),
                "SELECT n, STDDEV(x) OVER w, STDDEV_SAMP(x) OVER w, VARIANCE(x) OVER w, VAR_SAMP(x) OVER w FROM ($input) t WINDOW w AS (PARTITION BY g ORDER BY n ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) ORDER BY n" to
                    listOf(listOf("1", "0.0", null, "0.0", null), listOf("2", "1.0", "1.4142135623730951", "1.0", "2.0"), listOf("3", null, null, null, null)),
            )
            for ((sql, expected) in queries) {
                val output = SqlFragment(sql, "mysql").toExecutable("duckdb").sql
                c.createStatement().use { s -> s.executeQuery(output).use { r ->
                    val rows = buildList { while (r.next()) add((1..5).map(r::getString)) }
                    assertEquals(expected, rows, output)
                } }
            }
        }
    }
}
