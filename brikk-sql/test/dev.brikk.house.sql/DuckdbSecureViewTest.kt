package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbSecureViewTest {
    private val definition = "CREATE SECURE VIEW public_users(id) AS SELECT id FROM private_users WHERE access_level='standard'"
    private val recursive = "CREATE SECURE RECURSIVE VIEW rv(n) AS SELECT 1 UNION ALL SELECT n+1 FROM rv WHERE n<3"

    @Test
    fun nativeModifiersAndNamesSurviveCopySerdeAndRegeneration() {
        val native = ExpressionRegistry.entries.getValue("DuckdbRecursiveViewProperty")
        assertEquals("brikk.duckdb", native.module)
        assertIs<DuckdbRecursiveViewProperty>(native.factory())
        assertTrue(native.factory().argTypes.isEmpty())
        for (sql in listOf(definition, "CREATE OR REPLACE SECURE VIEW ns.v(a,b) AS SELECT 1,2",
            "CREATE SECURE VIEW IF NOT EXISTS v AS SELECT 1", "CREATE TEMP SECURE VIEW v AS SELECT 1",
            "CREATE TEMPORARY SECURE VIEW v AS VALUES(1)", recursive,
            recursive.replace("CREATE SECURE", "CREATE OR REPLACE TEMP SECURE"),
            "CREATE SECURE VIEW \"a.b\"(\"Case\") AS WITH c AS (SELECT 1 AS x) SELECT x FROM c")) {
            val tree = assertIs<Create>(Dialects.DUCKDB.parseOne(sql), sql)
            assertTrue(tree.isSecureViewDefinition())
            assertEquals(1, tree.findAll<SecureProperty>().count())
            for (copy in listOf(tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree, copy)
                for (pretty in listOf(false, true)) {
                    val rendered = copy.sql("duckdb", pretty)
                    val reparsed = assertIs<Create>(Dialects.DUCKDB.parseOne(rendered))
                    assertTrue(reparsed.isSecureViewDefinition())
                    assertEquals(rendered, reparsed.sql("duckdb", pretty))
                    assertEquals(tree.isDuckdbRecursiveViewDefinition(), reparsed.isDuckdbRecursiveViewDefinition())
                }
            }
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
    }

    @Test
    fun invalidSecurePlacementAndNonViewObjectsAreNotAcceptedAsRawCommands() {
        for (sql in listOf("CREATE SECURE TABLE t(i INT)", "CREATE SECURE SCHEMA ns",
            "CREATE VIEW v SECURE AS SELECT 1", "CREATE SECURE TEMP VIEW v AS SELECT 1",
            "CREATE SECURE SECURE VIEW v AS SELECT 1", "CREATE RECURSIVE SECURE VIEW v(n) AS SELECT 1",
            "CREATE \"SECURE\" VIEW v AS SELECT 1", "CREATE SECURE VIEW v", "CREATE SECURE VIEW v AS",
            "CREATE PERSISTENT SECURE VIEW v AS SELECT 1", "CREATE OR REPLACE PERSISTENT SECURE VIEW v AS SELECT 1",
            "CREATE SECURE VIEW v SELECT 1", "CREATE SECURE VIEW v SELECT 1 AS n",
            "CREATE SECURE RECURSIVE VIEW v AS SELECT 1", "CREATE OR ALTER SECURE VIEW v AS SELECT 1")) {
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
        assertTrue(Dialects.DUCKDB.parseOne("CREATE TABLE secure(secure INT)").findAll<SecureProperty>().none())
        assertTrue(Dialects.DUCKDB.parseOne("SELECT secure FROM secure").findAll<SecureProperty>().none())
    }

    @Test
    fun viewBodiesHaveOrdinaryColumnScopesWithoutInventingADdlResultOrCatalogAccessPolicy() {
        val facts = RelationFacts(tables = mapOf("private_users" to RelationFact(listOf(
            ColumnFact("id", "INT"), ColumnFact("access_level", "VARCHAR"),
        ))), catalog = CatalogKnowledge.CLOSED)
        val scope = RelationScope.analyze(definition, "duckdb", facts)
        assertTrue(scope.parsed)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertNull(scope.output)
        assertTrue(scope.references.any { it.columns.any { column -> column.name == "id" && column.sqlType == "INT" } })
        val caretSql = definition.replace("SELECT id", "SELECT <caret>")
        assertEquals(listOf("id", "access_level"), RelationScope.complete(caretSql.replace("<caret>", ""), "duckdb", facts, caretSql.indexOf("<caret>")).items.map { it.name })
        assertFailsWith<ShapeError> { SqlFragment(definition, "duckdb").outputShape() }
        assertFailsWith<SqlglotError> { lineageAll(definition, dialect = Dialects.DUCKDB) }
        assertFailsWith<ShapeError> { SqlFragment(recursive, "duckdb").outputShape() }
        assertFailsWith<ShapeError> { SqlFragment(recursive, "duckdb").sourceTables }
        assertFailsWith<UnsupportedError> { RelationScope.analyze(recursive, "duckdb", facts) }
        // A catalog shape is only a row contract, never permission to inline a stored definition.
        assertTrue(DdlCatalog.fromDdl(definition, "duckdb").tables.isEmpty())
    }

    @Test
    fun supportedDefinitionRewritesKeepSecurityWhileUnmodeledRecursiveRewritesRefuse() {
        val tree = Dialects.DUCKDB.parseOne(definition)
        val schema = mapOf("private_users" to mapOf("id" to "INT", "access_level" to "VARCHAR"))
        val qualified = qualify(tree.copy(), dialect = Dialects.DUCKDB, schema = schema)
        val typed = annotateTypes(qualified, dialect = Dialects.DUCKDB, schema = schema)
        assertTrue(typed.isSecureViewDefinition())
        assertTrue(Dialects.DUCKDB.parseOne(typed.sql("duckdb")).isSecureViewDefinition())
        assertFailsWith<OptimizeError> { qualify(Dialects.DUCKDB.parseOne(recursive), dialect = Dialects.DUCKDB) }
        assertFailsWith<OptimizeError> { annotateTypes(Dialects.DUCKDB.parseOne(recursive), dialect = Dialects.DUCKDB) }
    }

    @Test
    fun unprovedCrossTargetSecurityNeverBecomesExecutableOrdinaryViewSql() {
        for (sql in listOf(definition, recursive)) for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql", "starrocks", "presto", "hive", "spark2", "spark", "bigquery", "datafusion", "sqlglot")) {
            val fragment = SqlFragment(sql, "duckdb")
            assertFailsWith<UnsupportedError> { fragment.toExecutable(target) }
            val preview = fragment.transpileTo(target)
            assertTrue(preview.unsupportedMessages.any { "SECURE VIEW" in it }, target)
        }
        assertFailsWith<UnsupportedError> { SqlFragment("CREATE SECURE VIEW v AS SELECT 1", "sqlglot").toExecutable("duckdb") }
        // Direct unknown-source AST generation must also refuse silently dropping the property.
        val generator = Dialects.forName("postgres").generator().apply { forExecution = true }
        assertFailsWith<UnsupportedError> { generator.generate(Dialects.DUCKDB.parseOne(definition)) }
    }

    @Test
    fun scriptsAndDefinitionReadersKeepTheStoredSecureMarker() {
        val snapshot = SqlScriptSnapshot.parse("$definition; SELECT * FROM public_users;", "duckdb")
        assertEquals(2, snapshot.statements.count { it.hasSql })
        assertTrue(snapshot.statements.all { it.boundaryError == null })
        val reader = assertNotNull(definitionQuery("duckdb", DefinedObject.VIEW, listOf("memory", "main", "public_users")))
        assertTrue("system.main.duckdb_views()" in reader.sql)
        assertEquals(definition, reader.definition(listOf(mapOf("sql" to definition))))
        assertTrue(Dialects.DUCKDB.parseOne(assertNotNull(reader.definition(listOf(mapOf("sql" to definition))))).isSecureViewDefinition())
    }
}
