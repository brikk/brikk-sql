package dev.brikk.house.sql.verify

import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** DuckDB resolves projection aliases in WHERE, GROUP BY, HAVING and later SELECT items; so does the scope. */
class ProjectionAliasScopeTest {
    private val facts = RelationFacts(tables = mapOf("t" to RelationFact(listOf(ColumnFact("id", "INTEGER")))))

    @Test
    fun duckdbAndTheScopeAcceptTheSameAliases() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (id INTEGER)")
                for (sql in listOf(
                    "SELECT id AS k FROM t WHERE k > 0",
                    "SELECT id AS k, k + 1 AS j FROM t",
                    "SELECT id + 1 AS k FROM t GROUP BY k",
                    "SELECT id AS k FROM t GROUP BY id HAVING k > 0",
                )) {
                    statement.executeQuery(sql).close()
                    assertEquals(emptyList(), RelationScope.analyze(sql, "duckdb", facts).diagnostics, sql)
                }
            }
        }
    }
}
