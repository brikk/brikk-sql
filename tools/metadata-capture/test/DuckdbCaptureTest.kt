import dev.brikk.house.sql.metadata.DUCKDB_DUMPS
import dev.brikk.house.sql.metadata.DUCKDB_EXTENSIONS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_SETTINGS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DuckdbCapture
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.DuckdbExtensionFunctionRow
import dev.brikk.house.sql.metadata.toSnapshot
import dev.brikk.house.sql.metadata.userFunctions
import java.sql.DriverManager
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DuckdbCaptureTest {

    private val vendorData = Path("../../vendor/data")

    private val captures = listOf(DUCKDB_FUNCTIONS, DUCKDB_KEYWORDS, DUCKDB_SETTINGS, DUCKDB_EXTENSIONS)

    private fun <T> DuckdbCapture<T>.vendored(version: String): List<T>? =
        vendorData.resolve("duckdb-$name-$version.tsv").takeIf { it.exists() }?.let { readDump(it).rows.map(::decode) }

    private fun vendoredDump(version: String) = DuckdbDump(
        engineVersion = version,
        functions = checkNotNull(DUCKDB_FUNCTIONS.vendored(version)),
        keywords = DUCKDB_KEYWORDS.vendored(version),
        settings = DUCKDB_SETTINGS.vendored(version),
        extensions = DUCKDB_EXTENSIONS.vendored(version),
        extensionFunctions = vendorData.resolve("duckdb-extension-functions-$version.tsv").takeIf { it.exists() }
            ?.let { file -> readDump(file).rows.map { DuckdbExtensionFunctionRow(checkNotNull(it[0]), checkNotNull(it[1]), DUCKDB_FUNCTIONS.decode(it.drop(2))) } },
    )

    @Test
    fun bundledRowsEqualTheVendoredDumpRows() {
        val versions = vendorData.listDirectoryEntries("duckdb-functions-*.tsv")
            .map { it.name.removePrefix("duckdb-functions-").removeSuffix(".tsv") }
        assertEquals(versions.toSet(), DUCKDB_DUMPS.map { it.engineVersion }.toSet())
        for (bundled in DUCKDB_DUMPS) {
            val vendored = vendoredDump(bundled.engineVersion)
            assertEquals(vendored.functions, bundled.functions, bundled.engineVersion)
            assertEquals(vendored.keywords, bundled.keywords, bundled.engineVersion)
            assertEquals(vendored.settings, bundled.settings, bundled.engineVersion)
            assertEquals(vendored.extensions, bundled.extensions, bundled.engineVersion)
            assertEquals(vendored.extensionFunctions, bundled.extensionFunctions, bundled.engineVersion)
        }
    }

    @Test
    fun theBundledSnapshotsAreMappedFromTheBundledRows() {
        assertEquals(DUCKDB_DUMPS.map { it.engineVersion }, DUCKDB_METADATA.snapshots.map { it.engineVersion })
        for ((dump, snapshot) in DUCKDB_DUMPS.zip(DUCKDB_METADATA.snapshots)) {
            assertEquals(dump.toSnapshot().functions.functions, snapshot.functions.functions, dump.engineVersion)
        }
    }

    /** toSnapshot keeps the first row's parameter names for a deduplicated overload; prove no dump needs that rule. */
    @Test
    fun noVendoredOverloadHasConflictingParameterNames() {
        val normalizedType = mapOf("macro" to "scalar", "table_macro" to "table")
        for (dump in DUCKDB_DUMPS) {
            val rows = dump.functions + dump.extensionFunctions.orEmpty().map { it.function }
            val conflicts = rows
                .groupBy { row ->
                    listOf(
                        row.database, row.schema, row.name, normalizedType[row.type] ?: row.type,
                        row.parameterTypes.map { it ?: "ANY" }, row.returnType ?: "ANY", row.varargs != null,
                    )
                }
                .filterValues { group -> group.map { it.parameters }.distinct().size > 1 }
            assertEquals(emptyMap(), conflicts, dump.engineVersion)
        }
    }

    @Test
    fun liveJdbcEngineEqualsTheVendoredDump() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val version = connection.engineVersion()
            for (capture in captures) {
                assertEquals(readDump(vendorData.resolve("duckdb-${capture.name}-$version.tsv")).rows, capture.dump(connection).rows, capture.name)
            }
        }
    }

    @Test
    fun liveSessionMacrosKeepTheirOwnerThroughTheCapture() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute(
                    "CREATE SCHEMA a; CREATE SCHEMA b; CREATE MACRO a.review_fn(x) AS x + 1; " +
                        "CREATE MACRO b.review_fn(x, y) AS x + y; CREATE TEMP MACRO t_fn(x) AS x;",
                )
            }
            val dump = DuckdbDump("live", DUCKDB_FUNCTIONS.dump(connection).rows.map(DUCKDB_FUNCTIONS::decode))

            assertEquals(
                setOf("memory.a.review_fn(1)", "memory.b.review_fn(2)", "temp.main.t_fn(1)"),
                dump.userFunctions().map { "${it.database}.${it.schema}.${it.def.name}(${it.def.overloads.single().argTypes.size})" }.toSet(),
            )
            assertFalse("review_fn" in dump.toSnapshot().functions)
        }
    }

    @Test
    fun pythonClientFunctionsAreAbsent() {
        for (name in listOf("pandas_scan", "python_map_function")) assertFalse(name in DUCKDB_FUNCTION_CATALOG, name)
    }
}
