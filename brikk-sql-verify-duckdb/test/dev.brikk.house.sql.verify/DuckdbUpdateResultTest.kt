package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualifyTables
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbUpdateResultTest {
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.generated(sql: String, qualify: Boolean = false) {
        val tree = Dialects.DUCKDB.parseOne(sql)
        execute((if (qualify) qualifyTables(tree, db = "main", catalog = "memory", dialect = Dialects.DUCKDB) else tree).sql("duckdb"))
    }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }

    @Test
    fun documentedScalarAndFromSelfUpdatesPreserveBothAliasesAfterQualification() {
        val statements = listOf(
            "UPDATE original AS true_original SET value = (SELECT new.value || ' a change!' AS value FROM original AS new WHERE true_original.key = new.key)",
            "UPDATE original AS true_original SET value = new.value || ' a change!' FROM original AS new WHERE true_original.key = new.key",
        )
        for (sql in statements) for (qualify in listOf(false, true)) {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.execute("CREATE TABLE original(key INTEGER, value VARCHAR)")
                connection.execute("INSERT INTO original VALUES (1, 'original value'), (2, 'original value 2')")
                connection.generated(sql, qualify)
                assertEquals(listOf(listOf("1", "original value a change!"), listOf("2", "original value 2 a change!")),
                    connection.rows("SELECT * FROM original ORDER BY key"), "$sql, qualified=$qualify")
            }
        }
    }

    @Test
    fun documentedWhereJoinExplicitJoinAndDerivedSourcesKeepNonmatchingRows() {
        for (sql in listOf(
            "UPDATE city SET revenue = revenue + 100 FROM country WHERE city.country_code = country.code AND country.name = 'France'",
            "UPDATE city t SET revenue = t.revenue + r.bonus FROM country c JOIN region r ON c.code = r.code WHERE t.country_code = c.code AND c.name = 'France'",
            "UPDATE city t SET revenue = t.revenue + s.bonus FROM (SELECT c.code, r.bonus FROM country c JOIN region r ON c.code = r.code WHERE c.name = 'France') s WHERE t.country_code = s.code",
        )) {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.execute("CREATE TABLE city(name VARCHAR, revenue BIGINT, country_code VARCHAR)")
                connection.execute("INSERT INTO city VALUES ('Paris',700,'FR'),('Lyon',200,'FR'),('Brussels',400,'BE')")
                connection.execute("CREATE TABLE country(code VARCHAR, name VARCHAR)")
                connection.execute("INSERT INTO country VALUES ('FR','France'),('BE','Belgium')")
                connection.execute("CREATE TABLE region(code VARCHAR, bonus INTEGER)")
                connection.execute("INSERT INTO region VALUES ('FR',100),('BE',50)")
                connection.generated(sql)
                assertEquals(listOf(listOf("Brussels", "400"), listOf("Lyon", "300"), listOf("Paris", "800")),
                    connection.rows("SELECT name, revenue FROM city ORDER BY name"), sql)
            }
        }
    }

    private fun matchingFixture(connection: Connection) {
        connection.execute("CREATE TABLE target(id INTEGER, v INTEGER, touches INTEGER)")
        connection.execute("INSERT INTO target VALUES (1,0,0),(2,99,0)")
        connection.execute("CREATE TABLE source(id INTEGER, v INTEGER)")
        connection.execute("INSERT INTO source VALUES (1,10),(1,20)")
    }

    @Test
    fun multipleFromMatchesUpdateTheTargetOnceWithoutChoosingADeterministicWinner() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            matchingFixture(connection)
            val sql = Dialects.DUCKDB.parseOne("UPDATE target SET v = source.v, touches = target.touches + 1 FROM source WHERE target.id = source.id RETURNING id, v, touches").sql("duckdb")
            val returned = connection.rows(sql)
            assertEquals(1, returned.size)
            assertEquals("1", returned.single()[0])
            assertTrue(returned.single()[1] in setOf("10", "20"))
            assertEquals("1", returned.single()[2])
            assertEquals(listOf(listOf("2", "99", "0")), connection.rows("SELECT * FROM target WHERE id = 2"))
            assertEquals(listOf(listOf("1", "10"), listOf("1", "20")), connection.rows("SELECT * FROM source ORDER BY v"))
        }
    }

    @Test
    fun scalarMultipleMatchErrorsAreAtomicAndTheCompatibilitySettingDoesNotPromiseAWinner() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            matchingFixture(connection)
            val sql = "UPDATE target SET v = (SELECT source.v FROM source WHERE target.id = source.id)"
            val error = assertFailsWith<SQLException> { connection.generated(sql) }
            assertTrue(error.message.orEmpty().contains("more than one row", ignoreCase = true), error.message)
            assertEquals(listOf(listOf("1", "0", "0"), listOf("2", "99", "0")), connection.rows("SELECT * FROM target ORDER BY id"))
            connection.execute("SET scalar_subquery_error_on_multiple_rows = false")
            connection.generated(sql)
            assertTrue(connection.rows("SELECT v FROM target WHERE id = 1").single().single() in setOf("10", "20"))
            // A scalar with no match yields NULL; an unmatched UPDATE FROM target stayed unchanged above.
            assertEquals(listOf(listOf("2", null)), connection.rows("SELECT id, v FROM target WHERE id = 2"))
        }
    }

    @Test
    fun aliasAmbiguitySetQualificationReturningAndFromVisibilityHaveNativeErrors() {
        val cases = mapOf(
            "UPDATE target SET v = target.v FROM target WHERE target.id = target.id" to "duplicate alias",
            "UPDATE target t SET v = v FROM source s WHERE t.id = s.id" to "Ambiguous reference to column",
            "UPDATE target t SET t.v = 100" to "Qualified column names",
            "UPDATE target t SET v = s.v FROM source s WHERE t.id = s.id RETURNING s.v" to "Referenced table",
            "UPDATE target t SET v = a.v FROM source a JOIN source b ON t.id = b.id" to "Referenced table",
        )
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            matchingFixture(connection)
            for ((sql, expected) in cases) {
                val error = assertFailsWith<SQLException>(sql) { connection.execute(sql) }
                assertTrue(error.message.orEmpty().contains(expected, ignoreCase = true), "$sql: ${error.message}")
            }
            assertEquals(listOf(listOf("1", "0", "0"), listOf("2", "99", "0")), connection.rows("SELECT * FROM target ORDER BY id"))
        }
    }
}
