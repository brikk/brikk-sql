package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.*

/** Native DuckDB control of the emitted BigQuery weekday contract, not a BigQuery backend claim. */
class BigqueryTrinoCatchupResultTest {
    @Test fun allWeekdaysAndNullSurviveBigqueryRoundtrip() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for (index in 0..6) {
                val date = LocalDate.of(2021, 1, 3).plusDays(index.toLong())
                val source = "DAY_OF_WEEK(DATE '$date') * 2"
                val bq = Dialects.BIGQUERY.generate(Dialects.TRINO.parseOne(source))
                assertTrue(bq.contains("EXTRACT(DAYOFWEEK"), bq)
                val sql = Dialects.DUCKDB.generate(Dialects.BIGQUERY.parseOne(bq), sourceDialect = "bigquery")
                s.executeQuery("SELECT $sql").use { r -> assertTrue(r.next()); assertEquals(date.dayOfWeek.value * 2, r.getInt(1), sql) }
            }
            val bq = Dialects.BIGQUERY.generate(Dialects.TRINO.parseOne("DAY_OF_WEEK(CAST(NULL AS DATE))"))
            val sql = Dialects.DUCKDB.generate(Dialects.BIGQUERY.parseOne(bq), sourceDialect = "bigquery")
            s.executeQuery("SELECT $sql").use { r -> assertTrue(r.next()); assertNull(r.getObject(1)) }
        } }
    }
}
