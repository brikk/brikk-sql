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

internal class DuckdbGroupFetchResultTest {
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private val inputSchema = mapOf("items" to mapOf("tags" to "VARCHAR[]", "weight" to "INT"))
    private fun Connection.check(sql: String, expected: List<List<String?>>, qualified: Boolean = false) {
        // Check authored SQL as well as generated SQL. Parser/generator agreement alone is not a result oracle.
        assertEquals(expected, rows(sql), "native: $sql")
        val tree = Dialects.DUCKDB.parseOne(sql)
        val rendered = if (qualified) try {
            qualify(tree, dialect = Dialects.DUCKDB, schema = inputSchema)
        } catch (error: dev.brikk.house.sql.optimizer.OptimizeError) {
            throw AssertionError("Qualification failed for $sql; current SQL: ${tree.sql("duckdb")}", error)
        } else tree
        assertEquals(expected, rows(rendered.sql("duckdb")), "generated qualified=$qualified: $sql")
    }
    private fun lists(c: Connection) {
        c.execute("CREATE TABLE items(tags VARCHAR[],weight INT)")
        c.execute("INSERT INTO items VALUES(['a','b','b'],10),(['b',NULL],20),([],30),(NULL,40)")
    }
    private fun numbers(c: Connection) {
        c.execute("CREATE TABLE t(a INT)")
        c.execute("INSERT INTO t VALUES(5),(1),(4),(2),(3)")
    }

