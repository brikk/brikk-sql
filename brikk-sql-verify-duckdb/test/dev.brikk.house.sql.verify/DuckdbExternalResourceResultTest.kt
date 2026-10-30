package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import org.duckdb.DuckDBConnection
import kotlin.test.*

/** SQL-only recipes over an owned local file and sequences. No provider extension, remote service or real resource. */
internal class DuckdbExternalResourceResultTest {
    private fun render(sql: String, generated: Boolean) = if (generated)
        assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb") else sql
    private fun Connection.execute(sql: String, generated: Boolean = false) = createStatement().use { s ->
        s.queryTimeout = 5
        if (s.execute(render(sql, generated))) s.resultSet.use { while (it.next()) { /* consume once, never retry */ } }
    }
    private fun Connection.rows(sql: String, generated: Boolean = false): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(render(sql, generated)).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.scalar(sql: String) = rows(sql).single().single()!!
    private fun quote(text: String) = "'${text.replace("'", "''")}'"
    private fun Connection.count(sequence: String): Long = scalar("SELECT coalesce(last_value,0) FROM duckdb_sequences() WHERE sequence_name='$sequence'").toLong()

    private fun fixture(block: (DuckDBConnection, Path) -> Unit) {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-external-resources-")
        try {
            (DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
                setProperty("autoinstall_known_extensions", "false")
                setProperty("autoload_known_extensions", "false")
            }) as DuckDBConnection).use { c ->
                val path = directory.resolve("fixture.duckdb")
                c.execute("ATTACH ${quote(path.toString())} AS seed")
                c.execute("CREATE TABLE seed.payload(value INT)")
                c.execute("INSERT INTO seed.payload VALUES(42)")
                c.execute("DETACH seed")
                for (name in listOf("create_seq", "status_seq", "destroy_seq", "list_seq", "option_seq", "handle_seq")) c.execute("CREATE SEQUENCE $name")
                c.execute("CREATE MACRO mock_create(p) AS TABLE SELECT MAP {'id':nextval('create_seq')::VARCHAR} AS handle")
                c.execute("CREATE MACRO mock_status(h) AS TABLE SELECT 'ready' AS state,MAP {'uri':${quote(path.toString())},'attached_db_type':'duckdb'} AS result WHERE nextval('status_seq')>0")
                c.execute("CREATE MACRO mock_destroy(h) AS TABLE SELECT nextval('destroy_seq') AS status")
                c.execute("CREATE MACRO mock_list(p) AS TABLE SELECT MAP {'id':'discovered'} AS handle,'reference-local' AS reference,'ready' AS state WHERE nextval('list_seq')>0")
                c.execute("CALL register_external_resource_type('mock@local',kind:='catalog',create_function:='mock_create',status_function:='mock_status',destroy_function:='mock_destroy',list_function:='mock_list')")
                block(c, path)
            }
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun modes(block: (DuckDBConnection, Path, Boolean) -> Unit) {
        for (generated in listOf(false, true)) fixture { c, path -> block(c, path, generated) }
    }

    @Test
    fun createRegisterAndDestroyKeepNamesAndExecuteOnlyTheirExpectedMockCallbacks() = modes { c, _, generated ->
        c.execute("CREATE EXTERNAL RESOURCE 'mock@local' AS \"Case Name\" (REGION 'local',SPOT)", generated)
        assertEquals(1, c.count("create_seq"))
        assertEquals(1, c.count("status_seq"))
        assertFailsWith<SQLException> { c.execute("CREATE EXTERNAL RESOURCE 'mock@local' AS \"Case Name\"", generated) }
        assertEquals(1, c.count("create_seq"), "Duplicate registration is checked before mock creation")
        c.execute("REGISTER EXTERNAL RESOURCE 'mock@local' AS adopted FROM MAP {'id':'adopted'}", generated)
        assertEquals(1, c.count("create_seq"), "REGISTER skips create")
        assertEquals(2, c.count("status_seq"))
        val shown = c.rows("SHOW EXTERNAL RESOURCES", generated).sortedBy { it[0] }
        assertEquals(listOf("Case Name", "adopted"), shown.map { it[0] })
        assertTrue(shown.all { it[1] == "mock@local" && it[2] == "true" })
        c.execute("DESTROY EXTERNAL RESOURCE \"Case Name\"", generated)
        assertEquals(1, c.count("destroy_seq"))
        assertFailsWith<SQLException> { c.execute("DESTROY EXTERNAL RESOURCE \"Case Name\"", generated) }
        c.execute("DESTROY EXTERNAL RESOURCE adopted", generated)
        assertEquals(2, c.count("destroy_seq"))
        assertTrue(c.rows("SHOW EXTERNAL RESOURCES", generated).isEmpty())
    }

