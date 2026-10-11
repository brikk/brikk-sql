package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import java.sql.DriverManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetByteResultTest {
    @Test
    fun portableBigqueryByteValuesExecuteInDuckdbWithoutTextOrUtf8Recoding() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((source, expected) in listOf("b'a'" to "61", "b'Â'" to "C382", "b'😀\\n'" to "F09F98800A", "b'\\xff\\x00\\\\'" to "FF005C")) {
                val output = Dialects.BIGQUERY.parseOne(source).sql("duckdb")
                connection.createStatement().use { statement -> statement.executeQuery("SELECT HEX($output)").use { result ->
                    result.next(); assertEquals(expected, result.getString(1), output)
                } }
            }
        }
    }

    @Test
    fun livePostgresByteLoweringsPreserveNonUtf8NulAndBackslashBytes() {
        val container = System.getenv("BRIKK_POSTGRES_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_POSTGRES_TEST_CONTAINER is not configured")
        val client = listOf("psql", "-U", "postgres", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c")
        for ((source, expected) in listOf("b'a'" to "61", "b'Â'" to "c382", "b'\\xff\\x00\\\\'" to "ff005c")) {
            val output = Dialects.BIGQUERY.parseOne(source).sql("postgres")
            assertEquals(expected, runCatchupEngineSql(container!!, client, "SELECT ENCODE($output,'hex')"))
        }
    }
}
