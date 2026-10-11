import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.functionCatalog
import java.sql.DriverManager
import kotlin.io.path.Path
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
    fun bundledCatalogEqualsTheVendoredDump() {
        assertEquals(vendoredDump("1.5.6").functionCatalog().functions, DUCKDB_FUNCTION_CATALOG.functions)
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
