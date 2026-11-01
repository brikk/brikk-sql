import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class DuckdbHermeticCaptureTest {
    @Test fun coreCaptureIsRestrictedAndDoesNotSeeUserExtensionOrSecretDirectories() {
        var root:Path?=null
        withDuckdbCaptureEngine { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT current_setting('enable_external_access'),current_setting('autoload_known_extensions'),current_setting('autoinstall_known_extensions'),current_setting('allow_unsigned_extensions'),current_setting('extension_directory'),current_setting('secret_directory')").use { r ->
                assertTrue(r.next());for(i in 1..4)assertEquals("false",r.getString(i))
                root=Path.of(r.getString(5)).parent
                assertEquals(root!!.resolve("secrets").toString(),r.getString(6))
            } }
            assertFails { c.createStatement().use { it.execute("INSTALL inet") } }
        }
        assertFalse(Files.exists(root!!))
    }
    @Test fun capturesIncludeRuntimeProvenanceWithoutCredentialsOrCurrentSettingsValues() {
        val root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"capture-output-")
        try {
            captureDuckdb(root)
            val provenance=readDump(root.resolve("duckdb-capture-provenance-2.0.0.tsv"))
            assertEquals("2.0.0",provenance.rows.single()[0]);assertEquals("d54cbbe3df",provenance.rows.single()[1])
            assertEquals(listOf("false","false","false","false"),provenance.rows.single().drop(3))
            assertFalse("typed_value" in readDump(root.resolve("duckdb-settings-2.0.0.tsv")).columns)
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun badOrDuplicateManifestCannotLoadAnything() {
        val root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"capture-manifest-")
        try {
            val binary=root.resolve("inet.duckdb_extension");Files.writeString(binary,"not an extension")
            val manifest=root.resolve("fixtures.tsv")
            writeDump(manifest,Dump(listOf("extension","path","sha256"),listOf(listOf("inet",binary.toString(),"0".repeat(64)))))
            assertFailsWith<IllegalArgumentException> { readExtensionFixtures(manifest) }
            val actual=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it.toInt() and 255) }
            val row=listOf("inet",binary.toString(),actual)
            writeDump(manifest,Dump(listOf("extension","path","sha256"),listOf(row,row)))
            assertFailsWith<IllegalArgumentException> { readExtensionFixtures(manifest) }
            writeDump(manifest,Dump(listOf("extension","path","sha256"),emptyList()))
            assertFailsWith<IllegalArgumentException> { readExtensionFixtures(manifest) }
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun suppliedSignedFixtureGetsFreshRowsAndVersionHashProvenanceWithoutInstallation() {
        val supplied=System.getenv("BRIKK_DUCKDB_REPOSITORY_EXTENSION")
        assumeTrue(supplied!=null,"Supply the trusted matching inet fixture for the explicit local LOAD control")
        val binary=Path.of(supplied!!)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"extension-capture-output-")
        try {
            val manifest=root.resolve("fixtures.tsv")
            writeDump(manifest,Dump(listOf("extension","path","sha256"),listOf(listOf("inet",binary.toString(),hash))))
            val selected=System.getenv("BRIKK_DUCKDB_CAPTURE_EXTENSIONS")?.let(Path::of) ?: manifest
            captureDuckdbExtensionFunctions(root,readExtensionFixtures(selected))
            val provenance=readDump(root.resolve("duckdb-extension-provenance-2.0.0.tsv")).rows
            val p=provenance.single { it[7]=="inet" }
            assertEquals("inet",p[7]);assertTrue(p[8]!!.isNotEmpty());assertEquals(hash,p[9]);assertEquals("fresh-local-load",p[11])
            val all=readDump(root.resolve("duckdb-extension-functions-2.0.0.tsv")).rows
            val rows=all.filter { it[0]=="inet" }
            assertTrue(rows.isNotEmpty());assertEquals(rows.size,p[10]!!.toInt())
            assertTrue(rows.all { it[0]=="inet" && it[1]==p[8] })
            val historical=readDump(Path.of("../../vendor/data/duckdb-extension-functions-2.0.0.tsv")).rows
            for(record in provenance) {
                val before=historical.filter { it[0]==record[7] }
                if(before.isNotEmpty() && before.all { it[1]==record[8] })assertEquals(before,all.filter { it[0]==record[7] },record[7])
                println("DDB20-059 fresh ${record[7]} version=${record[8]} sha256=${record[9]} rows=${record[10]}")
            }
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
