import dev.brikk.house.sql.metadata.DUCKDB_EXTENSIONS
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.DUCKDB_SETTINGS
import dev.brikk.house.sql.metadata.DUCKDB_TYPES
import dev.brikk.house.sql.metadata.DuckdbCapture
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

/** The extensions whose functions are captured; the DuckDB IDE plugin offers these today. */
val CAPTURED_EXTENSIONS = listOf("fts", "httpfs", "icu", "inet", "postgres_scanner", "spatial", "sqlite_scanner")

/** Writes vendor/data/duckdb-<capture>-<version>.tsv for the in-process engine on the classpath. */
fun captureDuckdb(vendorData: Path) {
    withDuckdbCaptureEngine { connection ->
        val version = connection.engineVersion()
        println("DuckDB $version (${connection.sourceId()})")
        val captures=listOf(DUCKDB_FUNCTIONS, DUCKDB_KEYWORDS, DUCKDB_SETTINGS, DUCKDB_EXTENSIONS, DUCKDB_TYPES)
            .map { it.name to it.dump(connection) }
        for((name,dump)in captures)writeDump(vendorData.resolve("duckdb-$name-$version.tsv"),dump)
        writeDump(vendorData.resolve("duckdb-capture-provenance-$version.tsv"), captureProvenance(connection))
    }
}

/**
 * Writes vendor/data/duckdb-extension-functions-<version>.tsv: for each of [CAPTURED_EXTENSIONS],
 * the `duckdb_functions()` rows that `LOAD` adds on a fresh engine, with the loaded
 * `extension_version`. Requires an explicit local fixture manifest in
 * BRIKK_DUCKDB_CAPTURE_EXTENSIONS. Never installs or downloads an extension.
 */
fun captureDuckdbExtensionFunctions(vendorData: Path) {
    val manifest=System.getenv("BRIKK_DUCKDB_CAPTURE_EXTENSIONS")
        ?: error("Extension capture requires BRIKK_DUCKDB_CAPTURE_EXTENSIONS pointing to an explicitly reviewed local TSV manifest")
    captureDuckdbExtensionFunctions(vendorData,readExtensionFixtures(Path.of(manifest)))
}

data class ExtensionFixture(val name:String,val path:Path,val sha256:String)

fun readExtensionFixtures(path:Path):List<ExtensionFixture> {
    val dump=readDump(path)
    require(dump.columns==listOf("extension","path","sha256")) { "Expected extension/path/sha256 fixture manifest" }
    val fixtures=dump.rows.map { row ->
        val name=requireNotNull(row[0]);val binary=Path.of(requireNotNull(row[1])).toAbsolutePath().normalize()
        require(name in CAPTURED_EXTENSIONS) { "Extension is outside the reviewed capture set" }
        require(Files.isRegularFile(binary) && binary.fileName.toString()=="$name.duckdb_extension") { "Expected a local matching extension binary" }
        val expected=requireNotNull(row[2]);require(Regex("[0-9a-f]{64}").matches(expected)) { "Expected SHA-256" }
        require(sha256(Files.readAllBytes(binary))==expected) { "Extension fixture checksum mismatch" }
        ExtensionFixture(name,binary,expected)
    }
    require(fixtures.isNotEmpty() && fixtures.map { it.name }.distinct().size==fixtures.size) { "Fixture manifest must be nonempty with unique extension names" }
    return fixtures
}

