package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.*

class SharedAnalysisCatchupResultTest {
    @Test fun valuesAndNestedSetsAgreeWithNativeTypesAndQualifiedRows() {
        val deck = listOf("SELECT x FROM (VALUES (NULL), (CAST(2.5 AS DOUBLE)), (1)) AS t(x)",
            "SELECT t.a FROM (SELECT 1 AS a UNION ALL (SELECT 2 AS b UNION ALL SELECT CAST(2.5 AS DOUBLE) AS c)) AS t",
            "WITH t AS (SELECT 1 AS a UNION ALL SELECT CAST(2.5 AS DOUBLE)) SELECT x.a,y.a FROM t x CROSS JOIN t y")
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            fun rows(sql: String): Pair<List<String>, List<List<String?>>> = s.executeQuery(sql).use { r ->
                val types = (1..r.metaData.columnCount).map { r.metaData.getColumnTypeName(it) }
                val data = mutableListOf<List<String?>>()
                while (r.next()) data += types.indices.map { r.getString(it + 1) }
                types to data
            }
            for (sql in deck) {
                val fragment = SqlFragment(sql, "duckdb")
                val original = rows(sql)
                val normalized = rows(fragment.toStandardSql(expandStars = true))
                assertEquals(original, normalized, sql)
                assertEquals(original.first, fragment.outputShape().columns.map { it.type }, sql)
            }
        } }
    }
}