    @Test
    fun showAllRunsDiscoveryAndUnionsOpaqueHandlesWithoutTreatingItAsALocalOnlyRead() = modes { c, _, generated ->
        c.createStatement().use { s -> s.executeQuery(render("SHOW EXTERNAL RESOURCES", generated)).use { r ->
            val shape = SqlFragment("SHOW EXTERNAL RESOURCES", "duckdb").outputShape()
            assertEquals(shape.columns.map { it.name }, (1..r.metaData.columnCount).map { r.metaData.getColumnName(it) })
            assertEquals(shape.columns.map { it.type }, (1..r.metaData.columnCount).map { r.metaData.getColumnTypeName(it) })
            assertFalse(r.next())
        } }
        assertEquals(0, c.count("list_seq"))
        val discovered = c.rows("SHOW ALL EXTERNAL RESOURCES", generated).single()
        assertNull(discovered[0])
        assertEquals(listOf("mock@local", "false", "ready", "reference-local", null, null), discovered.subList(1, 7))
        assertTrue("discovered" in discovered[7]!!)
        assertEquals(1, c.count("list_seq"))
        c.execute("REGISTER EXTERNAL RESOURCE 'mock@local' AS adopted FROM MAP {'id':'discovered'}", generated)
        val merged = c.rows("SHOW ALL EXTERNAL RESOURCES", generated)
        assertEquals(1, merged.size, "The same type/handle appears once under its managed name")
        assertEquals("adopted", merged.single()[0])
        assertEquals("true", merged.single()[2])
        assertNull(merged.single()[3], "Managed rows do not pretend discovery state was persisted")
        assertEquals(2, c.count("list_seq"))
        c.execute("DESTROY EXTERNAL RESOURCE adopted", generated)
    }

    @Test
    fun borrowedAttachmentsSurviveDetachWhileOwnedTemporaryAttachmentsRunTheirMockDeleter() = modes { c, _, generated ->
        c.execute("CREATE EXTERNAL RESOURCE 'mock@local' AS shared", generated)
        c.execute("ATTACH TO EXTERNAL RESOURCE shared AS borrower1 (READ_ONLY)", generated)
        c.execute("ATTACH TO EXTERNAL RESOURCE shared AS borrower2 (READ_ONLY)", generated)
        assertEquals("42", c.scalar("SELECT value FROM borrower1.payload"))
        c.execute("DETACH borrower1")
        assertEquals(0, c.count("destroy_seq"))
        assertEquals("1", c.scalar("SELECT count(*) FROM duckdb_external_resources()"))
        c.execute("ATTACH ':memory:' AS unrelated")
        c.execute("DESTROY EXTERNAL RESOURCE shared", generated)
        assertEquals(1, c.count("destroy_seq"))
        assertEquals("0", c.scalar("SELECT count(*) FROM duckdb_databases() WHERE database_name='borrower2'"))
        assertEquals("1", c.scalar("SELECT count(*) FROM duckdb_databases() WHERE database_name='unrelated'"))
        c.execute("ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' (REGION 'local') AS temporary_owned (READ_ONLY)", generated)
        assertEquals("42", c.scalar("SELECT value FROM temporary_owned.payload"))
        c.execute("DETACH temporary_owned")
        assertEquals(2, c.count("create_seq"))
        assertEquals(2, c.count("destroy_seq"))
        assertTrue(c.rows("SHOW EXTERNAL RESOURCES", generated).isEmpty())
    }

    @Test
    fun failuresReapOnlyMockResourcesOwnedByThatStatementAndNeverDestroyABorrowedOne() = modes { c, path, generated ->
        c.execute("CREATE EXTERNAL RESOURCE 'mock@local' AS shared", generated)
        c.execute("ATTACH ${quote(path.toString())} AS holder")
        assertFailsWith<SQLException> { c.execute("ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' AS failed_owned", generated) }
        assertEquals(2, c.count("create_seq"))
        assertEquals(1, c.count("destroy_seq"), "A failure after mock creation reaps that owned resource")
        assertFailsWith<SQLException> { c.execute("ATTACH TO EXTERNAL RESOURCE shared AS failed_borrow", generated) }
        assertEquals(1, c.count("destroy_seq"), "A borrowing failure must not tear down the registered resource")
        assertEquals("1", c.scalar("SELECT count(*) FROM duckdb_external_resources() WHERE name='shared'"))
        c.execute("DETACH holder")
        val connect = assertFailsWith<SQLException> { c.execute("CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local'", generated) }
        assertTrue(connect.message.orEmpty().contains("does not support CONNECT"))
        assertEquals(3, c.count("create_seq"))
        assertEquals(2, c.count("destroy_seq"))
        assertEquals("0", c.scalar("SELECT count(*) FROM duckdb_databases() WHERE database_name LIKE '__connect%'"))
        assertFailsWith<SQLException> { c.execute("CONNECT TO EXTERNAL RESOURCE shared", generated) }
        assertEquals(2, c.count("destroy_seq"))
        c.execute("DESTROY EXTERNAL RESOURCE shared", generated)
        assertEquals(3, c.count("destroy_seq"))
    }

