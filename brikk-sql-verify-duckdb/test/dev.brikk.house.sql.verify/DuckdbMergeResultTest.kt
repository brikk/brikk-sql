package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbMergeResultTest {
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.generated(sql: String) = execute(Dialects.DUCKDB.parseOne(sql).sql("duckdb"))
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private fun Connection.returned(sql: String) = rows(Dialects.DUCKDB.parseOne(sql).sql("duckdb"))

    @Test
    fun documentedPeopleSequenceKeepsWholeRowKeyUpdateDeleteAndReturningBehavior() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE people(id INTEGER, name VARCHAR, salary FLOAT)")
            c.execute("INSERT INTO people VALUES (1,'John',92000.0),(2,'Anna',100000.0)")
            c.generated("MERGE INTO people USING (SELECT unnest([3,1]) AS id, unnest(['Sarah','John']) AS name, unnest([95000.0,105000.0]) AS salary) AS upserts ON (upserts.id = people.id) WHEN MATCHED THEN UPDATE WHEN NOT MATCHED THEN INSERT")
            assertEquals(listOf(listOf("1", "John", "105000"), listOf("2", "Anna", "100000"), listOf("3", "Sarah", "95000")), c.rows("SELECT id,name,salary::BIGINT FROM people ORDER BY id"))
            c.generated("MERGE INTO people USING (SELECT 1 AS id, 98000.0 AS salary) AS salary_updates USING (id) WHEN MATCHED THEN UPDATE SET salary = salary_updates.salary")
            assertEquals(listOf(listOf("98000")), c.rows("SELECT salary::BIGINT FROM people WHERE id=1"))
            c.generated("MERGE INTO people USING (SELECT 1 AS id) AS deletes USING (id) WHEN MATCHED THEN DELETE")
            c.generated("MERGE INTO people USING (SELECT unnest([3,2]) AS id) AS deletes USING (id) WHEN MATCHED AND people.salary >= 100000.0 THEN DELETE")
            assertEquals(listOf(listOf("3", "Sarah", "95000")), c.rows("SELECT id,name,salary::BIGINT FROM people"))
            c.execute("INSERT INTO people VALUES (1,'John',105000.0)")
            val returned = c.returned("MERGE INTO people USING (SELECT unnest([3,1]) AS id, unnest([89000.0,70000.0]) AS salary) AS upserts USING (id) WHEN MATCHED AND people.salary < 100000.0 THEN UPDATE SET salary = upserts.salary WHEN MATCHED AND people.salary > 100000.0 THEN DELETE WHEN NOT MATCHED THEN INSERT BY NAME RETURNING merge_action, *")
            assertEquals(setOf(listOf("UPDATE", "3", "Sarah", "89000.0"), listOf("DELETE", "1", "John", "105000.0")), returned.toSet())
            assertEquals(listOf(listOf("3", "Sarah", "89000")), c.rows("SELECT id,name,salary::BIGINT FROM people"))
        }
    }

    @Test
    fun insertByNameMapsReorderedSourceColumnsInsteadOfTheirPositions() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE people(id INTEGER, name VARCHAR, salary INTEGER)")
            c.generated("MERGE INTO people t USING (SELECT 95000 AS salary, 3 AS id, 'Sarah' AS name) s USING (id) WHEN NOT MATCHED THEN INSERT BY NAME")
            assertEquals(listOf(listOf("3", "Sarah", "95000")), c.rows("SELECT * FROM people"))
        }
    }

    @Test
    fun returningMergeActionIsTypedUnlessAnActualTargetColumnShadowsIt() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE target(id INTEGER, v INTEGER)")
            c.execute("INSERT INTO target VALUES(1,0)")
            val head = "MERGE INTO target t USING (SELECT 1 AS id, 10 AS v) s USING(id) WHEN MATCHED THEN UPDATE SET v=s.v RETURNING "
            c.createStatement().use { statement ->
                statement.executeQuery(Dialects.DUCKDB.parseOne(head + "merge_action").sql("duckdb")).use { result ->
                    assertEquals("VARCHAR", result.metaData.getColumnTypeName(1))
                    assertTrue(result.next())
                    assertEquals("UPDATE", result.getString(1))
                    assertTrue(!result.next())
                }
            }
            c.execute("ALTER TABLE target ADD COLUMN merge_action INTEGER DEFAULT 42")
            c.createStatement().use { statement ->
                statement.executeQuery(Dialects.DUCKDB.parseOne(head + "merge_action, t.merge_action AS stored_action").sql("duckdb")).use { result ->
                    assertEquals("INTEGER", result.metaData.getColumnTypeName(1))
                    assertTrue(result.next())
                    assertEquals(42, result.getInt(1))
                    assertEquals(42, result.getInt(2))
                    assertTrue(!result.next())
                }
            }
        }
    }

    private fun fixture(c: Connection, duplicates: Boolean = false) {
        c.execute("CREATE TABLE target(id INTEGER,v INTEGER)")
        c.execute("INSERT INTO target VALUES(1,0),(2,99)")
        c.execute("CREATE TABLE source(id INTEGER,v INTEGER)")
        c.execute(if (duplicates) "INSERT INTO source VALUES(1,10),(1,20)" else "INSERT INTO source VALUES(1,10),(3,30)")
    }

    @Test
    fun sourceAndTargetUnmatchedBranchesActOnDifferentRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val returned = c.returned("MERGE INTO target t USING source s USING(id) WHEN MATCHED THEN UPDATE SET v=s.v WHEN NOT MATCHED BY SOURCE THEN DELETE WHEN NOT MATCHED BY TARGET THEN INSERT(id,v) VALUES(s.id,s.v) RETURNING merge_action, *")
            assertEquals(setOf(listOf("UPDATE", "1", "10"), listOf("DELETE", "2", "99"), listOf("INSERT", "3", "30")), returned.toSet())
            assertEquals(listOf(listOf("1", "10"), listOf("3", "30")), c.rows("SELECT * FROM target ORDER BY id"))
        }
    }

    @Test
    fun duplicateMatchedUpdatesAndDeletesFailAtomicallyUnlikeUpdateFrom() {
        for (action in listOf("UPDATE SET v=s.v", "DELETE")) {
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                fixture(c, duplicates = true)
                val error = assertFailsWith<SQLException> { c.generated("MERGE INTO target t USING source s ON t.id=s.id WHEN MATCHED THEN $action") }
                assertTrue(error.message.orEmpty().contains("cannot affect the same target row more than once"), error.message)
                assertEquals(listOf(listOf("1", "0"), listOf("2", "99")), c.rows("SELECT * FROM target ORDER BY id"))
            }
        }
    }

    @Test
    fun deduplicationAndGuardsCanLimitTheActualTargetChangesWithoutBanningAllDuplicateSources() {
        val cases = mapOf(
            "USING (SELECT id,max(v) AS v FROM source GROUP BY id) s ON t.id=s.id WHEN MATCHED THEN UPDATE SET v=s.v" to "20",
            "USING source s ON t.id=s.id WHEN MATCHED AND s.v=10 THEN UPDATE SET v=s.v" to "10",
            "USING source s ON t.id=s.id AND s.v=10 WHEN MATCHED THEN UPDATE SET v=s.v" to "10",
            "USING source s ON t.id=s.id WHEN NOT MATCHED THEN INSERT" to "0",
            "USING source s ON t.id=s.id WHEN MATCHED AND s.v=999 THEN DELETE" to "0",
        )
        for ((tail, expected) in cases) {
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                fixture(c, duplicates = true)
                c.generated("MERGE INTO target t $tail")
                assertEquals(listOf(listOf("1", expected), listOf("2", "99")), c.rows("SELECT * FROM target ORDER BY id"), tail)
            }
        }
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c, duplicates = true)
            c.execute("DELETE FROM target WHERE id=1")
            c.generated("MERGE INTO target t USING source s USING(id) WHEN NOT MATCHED THEN INSERT")
            assertEquals(listOf(listOf("1", "10"), listOf("1", "20"), listOf("2", "99")), c.rows("SELECT * FROM target ORDER BY id,v"))
        }
    }

    @Test
    fun unavailableBranchAliasesAndReturningSourcesAreNativeErrors() {
        val head = "MERGE INTO target t USING source s USING(id) "
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for (tail in listOf(
                "WHEN NOT MATCHED BY SOURCE AND s.v > 0 THEN DELETE",
                "WHEN NOT MATCHED BY TARGET AND t.v > 0 THEN INSERT(id,v) VALUES(s.id,s.v)",
                "WHEN MATCHED THEN UPDATE SET v=s.v RETURNING s.v",
            )) {
                val error = assertFailsWith<SQLException> { c.execute(head + tail) }
                assertTrue(error.message.orEmpty().contains("Referenced table"), error.message)
            }
            assertEquals(listOf(listOf("1", "0"), listOf("2", "99")), c.rows("SELECT * FROM target ORDER BY id"))
        }
    }
}
