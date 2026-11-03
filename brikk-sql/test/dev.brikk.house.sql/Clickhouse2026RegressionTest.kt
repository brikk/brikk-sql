package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.*

class Clickhouse2026RegressionTest {
    @Test
    fun materializedCtesAndProjectionWhereRemainStructured() {
        val cte = parseOne("WITH c AS MATERIALIZED (SELECT 1 x) SELECT x FROM c", "clickhouse")
        assertEquals(true, cte.find<CTE>()?.args?.get("materialized"))
        val projection = parseOne("CREATE TABLE t (x Int32, PROJECTION p (SELECT x WHERE x > 0 ORDER BY x)) ENGINE=MergeTree ORDER BY x", "clickhouse")
        assertNotNull(projection.find<ProjectionDef>()?.find<Where>())
        for (ast in listOf(cte, projection)) assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"))
    }

    @Test
    fun jsonBracketsLikeEscapesAndArrayLiteralsKeepTheirNativeNodes() {
        val json = parseOne("SELECT j['a']['b'] FROM t", "clickhouse")
        assertEquals(2, json.findAll<Bracket>().count())
        val escape = parseOne("SELECT x LIKE 'a!_%' ESCAPE '!' FROM t", "clickhouse")
        assertNotNull(escape.find<Escape>())
        assertNotNull(parseOne("SELECT ARRAY[1,2,3]", "clickhouse").find<ArrayNode>())
        for (ast in listOf(json, escape)) assertEquals(ast, Serde.load(Serde.dump(ast)))
    }

    @Test
    fun unchangedNativeSqlKeepsSourceRangesAndParametersThroughExecutablePreservation() {
        for (sql in listOf(
            "WITH c AS MATERIALIZED (SELECT {n:UInt64} x) SELECT * FROM c",
            "SELECT j['a']['b'] FROM t WHERE x LIKE {pattern:String} ESCAPE '!'",
            "SELECT CAST(NULL AS Nullable(Tuple(x Int32))).x SETTINGS enable_nullable_tuple_type=1",
            "SELECT sum(x) OVER (ORDER BY x GROUPS BETWEEN CURRENT ROW AND 1 FOLLOWING) FROM t",
        )) {
            val fragment = SqlFragment(sql, "clickhouse")
            assertEquals(sql, fragment.toSourcePreservingExecutable().sql)
            assertEquals(fragment.ast, Serde.load(Serde.dump(fragment.ast)))
        }
    }
}