    @Test
    fun resourceSuppliedOptionsCannotBeOverriddenAndFailedNewAttachmentsAreReaped() = modes { c, path, generated ->
        c.execute("CREATE MACRO fixed_status(h) AS TABLE SELECT 'ready' AS state,MAP {'uri':${quote(path.toString())},'attached_db_type':'duckdb','read_only':'true'} AS result")
        c.execute("CALL register_external_resource_type('fixed@local',kind:='catalog',create_function:='mock_create',status_function:='fixed_status',destroy_function:='mock_destroy')")
        c.execute("CREATE EXTERNAL RESOURCE 'fixed@local' AS fixed", generated)
        for (option in listOf("READ_ONLY false", "READ_ONLY true", "READONLY false", "READWRITE true", "TYPE 'duckdb'")) {
            val error = assertFailsWith<SQLException> { c.execute("ATTACH TO EXTERNAL RESOURCE fixed AS blocked ($option)", generated) }
            assertTrue("resource supplies" in error.message.orEmpty())
        }
        assertEquals(0, c.count("destroy_seq"))
        assertFailsWith<SQLException> { c.execute("ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'fixed@local' AS blocked (READ_ONLY false)", generated) }
        assertEquals(1, c.count("destroy_seq"))
        c.execute("DESTROY EXTERNAL RESOURCE fixed", generated)
    }

    @Test
    fun runtimeValidationRejectsMissingNamesNullOptionsAndInvalidHandlesBeforeCallbacks() = modes { c, _, generated ->
        for (sql in listOf("CREATE EXTERNAL RESOURCE 'mock@local'", "REGISTER EXTERNAL RESOURCE 'mock@local' FROM MAP {'id':'x'}",
            "CREATE EXTERNAL RESOURCE 'mock@local' AS r (REGION NULL)", "REGISTER EXTERNAL RESOURCE 'mock@local' AS r FROM NULL",
            "REGISTER EXTERNAL RESOURCE 'mock@local' AS r FROM 'not a map'", "CREATE EXTERNAL RESOURCE 'unknown@fixture' AS r")) {
            assertFailsWith<SQLException> { c.execute(sql, generated) }
        }
        assertEquals(0, c.count("create_seq"))
        assertEquals(0, c.count("status_seq"))
        assertTrue(c.rows("SHOW EXTERNAL RESOURCES", generated).isEmpty())
    }

    @Test
    fun failedDestroyRetainsRegistrationUntilAnExplicitUserRepairAndAnotherDestroy() = modes { c, _, generated ->
        c.execute("CREATE MACRO failing_destroy(h) AS TABLE SELECT CASE WHEN nextval('destroy_seq')>0 THEN error('mock teardown failure') ELSE 'ok' END AS status")
        c.execute("CALL register_external_resource_type('failing@local',kind:='catalog',create_function:='mock_create',status_function:='mock_status',destroy_function:='failing_destroy')")
        c.execute("CREATE EXTERNAL RESOURCE 'failing@local' AS retained", generated)
        assertFailsWith<SQLException> { c.execute("DESTROY EXTERNAL RESOURCE retained", generated) }
        assertEquals("1", c.scalar("SELECT count(*) FROM duckdb_external_resources() WHERE name='retained'"))
        assertEquals(1, c.count("destroy_seq"))
        c.execute("CREATE OR REPLACE MACRO failing_destroy(h) AS TABLE SELECT nextval('destroy_seq') AS status")
        c.execute("DESTROY EXTERNAL RESOURCE retained", generated)
        assertEquals(2, c.count("destroy_seq"))
        assertTrue(c.rows("SHOW EXTERNAL RESOURCES", generated).isEmpty())
    }

