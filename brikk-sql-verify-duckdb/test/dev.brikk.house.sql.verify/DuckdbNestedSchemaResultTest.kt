package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbNestedSchemaResultTest {
    @Test
    fun parentIdsDistinguishSchemasThatJdbcFlattensToTheSameName() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in listOf(
                    "CREATE SCHEMA finance", "CREATE SCHEMA finance.reports",
                    "CREATE SCHEMA ops", "CREATE SCHEMA ops.reports",
                    "CREATE SCHEMA \"finance.reports\"",
                    "CREATE TABLE finance.reports.q3(revenue INTEGER)",
                    "CREATE TABLE ops.reports.q3(expenses INTEGER)",
                )) statement.execute(sql)
                val namespaces = LinkedHashMap<Long, Pair<String, Long?>>()
                statement.executeQuery("SELECT oid, schema_name, parent_schema_oid FROM duckdb_schemas() WHERE database_name = 'memory'").use { rows ->
                    while (rows.next()) {
                        val parent = rows.getLong(3).takeUnless { rows.wasNull() }
                        namespaces[rows.getLong(1)] = rows.getString(2) to parent
                    }
                }
                val reports = namespaces.filterValues { it.first == "reports" }
                assertEquals(2, reports.size)
                assertEquals(setOf("finance", "ops"), reports.values.map { namespaces.getValue(it.second!!).first }.toSet())
                assertEquals(null, namespaces.values.single { it.first == "finance.reports" }.second)
                val tableSchemas = LinkedHashMap<String, Long>()
                statement.executeQuery("SELECT table_oid, schema_oid FROM duckdb_tables() WHERE database_name = 'memory' AND table_name = 'q3'").use { rows ->
                    while (rows.next()) tableSchemas[rows.getString(1)] = rows.getLong(2)
                }
                assertEquals(reports.keys, tableSchemas.values.toSet())
                // The current JDBC interface cannot identify which parent owns either row.
                val jdbc = buildList {
                    connection.metaData.getTables("memory", null, "q3", arrayOf("TABLE")).use { rows ->
                        while (rows.next()) add(listOf(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME")))
                    }
                }
                assertEquals(listOf(listOf("memory", "reports", "q3"), listOf("memory", "reports", "q3")), jdbc)
            }
        }
    }

    @Test
    fun generatedNestedDdlAndDmlTargetTheRightSchemaIncludingAttachedCatalogsAndQuotedDots() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                fun execute(sql: String) = statement.execute(Dialects.DUCKDB.parseOne(sql).sql("duckdb"))
                fun value(sql: String): Int = statement.executeQuery(Dialects.DUCKDB.parseOne(sql).sql("duckdb")).use {
                    assertTrue(it.next()); it.getInt(1)
                }
                execute("CREATE SCHEMA finance")
                execute("CREATE SCHEMA finance.reports")
                execute("CREATE SCHEMA finance.reports.monthly")
                execute("CREATE SCHEMA \"finance.reports\"")
                execute("ATTACH ':memory:' AS other")
                execute("CREATE SCHEMA other.finance")
                execute("CREATE SCHEMA other.finance.reports")
                for ((table, amount) in listOf(
                    "finance.reports.q3" to 1,
                    "finance.reports.monthly.q3" to 2,
                    "\"finance.reports\".q3" to 3,
                    "other.finance.reports.q3" to 4,
                )) {
                    execute("CREATE TABLE $table (revenue INTEGER)")
                    execute("INSERT INTO $table VALUES ($amount)")
                    execute("UPDATE $table SET revenue = revenue + 10")
                    assertEquals(amount + 10, value("SELECT q.revenue FROM $table q"), table)
                }
                assertEquals(12, value("SELECT memory.finance.reports.monthly.q3.revenue FROM memory.finance.reports.monthly.q3"))
                execute("DELETE FROM finance.reports.monthly.q3 WHERE revenue = 12")
                assertEquals(0, value("SELECT COUNT(*) FROM finance.reports.monthly.q3"))
                assertFailsWith<SQLException> { connection.createStatement().use { it.executeQuery("SELECT * FROM monthly.q3") } }
                for (sql in listOf("USE finance.reports", "SET schema = 'finance.reports'")) {
                    val error = assertFailsWith<SQLException> { connection.createStatement().use { it.execute(sql) } }
                    assertTrue(error.message.orEmpty().contains("nested schemas cannot be used in the search path"))
                }
                assertEquals("main", statement.executeQuery("SELECT current_schema()").use { it.next(); it.getString(1) })
                execute("DROP TABLE finance.reports.monthly.q3")
                execute("DROP SCHEMA finance.reports.monthly")
                assertEquals(11, value("SELECT revenue FROM finance.reports.q3"))
                assertEquals(14, value("SELECT revenue FROM other.finance.reports.q3"))
            }
        }
    }
}
