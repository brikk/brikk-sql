import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.toSnapshot
import java.sql.DriverManager
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DuckdbCaptureTest {

    private val vendorData = Path("../../vendor/data")

    private fun vendoredDump(version: String) = DuckdbDump(
        engineVersion = version,
        functions = readDump(vendorData.resolve("duckdb-functions-$version.tsv")).rows.map(DUCKDB_FUNCTIONS::decode),
    )

    @Test
    fun bundledSnapshotsEqualTheVendoredDumps() {
        val versions = vendorData.listDirectoryEntries("duckdb-functions-*.tsv")
            .map { it.name.removePrefix("duckdb-functions-").removeSuffix(".tsv") }
        assertEquals(versions.toSet(), DUCKDB_METADATA.snapshots.map { it.engineVersion }.toSet())
        for (bundled in DUCKDB_METADATA.snapshots) {
            val vendored = vendoredDump(bundled.engineVersion).toSnapshot()
            assertEquals(vendored.functions.functions, bundled.functions.functions, bundled.engineVersion)
        }
    }

    @Test
    fun liveJdbcEngineEqualsTheVendoredDump() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val version = connection.engineVersion()
            assertEquals(readDump(vendorData.resolve("duckdb-functions-$version.tsv")).rows, DUCKDB_FUNCTIONS.dump(connection).rows)
        }
    }

    @Test
    fun pythonClientFunctionsAreAbsent() {
        for (name in listOf("pandas_scan", "python_map_function")) assertFalse(name in DUCKDB_FUNCTION_CATALOG, name)
    }
}
