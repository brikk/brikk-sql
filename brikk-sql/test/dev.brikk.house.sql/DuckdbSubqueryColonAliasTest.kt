package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbSubqueryColonAliasTest {
    @Test fun subqueryPrefixAliasesSurviveGenerationAndResolveInFromFirstJoinAndNestedScopes() {
        for(sql in listOf("SELECT q.n FROM q: (SELECT 1 AS n)","FROM q: (SELECT 1 AS n) SELECT q.n",
            "SELECT q.n,r.m FROM q: (SELECT 1 AS n) JOIN r: (SELECT 2 AS m) ON q.n<r.m",
            "SELECT q.n FROM q: (SELECT r.n FROM r: (SELECT 3 AS n))",
            "WITH c AS (SELECT 4 AS n) SELECT q.n FROM q: (FROM c SELECT n)")) {
            val tree=parseDuckdbNativeOne(sql)
            assertTrue(tree.findAll<Subquery>().any { it.alias=="q" },sql)
            assertEquals(tree,Serde.load(Serde.dump(tree)))
            val generated=tree.copy().sql("duckdb")
            assertEquals(generated,parseDuckdbNativeOne(generated).sql("duckdb"))
            assertEquals(listOf("n")+if("r.m" in sql)listOf("m")else emptyList(),RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.map { it.name })
            assertFalse(SqlFragment(sql,"duckdb").toExecutable("duckdb").isRawPassthroughStatement)
        }
        val sql="SELECT q. FROM q: (SELECT 1 AS n)"
        assertEquals("INT",RelationScope.complete(sql,"duckdb",RelationFacts(),sql.indexOf("q.")+2).items.single { it.name=="n" }.type)
    }
    @Test fun nativeDoubleAliasesAndMalformedColonTargetsAreNotSilentlyRewritten() {
        for(sql in listOf("SELECT * FROM q: (SELECT 1 AS n) AS r","SELECT * FROM q(n): (SELECT 1 AS n)",
            "SELECT * FROM q:","SELECT * FROM 7: (SELECT 1)","SELECT * FROM q: (SELECT 1) (x)"))
            assertFails(sql) { parseDuckdbNativeOne(sql) }
    }
}
