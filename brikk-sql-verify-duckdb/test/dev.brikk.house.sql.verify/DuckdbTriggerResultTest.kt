package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbTriggerResultTest {
    private fun render(sql: String, generated: Boolean) = if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql
    private fun Connection.execute(sql: String, generated: Boolean = false) = createStatement().use { s ->
        s.queryTimeout = 5
        if (s.execute(render(sql, generated))) s.resultSet.use { while (it.next()) { /* drain once */ } }
    }
    private fun Connection.rows(sql: String, generated: Boolean = false): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(render(sql, generated)).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun modes(block: (Connection, Boolean) -> Unit) {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { block(it, generated) }
    }

    @Test
    fun documentedUpdateTransitionTablesReadOldAndNewImagesAndReturningReportsTheOuterRows() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT,v INT)", generated)
        c.execute("CREATE TABLE audit(id INT,old_v INT,new_v INT)", generated)
        c.execute("CREATE TRIGGER tr AFTER UPDATE ON target REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT INSERT INTO audit SELECT n.id,o.v,n.v FROM o JOIN n USING(id)", generated)
        c.execute("INSERT INTO target VALUES(1,10),(2,20)", generated)
        assertEquals(listOf(listOf("1", "100"), listOf("2", "200")), c.rows("UPDATE target SET v=v*10 RETURNING id,v", generated).sortedBy { it[0] })
        assertEquals(listOf(listOf("1", "10", "100"), listOf("2", "20", "200")), c.rows("SELECT * FROM audit ORDER BY id"))
        c.execute("CREATE TABLE generated_target(a INT,g INT GENERATED ALWAYS AS(a+1),b INT)", generated)
        c.execute("CREATE TABLE generated_log(n INT)", generated)
        c.execute("CREATE TRIGGER generated_tr AFTER INSERT ON generated_target FOR EACH STATEMENT INSERT INTO generated_log VALUES(1)", generated)
        assertEquals(listOf(listOf("5", "6", "7")), c.rows("INSERT INTO generated_target(a,b) VALUES(5,7) RETURNING *", generated))
        assertEquals(listOf(listOf("1")), c.rows("SELECT count(*) FROM generated_log"))
    }

    @Test
    fun beforeAndAfterStatementsObserveDifferentStatesAndFireForEmptyInput() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT)", generated)
        c.execute("CREATE TABLE audit(phase VARCHAR,n BIGINT)", generated)
        c.execute("CREATE TRIGGER bef BEFORE INSERT ON target FOR EACH STATEMENT INSERT INTO audit SELECT 'before',count(*) FROM target", generated)
        c.execute("CREATE TRIGGER aft AFTER INSERT ON target FOR EACH STATEMENT INSERT INTO audit SELECT 'after',count(*) FROM target", generated)
        c.execute("INSERT INTO target VALUES(1),(2)", generated)
        assertEquals(listOf(listOf("after", "2"), listOf("before", "0")), c.rows("SELECT * FROM audit ORDER BY phase"))
        c.execute("DELETE FROM audit")
        c.execute("INSERT INTO target SELECT 3 WHERE false", generated)
        assertEquals(listOf(listOf("after", "2"), listOf("before", "2")), c.rows("SELECT * FROM audit ORDER BY phase"))
    }

    @Test
    fun afterRowInsertAndDeleteFirePerRowWithoutDeduplicatingIdenticalImages() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT,v INT)", generated)
        c.execute("CREATE TABLE audit(phase VARCHAR,id INT,v INT)", generated)
        c.execute("CREATE TRIGGER ins AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES('new',NEW.id,NEW.v)", generated)
        c.execute("CREATE TRIGGER del AFTER DELETE ON target FOR EACH ROW INSERT INTO audit VALUES('old',OLD.id,OLD.v)", generated)
        c.execute("INSERT INTO target VALUES(1,10),(1,10),(2,20)", generated)
        assertEquals(listOf(listOf("3")), c.rows("SELECT count(*) FROM audit WHERE phase='new'"))
        c.execute("DELETE FROM target WHERE id=1", generated)
        assertEquals(listOf(listOf("old", "1", "10"), listOf("old", "1", "10")), c.rows("SELECT * FROM audit WHERE phase='old'"))
        c.execute("CREATE TABLE keyword_target(id INT,\"begin\" INT)", generated)
        c.execute("CREATE TRIGGER keyword_row AFTER INSERT ON keyword_target FOR EACH ROW INSERT INTO audit SELECT 'keyword',NEW.id,NEW.begin", generated)
        c.execute("INSERT INTO keyword_target VALUES(7,70)", generated)
        assertEquals(listOf(listOf("keyword", "7", "70")), c.rows("SELECT * FROM audit WHERE phase='keyword'"))
    }

    @Test
    fun updateOfUsesAssignedColumnsAndMultipleStatementTriggersKeepCreationOrder() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT,v INT)", generated)
        c.execute("INSERT INTO target VALUES(1,10)", generated)
        c.execute("CREATE TABLE audit(seq BIGINT,label VARCHAR)", generated)
        c.execute("CREATE SEQUENCE order_seq", generated)
        c.execute("CREATE TRIGGER first_tr AFTER UPDATE OF v ON target FOR EACH STATEMENT INSERT INTO audit VALUES(nextval('order_seq'),'first')", generated)
        c.execute("CREATE TRIGGER second_tr AFTER UPDATE OF v ON target FOR EACH STATEMENT INSERT INTO audit VALUES(nextval('order_seq'),'second')", generated)
        c.execute("UPDATE target SET id=id+1", generated)
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM audit"))
        c.execute("UPDATE target SET v=v", generated)
        assertEquals(listOf(listOf("1", "first"), listOf("2", "second")), c.rows("SELECT * FROM audit ORDER BY seq"))
    }

    @Test
    fun mergeIsSupportedAsAnInlineActionButNotAsTheOuterWriteToATriggeredTable() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT,v INT)", generated)
        c.execute("CREATE TABLE summary(id INT,v INT)", generated)
        c.execute("CREATE TRIGGER sync AFTER INSERT ON target REFERENCING NEW TABLE AS n FOR EACH STATEMENT MERGE INTO summary s USING n ON s.id=n.id WHEN MATCHED THEN UPDATE SET v=n.v WHEN NOT MATCHED THEN INSERT VALUES(n.id,n.v)", generated)
        c.execute("INSERT INTO target VALUES(1,10),(2,20)", generated)
        c.execute("INSERT INTO target VALUES(1,99),(3,30)", generated)
        assertEquals(listOf(listOf("1", "99"), listOf("2", "20"), listOf("3", "30")), c.rows("SELECT * FROM summary ORDER BY id"))
        assertFailsWith<SQLException> { c.execute("MERGE INTO target t USING (SELECT 1 AS id,100 AS v) s ON t.id=s.id WHEN MATCHED THEN UPDATE SET v=s.v", generated) }
        assertEquals(listOf(listOf("4")), c.rows("SELECT count(*) FROM target"))
        c.execute("CREATE TABLE self_target(id INT,v INT)", generated)
        c.execute("CREATE TRIGGER self_tr AFTER INSERT ON self_target FOR EACH STATEMENT MERGE INTO self_target t USING (SELECT 1 AS id,99 AS v) s ON t.id=s.id WHEN MATCHED THEN UPDATE SET v=s.v", generated)
        c.execute("INSERT INTO self_target VALUES(1,10)", generated)
        assertEquals(listOf(listOf("1", "99")), c.rows("SELECT * FROM self_target"))
    }

    @Test
    fun triggerFailureAndCallerRollbackUndoBaseAndAuditTableChanges() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT)", generated)
        c.execute("CREATE TABLE audit(id INT CHECK(id<3))", generated)
        c.execute("CREATE TRIGGER tr AFTER INSERT ON target REFERENCING NEW TABLE AS n FOR EACH STATEMENT INSERT INTO audit SELECT id FROM n", generated)
        assertFailsWith<SQLException> { c.execute("INSERT INTO target VALUES(1),(3)", generated) }
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM target"))
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM audit"))
        c.execute("BEGIN TRANSACTION")
        c.execute("INSERT INTO target VALUES(1)", generated)
        assertEquals(listOf(listOf("1")), c.rows("SELECT count(*) FROM audit"))
        c.execute("ROLLBACK")
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM target"))
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM audit"))
    }

    @Test
    fun replaceExistsQualifiedDropAndDependenciesFollowTheNativeCatalog() = modes { c, generated ->
        c.execute("CREATE SCHEMA ns", generated)
        c.execute("CREATE TABLE ns.target(id INT)", generated)
        c.execute("CREATE TABLE audit(id INT)", generated)
        c.execute("CREATE TRIGGER tr AFTER INSERT ON ns.target INSERT INTO audit VALUES(1)", generated)
        c.execute("CREATE TRIGGER IF NOT EXISTS tr AFTER INSERT ON ns.target INSERT INTO audit VALUES(2)", generated)
        c.execute("INSERT INTO ns.target VALUES(1)", generated)
        assertEquals(listOf(listOf("1")), c.rows("SELECT id FROM audit"))
        c.execute("CREATE OR REPLACE TRIGGER tr AFTER INSERT ON ns.target INSERT INTO audit VALUES(3)", generated)
        assertFailsWith<SQLException> { c.execute("DROP TABLE audit RESTRICT", generated) }
        c.execute("INSERT INTO ns.target VALUES(2)", generated)
        assertEquals(listOf(listOf("1"), listOf("3")), c.rows("SELECT id FROM audit ORDER BY id"))
        c.execute("DROP TRIGGER tr ON ns.target", generated)
        c.execute("DROP TRIGGER IF EXISTS tr ON ns.target", generated)
        assertFailsWith<SQLException> { c.execute("DROP TRIGGER IF EXISTS tr ON ns.missing", generated) }
        c.execute("CREATE TRIGGER tr AFTER INSERT ON ns.target INSERT INTO audit VALUES(4)", generated)
        c.execute("DROP TABLE audit CASCADE", generated)
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM duckdb_triggers() WHERE trigger_name='tr'"))
        c.execute("CREATE TABLE audit(id INT)")
        c.execute("CREATE TRIGGER tr AFTER INSERT ON ns.target INSERT INTO audit VALUES(5)", generated)
        c.execute("DROP TABLE ns.target", generated)
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM duckdb_triggers() WHERE trigger_name='tr'"))
    }

    @Test
    fun unsupportedModesAndTransitionCombinationsFailBeforeRegistrationOrWrites() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE target(id INT,v INT)")
            c.execute("CREATE TABLE audit(id INT,v INT)")
            val definitions = listOf(
                "CREATE TRIGGER tr BEFORE INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
                "CREATE TRIGGER tr AFTER UPDATE ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)",
                "CREATE TEMP TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(1,2)",
                "CREATE TRIGGER tr INSTEAD OF INSERT ON target INSERT INTO audit VALUES(1,2)",
                "CREATE TRIGGER tr BEFORE INSERT ON target REFERENCING NEW TABLE AS n INSERT INTO audit SELECT * FROM n",
                "CREATE TRIGGER tr AFTER INSERT ON target REFERENCING OLD TABLE AS o INSERT INTO audit SELECT * FROM o",
                "CREATE TRIGGER tr AFTER DELETE ON target REFERENCING NEW TABLE AS n INSERT INTO audit SELECT * FROM n",
                "CREATE TRIGGER tr AFTER UPDATE OF v ON target REFERENCING NEW TABLE AS n INSERT INTO audit SELECT * FROM n",
                "CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(1,2)",
                "CREATE TRIGGER tr AFTER INSERT ON target FOR EACH ROW UPDATE audit SET v=NEW.v",
            )
            for (sql in definitions) for (generated in listOf(false, true)) {
                val error = assertFailsWith<SQLException>("trigger mode") { c.execute(sql, generated) }
                if (sql.startsWith("CREATE TEMP TRIGGER")) {
                    // The native PEG transformer rejects TEMP before its binder sees it.
                    assertTrue("Temporary triggers are not supported" in error.message.orEmpty())
                } else assertFalse("Parser Error" in error.message.orEmpty(), "Expected native binder/runtime restriction: $sql")
                assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM duckdb_triggers()"))
            }
            c.execute("CREATE VIEW target_view AS SELECT * FROM target")
            assertFailsWith<SQLException> { c.execute("CREATE TRIGGER tr AFTER INSERT ON target_view INSERT INTO audit VALUES(1,2)") }
            c.execute("CREATE TABLE gen(id INT,v INT GENERATED ALWAYS AS (id+1))")
            assertFailsWith<SQLException> { c.execute("CREATE TRIGGER tr AFTER INSERT ON gen FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)") }
            c.execute("ATTACH ':memory:' AS extra")
            c.execute("CREATE TABLE extra.other_audit(id INT,v INT)")
            val crossCatalog = assertFailsWith<SQLException> { c.execute("CREATE TRIGGER tr AFTER INSERT ON target INSERT INTO extra.other_audit VALUES(1,2)") }
            assertTrue("different catalog" in crossCatalog.message.orEmpty())
        }
    }

    @Test
    fun rowReturningMixedGranularityAndUpdateUpsertHaveSeparateRuntimeLimits() = modes { c, generated ->
        c.execute("CREATE TABLE target(id INT PRIMARY KEY,v INT)", generated)
        c.execute("CREATE TABLE audit(id INT,v INT)", generated)
        c.execute("CREATE TRIGGER row_tr AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)", generated)
        assertFailsWith<SQLException> { c.rows("INSERT INTO target VALUES(1,10) RETURNING id", generated) }
        assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM target"))
        assertFailsWith<SQLException> { c.execute("CREATE TRIGGER stmt_tr AFTER INSERT ON target FOR EACH STATEMENT INSERT INTO audit VALUES(0,0)", generated) }
        c.execute("DROP TRIGGER row_tr ON target", generated)
        c.execute("CREATE TRIGGER upd AFTER UPDATE ON target INSERT INTO audit VALUES(0,0)", generated)
        c.execute("INSERT INTO target VALUES(1,10)", generated)
        assertFailsWith<SQLException> { c.execute("INSERT INTO target VALUES(1,20) ON CONFLICT(id) DO UPDATE SET v=excluded.v", generated) }
        assertEquals(listOf(listOf("1", "10")), c.rows("SELECT * FROM target"))
        c.execute("CREATE TABLE audit2(id INT,v INT)", generated)
        c.execute("CREATE TRIGGER audit_row AFTER INSERT ON audit FOR EACH ROW INSERT INTO audit2 VALUES(NEW.id,NEW.v)", generated)
        val cascading = assertFailsWith<SQLException> { c.execute("CREATE TRIGGER cascade_row AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id,NEW.v)", generated) }
        assertTrue(cascading.message.orEmpty().contains("cascading", ignoreCase = true))
    }

    @Test
    fun triggerDefinitionAnalysisAndParseOnlyVerificationNeverRegisterOrRunTheAction() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            user.execute("CREATE TABLE target(id INT)")
            user.execute("CREATE TABLE audit(id BIGINT)")
            user.execute("CREATE SEQUENCE trigger_probe START 1")
            val sql = "CREATE TRIGGER tr AFTER INSERT ON target INSERT INTO audit VALUES(nextval('trigger_probe'))"
            Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            RelationScope.analyze(sql, "duckdb", RelationFacts())
            val result = ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(user.duplicate()) }).verify(sql)
            assertIs<VerifyResult.Unverified>(result)
            assertEquals(listOf(listOf("0")), user.rows("SELECT count(*) FROM duckdb_triggers()"))
            assertEquals(listOf(listOf("0")), user.rows("SELECT count(*) FROM audit"))
            assertEquals(listOf(listOf("1")), user.rows("SELECT nextval('trigger_probe')"))
        }
    }
}
