import dev.brikk.house.sql.metadata.DUCKDB_EXTENSIONS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_SETTINGS
import dev.brikk.house.sql.metadata.DUCKDB_TYPES
import dev.brikk.house.sql.metadata.DuckdbCapture
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** The extensions whose functions are captured; the DuckDB IDE plugin offers these today. */
val CAPTURED_EXTENSIONS = listOf("fts", "httpfs", "icu", "inet", "postgres_scanner", "spatial", "sqlite_scanner")

/** Writes vendor/data/duckdb-<capture>-<version>.tsv for the in-process engine on the classpath. */
fun captureDuckdb(vendorData: Path) {
    DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        val version = connection.engineVersion()
        println("DuckDB $version (${connection.sourceId()})")
        for (capture in listOf(DUCKDB_FUNCTIONS, DUCKDB_KEYWORDS, DUCKDB_SETTINGS, DUCKDB_EXTENSIONS, DUCKDB_TYPES)) {
            writeDump(vendorData.resolve("duckdb-${capture.name}-$version.tsv"), capture.dump(connection))
        }
    }
}

/**
 * Writes vendor/data/duckdb-extension-functions-<version>.tsv: for each of [CAPTURED_EXTENSIONS],
 * the `duckdb_functions()` rows that `LOAD` adds on a fresh engine, with the loaded
 * `extension_version` (empty for a statically linked extension). Installing an extension
 * needs the DuckDB extension repository (network); a statically linked one adds no rows.
 */
fun captureDuckdbExtensionFunctions(vendorData: Path) {
    val rows = CAPTURED_EXTENSIONS.flatMap { extension ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val baseline = DUCKDB_FUNCTIONS.dump(connection).rows.toSet()
            connection.createStatement().use { it.execute("INSTALL $extension; LOAD $extension;") }
            val extensionVersion = connection.scalar(
                "SELECT extension_version FROM duckdb_extensions() WHERE extension_name = '$extension'",
            )
            val added = DUCKDB_FUNCTIONS.dump(connection).rows.filterNot(baseline::contains)
            println("$extension $extensionVersion: ${added.size} rows")
            added.map { listOf(extension, extensionVersion) + it }
        }
    }
    val version = DriverManager.getConnection("jdbc:duckdb:").use { it.engineVersion() }
    writeDump(
        vendorData.resolve("duckdb-extension-functions-$version.tsv"),
        Dump(listOf("extension", "extension_version") + DUCKDB_FUNCTIONS.columns, rows),
    )
}

fun DuckdbCapture<*>.dump(connection: Connection): Dump = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { rs ->
        val labels = (1..rs.metaData.columnCount).map(rs.metaData::getColumnLabel)
        check(labels == columns) { "$name query returns $labels, declared $columns" }
        val rows = buildList { while (rs.next()) add(columns.indices.map { rs.getString(it + 1) }) }
        Dump(columns, rows)
    }
}

/** The release number without DuckDB's `v` prefix: "1.5.6". */
fun Connection.engineVersion(): String = scalar("SELECT library_version FROM pragma_version()").removePrefix("v")

fun Connection.sourceId(): String = scalar("SELECT source_id FROM pragma_version()")

private fun Connection.scalar(sql: String): String = createStatement().use { statement ->
    statement.executeQuery(sql).use { rs -> check(rs.next()); rs.getString(1) }
}
