package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.*

class FrozenTargetIntegrationResultTest {
    @Test fun duckdbIntervalArithmeticKeepsNativeValuesAndResultTypes() {
        val queries = listOf(
            "SELECT DATE '2026-01-01' + INTERVAL '1 DAY' AS x",
            "SELECT INTERVAL '1 DAY' + DATE '2026-01-01' AS x",
            "SELECT DATE '2026-01-01' - INTERVAL '1 DAY' AS x",
            "SELECT TIMESTAMP '2026-01-01 12:00:00' + INTERVAL '1 HOUR' AS x",
            "SELECT TIME '12:00:00' + INTERVAL '1 HOUR' AS x",
            "SELECT CAST(NULL AS DATE) + INTERVAL '1 DAY' AS x",
            "SELECT ([1,2,3])[2:-:1] AS x",
            "SELECT ([1,2,3])[:-:-1] AS x",
        )
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            fun result(sql: String): Pair<String, String?> = statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.metaData.getColumnTypeName(1) to rows.getString(1)
            }
            for (sql in queries) {
                val original = result(sql)
                val fragment = SqlFragment(sql, "duckdb")
                assertEquals(original, result(fragment.toStandardSql()), sql)
                // JDBC INTEGER[] and the shape API's ARRAY<INT> name the same exact type.
                assertEquals(dataTypeFromStr(original.first, Dialects.DUCKDB),
                    dataTypeFromStr(checkNotNull(fragment.outputShape().columns.single().type), Dialects.DUCKDB), sql)
            }
        } }
    }
}
