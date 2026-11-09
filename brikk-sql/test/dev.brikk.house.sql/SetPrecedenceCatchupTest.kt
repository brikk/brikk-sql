package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.ColumnShape
import kotlin.test.*

/** SG29-006: upstream 86abcde3/a4e08982; native result controls live in verify modules. */
class SetPrecedenceCatchupTest {
    @Test
    fun intersectBindsTighterExceptInHiveAndExplicitGroupingWins() {
        val sql = "SELECT 1 UNION ALL SELECT 2 INTERSECT DISTINCT SELECT 2"
        for (dialect in listOf("", "mysql", "doris", "starrocks", "postgres", "duckdb", "clickhouse",
            "spark2", "spark", "presto", "trino", "bigquery", "datafusion", "spice")) {
            val union = assertIs<Union>(parseOne(sql, dialect), dialect)
            assertIs<Intersect>((union.expressionArg as Expression).unnest())
            val explicit = assertIs<Intersect>(parseOne("(SELECT 1 UNION ALL SELECT 2) INTERSECT DISTINCT SELECT 2", dialect))
            assertIs<Union>((explicit.thisArg as Expression).unnest())
        }
        val hive = assertIs<Intersect>(parseOne(sql, "hive"))
        assertIs<Subquery>(hive.thisArg)
        assertIs<Union>((hive.thisArg as Expression).unnest())
        val rendered = hive.sql("duckdb")
        val again = assertIs<Intersect>(parseOne(rendered, "duckdb"))
        assertIs<Union>((again.thisArg as Expression).unnest())
        assertEquals(hive, Serde.loadExpression(Serde.dump(hive)))
    }

    @Test
    fun finalParenthesizedOperandDoesNotSwallowOuterModifiers() {
        for (dialect in listOf("postgres", "duckdb", "mysql", "hive", "spark", "trino", "datafusion", "spice")) {
            val sql = "SELECT 1 UNION ALL (SELECT 2 INTERSECT SELECT 2) ORDER BY 1 LIMIT 1 OFFSET 0"
            val tree = assertIs<Union>(parseOne(sql, dialect), dialect)
            for (key in listOf("order", "limit", "offset")) assertNotNull(tree.args[key], "$dialect: $key")
            val arm = (tree.expressionArg as Expression).unnest()
            assertNull(arm.args["limit"])
            val output = tree.sql(dialect)
            assertEquals(output, parseOne(output, dialect).sql(dialect))
        }
    }

    @Test
    fun valuesModifiersStayOnTheSelectWrapperOfTheirOwnArm() {
        val sql = "SELECT 1 AS a UNION ALL (VALUES (2), (3) ORDER BY 1 DESC LIMIT 1)"
        val tree = assertIs<Union>(parseOne(sql, "duckdb"))
        val right = assertIs<Select>((tree.expressionArg as Expression).unnest())
        assertNotNull(right.args["order"])
        assertNotNull(right.args["limit"])
        val values = right.findAll<Values>().single()
        assertNull(values.args["order"])
        assertNull(values.args["limit"])
        assertNull(tree.args["limit"])
        assertNotNull(buildScope(tree))
        assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
    }

    @Test
    fun clickhouseMovesOnlyGlobalSettingsAndFormatNotFinalArmPagination() {
        val tree = assertIs<Union>(parseOne("SELECT 1 UNION ALL SELECT 2 INTERSECT DISTINCT SELECT 2 ORDER BY 1 LIMIT 1 SETTINGS max_threads = 1 FORMAT TabSeparated", "clickhouse"))
        assertNotNull(tree.args["settings"])
        assertNotNull(tree.args["format"])
        assertNull(tree.args["limit"])
        val intersect = assertIs<Intersect>((tree.expressionArg as Expression).unnest())
        assertNotNull((intersect.expressionArg as Expression).args["limit"])
        val output = tree.sql("clickhouse")
        assertEquals(output, parseOne(output, "clickhouse").sql("clickhouse"))
    }

    @Test
    fun groupedSetScopesAndParenthesizedValuesKeepOutputTypesAndDuplicateNames() {
        val scope = assertNotNull(buildScope(parseOne("SELECT 1 AS v UNION ALL SELECT 2 AS v INTERSECT SELECT 2 AS v", "duckdb")))
        assertIs<Union>(scope.expression)
        assertIs<Intersect>(scope.unionScopes[1].expression)
        val typed = SqlFragment("SELECT v FROM (SELECT CAST(1 AS BIGINT) AS v UNION ALL (VALUES (CAST(2 AS DOUBLE)))) AS s", "duckdb")
            .outputShape().columns.single()
        // VALUES nullability is not yet modeled by the sidecar. Do not invent a false
        // NOT NULL guarantee merely because its result type is now correctly available.
        assertEquals(ColumnShape("v", "DOUBLE", null), typed)
        assertEquals(listOf("x", "x"), SqlFragment("SELECT 1 AS x, 2 AS x UNION ALL SELECT 3, 4 INTERSECT SELECT 3, 4", "duckdb")
            .outputShape().columns.map { it.name })
    }

    @Test
    fun longFlatAndLeftGroupedChainsRenderAndBuildScopesIteratively() {
        val flat = parseOne((0..1500).joinToString(" UNION ALL ") { "SELECT $it AS a" }, "duckdb")
        assertNotNull(buildScope(flat))
        assertEquals(1500, Regex("UNION ALL").findAll(flat.sql("duckdb")).count())
        val hiveText = "SELECT 0 AS a" + (1..180).joinToString("") {
            " UNION ALL SELECT $it AS a INTERSECT DISTINCT SELECT $it AS a"
        }
        val tree = parseOne(hiveText, "hive")
        val before = Serde.dump(tree)
        assertNotNull(buildScope(tree))
        // Hive's implicit left grouping must not introduce one derived-table wrapper per
        // operation, which would rename duplicate outputs and exhaust the call stack.
        assertFalse("SELECT * FROM" in tree.sql("hive"))
        assertTrue(tree.sql("duckdb").contains("("))
        assertEquals(before, Serde.dump(tree))
    }
}