    @Test
    fun documentedGroupingCountsElementsAndPreservesAliasesOrdinalsAndQualifiedForms() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val documented = "SELECT unnest(tags) AS tag,count(*) AS n FROM (VALUES (['a','b']),(['b','c']),(['b'])) t(tags) GROUP BY unnest(tags) ORDER BY tag"
            c.check(documented, listOf(listOf("a", "1"), listOf("b", "3"), listOf("c", "1")))
            lists(c)
            val expected = listOf(listOf("a", "1", "10"), listOf("b", "3", "40"), listOf(null, "1", "20"))
            for (group in listOf("unnest(tags)", "tag", "1")) for (qualified in listOf(false, true)) {
                c.check("SELECT unnest(tags) AS tag,count(*) AS n,sum(weight) AS total FROM items GROUP BY $group ORDER BY tag NULLS LAST", expected, qualified)
            }
            c.check("SELECT unnest(tags) AS tag,count(*) AS n FROM items GROUP BY tag HAVING count(*)>1 ORDER BY tag",
                listOf(listOf("b", "3")), qualified = true)
        }
    }

    @Test
    fun repeatedExpressionsNestedScalarWrappersAndRecursiveListsKeepTheirNativeMeaning() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            lists(c)
            c.check("SELECT unnest(tags) AS tag,unnest(tags) AS again,count(*) AS n FROM items GROUP BY unnest(tags) ORDER BY tag NULLS LAST",
                listOf(listOf("a", "a", "1"), listOf("b", "b", "3"), listOf(null, null, "1")), qualified = true)
            for (group in listOf("tag", "upper(trim(unnest(tags)))")) {
                c.check("SELECT upper(trim(unnest(tags))) AS tag,count(*) AS n FROM items GROUP BY $group ORDER BY tag NULLS LAST",
                    listOf(listOf("A", "1"), listOf("B", "3"), listOf(null, "1")), qualified = true)
            }
            c.check("SELECT abs(coalesce(unnest([-1,NULL,-2]),0)) AS v,count(*) AS n FROM range(1) GROUP BY v ORDER BY v",
                listOf(listOf("0", "1"), listOf("1", "1"), listOf("2", "1")))
            c.check("SELECT unnest([[1,2],[2,3]],recursive:=true) AS v,count(*) AS n FROM range(1) GROUP BY v ORDER BY v",
                listOf(listOf("1", "1"), listOf("2", "2"), listOf("3", "1")))
            c.check("SELECT unnest([[1,2],[2,3]],max_depth:=1) AS v,count(*) AS n FROM range(1) GROUP BY v ORDER BY v",
                listOf(listOf("[1, 2]", "1"), listOf("[2, 3]", "1")))
            c.check("SELECT unnest([[1,2],[2,3]],max_depth:=2) AS v,count(*) AS n FROM range(1) GROUP BY v ORDER BY v",
                listOf(listOf("1", "1"), listOf("2", "2"), listOf("3", "1")))
        }
    }

    @Test
    fun emptyAndNullListsEmitNoRowsButNullElementsFormAGroup() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (list in listOf("[]::INTEGER[]", "NULL::INTEGER[]")) {
                c.check("SELECT unnest($list) AS v,count(*) AS n FROM range(1) GROUP BY v", emptyList())
            }
            c.check("SELECT unnest([NULL,NULL,1]) AS v,count(*) AS n FROM range(1) GROUP BY v ORDER BY v NULLS LAST",
                listOf(listOf("1", "1"), listOf(null, "2")))
        }
    }

    @Test
    fun groupedUnnestsZipWithPaddingWhileUngroupedProjectionExpandsAfterAggregation() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT unnest([1,2]) AS a,unnest([10]) AS b,count(*) AS n FROM range(1) GROUP BY a,b ORDER BY a",
                listOf(listOf("1", "10", "1"), listOf("2", null, "1")))
            c.check("SELECT unnest([1,2]) AS a,unnest([10,20]) AS b,count(*) AS n FROM range(1) GROUP BY a ORDER BY a,b",
                listOf(listOf("1", "10", "1"), listOf("1", "20", "1"), listOf("2", "10", "1"), listOf("2", "20", "1")))
        }
    }

    @Test
    fun groupedNativeOutputMetadataDistinguishesElementsCountsAndAggregatePromotions() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            lists(c)
            c.createStatement().use { statement ->
                val sql = "SELECT unnest(tags) AS tag,count(*) AS n,max(weight) AS largest,sum(weight) AS total FROM items GROUP BY tag"
                statement.executeQuery(Dialects.DUCKDB.parseOne(sql).sql("duckdb")).use { result ->
                    val metadata = result.metaData
                    assertEquals(listOf("tag", "n", "largest", "total"), (1..4).map(metadata::getColumnLabel))
                    // The generic static SUM(INT)->BIGINT promotion is a separate known gap,
                    // recorded in DDB20-073. Do not silently call it DuckDB return-type parity.
                    assertEquals(listOf("VARCHAR", "BIGINT", "INTEGER", "HUGEINT"), (1..4).map(metadata::getColumnTypeName))
                }
            }
        }
    }

    @Test
    fun groupingBinderRejectsUnsupportedStructsNestedUnnestAggregatesWindowsAndGroupByAll() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((sql, message) in listOf(
                "SELECT unnest([1,2]) AS v,count(*) FROM range(1) GROUP BY ALL" to "Cannot group on an UNNEST",
                "SELECT count(*) FROM range(1) GROUP BY unnest({'a':1})" to "UNNEST of struct",
                "SELECT unnest(unnest([[1,2]])) AS v,count(*) FROM range(1) GROUP BY v" to "Nested UNNEST calls",
                "SELECT count(*) FROM range(3) t(i) GROUP BY unnest([sum(i)])" to "cannot contain aggregates",
                "SELECT count(*) FROM range(3) t(i) GROUP BY unnest([row_number() OVER ()])" to "cannot contain window functions",
            )) {
                // These are binder/type restrictions, not claims that static parsing validates GROUP BY semantics.
                for (text in listOf(sql, Dialects.DUCKDB.parseOne(sql).sql("duckdb"))) {
                    val error = assertFailsWith<SQLException>(text) { c.rows(text) }
                    assertTrue(message in error.message.orEmpty(), error.message)
                }
            }
        }
    }

    @Test
    fun fetchCountlessFirstNextAndOffsetOrdersReturnTheSameRowsAsLimit() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            numbers(c)
            for (direction in listOf("FIRST", "NEXT")) for (word in listOf("ROW", "ROWS")) for ((count, size) in listOf("" to 1, "2 " to 2)) {
                for ((suffix, offset) in listOf(
                    "FETCH $direction $count$word ONLY" to 0,
                    "OFFSET 1 $word FETCH $direction $count$word ONLY" to 1,
                    "FETCH $direction $count$word ONLY OFFSET 1 $word" to 1,
                )) {
                    val expected = (offset + 1..offset + size).map { listOf(it.toString()) }
                    c.check("SELECT a FROM t ORDER BY a $suffix", expected)
                    assertEquals(expected, c.rows("SELECT a FROM t ORDER BY a LIMIT $size OFFSET $offset"))
                }
            }
        }
    }

    @Test
    fun fetchExpressionsNullZeroDecimalAndBoundsUseNativeLimitSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            numbers(c)
            for ((count, size) in listOf("1+1" to 2, "(1+1)" to 2, "(SELECT 2)" to 2,
                "CASE WHEN true THEN 2 ELSE 1 END" to 2, "NULL" to 5, "0" to 0, "1.5" to 2, "100" to 5)) {
                c.check("SELECT a FROM t ORDER BY a FETCH FIRST $count ROWS ONLY", (1..size).map { listOf(it.toString()) })
            }
            c.check("SELECT a FROM t ORDER BY a OFFSET 100 ROWS FETCH NEXT ROW ONLY", emptyList())
            c.check("SELECT a FROM t ORDER BY a OFFSET NULL ROWS FETCH NEXT ROW ONLY", listOf(listOf("1")))
            for (suffix in listOf("FETCH FIRST -1 ROWS ONLY", "OFFSET -1 ROW FETCH NEXT ROW ONLY", "FETCH FIRST a ROWS ONLY")) {
                val sql = "SELECT a FROM t ORDER BY a $suffix"
                assertFailsWith<SQLException> { c.rows(sql) }
                assertFailsWith<SQLException> { c.rows(Dialects.DUCKDB.parseOne(sql).sql("duckdb")) }
            }
        }
    }

    @Test
    fun paginationStaysOnItsOwnCteAndSetOperationRatherThanMovingAcrossBranches() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            numbers(c)
            c.check("WITH q AS (SELECT a FROM t ORDER BY a FETCH FIRST 2 ROWS ONLY) SELECT a FROM q ORDER BY a DESC FETCH NEXT ROW ONLY",
                listOf(listOf("2")))
            c.check("SELECT a FROM t UNION ALL SELECT a FROM t ORDER BY a OFFSET 1 ROW FETCH NEXT 3 ROWS ONLY",
                listOf(listOf("1"), listOf("2"), listOf("2")))
            c.check("(SELECT a FROM t ORDER BY a DESC FETCH FIRST 2 ROWS ONLY) UNION ALL SELECT a FROM t ORDER BY a FETCH NEXT 3 ROWS ONLY",
                listOf(listOf("1"), listOf("2"), listOf("3")))
        }
    }

    @Test
    fun preparedFetchCountAndOffsetAreBoundOnEachExecution() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            numbers(c)
            for ((authored, offsetFirst) in listOf(
                "SELECT a FROM t ORDER BY a OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" to true,
                "SELECT a FROM t ORDER BY a FETCH NEXT ? ROWS ONLY OFFSET ? ROWS" to false,
            )) {
                for (text in listOf(authored, Dialects.DUCKDB.parseOne(authored).sql("duckdb"))) {
                    c.prepareStatement(text).use { statement ->
                        for ((offset, count) in listOf(1 to 2, 3 to 1)) {
                            // Exactly the same authored bindings, also for generated SQL.
                            statement.setInt(if (offsetFirst) 1 else 2, offset)
                            statement.setInt(if (offsetFirst) 2 else 1, count)
                            statement.executeQuery().use { result ->
                                val values = buildList { while (result.next()) add(result.getInt(1)) }
                                assertEquals((offset + 1..offset + count).toList(), values)
                            }
                        }
                    }
                }
            }
            val named = "SELECT a FROM t ORDER BY a OFFSET \$1 ROWS FETCH NEXT \$2 ROWS ONLY"
            for (text in listOf(named, Dialects.DUCKDB.parseOne(named).sql("duckdb"))) {
                c.prepareStatement(text).use { statement ->
                    statement.setInt(1, 1)
                    statement.setInt(2, 2)
                    statement.executeQuery().use { result ->
                        assertEquals(listOf(2, 3), buildList { while (result.next()) add(result.getInt(1)) })
                    }
                }
            }
        }
    }

    @Test
    fun nativeFetchGrammarRejectsMissingKeywordsTiesPercentAndDuplicatePagination() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            numbers(c)
            for (suffix in listOf("FETCH 2 ROWS ONLY", "FETCH FIRST 2", "FETCH FIRST 2 ONLY", "FETCH FIRST 2 ROWS",
                "FETCH FIRST 2 ROWS WITH TIES", "FETCH FIRST 50 PERCENT ROWS ONLY", "FETCH FIRST ROW",
                "LIMIT 2 FETCH FIRST ROW ONLY", "FETCH FIRST ROW ONLY LIMIT 2", "FETCH FIRST ROW ONLY FETCH NEXT ROW ONLY",
                "OFFSET 1 ROW OFFSET 2 ROW FETCH FIRST ROW ONLY")) {
                assertFailsWith<SQLException>(suffix) { c.rows("SELECT a FROM t ORDER BY a $suffix") }
            }
        }
    }
}
