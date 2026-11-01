package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** The 1.5.6 native library runs only in child JVMs, never alongside 2.0 in this JVM. */
internal class DuckdbStorageCompatibilityResultTest {
    private fun quote(value:String)="'${value.replace("'","''")}'"
    private fun Connection.run(sql:String,generated:Boolean)=createStatement().use { it.execute(if(generated)SqlFragment(sql,"duckdb").toExecutable("duckdb").sql else sql) }
    private fun Connection.scalar(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> assertTrue(r.next());r.getString(1) } }
    private fun Connection.tag(alias:String)=scalar("SELECT tags['storage_version'] FROM duckdb_databases() WHERE database_name=${quote(alias)}")
    private fun hash(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class OldEngine(private val root:Path,private val jar:Path) {
        private val source=root.resolve("StorageCompatibilityProbe.java")
        init { Files.writeString(source,"""
            import java.sql.*;
            import java.util.*;
            class StorageCompatibilityProbe {
              public static void main(String[] args) throws Exception {
                Class.forName("org.duckdb.DuckDBDriver");
                Properties base = new Properties();
                base.setProperty("autoload_known_extensions", "false");
                base.setProperty("autoinstall_known_extensions", "false");
                try (Connection c = DriverManager.getConnection("jdbc:duckdb:", base); Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA version")) {
                  r.next(); System.out.println("ENGINE\t" + r.getString(1) + "\t" + r.getString(2));
                }
                Properties p = new Properties(); p.putAll(base);
                if (!args[0].equals("create")) p.setProperty("duckdb.read_only", "true");
                try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + args[1], p); Statement s = c.createStatement()) {
                  if (args[0].equals("create")) { s.execute("CREATE TABLE fixture(v INTEGER)"); s.execute("INSERT INTO fixture VALUES(7)"); s.execute("CHECKPOINT"); }
                  try (ResultSet r = s.executeQuery("SELECT sum(v) FROM fixture")) { r.next(); System.out.println("SUM\t" + r.getString(1)); }
                  try (ResultSet r = s.executeQuery("SELECT readonly FROM duckdb_databases() WHERE NOT internal")) { r.next(); System.out.println("READONLY\t" + r.getString(1)); }
                } catch (SQLException e) { System.out.println("FAIL\t" + e.getMessage()); System.exit(2); }
              }
            }
        """.trimIndent()) }
        fun probe(path:Path,create:Boolean=false,expected:Int=0):String {
            require(path.toAbsolutePath().startsWith(root.toAbsolutePath()))
            val log=Files.createTempFile(root,"old-engine-",".log")
            val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "--enable-native-access=ALL-UNNAMED","-cp",jar.toString(),source.toString(),if(create)"create"else"read",path.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            val finished=process.waitFor(60,TimeUnit.SECONDS)
            if(!finished)process.destroyForcibly()
            assertTrue(finished,"Isolated old-engine fixture exceeded its deadline")
            val output=Files.readString(log)
            assertEquals(expected,process.exitValue(),output)
            assertTrue("ENGINE\tv1.5.6\t069cc9f9b5" in output,output)
            if(!create && expected==0)assertTrue("READONLY\ttrue" in output,output)
            return output
        }
    }
    private fun fixture(block:(Path,OldEngine)->Unit) {
        val value=System.getenv("BRIKK_DUCKDB_OLD_JDBC")
        assumeTrue(value!=null,"Set BRIKK_DUCKDB_OLD_JDBC to a trusted 1.5.6.0 JDBC fixture; loaded only in isolated JVMs")
        val jar=Path.of(value!!).toAbsolutePath();require(Files.isRegularFile(jar))
        val root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"storage-compat-").toAbsolutePath()
        try { block(root,OldEngine(root,jar)) }
        finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun defaultTwoPointZeroFileUsesNewFormatAndOldReadonlyReaderRejectsItWithoutMutation() = fixture { root,old ->
        for(generated in listOf(false,true)) {
            val file=root.resolve("new-$generated.db")
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                assertEquals("v2.0.0",c.scalar("SELECT version()"))
                assertEquals("latest",c.scalar("SELECT current_setting('storage_compatibility_version')"))
                c.run("ATTACH ${quote(file.toString())} AS current_file",generated)
                c.run("CREATE TABLE current_file.fixture(v INTEGER)",generated);c.run("INSERT INTO current_file.fixture VALUES(7)",generated)
                c.run("CHECKPOINT current_file",generated);assertEquals("v2.0.0+",c.tag("current_file"))
                c.run("DETACH current_file",generated)
            }
            val before=hash(file)
            val rejection=old.probe(file,expected=2)
            assertTrue("version number 69" in rejection && "between 64 and 68" in rejection,rejection)
            assertEquals(before,hash(file),"An unsupported old read must not repair or migrate the file")
        }
    }
    @Test fun explicitAttachAndGlobalCompatibilityOptionsCreateOldReadableSelectedTypeFixtures() = fixture { root,old ->
        for(generated in listOf(false,true))for(option in listOf("attach","setting")) {
            val file=root.resolve("compatible-$option-$generated.db")
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                if(option=="setting")c.run("SET storage_compatibility_version='v1.5.0'",generated)
                val suffix=if(option=="attach")" (STORAGE_VERSION 'v1.5.0')"else""
                c.run("ATTACH ${quote(file.toString())} AS compat$suffix",generated)
                c.run("CREATE TABLE compat.fixture(v INTEGER)",generated);c.run("INSERT INTO compat.fixture VALUES(7)",generated)
                c.run("CHECKPOINT compat",generated);assertEquals("v1.5.0+",c.tag("compat"));c.run("DETACH compat",generated)
                c.run("RESET storage_compatibility_version",generated)
                assertEquals("latest",c.scalar("SELECT current_setting('storage_compatibility_version')"))
            }
            val before=hash(file);assertTrue("SUM\t7" in old.probe(file));assertEquals(before,hash(file))
        }
    }
    @Test fun existingOldFileKeepsItsVersionAndLatestWriteTargetDoesNotPromiseAForcedFormatRewrite() = fixture { root,old ->
        for(generated in listOf(false,true)) {
            val file=root.resolve("old-$generated.db")
            assertTrue("SUM\t7" in old.probe(file,create=true))
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.run("ATTACH ${quote(file.toString())} AS old_file",generated)
                val beforeTag=c.tag("old_file");assertEquals("v1.0.0+",beforeTag)
                c.run("INSERT INTO old_file.fixture VALUES(8)",generated);c.run("CHECKPOINT old_file",generated)
                assertEquals(beforeTag,c.tag("old_file"));c.run("DETACH old_file",generated)
            }
            assertTrue("SUM\t15" in old.probe(file))
            val originalHash=hash(file)
            val copy=root.resolve("explicit-upgrade-$generated.db");Files.copy(file,copy)
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.run("ATTACH ${quote(copy.toString())} AS upgrade_copy (STORAGE_VERSION 'latest')",generated)
                c.run("CHECKPOINT upgrade_copy",generated);assertEquals("v2.0.0+",c.tag("upgrade_copy"));c.run("DETACH upgrade_copy",generated)
            }
            // A selected write target/tag is not a promise that old-readable INTEGER
            // contents were physically rewritten into a higher minimum format.
            assertTrue("SUM\t15" in old.probe(copy))
            assertEquals(originalHash,hash(file));assertTrue("SUM\t15" in old.probe(file))
        }
    }
}
