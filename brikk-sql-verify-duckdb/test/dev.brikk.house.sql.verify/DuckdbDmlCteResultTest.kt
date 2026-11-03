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

internal class DuckdbDmlCteResultTest {
    private fun sql(text: String) = Dialects.DUCKDB.parseOne(text).sql("duckdb")
    private fun Connection.execute(text: String): Long = createStatement().use { statement ->
        if (statement.execute(text)) {
            statement.resultSet.use { while (it.next()) { /* drain before closing */ } }
            -1L
        } else statement.largeUpdateCount
    }
    private fun Connection.rows(text: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(text).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private fun fixture(c: Connection) {
        c.execute("CREATE TABLE staging(id INTEGER,v VARCHAR)")
        c.execute("INSERT INTO staging VALUES(1,'one'),(2,'two'),(3,'three')")
        c.execute("CREATE TABLE archive(id INTEGER PRIMARY KEY,v VARCHAR)")
        c.execute("CREATE TABLE counter(id INTEGER,v INTEGER)")
        c.execute("INSERT INTO counter VALUES(1,0)")
    }

    @Test
    fun documentedUpdateCountUsesTheNewReturningRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE orders(id INTEGER,status VARCHAR)")
            c.execute("INSERT INTO orders VALUES(1,'ready'),(2,'ready'),(3,'waiting')")
            assertEquals(listOf(listOf("2")), c.rows(sql("WITH updated AS (UPDATE orders SET status='shipped' WHERE status='ready' RETURNING *) SELECT count(*) AS shipped FROM updated")))
            assertEquals(listOf(listOf("1", "shipped"), listOf("2", "shipped"), listOf("3", "waiting")), c.rows("SELECT * FROM orders ORDER BY id"))
        }
    }

    @Test
    fun documentedDeleteInsertPipelineMovesRowsAndParticipatesInCallerRollback() {
        for (rollback in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            if (rollback) c.execute("BEGIN TRANSACTION")
            c.execute(sql("WITH moved AS MATERIALIZED (DELETE FROM staging WHERE id<3 RETURNING *) INSERT INTO archive SELECT * FROM moved"))
            assertEquals(listOf(listOf("3", "three")), c.rows("SELECT * FROM staging"))
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two")), c.rows("SELECT * FROM archive ORDER BY id"))
            if (rollback) {
                c.execute("ROLLBACK")
                assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM staging ORDER BY id"))
                assertEquals(emptyList(), c.rows("SELECT * FROM archive"))
            }
        }
    }

    @Test
    fun multipleReferencesAndEmptyConsumersDoNotDuplicateOrSuppressWritesInAnyHintMode() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for (hint in listOf("", "MATERIALIZED ", "NOT MATERIALIZED ")) {
                c.execute("UPDATE counter SET v=0")
                assertEquals(listOf(listOf("1", "1")), c.rows(sql("WITH changed AS $hint(UPDATE counter SET v=v+1 RETURNING id,v) SELECT a.v,b.v FROM changed a JOIN changed b USING(id)")), hint)
                assertEquals(listOf(listOf("1")), c.rows("SELECT v FROM counter"))
                c.execute("UPDATE counter SET v=0")
                assertEquals(listOf(listOf("1")), c.rows(sql("WITH changed AS $hint(UPDATE counter SET v=v+1 RETURNING id) SELECT v FROM counter")), "The query must see the completed write: $hint")
                for (returning in listOf("", " RETURNING v")) {
                    c.execute("UPDATE counter SET v=0")
                    // Use execute, not executeQuery: this pinned JDBC can mark the outer SELECT
                    // as CHANGED_ROWS when a producer lacks RETURNING. Never retry it as a query.
                    c.execute(sql("WITH changed AS $hint(UPDATE counter SET v=v+1$returning) SELECT 42 WHERE false"))
                    assertEquals(listOf(listOf("1")), c.rows("SELECT v FROM counter"), "$hint$returning")
                }
            }
        }
    }

    @Test
    fun noReturningCountContractsAreVerifiedThroughAnOuterReturningConsumer() {
        val cases = mapOf(
            "INSERT INTO archive VALUES(10,'ten'),(11,'eleven')" to "2",
            "UPDATE staging SET v='changed' WHERE id<3" to "2",
            "DELETE FROM staging WHERE id=1" to "1",
            "UPDATE staging SET v='changed' WHERE false" to "0",
        )
        for ((body, expected) in cases) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("CREATE TABLE observed(n BIGINT)")
            assertEquals(listOf(listOf(expected)), c.rows(sql("WITH changed(rows_written) AS ($body) INSERT INTO observed SELECT rows_written FROM changed RETURNING n")), body)
            assertEquals(listOf(listOf(expected)), c.rows("SELECT n FROM observed"))
        }
    }

    @Test
    fun dependentWriteCtesConsumeReturningRatherThanRereadingOrReexecutingAProducer() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val text = "WITH removed AS (DELETE FROM staging WHERE id<3 RETURNING *), added AS (INSERT INTO archive SELECT * FROM removed RETURNING id,v) SELECT a.id,b.v FROM added a JOIN added b USING(id) ORDER BY a.id"
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two")), c.rows(sql(text)))
            assertEquals(listOf(listOf("3", "three")), c.rows("SELECT * FROM staging"))
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two")), c.rows("SELECT * FROM archive ORDER BY id"))
        }
    }

    @Test
    fun preparedSideEffectingCteRunsOnceOnEachExecutionRatherThanOnceForThePlan() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.prepareStatement(sql("WITH changed AS NOT MATERIALIZED (UPDATE counter SET v=v+? RETURNING v) SELECT v FROM changed")).use { prepared ->
                for ((increment, expected) in listOf(1 to 1, 2 to 3)) {
                    prepared.setInt(1, increment)
                    prepared.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(expected, result.getInt(1))
                        assertTrue(!result.next())
                    }
                }
            }
            assertEquals(listOf(listOf("3")), c.rows("SELECT v FROM counter"))
        }
    }

    @Test
    fun constraintFailureRollsBackAllTableChangesInTheStatement() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("INSERT INTO archive VALUES(1,'existing')")
            val text = "WITH removed AS (DELETE FROM staging RETURNING *), added AS (INSERT INTO archive SELECT * FROM removed RETURNING id) SELECT * FROM added"
            val error = assertFailsWith<SQLException> { c.rows(sql(text)) }
            assertTrue(error.message.orEmpty().contains("Constraint Error"), error.message)
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM staging ORDER BY id"))
            assertEquals(listOf(listOf("1", "existing")), c.rows("SELECT * FROM archive"))
        }
    }

    @Test
    fun unsupportedBodyAndPlacementErrorsHappenBeforeTableChanges() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for (text in listOf(
                "WITH changed AS (MERGE INTO archive a USING staging s ON a.id=s.id WHEN MATCHED THEN DELETE) SELECT * FROM changed",
                "WITH RECURSIVE changed AS (DELETE FROM staging RETURNING *) SELECT * FROM changed",
                "SELECT * FROM (WITH changed AS (DELETE FROM staging RETURNING *) SELECT * FROM changed)",
                "CREATE VIEW forbidden AS WITH changed AS (DELETE FROM staging RETURNING *) SELECT * FROM changed",
                "WITH changed AS (INSERT INTO archive VALUES(10,'ten')) INSERT INTO counter SELECT changed.id,0 FROM changed RETURNING id",
            )) assertFailsWith<SQLException>(text) { c.execute(text) }
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM staging ORDER BY id"))
            assertEquals(emptyList(), c.rows("SELECT * FROM archive"))
            assertEquals(listOf(listOf("1", "0")), c.rows("SELECT * FROM counter"))
        }
    }
}
