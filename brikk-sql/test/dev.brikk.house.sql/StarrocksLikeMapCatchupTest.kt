package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

/** SG29-014 wrong-result sub-round only; the other StarRocks features stay open. */
class StarrocksLikeMapCatchupTest {
    @Test fun functionLikeKeepsValueBeforePatternAndDoesNotChangeBaseLike() {
        val like = assertIs<Like>(parseOne("LIKE('xyz','x%')", "starrocks"))
        assertEquals("xyz", (like.thisArg as Expression).name)
        assertEquals("x%", (like.expressionArg as Expression).name)
        assertEquals("'xyz' LIKE 'x%'", like.sql("starrocks"))
        assertEquals("SELECT 'xyz' LIKE 'x%'", transpile("SELECT LIKE('xyz','x%')", "starrocks", "duckdb"))
        assertEquals("xyz", (assertIs<Like>(parseOne("LIKE('x%','xyz')")).thisArg as Expression).name)
        assertIs<Null>(assertIs<Like>(parseOne("LIKE(NULL,'x%')", "starrocks")).thisArg)
        assertFailsWith<ParseError> { parseOne("LIKE('a','a','!')", "starrocks") }
    }
    @Test fun literalIsAMapAndLookupWrapsOnlyTheLiteral() {
        for (sql in listOf("MAP{1:2, 3:NULL}", "MAP{}", "MAP{TRUE:MAP{3.13:'abc'}, FALSE:MAP{}}")) {
            val tree = parseOne(sql, "starrocks")
            assertIs<ToMap>(tree)
            assertEquals(sql, tree.sql("starrocks"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
        val lookup = assertIs<Bracket>(parseOne("MAP{1:MAP{2:10}}[1][2]", "starrocks"))
        assertIs<ToMap>(assertIs<Bracket>(lookup.thisArg).thisArg)
        assertEquals("MAP{1:MAP{2:10}}[1][2]", lookup.sql("starrocks"))
    }
    @Test fun qualifiedAndComputedKeysStayExpressionsInStarrocksAndDuckdb() {
        for (dialect in listOf("starrocks", "duckdb")) {
            val tree = parseOne("SELECT MAP{t.k:t.v} FROM (SELECT 1 AS k, 10 AS v) AS t", dialect)
            val entry = tree.findAll<ToMap>().single().findAll<PropertyEQ>().single()
            assertEquals("t", assertIs<Column>(entry.thisArg).table)
            assertEquals("k", (entry.thisArg as Expression).name)
            assertEquals("t", assertIs<Column>(entry.expressionArg).table)
        }
        val expr = parseOne("MAP{1 + 2:10}", "starrocks").findAll<PropertyEQ>().single()
        assertIs<Add>(expr.thisArg)
        assertTrue(parseOne("SELECT map, t.map, map[1] FROM t", "starrocks").findAll<ToMap>().none())
        assertIs<VarMap>(parseOne("MAP('a',1,'b',2)", "starrocks"))
    }
    @Test fun nestedLookupsAndGenerationDoNotMutateTheOwnedAst() {
        for (source in listOf("SELECT MAP{1:MAP{2:10}}[1][2]", "SELECT MAP{1:[10,20]}[1][2]",
            "SELECT MAP{t.k:10}[1] FROM (SELECT 1 AS k) AS t CROSS JOIN (SELECT 2 AS k) AS u")) {
            val tree = parseOne(source, "starrocks"); val before = Serde.dump(tree)
            val parents = tree.walk().map { it to it.parent }.toList()
            val output = Dialects.STARROCKS.generator(sourceDialect="starrocks").generate(tree,copy=false)
            assertEquals(before,Serde.dump(tree),output)
            for ((node,parent) in parents) assertSame(parent,node.parent)
            assertEquals(output,Serde.loadExpression(before).sql("starrocks"))
        }
    }
    @Test fun rawMapContractsDoNotBecomeUnqualifiedCrossEngineExecution() {
        for (target in listOf("duckdb","postgres","mysql","trino","clickhouse")) {
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT MAP{NULL:1,'a':2}","starrocks").toExecutable(target) }
        }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT MAP {'a':1}","duckdb").toExecutable("starrocks") }
        assertContains(transpile("SELECT MAP{'a':1}['a']","starrocks","duckdb"),"MAP")
    }
    @Test fun literalMapTypingUsesKeyExpressionsRatherThanStructFieldNames() {
        val tree = parseOne("MAP{CAST(1 AS INT):CAST(2 AS INT)}","starrocks")
        dev.brikk.house.sql.optimizer.annotateTypes(tree,dialect=Dialects.STARROCKS)
        val type = assertIs<DataType>(tree.type)
        assertEquals(DType.MAP,type.thisArg)
        assertEquals(listOf(DType.INT,DType.INT),type.expressionsArg.map { assertIs<DataType>(it).thisArg })
    }
}
