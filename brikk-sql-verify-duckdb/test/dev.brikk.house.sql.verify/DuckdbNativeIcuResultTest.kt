package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

/** Native 2.0 d54cbbe3df timezone edge/calendar and collate_list_contains contracts. */
internal class DuckdbNativeIcuResultTest {
    private fun connection() = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
    })
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun forms(sql: String) = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
        assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
    private fun Connection.check(sql: String, expected: List<List<String?>>) { for (form in forms(sql)) assertEquals(expected, rows(form), form) }
    @Test fun dstGapsOverlapsHalfHourChangesDateLineAndHistoricalOffsetsKeepNativeEpochs() {
        connection().use { c ->
            c.createStatement().use { it.execute("SET TimeZone='UTC'") }
            c.check("SELECT epoch_ns(TIMESTAMP '2024-03-10 02:30:00' AT TIME ZONE 'America/New_York'),epoch_ns(TIMESTAMP '2024-11-03 01:30:00' AT TIME ZONE 'America/New_York')", listOf(listOf("1710055800000000000", "1730615400000000000")))
            c.check("SELECT CAST(epoch(TIMESTAMP '2024-04-07 01:45:00' AT TIME ZONE 'Australia/Lord_Howe') AS BIGINT),CAST(epoch(TIMESTAMP '2011-12-30 12:00:00' AT TIME ZONE 'Pacific/Apia') AS BIGINT),CAST(epoch(TIMESTAMP '1800-01-01' AT TIME ZONE 'Europe/Amsterdam') AS BIGINT)", listOf(listOf("1712416500", "1325282400", "-5364663450")))
        }
    }
    @Test fun calendarChoicesAffectTheSameInstantWithoutReinterpretingOrReplayingItsLiteral() {
        connection().use { c ->
            c.createStatement().use { it.execute("SET TimeZone='UTC'") }
            for ((calendar, year) in listOf("gregorian" to "2020", "buddhist" to "2563", "japanese" to "2")) {
                c.createStatement().use { it.execute("SET Calendar='$calendar'") }
                c.check("SELECT date_part('year',make_timestamptz(1577836800000000))", listOf(listOf(year)))
            }
            c.createStatement().use { it.execute("RESET Calendar") }
            assertEquals(listOf(listOf("gregorian")), c.rows("SELECT current_setting('Calendar')"))
        }
    }
    @Test fun scalarLocaleAndNestedListCollationsPropagateWithoutBecomingHostStringComparisons() {
        connection().use { c ->
            c.check("SELECT 'ä' COLLATE de < 'z' COLLATE de,'ä' COLLATE sv < 'z' COLLATE sv,'Ä' COLLATE nocase = 'ä' COLLATE nocase", listOf(listOf("true", "false", "true")))
            c.check("SELECT list_contains(['hello','world'],'HELLO' COLLATE nocase),list_position(['hello','world'],'WORLD' COLLATE nocase),list_contains(['héllo'],'HELLO' COLLATE \"noaccent.nocase\"),list_position([NULL,'BB'],'bb' COLLATE nocase)", listOf(listOf("true", "2", "true", "2")))
            c.check("SELECT list_contains(['A' COLLATE nocase],'a'),list_position(['A' COLLATE nocase],'a')", listOf(listOf("true", "1")))
        }
    }
    @Test fun invalidNamesAndNanosecondValuesRemainNativeWithPreparedArgumentOrder() {
        connection().use { c ->
            c.createStatement().use { it.execute("SET TimeZone='UTC'") }
            c.check("SELECT epoch_ns(TIMESTAMP_NS '2024-01-01 00:00:00.123456789' AT TIME ZONE 'UTC')", listOf(listOf("1704067200123456789")))
            for (sql in listOf("SELECT TIMESTAMP '2024-01-01' AT TIME ZONE 'NoSuch/Zone'", "SELECT 'a' COLLATE no_such_collation = 'A'")) {
                val errors = forms(sql).map { form -> assertFailsWith<SQLException> { c.rows(form) }.message!!.substringBefore(':') }
                assertEquals(1, errors.toSet().size)
            }
            assertFailsWith<SQLException> { c.createStatement().use { it.execute("SET Calendar='fnord'") } }
            val sql = SqlFragment("SELECT epoch_ns(CAST(? AS TIMESTAMP_NS) AT TIME ZONE ?)", "duckdb").toExecutable("duckdb").sql
            c.prepareStatement(sql).use { p ->
                assertEquals(2, p.parameterMetaData.parameterCount)
                p.setString(1, "2024-01-01 00:00:00.123456789")
                p.setString(2, "UTC")
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals("1704067200123456789", r.getString(1))
                }
            }
        }
    }
}
