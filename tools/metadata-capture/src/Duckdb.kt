import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_SETTINGS
import dev.brikk.house.sql.metadata.DuckdbCapture
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Writes vendor/data/duckdb-<capture>-<version>.tsv for the in-process engine on the classpath. */
fun captureDuckdb(vendorData: Path) {
    DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        val version = connection.engineVersion()
        println("DuckDB $version (${connection.sourceId()})")
        for (capture in listOf(DUCKDB_FUNCTIONS, DUCKDB_KEYWORDS, DUCKDB_SETTINGS)) {
            writeDump(vendorData.resolve("duckdb-${capture.name}-$version.tsv"), capture.dump(connection))
        }
    }
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