    @Test
    fun nativeGrammarCoversTheStructuredFormsAndRejectsAmbiguousOrMalformedOptions() = fixture { c, _ ->
        val valid = listOf("CREATE EXTERNAL RESOURCE 'mock@local' AS \"select\" (SPOT)",
            "REGISTER EXTERNAL RESOURCE 'mock@local' AS \"Case Name\" FROM {'id':'x'}", "DESTROY EXTERNAL RESOURCE \"he said \"\"hi\"\"\"",
            "SHOW EXTERNAL RESOURCES", "SHOW ALL EXTERNAL RESOURCES", "ATTACH TO EXTERNAL RESOURCE r AS db (READ_ONLY)",
            "ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' (REGION 'x') AS db (READ_ONLY)",
            "CONNECT TO EXTERNAL RESOURCE r (TOKEN 'dummy')", "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' (REGION 'x')")
        for (sql in valid) for (text in listOf(sql, render(sql, true)))
            assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(text)).type)
        for (sql in listOf("CREATE OR REPLACE EXTERNAL RESOURCE 't' AS r", "ATTACH TO EXTERNAL RESOURCE r",
            "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 't' (X 1) (Y 2)", "CONNECT TO EXTERNAL RESOURCE r AS db",
            "CREATE EXTERNAL RESOURCE 't' AS r (X=1)", "CREATE EXTERNAL RESOURCE 't' AS r (X 1,x 2)", "SHOW ALL EXTERNAL RESOURCE"))
            assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
    }

    @Test
    fun allValidationPathsAvoidBindingHandlesOptionsOrDiscoveryAndNeverChangeRegistryState() = fixture { c, _ ->
        val before = c.rows("SELECT database_name FROM duckdb_databases() ORDER BY database_name")
        val typeCount = c.scalar("SELECT count(*) FROM duckdb_external_resource_types()")
        val statements = listOf("CREATE EXTERNAL RESOURCE 'mock@local' AS checked (REGION nextval('option_seq'))",
            "REGISTER EXTERNAL RESOURCE 'mock@local' AS checked FROM MAP {'id':nextval('handle_seq')::VARCHAR}",
            "DESTROY EXTERNAL RESOURCE missing", "SHOW EXTERNAL RESOURCES", "SHOW ALL EXTERNAL RESOURCES",
            "ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' (REGION nextval('option_seq')) AS checked",
            "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 'mock@local' (REGION nextval('option_seq'))",
            "CONNECT TO EXTERNAL RESOURCE missing (TOKEN nextval('option_seq'))",
            "CALL \"register_external_resource_type\"('never_registered@fixture',kind:='catalog',create_function:='mock_create')")
        val prepares = AtomicInteger()
        for (sql in statements) {
            Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            RelationScope.analyze(sql, "duckdb", RelationFacts())
            if (sql.startsWith("SHOW")) SqlFragment(sql, "duckdb").outputShape()
            val helper = c.duplicate()
            val guarded = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") { prepares.incrementAndGet(); throw AssertionError("Resource validation must never prepare") }
                try { method.invoke(helper, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            } as Connection
            DuckdbVerifier({ guarded }).use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            assertIs<VerifyResult.Unverified>(ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) }).verify(sql))
            ParseOnlyDuckdbVerifier().use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
        }
        assertEquals(0, prepares.get())
        for (name in listOf("create_seq", "status_seq", "destroy_seq", "list_seq", "option_seq", "handle_seq")) assertEquals(0, c.count(name), name)
        assertTrue(c.rows("SHOW EXTERNAL RESOURCES").isEmpty())
        assertEquals(typeCount, c.scalar("SELECT count(*) FROM duckdb_external_resource_types()"))
        assertEquals(before, c.rows("SELECT database_name FROM duckdb_databases() ORDER BY database_name"))
        c.rows("SHOW ALL EXTERNAL RESOURCES")
        assertEquals(1, c.count("list_seq"))
    }

    @Test
    fun nativePrepareEvaluatesResourceOperandsAndCanInvalidateItsOwnAutocommitDatabase() {
        // Test-only transaction lets us inspect bind-time evaluation without an
        // autocommit. The statements are never executed; no resource is registered
        // or provisioned, and nothing is retried.
        fixture { c, _ ->
            c.execute("BEGIN TRANSACTION")
            c.prepareStatement("CREATE EXTERNAL RESOURCE 'mock@local' AS checked (REGION nextval('option_seq'))").close()
            c.prepareStatement("REGISTER EXTERNAL RESOURCE 'mock@local' AS checked FROM MAP {'id':nextval('handle_seq')::VARCHAR}").close()
            assertEquals(1, c.count("option_seq"))
            assertEquals(1, c.count("handle_seq"))
            assertEquals(0, c.count("create_seq"))
            assertEquals(0, c.count("status_seq"))
            c.execute("ROLLBACK")
        }
        // Pinned 2.0's autocommit bookkeeping calls this a read-only transaction
        // even though binding nextval changed it. Isolate the native internal error;
        // do not touch that invalidated database again or add a production workaround.
        fixture { c, _ ->
            val error = assertFailsWith<SQLException> {
                c.prepareStatement("CREATE EXTERNAL RESOURCE 'mock@local' AS checked (REGION nextval('option_seq'))").close()
            }
            assertTrue("read-only but has made changes" in error.message.orEmpty())
        }
    }
}
