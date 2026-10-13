package dev.brikk.house.sql

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Editor scopes lower pipes leniently: what execution refuses still lowers, without the namespaces it cannot keep. */
class PipeLenientLoweringTest {
    private val duckdb = Dialects.DUCKDB

    private fun lower(sql: String, lenient: Boolean) =
        duckdb.generate(desugarPipes(duckdb.parseOne(sql), duckdb, copy = true, lenient = lenient))

    @Test
    fun anUnaliasedInputLosesItsQualifierAcrossABoundary() {
        val sql = "FROM src() |> LIMIT 1 |> WHERE src.id > 0"
        assertFailsWith<UnsupportedError> { lower(sql, lenient = false) }
        assertEquals("WITH __tmp1 AS (SELECT * FROM SRC() LIMIT 1) SELECT * FROM __tmp1 WHERE src.id > 0", lower(sql, lenient = true))
    }

    @Test
    fun aMultiSourceInputLosesItsQualifiersAcrossABoundary() {
        val sql = "FROM a e |> JOIN b r ON e.id = r.id |> DISTINCT |> WHERE e.id > 0"
        assertFailsWith<UnsupportedError> { lower(sql, lenient = false) }
        assertEquals("WITH __tmp1 AS (SELECT DISTINCT * FROM a AS e JOIN b AS r ON e.id = r.id) SELECT * FROM __tmp1 WHERE e.id > 0",
            lower(sql, lenient = true))
    }

    @Test
    fun aSliceThatIsNoLiteralIsKeptAsWritten() {
        val sql = "FROM a |> LIMIT :n"
        assertFailsWith<UnsupportedError> { lower(sql, lenient = false) }
        assertEquals("SELECT * FROM a LIMIT \$n", lower(sql, lenient = true))
    }

    @Test
    fun setAndDropOutputsAreClosedBeforeALaterJoin() {
        val sql = "FROM a e |> DROP x |> JOIN b r ON e.id = r.id |> WHERE r.code IS NULL"
        assertFailsWith<UnsupportedError> { lower(sql, lenient = false) }
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM a AS e), __tmp2 AS (SELECT * EXCLUDE (x) FROM __tmp1) " +
                "SELECT * FROM __tmp2 JOIN b AS r ON e.id = r.id WHERE r.code IS NULL",
            lower(sql, lenient = true),
        )
    }

    @Test
    fun lenientLoweringOfWhatExecutionAcceptsIsUnchanged() {
        val sql = "FROM a e |> WHERE e.id > 0 |> LIMIT 2 |> WHERE e.id < 9 |> SELECT id"
        assertEquals(lower(sql, lenient = false), lower(sql, lenient = true))
    }
}
