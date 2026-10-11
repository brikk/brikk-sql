package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.UsingColumnOrder
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StarUsingOrderingCatchupTest {
    private val schema = linkedMapOf(
        "a" to linkedMapOf("a_id" to "INT", "k1" to "INT", "k2" to "INT", "a_value" to "TEXT"),
        "b" to linkedMapOf("k2" to "INT", "b_id" to "INT", "k1" to "INT", "b_value" to "TEXT"),
        "c" to linkedMapOf("c_id" to "INT", "k1" to "INT"),
    )
    private fun qualified(source: String, dialect: String = "duckdb", input: Any? = schema): Expression =
        qualify(Dialects.forName(dialect).parseOne(source), dialect = Dialects.forName(dialect), schema = input,
            inferSchema = false, quoteIdentifiers = false)
    private fun names(tree: Expression) = (tree as Select).selects.filterIsInstance<Expression>().map { it.aliasOrName }

    @Test
    fun referencesAndStarsFollowFromOrderAcrossSubqueriesTablesAndUdtfs() {
        for ((source, expectedSources, expectedNames) in listOf(
            Triple("SELECT * FROM (SELECT a_id FROM a) AS s CROSS JOIN b", listOf("s", "b"), listOf("a_id", "k2", "b_id", "k1", "b_value")),
            Triple("SELECT * FROM UNNEST([1, 2]) AS u(v) CROSS JOIN a", listOf("u", "a"), listOf("v", "a_id", "k1", "k2", "a_value")),
            Triple("SELECT * FROM b CROSS JOIN (SELECT a_id FROM a) AS s", listOf("b", "s"), listOf("k2", "b_id", "k1", "b_value", "a_id")),
        )) {
            val tree = qualified(source)
            val scope = assertNotNull(buildScope(tree))
            assertEquals(expectedSources, scope.references.map { it.first })
            assertEquals(expectedNames, names(tree))
            scope.clearCache()
            assertEquals(expectedSources, scope.references.map { it.first })
        }
    }

    @Test
    fun dialectUsingOrderAndSuccessiveJoinKeysMatchTheirDeclaredPolicies() {
        val source = "SELECT * FROM a JOIN b USING (k2, k1)"
        for ((dialect, policy, keys) in listOf(
            Triple("duckdb", UsingColumnOrder.IN_PLACE, listOf("a_id", "k1", "k2", "a_value", "b_id", "b_value")),
            Triple("postgres", UsingColumnOrder.USING_LIST, listOf("k2", "k1", "a_id", "a_value", "b_id", "b_value")),
            Triple("trino", UsingColumnOrder.USING_LIST, listOf("k2", "k1", "a_id", "a_value", "b_id", "b_value")),
            Triple("starrocks", UsingColumnOrder.USING_LIST, listOf("k2", "k1", "a_id", "a_value", "b_id", "b_value")),
            Triple("mysql", UsingColumnOrder.LEFT_TABLE, listOf("k1", "k2", "a_id", "a_value", "b_id", "b_value")),
            Triple("doris", UsingColumnOrder.LEFT_TABLE, listOf("k1", "k2", "a_id", "a_value", "b_id", "b_value")),
        )) {
            assertEquals(policy, Dialects.forName(dialect).usingColumnOrder)
            assertEquals(keys, names(qualified(source, dialect)), dialect)
        }
        assertEquals(listOf("k1", "k2", "a_id", "a_value", "b_id", "b_value", "c_id"),
            names(qualified("$source JOIN c USING (k1)", "postgres")))
        assertEquals(listOf("k2", "k1", "b_id", "b_value", "a_id", "a_value"),
            names(qualified("SELECT * FROM a RIGHT JOIN b USING (k2, k1)", "mysql")))
    }

    @Test
    fun eachStarKeepsItsOwnKeysAndQualifiedStarsKeepRawDuplicateColumns() {
        val single = names(qualified("SELECT * FROM a JOIN b USING (k1)"))
        assertEquals(single + single, names(qualified("SELECT *, * FROM a JOIN b USING (k1)")))
        val allA = schema.getValue("a").keys.toList()
        val allB = schema.getValue("b").keys.toList()
        val raw = qualified("SELECT a.*, b.* FROM a FULL JOIN b USING (k1)")
        assertEquals(allA + allB, names(raw))
        assertFalse(raw.findAll<Coalesce>().any(), raw.sql("duckdb"))
        assertEquals(listOf("before") + single + listOf("after"),
            names(qualified("SELECT 0 AS before, *, 1 AS after FROM a JOIN b USING (k1)")))
        assertEquals(single + listOf("c_id", "k1"), names(qualified("SELECT * FROM a JOIN b USING (k1) CROSS JOIN c")))
    }

    @Test
    fun nestedJoinWrappersKeepAllSourcesIncludingAliasedInnerGroups() {
        for (source in listOf(
            "SELECT * FROM ((a CROSS JOIN b) CROSS JOIN c) AS j",
            "SELECT * FROM ((a CROSS JOIN b) AS i CROSS JOIN c) AS j",
            "SELECT * FROM ((SELECT a_id, k1 FROM a) CROSS JOIN c) AS j",
        )) {
            val tree = qualified(source)
            val expected = if (source.contains("SELECT a_id")) listOf("a_id", "k1", "c_id", "k1") else
                schema.values.flatMap { it.keys }
            // Duplicate derived outputs deliberately retain a star rather than inventing ambiguous j.k1 references.
            assertTrue(names(tree) == expected || names(tree) == listOf("*"), tree.sql("duckdb"))
            assertEquals(listOf("a", "b", "c").filter { !source.contains("SELECT a_id") || it != "b" },
                tree.findAll<Table>(bfs = false).map { it.name }.toList())
        }
        val visits = mutableListOf<String>()
        val tables = qualifyTables(Dialects.DUCKDB.parseOne("SELECT * FROM ((a CROSS JOIN b) CROSS JOIN c) AS j"),
            dialect = Dialects.DUCKDB, onQualify = { visits.add(it.name) })
        assertEquals(listOf("a", "b", "c"), visits)
        val first = tables.sql("duckdb")
        assertEquals(first, qualifyTables(tables, dialect = Dialects.DUCKDB).sql("duckdb"))
    }

    @Test
    fun unresolvedBareStarsKeepNativeUsingAndNaturalSyntaxWithoutInventingKeyOwnership() {
        for (input in listOf(null, mapOf("a" to schema.getValue("a")))) {
            val using = qualified("SELECT *, k1 AS joined_key, a.a_id FROM a RIGHT JOIN b USING (k1)", input = input)
            val output = using.sql("duckdb")
            assertTrue("USING (k1)" in output && "SELECT *" in output, output)
            assertFalse("a.k1 AS joined_key" in output, output)
            assertTrue(using.findAll<Join>().single().args["on"] == null)
            val natural = qualified("SELECT * FROM a NATURAL JOIN b", input = input)
            assertTrue("NATURAL JOIN" in natural.sql("duckdb"))
        }
    }
}
