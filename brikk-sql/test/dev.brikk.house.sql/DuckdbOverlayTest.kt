package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbOverlayTest {
    private val fields = listOf(ColumnFact("s", "VARCHAR", nullability = Nullability.NON_NULL, provenance = "strings.s"),
        ColumnFact("r", "VARCHAR", nullability = Nullability.NON_NULL), ColumnFact("p", "BIGINT", nullability = Nullability.NON_NULL),
        ColumnFact("n", "BIGINT", nullability = Nullability.NULLABLE), ColumnFact("u", "VARCHAR", nullability = Nullability.UNKNOWN))
    private val facts = RelationFacts(tables = mapOf("strings" to RelationFact(fields)), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(mapOf("strings" to Shape(fields.map { ColumnShape(it.name, it.sqlType!!,
        when (it.nullability) { Nullability.NON_NULL -> false; Nullability.NULLABLE -> true; else -> null }) })))

    @Test
    fun keywordAndCommaFormsUseTheExistingNodeAndPreserveCopySerdeAndExpressions() {
        for (call in listOf("overlay(s PLACING r FROM p)", "overlay(s,r,p)", "overlay(s PLACING r FROM p FOR n)",
            "overlay(s,r,p,n)", "overlay(coalesce(s,'') PLACING concat(r,'🦆') FROM p+1 FOR coalesce(n,0))",
            "overlay(s PLACING r FROM p=1 FOR CASE WHEN n IS NULL THEN 0 ELSE n END)")) {
            val tree = Dialects.DUCKDB.parseOne("SELECT $call AS value FROM strings")
            assertIs<Overlay>(assertIs<Alias>(tree.selects.single()).thisArg)
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val qualified = qualify(tree.copy(), dialect = Dialects.DUCKDB, schema = mapOf("strings" to fields.associate { it.name to it.sqlType }))
            val rendered = qualified.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            assertTrue("PLACING" in rendered)
        }
    }

    @Test
    fun quotedOrdinaryCallsRetainNamedArgumentOrderAndBindIntervalsWithoutExpandingTheDefaultLength() {
        val sql = "SELECT \"overlay\"(count:=\$count,start:=\$start,replacement:=\$replacement,string:=\$source) AS value"
        val fragment = SqlFragment(sql, "duckdb")
        val call = assertIs<Anonymous>(assertIs<Alias>(fragment.ast.selects.single()).thisArg)
        assertTrue(assertIs<Identifier>(call.thisArg).quoted)
        val rendered = fragment.toExecutable("duckdb").sql
        assertTrue(rendered.contains("\"overlay\"", ignoreCase = true))
        assertEquals(listOf("count", "start", "replacement", "source"), fragment.scalarParams.map { it.name })
        assertEquals(fragment.scalarParams.map { it.name }, SqlFragment(rendered, "duckdb").scalarParams.map { it.name })
        for (query in listOf("SELECT overlay(? PLACING ? FROM ? FOR ?)", "SELECT overlay(?,?,?)")) {
            val f = SqlFragment(query, "duckdb")
            assertEquals(query.count { it == '?' }, f.toExecutable("duckdb").sql.count { it == '?' })
        }
        val once = SqlFragment("SELECT overlay('abc',nextval('replacement')::VARCHAR,2)", "duckdb").toExecutable("duckdb").sql
        assertEquals(1, Regex("NEXTVAL", RegexOption.IGNORE_CASE).findAll(once).count())
        assertFalse(" FOR " in once)
    }

    @Test
    fun returnTypeIsAlwaysVarcharOnSuccessAndNullabilityUsesEveryPresentArgument() {
        for ((call, nullable) in listOf("overlay(s,r,p)" to false, "overlay(s,r,p,0)" to false,
            "overlay(s,r,p,-1)" to false, "overlay(s,r,p,n)" to true, "overlay(s,r,NULL)" to true,
            "overlay(NULL,r,p)" to true, "overlay(s,NULL,p)" to true, "overlay(s,r,p,NULL)" to true,
            "overlay(u,r,p)" to null, "overlay(NULL,NULL,NULL,NULL)" to true,
            "\"overlay\"(start:=p,replacement:=r,string:=s)" to false,
            "\"overlay\"(count:=NULL,start:=p,replacement:=r,string:=s)" to true)) {
            val sql = "SELECT $call AS value FROM strings"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            val output = assertNotNull(scope.output).columns.single()
            assertEquals("VARCHAR", output.sqlType, call)
            assertEquals(when (nullable) { true -> Nullability.NULLABLE; false -> Nullability.NON_NULL; null -> Nullability.UNKNOWN }, output.nullability, call)
            assertEquals(ColumnOrigin.COMPUTED, output.origin)
            assertNull(output.provenance)
            val shape = SqlFragment(sql, "duckdb").outputShape(catalog).columns.single()
            assertEquals("VARCHAR", shape.type, call)
            assertEquals(nullable, shape.nullable, call)
        }
        val typed = annotateTypes(Dialects.DUCKDB.parseOne("SELECT overlay(NULL,'X',1) AS value"), dialect = Dialects.DUCKDB)
        assertEquals(DType.VARCHAR, assertIs<DataType>(typed.selects.single().type).thisArg)
        assertEquals(mapOf("value" to setOf("strings")), SqlFragment("SELECT overlay(s,r,p,n) AS value FROM strings", "duckdb").columnDependencies(inputs = catalog))
    }

    @Test
    fun malformedSpecialSyntaxIsRejectedButBinderInvalidAritiesArePreservedWithoutTruncation() {
        for (sql in listOf("SELECT overlay('abc','X' FROM 2)", "SELECT overlay('abc' PLACING 'X',2)",
            "SELECT overlay('abc' PLACING 'X' FROM 2,1)", "SELECT overlay('abc' PLACING 'X' FOR 1)",
            "SELECT overlay(string:='abc',replacement:='X',start:=2)", "SELECT overlay()"))
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        for (call in listOf("overlay('abc')", "overlay('abc','X')", "overlay('abc','X',2,1,99)",
            "\"overlay\"(wrong:='abc',replacement:='X',start:=2)")) {
            val sql = "SELECT $call AS value"
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertIs<Anonymous>(assertIs<Alias>(tree.selects.single()).thisArg)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertEquals(tree.sql("duckdb"), Dialects.DUCKDB.parseOne(tree.sql("duckdb")).sql("duckdb"))
            assertEquals("UNKNOWN", SqlFragment(sql, "duckdb").outputShape().columns.single().type)
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
        }
    }

    @Test
    fun sameNameDoesNotCertifyCrossTargetSemanticsAndQualifiedUdfsStayUnknown() {
        for (call in listOf("overlay(s PLACING r FROM p FOR n)", "overlay(s,r,p)", "\"overlay\"(s,r,p)"))
            for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql", "bigquery")) {
                assertFailsWith<UnsupportedError> { SqlFragment("SELECT $call FROM strings", "duckdb").toExecutable(target) }
                assertTrue(SqlFragment("SELECT $call FROM strings", "duckdb").transpileTo(target).unsupportedMessages.any { "OVERLAY" in it })
            }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT overlay('abc' PLACING 'X' FROM 2)", "postgres").toExecutable("duckdb") }
        assertTrue(SqlFragment("SELECT overlay('abc' PLACING 'X' FROM 2)", "postgres").toExecutable("postgres").unsupportedMessages.isEmpty())
        val scope = RelationScope.analyze("SELECT custom.overlay(s,r,p) AS value FROM strings", "duckdb", facts)
        assertNull(scope.output!!.columns.single().sqlType)
        assertEquals(Nullability.UNKNOWN, scope.output.columns.single().nullability)
        assertFalse("PLACING" in Dialects.DUCKDB.parseOne("SELECT custom.overlay(s,r,p) FROM strings").sql("duckdb"))
    }

    @Test
    fun columnCompletionDependenciesAndScriptBoundariesRemainOrdinaryExpressionFeatures() {
        val sql = "SELECT overlay(s PLACING  FROM p) FROM strings"
        val caret = sql.indexOf(" FROM p")
        assertTrue(RelationScope.complete(sql, "duckdb", facts, caret).items.map { it.name }.containsAll(listOf("s", "r", "p", "n")))
        val script = "SELECT overlay('a;b','X',1); SELECT overlay('xyz' PLACING 'Y' FROM 2);"
        val ranges = SqlScriptSnapshot.parse(script, "duckdb").statements
        assertEquals(2, ranges.count { it.hasSql })
        assertTrue(ranges.all { it.boundaryError == null })
    }
}
