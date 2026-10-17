package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.optimizer.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComprehensionQualificationCatchupTest {
    private val schema = mapOf("t" to mapOf("items" to "ARRAY<INT>", "x" to "INT", "i" to "INT", "threshold" to "INT"))

    @Test
    fun declarationsParseAsIdentifiersRatherThanSourceColumns() {
        for (source in listOf("SELECT [x FOR x IN items] FROM t", "SELECT [x + i FOR x, i IN items IF i > 1] FROM t")) {
            val tree = Dialects.DUCKDB.parseOne(source)
            val comprehension = tree.findAll<Comprehension>().single()
            assertIs<Identifier>(comprehension.expressionArg)
            if (source.contains("x, i")) assertIs<Identifier>(comprehension.args["position"])
            else assertEquals(null, comprehension.args["position"])
            assertEquals(source, tree.sql("duckdb"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }

    @Test
    fun allInferencePoliciesBindBodiesAndFiltersLocallyButQualifyTheIteratorAndFreeColumns() {
        val source = "SELECT [x + threshold FOR x, i IN items IF x > 0 AND i > 1] AS result FROM t"
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB, schema = schema,
                inferSchema = policy, quoteIdentifiers = false)
            assertEquals("SELECT [x + t.threshold FOR x, i IN t.items IF x > 0 AND i > 1] AS result FROM t AS t",
                tree.sql("duckdb"), "policy=$policy")
            val scope = assertNotNull(buildScope(tree))
            assertEquals(setOf("items", "threshold"), scope.columns.map { it.name }.toSet())
            assertTrue(scope.columns.all { it.table == "t" })
            validateQualifyColumns(tree)
        }
        val noParameter = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB, schema = schema,
            quoteIdentifiers = false)
        assertFalse(noParameter.findAll<Column>().any { it.name in setOf("x", "i") })
    }

    @Test
    fun aliasesOutsideTheBodyAndNestedShadowingDoNotCaptureTheLocalVariable() {
        for ((source, expected) in listOf(
            "SELECT [1, 2] AS x, [x FOR x IN x] AS result" to
                "SELECT [1, 2] AS x, [x FOR x IN [1, 2]] AS result",
            "SELECT 99 AS x, [x FOR x IN items] AS result FROM t" to
                "SELECT 99 AS x, [x FOR x IN t.items] AS result FROM t AS t",
            "SELECT [[x FOR x IN x] FOR x IN items] AS result FROM t" to
                "SELECT [[x FOR x IN x] FOR x IN t.items] AS result FROM t AS t",
            "SELECT [[x + y FOR x IN [1, 2]] FOR y IN items] AS result FROM t" to
                "SELECT [[x + y FOR x IN [1, 2]] FOR y IN t.items] AS result FROM t AS t",
        )) {
            val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB, schema = schema,
                inferSchema = false, quoteIdentifiers = false)
            assertEquals(expected, tree.sql("duckdb"))
        }
    }

    @Test
    fun structFieldLocalsAndProvenTableFieldsKeepTheirDistinctAstKinds() {
        for ((fields, tableField) in listOf(
            mapOf("items" to "ARRAY<STRUCT<field INT>>") to false,
            mapOf("items" to "ARRAY<STRUCT<field INT>>", "field" to "INT") to true,
        )) {
            val tree = qualify(Dialects.DUCKDB.parseOne("SELECT [x.field FOR x IN items] AS result FROM t AS x"),
                dialect = Dialects.DUCKDB, schema = mapOf("t" to fields), inferSchema = false, quoteIdentifiers = false)
            val body = tree.findAll<Comprehension>().single().thisArg
            if (tableField) assertIs<Column>(body) else assertIs<Dot>(body)
            assertEquals("SELECT [x.field FOR x IN x.items] AS result FROM t AS x", tree.sql("duckdb"))
        }
    }

    @Test
    fun queryScopesAreNotRewrittenAsComprehensionLocalBodies() {
        val source = "SELECT [(SELECT x FROM q) + x FOR x IN items] AS result FROM t"
        val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
            schema = schema + mapOf("q" to mapOf("x" to "INT")), inferSchema = false, quoteIdentifiers = false)
        val scopes = traverseScope(tree)
        assertEquals(2, scopes.size)
        assertTrue(scopes.first().comprehensions.isEmpty())
        assertEquals(1, scopes.last().comprehensions.size)
        val inner = tree.findAll<Subquery>().single()
        assertEquals("q", inner.findAll<Column>().single().table)
        assertTrue(tree.sql("duckdb").contains("(SELECT q.x AS x FROM q AS q) + x"))
    }

    @Test
    fun scopeCachesAndLocalSourcePositionsSurviveTheRewrite() {
        val source = "SELECT [x.field FOR x IN items IF x.field > 0] AS result FROM t"
        val tree = Dialects.DUCKDB.parseOne(source)
        val before = assertNotNull(buildScope(tree))
        assertEquals(1, before.comprehensions.size)
        assertTrue(before.columns.any { it.table == "x" })
        before.clearCache()
        assertEquals(1, before.comprehensions.size)
        val column = tree.findAll<Column>().first { it.table == "x" }
        column.updatePositions(column.parts.first())
        val originalPos = assertNotNull(SourceMap.sourcePosOf(column))
        column.addComments(listOf("local field"))
        val qualified = qualify(tree, dialect = Dialects.DUCKDB,
            schema = mapOf("t" to mapOf("items" to "ARRAY<STRUCT<field INT>>")), inferSchema = false,
            quoteIdentifiers = false)
        val scope = assertNotNull(buildScope(qualified))
        assertEquals(listOf("items"), scope.columns.map { it.name })
        val local = qualified.findAll<Comprehension>().single().thisArg as Dot
        assertEquals(originalPos, SourceMap.sourcePosOf(local))
        assertTrue("local field" in local.comments.orEmpty())
        val generator = Dialects.DUCKDB.generator()
        generator.trackSpans = true
        val output = generator.generate(qualified)
        assertEquals(originalPos.start, assertNotNull(generator.lastSourceMap).sourcePosition(output.indexOf("x.field"))?.start)
    }

    @Test
    fun quotedDeclarationsAndWholeRowAliasesDoNotBecomeInventedColumnOwners() {
        val quoted = qualify(Dialects.DUCKDB.parseOne("SELECT [\"odd var\" FOR \"odd var\" IN [1, 2]] AS result"),
            dialect = Dialects.DUCKDB, inferSchema = false)
        assertTrue(quoted.sql("duckdb").contains("FOR \"odd var\" IN"))
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.DUCKDB.parseOne("SELECT t FROM t"), dialect = Dialects.DUCKDB,
                inferSchema = policy, quoteIdentifiers = false)
            assertEquals("SELECT t AS t FROM t AS t", tree.sql("duckdb"), "policy=$policy")
            assertIs<TableColumn>((tree as Select).expressionsArg.single().let { (it as Alias).thisArg })
        }
        val realColumn = qualify(Dialects.DUCKDB.parseOne("SELECT t FROM t"), dialect = Dialects.DUCKDB,
            schema = mapOf("t" to mapOf("t" to "INT")), inferSchema = null, quoteIdentifiers = false)
        assertEquals("SELECT t.t AS t FROM t AS t", realColumn.sql("duckdb"))
        assertIs<Column>((realColumn as Select).expressionsArg.single().let { (it as Alias).thisArg })
    }

    @Test
    fun oldSerializedColumnDeclarationsStillBindLocallyAndDiscardUnprovedSourceColumnTypes() {
        val tree = Dialects.DUCKDB.parseOne("SELECT [x FOR x IN items] AS result FROM t")
        val comprehension = tree.findAll<Comprehension>().single()
        comprehension.set("expression", column("x"))
        val body = comprehension.thisArg as Column
        body.typeSlot = DataType.build(DType.TEXT) // Not evidence about the list element's type.
        val loaded = Serde.loadExpression(Serde.dump(tree))
        val qualified = qualify(loaded, dialect = Dialects.DUCKDB, schema = schema,
            inferSchema = false, quoteIdentifiers = false)
        val rewritten = qualified.findAll<Comprehension>().single()
        assertIs<Identifier>(rewritten.expressionArg)
        assertIs<Identifier>(rewritten.thisArg)
        assertEquals(null, (rewritten.thisArg as Expression).type)
        assertEquals("SELECT [x FOR x IN t.items] AS result FROM t AS t", qualified.sql("duckdb"))
    }
}
