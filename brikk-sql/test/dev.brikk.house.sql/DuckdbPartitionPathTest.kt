package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbPartitionPathTest {
    private val facts = RelationFacts(tables = mapOf("sales" to RelationFact(listOf(ColumnFact("region","VARCHAR"),
        ColumnFact("day","INTEGER"),ColumnFact("id","INTEGER")))),catalog=CatalogKnowledge.CLOSED)
    private val expression = "CASE WHEN area='eu' THEN 'europe' ELSE path_join('region',coalesce(area,'none'),day::VARCHAR) END"
    private fun copy(path: String) = "COPY (SELECT region AS area,day,id FROM sales) TO 'out' (PARTITION_PATH $path,FORMAT parquet,PARTITION_BY (area,day),ORDER_BY (id DESC))"

    @Test
    fun fullPathExpressionsUseExistingNodesAndSurviveCopySerdeAndRegeneration() {
        for (path in listOf(expression,"($expression)","path_join(hive_partition_component('area',area),hive_partition_component('day',day))",
            "'prefix/' || coalesce(area,'none')", "(day)","struct_extract({'x':area},'x')")) {
            val sql = copy(path)
            val tree = assertIs<Copy>(Dialects.DUCKDB.parseOne(sql))
            val parameter = (tree.args["params"] as List<*>).filterIsInstance<CopyParameter>().first()
            assertEquals("PARTITION_PATH",(parameter.thisArg as Expression).name)
            assertNotNull(parameter.expressionArg)
            assertEquals(tree,tree.copy())
            val restored = assertIs<Expression>(Serde.load(Serde.dump(tree)))
            assertEquals(tree,restored)
            assertEquals(tree.sql("duckdb"),restored.sql("duckdb"))
            assertEquals(restored.sql("duckdb"),Dialects.DUCKDB.parseOne(restored.sql("duckdb")).sql("duckdb"))
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
    }

    @Test
    fun pathsBindOnlyToDeclaredPartitionOutputColumnsAndCompleteThatSubset() {
        val sql = copy(expression)
        val scope = RelationScope.analyze(sql,"duckdb",facts)
        assertTrue(scope.parsed)
        assertTrue(scope.diagnostics.isEmpty(),scope.diagnostics.toString())
        assertTrue(scope.references.any { it.name=="area" && it.start>sql.indexOf("PARTITION_PATH") && it.resolution==Resolution.RESOLVED })
        for (path in listOf("id::VARCHAR","unknown")) {
            val bad = RelationScope.analyze(copy(path),"duckdb",facts)
            assertTrue(bad.diagnostics.any { it.problem==ScopeProblem.MISSING_COLUMN },bad.diagnostics.toString())
        }
        val complete = copy("coalesce(, 'none')")
        val caret = complete.indexOf("coalesce(")+"coalesce(".length
        val items = RelationScope.complete(complete,"duckdb",facts,caret).items.map { it.name }
        assertTrue(items.containsAll(listOf("area","day")),items.toString())
        assertFalse("id" in items)
        for (path in listOf("sales.area","__copy_input.area","max(area)","row_number() OVER ()","(SELECT 'x')"))
            assertFailsWith<UnsupportedError>(path) { RelationScope.analyze(copy(path),"duckdb",facts) }
    }

    @Test
    fun pathParametersRetainExactAuthoredRangesAndOrderWithoutPathEvaluation() {
        val sql = "COPY (SELECT region,day,id+? AS id FROM sales WHERE id>?) TO (?) (PARTITION_BY (region,day),PARTITION_PATH path_join(?,coalesce(region,?),day::VARCHAR))"
        val parameters = parseParameterOccurrences(sql,"duckdb")
        assertEquals(sql.indices.filter { sql[it]=='?' },parameters.map { it.range.start })
        assertTrue(parameters.all { sql.substring(it.range.start,it.range.end)=="?" })
        assertEquals(5,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
        val named = copy("path_join(\$prefix,coalesce(area,\$fallback))")
        assertEquals(listOf("prefix","fallback"),parseParameterOccurrences(named,"duckdb").map { it.name })
        val script = copy("'a;b'")+"; SELECT path_join('x','y');"
        val statements = SqlScriptSnapshot.parse(script,"duckdb").statements
        assertEquals(2,statements.count { it.hasSql })
        assertTrue(statements.all { it.boundaryError==null })
    }

    @Test
    fun helperTypesAndDifferentNullContractsDoNotInventQualifiedUdfSemantics() {
        for ((call,nullable) in listOf("hive_partition_component('p',NULL)" to false,"hive_partition_component(NULL,NULL)" to false,
            "hive_partition_component(value:=NULL,name:='p')" to false,"path_join('a','b')" to false,
            "path_join('a',NULL)" to true,"path_join(path:=NULL)" to true,"path_join(NULL)" to true)) {
            val shape = SqlFragment("SELECT $call AS result","duckdb").outputShape().columns.single()
            assertEquals("VARCHAR",shape.type,call)
            assertEquals(nullable,shape.nullable,call)
            val column = RelationScope.analyze("SELECT $call AS result","duckdb",facts).output!!.columns.single()
            assertEquals("VARCHAR",column.sqlType,call)
            assertEquals(if (nullable) Nullability.NULLABLE else Nullability.NON_NULL,column.nullability,call)
        }
        for (call in listOf("custom.path_join('a')","custom.hive_partition_component('p',NULL)","path_join()","hive_partition_component('x')"))
            assertNull(RelationScope.analyze("SELECT $call AS result","duckdb",facts).output!!.columns.single().sqlType,call)
        assertEquals(Nullability.UNKNOWN,RelationScope.analyze("SELECT path_join('p',region) AS result FROM sales","duckdb",facts).output!!.columns.single().nullability)
        assertEquals(Nullability.NON_NULL,RelationScope.analyze("SELECT hive_partition_component('p',region) AS result FROM sales","duckdb",facts).output!!.columns.single().nullability)
    }

    @Test
    fun unsupportedTransformationsRefuseAndCopyCteOutputIsStillComputedCount() {
        for (sql in listOf(copy(expression),"COPY sales TO 'out' (PARTITION_PATH 'x')","SELECT path_join('a','b')","SELECT hive_partition_component('p',NULL)"))
            for (target in listOf("postgres","trino","doris","clickhouse","bigquery","mysql"))
                assertFailsWith<UnsupportedError>("$target: $sql") { SqlFragment(sql,"duckdb").toExecutable(target) }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT path_join('a','b')","postgres").toExecutable("duckdb") }
        assertFailsWith<OptimizeError> { qualify(Dialects.DUCKDB.parseOne(copy(expression)),dialect=Dialects.DUCKDB) }
        val sql = "WITH copied AS (${copy(expression)}) SELECT * FROM copied"
        val output = RelationScope.analyze(sql,"duckdb",facts).output!!.columns.single()
        assertEquals("Count",output.name)
        assertEquals(ColumnOrigin.COMPUTED,output.origin)
        assertNull(output.provenance)
        assertEquals("BIGINT",SqlFragment(sql,"duckdb").outputShape().columns.single().type)
        assertFails { SqlFragment(sql,"duckdb").columnDependencies() }
    }

    @Test
    fun malformedPathOptionsRejectInsteadOfBecomingRawCommandsOrOrderingLists() {
        for (path in listOf("","()","(area,day)","(area DESC)","(area ASC)","(area NULLS FIRST)"))
            assertFailsWith<ParseError>(path) { Dialects.DUCKDB.parseOne(copy(path)) }
        assertFailsWith<ParseError> { Dialects.DUCKDB.parseOne("COPY sales TO 'out' (PARTITION_PATH 'a',PARTITION_PATH 'b')") }
    }
}
