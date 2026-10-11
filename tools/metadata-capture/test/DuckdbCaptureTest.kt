import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_SETTINGS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DuckdbCapture
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.toSnapshot
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

    private val captures = listOf(DUCKDB_FUNCTIONS, DUCKDB_KEYWORDS, DUCKDB_SETTINGS)

    private fun <T> DuckdbCapture<T>.vendored(version: String): List<T>? =
        vendorData.resolve("duckdb-$name-$version.tsv").takeIf { it.exists() }?.let { readDump(it).rows.map(::decode) }

    private fun vendoredDump(version: String) = DuckdbDump(
        engineVersion = version,
        functions = checkNotNull(DUCKDB_FUNCTIONS.vendored(version)),
        keywords = DUCKDB_KEYWORDS.vendored(version),
        settings = DUCKDB_SETTINGS.vendored(version),
    )

    @Test
    fun bundledSnapshotsEqualTheVendoredDumps() {
        val versions = vendorData.listDirectoryEntries("duckdb-functions-*.tsv")
            .map { it.name.removePrefix("duckdb-functions-").removeSuffix(".tsv") }
        assertEquals(versions.toSet(), DUCKDB_METADATA.snapshots.map { it.engineVersion }.toSet())
        for (bundled in DUCKDB_METADATA.snapshots) {
            val vendored = vendoredDump(bundled.engineVersion).toSnapshot()
            assertEquals(vendored.functions.functions, bundled.functions.functions, bundled.engineVersion)
            assertEquals(vendored.keywords?.keywords, bundled.keywords?.keywords, bundled.engineVersion)
            assertEquals(vendored.pragmas, bundled.pragmas, bundled.engineVersion)
            assertEquals(vendored.settings, bundled.settings, bundled.engineVersion)
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
    fun pythonClientFunctionsAreAbsent() {
        for (name in listOf("pandas_scan", "python_map_function")) assertFalse(name in DUCKDB_FUNCTION_CATALOG, name)
    }
}