fun captureDuckdbExtensionFunctions(vendorData:Path,fixtures:List<ExtensionFixture>) {
    require(fixtures.isNotEmpty() && fixtures.map { it.name }.distinct().size==fixtures.size) { "Expected a nonempty unique fixture set" }
    require(fixtures.all { it.name in CAPTURED_EXTENSIONS && it.path.fileName.toString()=="${it.name}.duckdb_extension" }) { "Unreviewed extension fixture" }
    val provenance=mutableListOf<List<String?>>()
    val rows = fixtures.flatMap { fixture ->
        require(sha256(Files.readAllBytes(fixture.path))==fixture.sha256) { "Fixture changed before capture" }
        withDuckdbCaptureEngine(allowLocalLoad=true) { connection ->
            require(connection.engineVersion()=="2.0.0" && connection.sourceId()=="d54cbbe3df") { "Extension capture needs the reviewed 2.0 engine" }
            val baseline = DUCKDB_FUNCTIONS.dump(connection).rows.toSet()
            val extension=fixture.name
            val linked=connection.scalar("SELECT (install_mode='STATICALLY_LINKED')::VARCHAR FROM duckdb_extensions() WHERE extension_name='$extension'")=="true"
            if(!linked)connection.createStatement().use { it.execute("LOAD '${fixture.path.toString().replace("'","''")}'") }
            val extensionVersion = connection.scalar(
                "SELECT extension_version FROM duckdb_extensions() WHERE extension_name = '$extension'",
            )
            require(linked || extensionVersion.isNotEmpty()) { "No runtime extension version; capture is unproved" }
            val added = DUCKDB_FUNCTIONS.dump(connection).rows.filterNot(baseline::contains)
            println("$extension $extensionVersion: ${added.size} rows")
            provenance += captureProvenance(connection).rows.map { it+listOf(extension,extensionVersion,fixture.sha256.takeUnless { linked },added.size.toString(),if(linked)"fresh-static-engine"else"fresh-local-load") }
            added.map { listOf(extension, extensionVersion) + it }
        }
    }
    val version = "2.0.0"
    writeDump(
        vendorData.resolve("duckdb-extension-functions-$version.tsv"),
        Dump(listOf("extension", "extension_version") + DUCKDB_FUNCTIONS.columns, rows),
    )
    writeDump(vendorData.resolve("duckdb-extension-provenance-$version.tsv"),Dump(
        PROVENANCE_COLUMNS+listOf("extension","extension_version","binary_sha256","added_rows","origin"),provenance))
}

private val PROVENANCE_COLUMNS=listOf("engine_version","source_id","jdbc_sha256","external_access","autoload","autoinstall","unsigned_extensions")
private fun captureProvenance(c:Connection)=Dump(PROVENANCE_COLUMNS,listOf(listOf(c.engineVersion(),c.sourceId(),
    sha256(Files.readAllBytes(Path.of(org.duckdb.DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI()))),
    c.scalar("SELECT current_setting('enable_external_access')"),c.scalar("SELECT current_setting('autoload_known_extensions')"),
    c.scalar("SELECT current_setting('autoinstall_known_extensions')"),c.scalar("SELECT current_setting('allow_unsigned_extensions')"))))

private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Private engine and extension/secret/temp directories. LOAD is explicitly opt-in. */
fun <T> withDuckdbCaptureEngine(allowLocalLoad:Boolean=false,block:(Connection)->T):T {
    val root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"duckdb-capture-")
    try {
        val properties=Properties().apply {
            setProperty("extension_directory",root.resolve("extensions").toString())
            setProperty("secret_directory",root.resolve("secrets").toString())
            setProperty("temp_directory",root.resolve("temp").toString())
            setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
            setProperty("allow_unsigned_extensions","false");setProperty("allow_persistent_secrets","false")
            setProperty("threads","1")
        }
        return DriverManager.getConnection("jdbc:duckdb:",properties).use { c ->
            // duckdb_extensions() inventories this private directory even with
            // external access disabled. No user directory is allowlisted.
            c.createStatement().use { it.execute("SET allowed_directories=['${root.toString().replace("'","''")}/']") }
            if(!allowLocalLoad)c.createStatement().use { it.execute("SET enable_external_access=false") }
            c.createStatement().use { it.execute("SET lock_configuration=true") }
            block(c)
        }
    }finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

fun DuckdbCapture<*>.dump(connection: Connection): Dump = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { rs ->
        val labels = (1..rs.metaData.columnCount).map(rs.metaData::getColumnLabel)
        check(labels == columns) { "$name query returns $labels, declared $columns" }
        val rows = buildList { while (rs.next()) add(columns.indices.map { rs.getString(it + 1) }) }
        Dump(columns, rows)
    }
}

/** The release number without DuckDB's `v` prefix, for example "2.0.0". */
fun Connection.engineVersion(): String = scalar("SELECT library_version FROM pragma_version()").removePrefix("v")

fun Connection.sourceId(): String = scalar("SELECT source_id FROM pragma_version()")

private fun Connection.scalar(sql: String): String = createStatement().use { statement ->
    statement.executeQuery(sql).use { rs -> check(rs.next()); rs.getString(1) }
}
