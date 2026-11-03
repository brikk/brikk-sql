package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbNearestJoinResultTest {
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private val schema = mapOf(
        "queries" to mapOf("id" to "INT", "x" to "INT"),
        "points" to mapOf("id" to "INT", "y" to "INT"),
        "users" to mapOf("id" to "INT", "vec" to "FLOAT[2]"),
        "products" to mapOf("id" to "INT", "vec" to "FLOAT[2]"),
    )
    private fun Connection.generated(sql: String, qualified: Boolean = false): List<List<String?>> {
        val tree = Dialects.DUCKDB.parseOne(sql)
        val rendered = if (qualified) qualify(tree, dialect = Dialects.DUCKDB, schema = schema) else tree
        return rows(rendered.sql("duckdb"))
    }
    private fun scalarFixture(c: Connection) {
        c.execute("CREATE TABLE queries(id INTEGER,x INTEGER)")
        c.execute("INSERT INTO queries VALUES(1,0),(1,10),(2,20),(3,NULL)")
        c.execute("CREATE TABLE points(id INTEGER,y INTEGER)")
        c.execute("INSERT INTO points VALUES(10,1),(11,3),(12,11),(13,20),(14,NULL)")
    }
    private fun vectorFixture(c: Connection) {
        c.execute("CREATE TABLE users(id INTEGER,vec FLOAT[2])")
        c.execute("INSERT INTO users VALUES(1,[1,0]),(2,[0,1]),(3,NULL)")
        c.execute("CREATE TABLE products(id INTEGER,vec FLOAT[2])")
        c.execute("INSERT INTO products VALUES(10,[0.9,0.1]),(11,[0.8,0.2]),(12,[0.1,0.9]),(13,NULL)")
    }

    @Test
    fun pinnedEngineModesReturnTopKVectorMatchesForEachLeftRow() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            vectorFixture(c)
            val expected = listOf(listOf("1", "10"), listOf("1", "11"), listOf("2", "11"), listOf("2", "12"))
            // APPROX is informational in v2.0.0's binder. This fixture is not a promise about
            // approximate indexes or another engine version, and has no ties at the boundary.
            for (mode in listOf("", "EXACT ", "APPROX ")) for ((metric, function) in listOf(
                "DISTANCE" to "array_distance", "SIMILARITY" to "array_cosine_similarity",
            )) for (qualified in listOf(false, true)) {
                val sql = "SELECT q.id,p.id FROM users q INNER JOIN products p ${mode}NEAREST 2 BY $metric $function(q.vec,p.vec) ORDER BY q.id,p.id"
                assertEquals(expected, c.generated(sql, qualified), "$mode$metric qualified=$qualified")
            }
            assertEquals(listOf(listOf("1", "10"), listOf("2", "12")), c.generated(
                "SELECT q.id,products.id FROM users q JOIN products NEAREST BY DISTANCE array_distance(q.vec,products.vec) ORDER BY q.id"))
        }
    }

    @Test
    fun correlatedTopKPreservesDuplicateLeftRowsAndArithmeticRankingMatchesALateralOracle() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            scalarFixture(c)
            c.execute("INSERT INTO queries VALUES(1,0)")
            val projection = "SELECT q.id,q.x,p.id,p.y"
            val ordering = " ORDER BY q.id,q.x,p.id"
            val simple = "$projection FROM queries q JOIN points p NEAREST 2 BY DISTANCE abs(q.x-p.y)$ordering"
            val expected = listOf(
                listOf("1", "0", "10", "1"), listOf("1", "0", "10", "1"),
                listOf("1", "0", "11", "3"), listOf("1", "0", "11", "3"),
                listOf("1", "10", "11", "3"), listOf("1", "10", "12", "11"),
                listOf("2", "20", "12", "11"), listOf("2", "20", "13", "20"),
            )
            assertEquals(expected, c.generated(simple))
            assertEquals(expected, c.generated(simple, qualified = true))
            for (metric in listOf("abs(q.x-c.y)", "abs(q.x-c.y) + 2 * c.y")) {
                // The oracle SQL goes straight to the engine, not through Brikk generation.
                val oracle = "$projection FROM queries q JOIN LATERAL (SELECT * FROM points c WHERE ($metric) IS NOT NULL ORDER BY $metric LIMIT 2) p ON TRUE$ordering"
                val actual = "$projection FROM queries q JOIN points p NEAREST 2 BY DISTANCE ${metric.replace("c.y", "p.y")}$ordering"
                assertEquals(c.rows(oracle), c.generated(actual), metric)
                assertEquals(c.rows(oracle), c.generated(actual, qualified = true), metric)
            }
        }
    }

    @Test
    fun similarityAndDistanceSortInOppositeDirectionsWithoutRequiringLeftReferences() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            scalarFixture(c)
            for ((metric, id) in listOf("DISTANCE" to "10", "SIMILARITY" to "13")) {
                assertEquals(listOf(listOf("1", "0", id), listOf("1", "10", id), listOf("2", "20", id), listOf("3", null, id)),
                    c.generated("SELECT q.id,q.x,p.id FROM queries q JOIN points p NEAREST 1 BY $metric p.y ORDER BY q.id,q.x NULLS LAST"), metric)
            }
        }
    }

    @Test
    fun nullRankingsAreExcludedAndLeftJoinsPadOnlyWhenNoCandidatesRemain() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            vectorFixture(c)
            val matched = listOf(listOf("1", "10"), listOf("1", "11"), listOf("1", "12"), listOf("2", "10"), listOf("2", "11"), listOf("2", "12"))
            for ((kind, expected) in listOf("INNER" to matched, "LEFT OUTER" to (matched + listOf(listOf("3", null))))) {
                assertEquals(expected, c.generated("SELECT q.id,p.id FROM users q $kind JOIN products p NEAREST 10 BY DISTANCE array_distance(q.vec,p.vec) ORDER BY q.id,p.id"), kind)
            }
            c.execute("CREATE TABLE empty_products(id INTEGER,vec FLOAT[2])")
            c.execute("CREATE TABLE null_products(id INTEGER,vec FLOAT[2])")
            c.execute("INSERT INTO null_products VALUES(1,NULL),(2,NULL)")
            for (table in listOf("empty_products", "null_products")) {
                assertEquals(emptyList(), c.generated("SELECT q.id,p.id FROM users q JOIN $table p NEAREST 2 BY DISTANCE array_distance(q.vec,p.vec)"), table)
                assertEquals(listOf(listOf("1", null), listOf("2", null), listOf("3", null)), c.generated(
                    "SELECT q.id,p.id FROM users q LEFT JOIN $table p NEAREST 2 BY DISTANCE array_distance(q.vec,p.vec) ORDER BY q.id"), table)
            }
        }
    }

    @Test
    fun tiesLimitCardinalityWithoutPromisingWhichTiedCandidateWins() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE queries(id INTEGER,x INTEGER)")
            c.execute("INSERT INTO queries VALUES(1,0),(2,0)")
            c.execute("CREATE TABLE points(id INTEGER,y INTEGER)")
            c.execute("INSERT INTO points VALUES(10,-1),(11,1),(12,-1),(13,NULL)")
            for (k in listOf(1, 2)) {
                val rows = c.generated("SELECT q.id,p.id FROM queries q JOIN points p EXACT NEAREST $k BY DISTANCE abs(q.x-p.y)")
                val perLeft = rows.groupBy { it[0] }
                assertEquals(setOf("1", "2"), perLeft.keys)
                for (chosen in perLeft.values) {
                    assertEquals(k, chosen.size)
                    val ids = chosen.map { it[1] }.toSet()
                    assertEquals(k, ids.size)
                    assertTrue(ids.all { it in setOf("10", "11", "12") })
                }
            }
        }
    }

    @Test
    fun targetPrefiltersAreNotMovedPastRankingAndQuotedModeAliasesStillBind() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            vectorFixture(c)
            assertEquals(listOf(listOf("1", "11"), listOf("2", "12")), c.generated(
                "SELECT q.id,p.id FROM users q JOIN (SELECT * FROM products WHERE id <> 10) p NEAREST 1 BY DISTANCE array_distance(q.vec,p.vec) ORDER BY q.id"))
            assertEquals(listOf(listOf("2", "12")), c.generated(
                "SELECT q.id,p.id FROM users q JOIN products p NEAREST 1 BY DISTANCE array_distance(q.vec,p.vec) WHERE p.id <> 10 ORDER BY q.id"))
            for (alias in listOf("exact", "approx", "nearest")) {
                assertEquals(listOf(listOf("1", "10"), listOf("2", "12")), c.generated(
                    "SELECT q.id,\"$alias\".id FROM users q JOIN products \"$alias\" NEAREST 1 BY DISTANCE array_distance(q.vec,\"$alias\".vec) ORDER BY q.id"), alias)
            }
            c.execute("CREATE TABLE nearest(approx INTEGER,exact INTEGER,distance INTEGER,similarity INTEGER)")
            c.execute("INSERT INTO nearest VALUES(1,2,3,4)")
            assertEquals(listOf(listOf("1", "2", "3", "4")), c.generated("SELECT approx,exact,distance,similarity FROM nearest"))
            assertEquals(listOf(listOf("1", "1"), listOf("3", "3")), c.generated(
                "SELECT q.x,range FROM (VALUES (1),(3)) q(x) JOIN range(5) NEAREST 1 BY DISTANCE abs(q.x-range) ORDER BY q.x"))
            assertEquals(listOf(listOf("10"), listOf("20")), c.generated(
                "SELECT col0 FROM (SELECT 1 AS x) q JOIN (VALUES (10),(20),(30)) NEAREST 2 BY DISTANCE abs(col0-q.x) ORDER BY col0"))
        }
    }

    @Test
    fun nativeParserRejectsInvalidCountsJoinTypesAndExtraQualifiersButAcceptsIntegerSpellings() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            scalarFixture(c)
            val rank = "BY DISTANCE abs(q.x-p.y)"
            val invalid = listOf("0", "-1", "1.5", "1.0", "1e0", "9223372036854775808", "?", "\$1").map {
                "SELECT * FROM queries q JOIN points p NEAREST $it $rank"
            } + listOf("RIGHT", "FULL OUTER", "SEMI", "ANTI", "CROSS", "NATURAL", "ASOF LEFT", "LEFT INNER").map {
                "SELECT * FROM queries q $it JOIN points p NEAREST 1 $rank"
            } + listOf(
                "SELECT * FROM queries q, points p NEAREST 1 $rank",
                "SELECT * FROM queries q JOIN points p ON q.id=p.id NEAREST 1 $rank",
                "SELECT * FROM queries q JOIN points p USING(id) NEAREST 1 $rank",
                "SELECT * FROM queries q JOIN points p NEAREST 1 BY \"DISTANCE\" abs(q.x-p.y)",
            )
            for (sql in invalid) {
                val error = assertFailsWith<SQLException>(sql) { c.rows(sql) }
                assertTrue(error.message.orEmpty().contains("Parser Error"), "$sql: ${error.message}")
            }
            for ((count, expected) in listOf("01" to 3, "1_0" to 12)) {
                assertEquals(expected, c.generated("SELECT q.id,p.id FROM queries q JOIN points p NEAREST $count $rank").size)
            }
        }
    }

    @Test
    fun nullVectorElementsAreAFunctionErrorRatherThanAWholeNullVectorCandidate() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val error = assertFailsWith<SQLException> { c.generated(
                "SELECT p.id FROM (SELECT [1,0]::FLOAT[2] AS vec) q JOIN (SELECT 1 AS id,[NULL,1]::FLOAT[2] AS vec) p NEAREST 1 BY DISTANCE array_distance(q.vec,p.vec)") }
            assertTrue(error.message.orEmpty().contains("Invalid Input Error"), error.message)
            assertTrue(error.message.orEmpty().contains("NULL"), error.message)
        }
    }
}
