package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.lineage
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class Clickhouse2026AnalysisTest {
    private val columns = listOf("abc", "Abc", "a_one", "ab", "xyz")
    private val facts = RelationFacts(tables = mapOf("t" to RelationFact(columns.map { ColumnFact(it, "UInt32") })), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(mapOf("t" to Shape.of(*columns.map { it to "UInt32" }.toTypedArray())))

    private fun roundTrip(sql: kotlin.String): Expression {
        val ast = parseOne(sql, "clickhouse")
        val snapshot = ast.copy()
        assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
        assertEquals(ast, Serde.load(Serde.dump(ast)), sql)
        assertEquals(snapshot, ast)
        return ast
    }

    @Test
    fun starPatternsExpandNamesWithCaseEscapesShapeAndLineage() {
        for ((projection, names) in listOf(
            "* LIKE 'a%'" to listOf("abc", "a_one", "ab"),
            "t.* ILIKE 'a%'" to listOf("abc", "Abc", "a_one", "ab"),
            "* LIKE 'a\\\\_%'" to listOf("a_one"),
        )) {
            val sql = "SELECT $projection FROM t"
            val ast = roundTrip(sql)
            assertNotNull(ast.find<ClickhouseStar>())
            assertNull(ast.find<Like>())
            val scope = RelationScope.analyze(sql, "clickhouse", facts)
            assertTrue(scope.parsed)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            assertEquals(names, scope.output?.columns?.map { it.name })
            assertEquals(names, SqlFragment(sql, "clickhouse").outputShape(catalog).names())
            val l = lineage(names.first(), sql, schema = mapOf("t" to columns.associateWith { "UInt32" }), dialect = Dialects.CLICKHOUSE)
            assertEquals(listOf("t.${names.first()}"), l.downstream.map { it.name })
        }
        assertNotNull(roundTrip("SELECT x LIKE 'a%' FROM t").find<Like>())
    }

    @Test
    fun patternsRetainIncompleteKnowledgeAndCompletionUsesFilteredOutput() {
        val sql = "SELECT s.abc FROM (SELECT * LIKE 'a%' FROM t) s"
        val scope = RelationScope.analyze(sql, "clickhouse", facts)
        assertTrue(scope.diagnostics.isEmpty())
        val missing = RelationScope.analyze(sql.replace("s.abc", "s.xyz"), "clickhouse", facts)
        assertTrue(missing.diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
        val incomplete = "SELECT s. FROM (SELECT * LIKE 'a%' FROM t) s"
        val completion = RelationScope.complete(incomplete, "clickhouse", facts, incomplete.indexOf("s.") + 2)
        assertEquals(listOf("abc", "a_one", "ab"), completion.items.map { it.name })
        val unknown = RelationScope.analyze("SELECT * LIKE 'a%' FROM other", "clickhouse", RelationFacts())
        assertTrue(unknown.output?.knowledge != Knowledge.FULL)
    }

    @Test
    fun bareBuiltinsDoNotStealQuotedQualifiedColumnsAndHaveNativeTypes() {
        val ast = roundTrip("SELECT LOCALTIME, LOCALTIMESTAMP, SESSION_USER, NOW")
        assertEquals(4, ast.findAll<ClickhouseBuiltin>().count())
        assertEquals(7, ast.findAll<ClickhouseBuiltin>().first().meta["start"])
        val scope = RelationScope.analyze(ast.sql("clickhouse"), "clickhouse", RelationFacts(catalog = CatalogKnowledge.CLOSED))
        assertTrue(scope.references.none { it.kind == ReferenceKind.COLUMN })
        val typed = annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
        assertEquals(listOf(DType.TIME, DType.DATETIME, DType.VARCHAR, DType.DATETIME), typed.expressionsArg.map { (it as Expression).type?.thisArg })
        for (name in listOf("LOCALTIME", "LOCALTIMESTAMP", "SESSION_USER", "NOW")) {
            val identifiers = roundTrip("SELECT `$name`, t.$name FROM t")
            assertEquals(2, identifiers.findAll<Column>().count())
            assertNull(identifiers.find<ClickhouseBuiltin>())
        }
    }

    @Test
    fun datePartsKeepUnitsOutOfColumnScopeAndDoNotGuessTypes() {
        val sql = "SELECT date_part('isodow', t.abc), EXTRACT(dow FROM t.abc), EXTRACT(timezone_hour FROM t.abc) FROM t"
        val ast = roundTrip(sql)
        assertNotNull(ast.find<ClickhouseDatePart>())
        val scope = RelationScope.analyze(sql, "clickhouse", facts)
        assertTrue(scope.diagnostics.isEmpty())
        assertEquals(setOf("abc"), scope.references.filter { it.kind == ReferenceKind.COLUMN }.map { it.name }.toSet())
        assertEquals(DType.UNKNOWN, annotateTypes(ast, dialect = Dialects.CLICKHOUSE).find<ClickhouseDatePart>()?.type?.thisArg)
        assertTrue(ast.findAll<ClickhouseExtract>().all { it.type?.thisArg == DType.UNKNOWN })
    }

    @Test
    fun compositionAndImplicitArgumentsHaveNoFalseTableDependencies() {
        for (sql in listOf(
            "SELECT arrayMap(lower | reverse, ['Hello'])",
            "SELECT arrayMap(_1 * 2 + 1, [1,2,3])",
            "SELECT arrayMap(_ + _, [1], [2])",
            "SELECT arrayMap(_2 - _1, [1], [2])",
            "SELECT arrayMap(arrayMap(_ + 1, _1), [[1,2]])",
            "SELECT arrayMap(arrayMap(x -> x + _1, [1]), [10])",
            "SELECT arrayMap(_1 -> _1 + 1, [1])",
            "SELECT arrayMap((lower) | reverse, ['Hello'])",
        )) {
            val ast = roundTrip(sql)
            assertNull(ast.find<BitwiseOr>())
            val scope = RelationScope.analyze(sql, "clickhouse", RelationFacts(catalog = CatalogKnowledge.CLOSED))
            assertTrue(scope.diagnostics.isEmpty(), "$sql: ${scope.diagnostics}")
            assertTrue(scope.references.none { it.kind == ReferenceKind.COLUMN }, sql)
        }
        val ast = roundTrip("SELECT arrayMap(_1 + t.abc, [1]) v FROM t")
        val scope = RelationScope.analyze(ast.sql("clickhouse"), "clickhouse", facts)
        assertEquals(setOf("abc"), scope.references.filter { it.kind == ReferenceKind.COLUMN }.map { it.name }.toSet())
        assertEquals(listOf("t.abc"), lineage("v", ast.sql("clickhouse"), schema = mapOf("t" to mapOf("abc" to "UInt32")), dialect = Dialects.CLICKHOUSE).downstream.map { it.name })
        val composition = annotateTypes(roundTrip("SELECT arrayMap(lower | reverse, ['Hello'])"), dialect = Dialects.CLICKHOUSE).find<ClickhouseComposition>()!!
        assertEquals(DType.UNKNOWN, composition.type?.thisArg)
    }

    @Test
    fun underscoresOutsideHigherOrderArgumentsStayPhysicalColumns() {
        val ast = roundTrip("SELECT _, _1, t._2, bitOr(a,b) FROM t")
        assertEquals(setOf("_", "_1", "_2", "a", "b"), ast.findAll<Column>().map { it.name }.toSet())
        assertNull(ast.find<ClickhouseImplicitLambda>())
        assertNotNull(roundTrip("SELECT 1 | 2").find<ClickhouseComposition>())
        assertNotNull(roundTrip("SELECT 1 & 2").find<BitwiseAnd>())
        assertNotNull(parseOne("SELECT 1 | 2", "postgres").find<BitwiseOr>())
        for (name in listOf("localtime", "localtimestamp", "session_user", "now", "current_timestamp"))
            assertIs<Column>(parseOne(name, "clickhouse")) // identifier fragments retain their contract
    }

    @Test
    fun nativeAnalysisFormsRefuseUnprovedCrossDialectGeneration() {
        for (sql in listOf("SELECT * LIKE 'a%' FROM t", "SELECT LOCALTIME", "SELECT date_part('dow', d) FROM t",
            "SELECT arrayMap(lower | reverse, ['Hello'])", "SELECT arrayMap(_1 + 1, [1])")) {
            for (target in listOf("duckdb", "trino", "doris")) assertFailsWith<UnsupportedError>(sql) {
                SqlFragment(sql, "clickhouse").toExecutable(target)
            }
        }
    }
}
