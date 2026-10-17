package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ClickHouse's `cond ? a : b` against IDE binds, as the IDE's ClickHouse grammar reads them: a `?` in
 * operand position is a bind, one after a complete operand the ternary operator, and the `:` closing a
 * ternary is never a bind, though a `:name` in operand position is.
 */
class ClickhouseTernaryBindsTest {
    /** The binds of [sql] as `text@offset`, then the ambiguous `?` offsets. */
    private fun binds(sql: String, dialect: String = "clickhouse"): Pair<List<String>, List<Int>> {
        val result = SqlScriptParameters.parse(sql, dialect, positional = true)
        return result.occurrences.filter { it.style != ParamStyle.NAMED_TYPED }
            .map { "${sql.substring(it.range.start, it.range.end)}@${it.range.start}" } to result.ambiguous.map { it.start }
    }

    private fun assertBinds(sql: String, vararg expected: String) = assertEquals(expected.toList() to emptyList(), binds(sql), sql)

    @Test
    fun aTernaryHasNoBinds() {
        assertBinds("SELECT flag ? a : b")
        assertBinds("SELECT flag?a:b")
        assertBinds("SELECT x ? y :z")
        assertBinds("SELECT a ? b : c ? d : e")
        assertBinds("SELECT x ? (y ? 1 : 2) : 3")
        assertBinds("SELECT (x > 1) ? 'a' : 'b'")
        assertBinds("SELECT {'k': a ? b : c}")
    }

    @Test
    fun bindsInOperandPositionStayBinds() {
        assertBinds("SELECT ?", "?@7")
        assertBinds("SELECT :name", ":name@7")
        assertBinds("SELECT f(?, ?)", "?@9", "?@12")
        assertBinds("SELECT 1 WHERE x = ? AND y = :n", "?@19", ":n@29")
        assertBinds("SELECT ? + 1", "?@7")
        assertBinds("SELECT 1 FROM t LIMIT ?", "?@22")
        assertBinds("SELECT [?, ?]", "?@8", "?@11")
        assertBinds("INSERT INTO t VALUES (?, ?)", "?@22", "?@25")
    }

    @Test
    fun bindsAndTernariesMix() {
        assertBinds("SELECT c ? 1 : 0, ?", "?@18")
        assertBinds("SELECT ? ? 1 : 0", "?@7")
        assertBinds("SELECT * FROM t WHERE a = ? ? 1 : 2", "?@26")
        assertBinds("SELECT x ? ? : ?", "?@11", "?@15")
        assertBinds("SELECT a ? b : :c", ":c@15")
        assertBinds("SELECT ?, flag ? 1 : 0", "?@7")
        // The IDE's grammar misreads this one (it reports the ternary `?` and one parameter `:p : ?`).
        assertBinds("SELECT x ? :p : ?", ":p@11", "?@16")
        assertBinds("SELECT {name:UInt64}, a ? :x : 1", ":x@26")
    }

    @Test
    fun whatTokensCannotProveIsAmbiguous() {
        // A ternary never closed: the IDE calls the `?` a parameter.
        assertEquals(emptyList<String>() to listOf(9), binds("SELECT x ?"))
        assertEquals(emptyList<String>() to listOf(11), binds("SELECT f(x ?) FROM t"))
        // `:y` begins the middle, and the ternary never closes: the IDE reports `?` and `:y` as parameters.
        assertEquals(listOf(":y@10") to listOf(9), binds("SELECT x ?:y"))
        // An empty middle.
        assertEquals(emptyList<String>() to listOf(9), binds("SELECT x ? : y"))
        assertEquals(listOf("?@19") to listOf(9), binds("SELECT x ?; SELECT ?"))
    }

    /** `?::` lexes as one token; its `?` follows the same rules, and the cast's type ends an operand. */
    @Test
    fun aCastBindIsABind() {
        assertBinds("SELECT ?::UInt64", "?@7")
        assertBinds("SELECT f(?::Int32, :n::Int32)", "?@9", ":n@19")
        assertBinds("SELECT :name::UInt64", ":name@7")
        assertBinds("SELECT ?::UInt8 ? 1 : 0", "?@7")
        assertBinds("SELECT x::Int32 ? 1 : 0")
        assertBinds("SELECT c ? ?::Int32 : 0", "?@11")
        assertBinds("SELECT ? ::Int32", "?@7")
    }

    @Test
    fun otherDialectsHaveNoTernary() {
        assertEquals(listOf("?@9", ":z@13") to emptyList(), binds("SELECT x ? y :z", "duckdb"))
        assertEquals(listOf("?@7") to emptyList(), binds("SELECT ?", "doris"))
        assertEquals(listOf("?@7", ":n@19") to emptyList(), binds("SELECT ?::INTEGER, :n::INTEGER", "duckdb"))
        assertEquals(listOf("?@9") to emptyList(), binds("SELECT f(?::int)", "postgres"))
    }

    @Test
    fun withoutPositionalOnlyColonBindsAreReportedAsBefore() {
        val result = SqlScriptParameters.parse("SELECT ?, x ? y :z, :n", "clickhouse")
        assertEquals(listOf("n"), result.occurrences.map { it.name })
        assertEquals(emptyList(), result.ambiguous)
    }
}
