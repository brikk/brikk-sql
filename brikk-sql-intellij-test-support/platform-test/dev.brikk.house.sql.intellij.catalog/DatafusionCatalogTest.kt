package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.scope.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import dev.brikk.house.sql.intellij.dialect.ConnectedDatafusionEditorProfile
import dev.brikk.house.sql.intellij.dialect.SpiceFixtureDialect
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.database.Dbms
import java.util.concurrent.TimeUnit

/** Replay exact JDBC string cells, including empty results and unsupported metadata. */
class DatafusionCatalogTest : BasePlatformTestCase() {
    private class Capture(product: String) : CatalogRowSource, CatalogServerInfoSource {
        val queries: MutableMap<String, List<List<String?>>>
        var info: CatalogServerInfo
        var fail: String? = null
        var before: (String, Int) -> Unit = { _, _ -> }
        val counts = mutableMapOf<String, Int>()
        init {
            val relative = "vendor/data/datafusion-spice/round9/$product-jdbc.json"
            val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve(relative)) }
            val observations = Json.parseToJsonElement(Files.readString(root.resolve(relative))).jsonObject.getValue("observations").jsonObject
            info = CatalogServerInfo(observations["product"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull,
                observations["version"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull)
            queries = observations.getValue("queries").jsonObject.mapNotNull { (sql, result) ->
                val rows = result.jsonObject["value"]?.jsonObject?.get("rows")?.jsonArray ?: return@mapNotNull null
                sql to rows.map { row -> row.jsonArray.map { it.jsonPrimitive.contentOrNull } }
            }.toMap().toMutableMap()
        }
        override fun serverInfo() = info
        override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
            val count = counts.merge(sql, 1, Int::plus)!!
            before(sql, count)
            if (sql == fail) throw SQLException("permission denied")
            for (row in requireNotNull(queries[sql]) { sql }) onRow(CatalogRow { row[it - 1] })
        }
    }

    fun testSpiceJdbcPreservesNamespacesViewsAndUnknownSignatures() {
        val harvest = SpiceCatalogHarvester().harvest(Capture("spice"))!!
        assertEquals("2.4.0", harvest.shared.serverVersion)
        assertFalse(harvest.shared.signatures)
        assertTrue(harvest.shared.functions.all { it.def.overloads.isEmpty() })
        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED), harvest.shared.functions.filter { it.def.name == "vector_search" }.map { it.def.kind }.toSet())
        assertEquals("SCALAR_STUB", harvest.shared.functions.single { it.def.name == "vector_search" && it.def.kind == FunctionKind.SCALAR }.def.nativeKind)
        val native = harvest.shared.nativeCatalog!!
        assertTrue(native.engineBuild.contains("55.2.0"))
        val dotted = native.relations.single { it.path == listOf("spice", "public", "a.b") }
        assertEquals("VIEW", dotted.kind)
        assertEquals("SELECT 9 AS id", dotted.definition)
        assertNull(dotted.constraints)
        assertNull(dotted.comment)
        assertEquals("Int64", dotted.columns.single().type)
        val facts = native.relationFacts(harvest.session.namespace)
        val result = RelationScope.complete("SELECT t. FROM \"a.b\" t", "spice", facts, 9)
        assertEquals(listOf("id"), result.items.map { it.name })
        assertEquals(listOf("spice", "public"), harvest.session.namespace)
    }

    fun testDatafusionJdbcHasItsOwnEmbeddingAndNativeArrowTypes() {
        val harvest = DatafusionCatalogHarvester().harvest(Capture("datafusion"))!!
        assertEquals("55.2.0", harvest.shared.serverVersion)
        val native = harvest.shared.nativeCatalog!!
        assertEquals("datafusion", native.product)
        val typed = native.relations.single { it.path.last() == "typed" }
        assertEquals(listOf("Decimal128(18, 3)", "List(Int64)"), typed.columns.map { it.type })
        assertTrue(native.relations.any { it.path.last() == "MixedRows" })
        assertNull(SpiceCatalogHarvester().harvest(Capture("datafusion")))
        assertNull(DatafusionCatalogHarvester().harvest(Capture("spice")))
    }

    fun testFailuresPartialRowsAndChangingSettingsNeverCertifyAHarvest() {
        for (query in listOf(DatafusionCatalogHarvester.SQL_COLUMNS, DatafusionCatalogHarvester.SQL_ROUTINES,
            DatafusionCatalogHarvester.SQL_SETTINGS, DatafusionCatalogHarvester.SQL_VIEWS)) {
            val rows = Capture("spice").apply { fail = query }
            try { SpiceCatalogHarvester().harvest(rows); fail("must propagate permission failure") } catch (_: SQLException) {}
        }
        val empty = Capture("spice").apply { queries[DatafusionCatalogHarvester.SQL_FUNCTIONS] = emptyList() }
        assertNull(SpiceCatalogHarvester().harvest(empty))
        val changed = Capture("spice")
        changed.before = { sql, count -> if (sql == DatafusionCatalogHarvester.SQL_SETTINGS && count == 2)
            changed.queries[sql] = changed.queries.getValue(sql) + listOf(listOf("new.setting", "true")) }
        assertNull(SpiceCatalogHarvester().harvest(changed))
        val reloaded = Capture("spice")
        reloaded.before = { sql, count -> if (sql == DatafusionCatalogHarvester.SQL_ROUTINES && count == 2)
            reloaded.queries[sql] = reloaded.queries.getValue(sql).drop(1) }
        assertNull(SpiceCatalogHarvester().harvest(reloaded))
        val malformed = Capture("spice").apply { queries[DatafusionCatalogHarvester.SQL_COLUMNS] = listOf(listOf("foreign", "schema", "table", "x", "0", null, "YES", "Int64")) }
        assertNull(SpiceCatalogHarvester().harvest(malformed))
    }

    fun testDiskAndSessionInventoriesStaySeparateAcrossVersionAndSettingsChanges() {
        val dir = Files.createTempDirectory("df-catalog")
        try {
            val identity = ConnectionIdentity.of(listOf("spice", "flight://one", "user"))
            val initial = SpiceCatalogHarvester().harvest(Capture("spice"))!!
            val store = LiveCatalogStore(dir)
            store.store("ds", identity, initial.shared)
            val a = Any(); val b = Any()
            val updated = initial.shared.copy(functions = listOf(LiveFunction(FunctionDef("private_udf", FunctionKind.SCALAR))))
            store.storeSession(a, "ds", identity, initial.session.copy(inventory = updated))
            assertEquals(updated, store.select("ds", identity, a).inventory)
            assertEquals(initial.shared, store.select("ds", identity, b).inventory)
            store.store("ds", identity, initial.shared.copy(serverVersion = "2.5.0"))
            assertEquals(updated, store.select("ds", identity, a).inventory)
            val disk = LiveCatalogStore(dir).select("ds", identity)
            assertTrue(disk.source is CatalogSource.Disk)
            assertEquals(initial.shared.nativeCatalog, disk.inventory!!.nativeCatalog)
            assertEquals(SessionFacts.NONE, disk.session)
            assertNull(store.select("ds", ConnectionIdentity.of(listOf("datafusion", "flight://one", "user")), a).inventory)
            store.storeSession(a, "ds", identity, SessionFacts.NONE)
            assertEquals("2.5.0", store.select("ds", identity, a).inventory!!.serverVersion)
        } finally { dir.toFile().deleteRecursively() }
    }

    fun testNamesOnlyRefreshDropsRemovedUnknownFunctionsAndDoesNotBorrowSqrtOverloads() {
        val inventory = LiveInventory("55.2.0", listOf(LiveFunction(FunctionDef("sqrt", FunctionKind.SCALAR))), false)
        val snapshot = DATAFUSION_METADATA.default
        val selection = LiveCatalogSelection(CatalogSource.Live(Instant.EPOCH), inventory)
        val merged = LiveMergePolicy.REPLACE_NAMES.merge(selection, snapshot, snapshot.completableFunctions())
        assertNull(merged.functions.single { it.name == "sqrt" }.signature)
        assertFalse(merged.functions.any { it.name == "abs" })
        assertTrue(merged.functions.any { it.name.equals("CAST", true) })
        val ordinary = LiveMergePolicy.REPLACE.merge(selection, snapshot, snapshot.completableFunctions())
        assertFalse(ordinary.functions.any { it.name == "abs" })
    }

    fun testBuildSettingsVersionAndFunctionReloadChangeTheContentRevision() {
        fun harvest(rows: Capture) = SpiceCatalogHarvester().harvest(rows)!!.shared
        val original = harvest(Capture("spice"))
        val version = Capture("spice").apply { info = CatalogServerInfo("Spice.ai", "2.5.0") }
        assertFalse(original.nativeCatalog!!.revision == harvest(version).nativeCatalog!!.revision)
        val settings = Capture("spice").apply {
            queries[DatafusionCatalogHarvester.SQL_SETTINGS] = queries.getValue(DatafusionCatalogHarvester.SQL_SETTINGS).map {
                if (it[0] == "datafusion.sql_parser.enable_ident_normalization") listOf(it[0], "true") else it
            }
        }
        assertFalse(original.nativeCatalog!!.revision == harvest(settings).nativeCatalog!!.revision)
        val reloaded = Capture("spice").apply {
            queries[DatafusionCatalogHarvester.SQL_ROUTINES] = queries.getValue(DatafusionCatalogHarvester.SQL_ROUTINES) + listOf(listOf("spice", "public", "new_udf", "SCALAR"))
        }
        assertFalse(original.nativeCatalog!!.revision == harvest(reloaded).nativeCatalog!!.revision)
        val build = Capture("spice").apply {
            queries[DatafusionCatalogHarvester.SQL_VERSION] = listOf(listOf("Apache DataFusion 55.2.0, custom build"))
        }
        assertFalse(original.nativeCatalog!!.revision == harvest(build).nativeCatalog!!.revision)
    }

    fun testOldConstructorsAndCopyDefaultShapesRemainLinkable() {
        fun shape(type: Class<*>, args: Array<Class<*>>) {
            val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
            assertNotNull(type.getConstructor(*args))
            assertNotNull(type.getConstructor(*args, Int::class.javaPrimitiveType, marker))
            assertNotNull(type.getMethod("copy", *args))
            assertNotNull(type.getMethod("copy\$default", type, *args, Int::class.javaPrimitiveType, Any::class.java))
        }
        shape(LiveInventory::class.java, arrayOf(String::class.java, List::class.java, Boolean::class.javaPrimitiveType!!, List::class.java, List::class.java))
        shape(SessionFacts::class.java, arrayOf(List::class.java, List::class.java))
    }

    fun testProductSettingsAndEmbeddingChangesAlterIdentityAndExecutionTriggers() {
        val source = com.intellij.database.dataSource.LocalDataSource().apply { url = "jdbc:arrow-flight-sql://localhost:50051"; username = "alice" }
        val df = DatafusionCatalogProfile(com.intellij.database.Dbms.POSTGRES)
        val spice = SpiceCatalogProfile(com.intellij.database.Dbms.POSTGRES)
        assertFalse(df.identity(source) == spice.identity(source))
        val before = df.identity(source)
        source.username = "bob"
        assertFalse(before == df.identity(source))
        val changedEmbedding = object : DatafusionCatalogProfile(com.intellij.database.Dbms.POSTGRES) {
            override fun embeddingProfile(dataSource: com.intellij.database.dataSource.LocalDataSource) = "custom-udfs-v2"
        }
        assertFalse(df.identity(source) == changedEmbedding.identity(source))
        for (profile in listOf(df, spice)) {
            assertTrue(profile.sessionScopedInventory)
            assertEquals(HarvestTrigger.EVERY_CONSOLE_CONNECT, profile.trigger)
            val statements = profile.statementDetector.detect("CREATE OR REPLACE FUNCTION f() RETURNS INT; SET datafusion.catalog.default_schema = 'new'; DROP VIEW v; PREPARE p AS SELECT 1; SELECT 'DROP TABLE x'")
            assertEquals(4, statements.size)
            assertTrue(statements.all { (it.effect as StatementEffect.FunctionInventory).sessionFacts })
        }
    }

    fun testConnectedEditorUsesCopiedLiveFactsAndFallsBackOnIdentityChange() {
        val file = myFixture.configureByText("connected.sql", "SELECT * FROM sample")
        val errors = BackgroundErrors(testRootDisposable)
        var harvest = SpiceCatalogHarvester().harvest(Capture("spice"))!!
        val profile = object : DatafusionCatalogProfile(Dbms.POSTGRES, "spice") {
            override val harvester = LiveCatalogHarvester { harvest }
        }
        val service = LiveService(errors.newScope(testRootDisposable), profile, null)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        RecordingConsole(project, file.virtualFile, "postgresql").use { recording ->
            embeddedDuckdb().use { jdbc ->
                val source = recording.console.dataSource
                connected(databaseConnection(jdbc, source, Dbms.POSTGRES, otherRequestor()))
                val editor = ConnectedDatafusionEditorProfile(SpiceFixtureDialect, { service })
                assertEquals("live connection", editor.catalogProvenance(file))
                assertEquals("2.4.0", editor.serverVersion(file))
                assertTrue(editor.functions(file).none { it.nativeKind == "SCALAR_STUB" })
                assertTrue(editor.snapshot(file).functions.getAll("sqrt").all { it.overloads.isEmpty() })
                val provider = editor.providers.single()
                val offline = provider.facts(file) as RelationFactsResult.Available
                assertTrue("helper defaults must not become a console namespace", offline.facts.searchPath.isEmpty())
                val requestor = LiveCatalogServiceTest.Engine()
                val connection = databaseConnection(jdbc, source, Dbms.POSTGRES, requestor)
                connected(connection)
                val observer = ExecutionObserverTest.Observer(project, errors.newScope(testRootDisposable),
                    ExecutionObserverTest.Profile(liveCatalog = LiveService::class.java, autoIntrospect = null, rules = emptyList()))
                observer.requestFinished(finished(recording.console, requestor, "SELECT 1"))
                val local = provider.facts(file) as RelationFactsResult.Available
                assertEquals(listOf("\"spice\".\"public\""), local.facts.searchPath)
                assertTrue(local.revision > offline.revision)
                harvest = harvest.copy(shared = harvest.shared.copy(functions = emptyList()))
                ApplicationManager.getApplication().executeOnPooledThread {
                    service.inventoryChanged(project, recording.console.session, requestor, sessionFacts = true)
                }.get(30, TimeUnit.SECONDS)
                assertTrue(editor.functions(file).none { it.name == "sqrt" })
                source.username = "another-user"
                assertEquals("bundled fallback", editor.catalogProvenance(file))
                assertNull(provider.facts(file))
                assertTrue(editor.functions(file).any { it.name == "sqrt" })
                java.lang.ref.Reference.reachabilityFence(connection)
            }
        }
        errors.awaitScopes()
        errors.assertNone()
    }
}
