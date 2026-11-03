package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.optimizer.qualifyColumns
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbTriggerTest {
    private val row = RelationFact(listOf(ColumnFact("id", "INT", nullability = Nullability.NON_NULL), ColumnFact("v", "INT", nullability = Nullability.NON_NULL)))
    private val facts = RelationFacts(tables = mapOf("target" to row, "audit" to row,
        "log" to RelationFact(listOf(ColumnFact("id", "INT"), ColumnFact("old_v", "INT"), ColumnFact("new_v", "INT")))), catalog = CatalogKnowledge.CLOSED)
    private val transition = "CREATE TRIGGER tr AFTER UPDATE ON target REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT INSERT INTO log SELECT n.id,o.v,n.v FROM o JOIN n USING(id)"

    @Test
    fun nativeDefinitionsBodiesAndQualifiedDropsRemainStructuredThroughSerde() {
        for (sql in listOf(transition, transition.replace("OLD TABLE AS o NEW TABLE AS n", "NEW TABLE AS n OLD TABLE AS o"),
            "CREATE OR REPLACE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
            "CREATE TRIGGER IF NOT EXISTS tr BEFORE UPDATE OF v ON target FOR EACH STATEMENT UPDATE audit SET v=1",
            "CREATE TRIGGER tr AFTER DELETE ON target FOR EACH ROW DELETE FROM audit WHERE id=OLD.id",
            "CREATE TRIGGER tr AFTER INSERT ON target REFERENCING NEW TABLE AS n FOR EACH STATEMENT MERGE INTO audit a USING n ON a.id=n.id WHEN MATCHED THEN UPDATE SET v=n.v WHEN NOT MATCHED THEN INSERT VALUES(n.id,n.v)",
            "DROP TRIGGER IF EXISTS tr ON catalog.schema.target")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertTrue(tree is Create || tree is Drop, sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val rendered = tree.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
        }
        assertEquals(setOf("target", "log"), SqlFragment(transition, "duckdb").sourceTables.toSet())
        assertEquals(listOf("catalog.schema.target"), SqlFragment("DROP TRIGGER tr ON catalog.schema.target", "duckdb").sourceTables)
    }

    @Test
    fun imagesAndTransitionTablesHaveTargetTypesButNoInventedValueProvenanceOrDdlResult() {
        for (sql in listOf(transition, "CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
            "CREATE TRIGGER tr AFTER INSERT ON target REFERENCING NEW TABLE AS n FOR EACH STATEMENT MERGE INTO audit a USING n ON a.id=n.id WHEN MATCHED THEN UPDATE SET v=n.v WHEN NOT MATCHED THEN INSERT VALUES(n.id,n.v)",
            "CREATE TRIGGER tr AFTER DELETE ON target FOR EACH ROW INSERT INTO audit VALUES(OLD.id,OLD.v)")) {
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            assertNull(scope.output)
            val images = scope.references.flatMap { it.columns }.filter { it.relation in setOf("n", "o", "NEW", "OLD") }
            assertTrue(images.isNotEmpty(), sql)
            assertTrue(images.all { it.sqlType == "INT" && it.origin == ColumnOrigin.COMPUTED && it.provenance == null && it.source == null })
            assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
        }
        val unavailable = "CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(OLD.id,OLD.v)"
        assertTrue(RelationScope.analyze(unavailable, "duckdb", facts).diagnostics.any { it.problem == ScopeProblem.UNKNOWN_QUALIFIER })
        val unknown = transition.replace("n.v", "n.missing")
        assertTrue(RelationScope.analyze(unknown, "duckdb", facts).diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }

    @Test
    fun completionUsesTheTargetAndTransitionFramesWithoutLeakingAliasesOutsideTheBody() {
        fun names(sql: String): List<String> {
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertEquals(listOf("id", "v"), names("CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.<caret>,1)"))
        assertEquals(listOf("id", "v"), names(transition.replace("n.v FROM", "n.<caret> FROM")))
        assertEquals(listOf("id", "v"), names("CREATE TRIGGER tr AFTER UPDATE OF <caret> ON target INSERT INTO audit VALUES(1,1)"))
        assertTrue(RelationScope.analyze("$transition; SELECT * FROM n", "duckdb", facts).diagnostics.any { it.problem == ScopeProblem.UNKNOWN_TABLE })
    }

    @Test
    fun supportedInlineBodiesHaveScriptBoundariesButCompoundBodiesStayOpaque() {
        val script = "CREATE TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(1,CASE WHEN true THEN 1 ELSE 2 END); INSERT INTO target VALUES(1,2); DROP TRIGGER tr ON target;"
        val snapshot = SqlScriptSnapshot.parse(script, "duckdb")
        assertEquals(3, snapshot.statements.count { it.hasSql })
        assertTrue(snapshot.statements.all { it.boundaryError == null })
        val keywordField = SqlScriptSnapshot.parse("CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit SELECT NEW.id,NEW.begin; SELECT 1;", "duckdb")
        assertEquals(2, keywordField.statements.count { it.hasSql })
        assertTrue(keywordField.statements.all { it.boundaryError == null })
        val compound = SqlScriptSnapshot.parse("CREATE TRIGGER tr AFTER INSERT ON target BEGIN INSERT INTO audit VALUES(1,2); END; SELECT 1;", "duckdb")
        assertTrue(compound.statements.any { it.boundaryError != null })
        assertEquals(1, compound.statements.count { it.hasSql })
    }

    @Test
    fun anotherDialectsTriggerGrammarIsNotSilentlyAcceptedAsDuckdb() {
        for (sql in listOf("CREATE TRIGGER tr AFTER INSERT OR DELETE ON target INSERT INTO audit VALUES(1,2)",
            "CREATE TRIGGER tr AFTER TRUNCATE ON target INSERT INTO audit VALUES(1,2)",
            "CREATE TRIGGER tr AFTER INSERT ON target REFERENCING NEW TABLE n INSERT INTO audit SELECT * FROM n",
            "CREATE TRIGGER tr AFTER INSERT ON target FOR EACH INSERT INTO audit VALUES(1,2)",
            "CREATE TRIGGER tr AFTER INSERT ON target WHEN(true) INSERT INTO audit VALUES(1,2)",
            "CREATE TRIGGER tr AFTER INSERT ON target EXECUTE FUNCTION f()", "CREATE TRIGGER ns.tr AFTER INSERT ON target INSERT INTO audit VALUES(1,2)",
            "CREATE OR ALTER TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(1,2)", "DROP TRIGGER tr")) {
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
    }

    @Test
    fun knownUnsupportedModesAndUnprovedTriggerTranslationsRefuseExecutionOrOptimization() {
        for (sql in listOf("CREATE TRIGGER tr BEFORE INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
            "CREATE TRIGGER tr AFTER UPDATE ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
            "CREATE TRIGGER tr INSTEAD OF INSERT ON target INSERT INTO audit VALUES(1,2)",
            "CREATE TEMP TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(1,2)",
            "CREATE TRIGGER tr BEFORE INSERT ON target REFERENCING NEW TABLE AS n INSERT INTO audit SELECT * FROM n",
            "CREATE TRIGGER tr AFTER UPDATE OF v ON target REFERENCING NEW TABLE AS n INSERT INTO audit SELECT * FROM n")) {
            assertFailsWith<UnsupportedError>(sql) { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
        }
        val tree = Dialects.DUCKDB.parseOne(transition)
        val before = Serde.dump(tree)
        assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
        assertFailsWith<OptimizeError> { qualifyColumns(tree, schema = emptyMap<String,String>(), dialect = Dialects.DUCKDB) }
        assertFailsWith<OptimizeError> { annotateTypes(tree, dialect = Dialects.DUCKDB) }
        assertEquals(before, Serde.dump(tree))
        assertFailsWith<SqlglotError> { lineageAll(transition, dialect = Dialects.DUCKDB) }
        for (sql in listOf(transition, "DROP TRIGGER tr ON target")) for (target in listOf("postgres", "trino", "doris", "clickhouse")) {
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
            assertTrue(SqlFragment(sql, "duckdb").transpileTo(target).unsupportedMessages.any { "trigger" in it.lowercase() })
        }
    }
}
