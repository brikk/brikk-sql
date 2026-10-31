package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.execute
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.quote
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.rows
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.scalar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Signature
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

internal class DuckdbWindowCatalogResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun Connection.capture()=rows(DUCKDB_FUNCTIONS.sql)
    private fun catalog(rows:List<List<String?>>)=DuckdbDump("2.0.0",rows.map(DUCKDB_FUNCTIONS::decode)).toSnapshot()
    private fun template():Path {
        val value=System.getenv("BRIKK_DUCKDB_WINDOW_EXTENSION")
        assumeTrue(value!=null,"Build the pinned real window fixture and set BRIKK_DUCKDB_WINDOW_EXTENSION")
        return Path.of(value!!).also { require(Files.isRegularFile(it) && it.fileName.toString()=="brikk_window.duckdb_extension") }
    }
    private fun publish(f:RepositoryFixture,c:Connection,source:Path):Path {
        val original=Files.readAllBytes(source)
        require(original.size>512)
        val payload=original.copyOf(original.size-256)
        val signer=Signature.getInstance("SHA256withRSA").apply { initSign(RepositoryTestKeys.first.private) }
        val hash=MessageDigest.getInstance("SHA-256")
        for(start in payload.indices step 1024*1024)signer.update(hash.digest(payload.copyOfRange(start,minOf(start+1024*1024,payload.size))))
        val signed=payload+signer.sign()
        val binary=f.directory.resolve("brikk_window.duckdb_extension")
        Files.write(binary,signed)
        val location=f.prefix.resolve(c.scalar("SELECT version()")).resolve(c.scalar("PRAGMA platform"))
        Files.createDirectories(location)
        GZIPOutputStream(Files.newOutputStream(location.resolve("brikk_window.duckdb_extension.gz"))).use { it.write(signed) }
        return binary
    }
    @Test fun builtInWindowRowsMatchCapturedKindsAndOrdinaryAggregatesStayAggregatesEvenWithOver() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val native=c.capture().map(DUCKDB_FUNCTIONS::decode).filter { it.internal && it.type=="window" }
            val bundled=DUCKDB_METADATA.default.functions.functions.filter { it.kind==FunctionKind.WINDOW }
            assertEquals(bundled.map { it.name }.toSet(),native.map { it.name }.toSet())
            for(row in native) {
                val entry=bundled.single { it.name==row.name }
                assertTrue(entry.overloads.any { it.argTypes==row.parameterTypes && it.returnType==row.returnType },row.toString())
            }
            assertEquals(FunctionKind.AGGREGATE,DUCKDB_METADATA.default.functions["sum"]!!.kind)
            assertEquals(setOf(FunctionKind.SCALAR,FunctionKind.TABLE_VALUED),DUCKDB_METADATA.default.functions.getAll("range").map { it.kind }.toSet())
            val sql="SELECT i,row_number() OVER(ORDER BY i),rank() OVER(ORDER BY i),lag(i) OVER(ORDER BY i),sum(i) OVER(ORDER BY i) FROM range(3)t(i) ORDER BY i"
            val expected=listOf(listOf("0","1","1",null,"0"),listOf("1","2","2","0","1"),listOf("2","3","3","1","3"))
            assertEquals(expected,c.rows(sql));assertEquals(expected,c.rows(render(sql)))
        }
    }
    @Test fun signedExtensionRegistersRealWindowsAlongsideSameNameScalarAndExecutesTheirDifferentContexts() {
        val source=template()
        RepositoryFixture.scratch { f -> f.connect().use { c ->
            assertTrue(c.scalar("SELECT source_id FROM pragma_version()").startsWith("d54cbbe3df"))
            val before=c.capture()
            assertTrue(before.none { it.first()?.startsWith("brikk_")==true })
            val unsigned=assertFailsWith<SQLException> { c.execute("LOAD ${quote(source.toString())}") }
            assertTrue("signature" in unsigned.message.orEmpty().lowercase(),unsigned.message)
            val signed=publish(f,c,source)
            c.execute(f.create("window_repo"))
            c.execute("INSTALL brikk_window FROM window_repo")
            c.execute("LOAD brikk_window FROM window_repo")
            assertEquals("false",c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
            val after=c.capture()
            val live=catalog(after)
            assertEquals(FunctionKind.WINDOW,live.functions["brikk_row_number"]!!.kind)
            assertEquals(setOf(FunctionKind.SCALAR,FunctionKind.WINDOW),live.functions.getAll("brikk_kind").map { it.kind }.toSet())
            val delta=after.toSet()-before.toSet()
            val extension=DuckdbDump("2.0.0",before.map(DUCKDB_FUNCTIONS::decode),extensionFunctions=delta.map {
                DuckdbExtensionFunctionRow("brikk_window","window-fixture-1",DUCKDB_FUNCTIONS.decode(it))
            }).toSnapshot()
            assertEquals(setOf(FunctionKind.SCALAR,FunctionKind.WINDOW),extension.extensionFunctions!!.filter { it.name=="brikk_kind" }.map { it.kind }.toSet())
            assertEquals(setOf("main","brikk_scalar"),after.map(DUCKDB_FUNCTIONS::decode).filter { it.name=="brikk_kind" }.map { it.schema }.toSet())
            val sql="SELECT p,v,brikk_row_number() OVER(PARTITION BY p ORDER BY v),system.brikk_scalar.brikk_kind(),brikk_kind() OVER(PARTITION BY p ORDER BY v) FROM (VALUES (1,30),(2,20),(1,10),(2,10))t(p,v) ORDER BY p,v"
            val expected=listOf(listOf("1","10","1","-7","1"),listOf("1","30","2","-7","2"),listOf("2","10","1","-7","1"),listOf("2","20","2","-7","2"))
            assertEquals(expected,c.rows(sql));assertEquals(expected,c.rows(render(sql)))
            c.createStatement().use { s -> s.executeQuery("SELECT brikk_row_number() OVER() AS n").use { r ->
                assertEquals("BIGINT",r.metaData.getColumnTypeName(1));assertTrue(r.next());assertEquals(1,r.getLong(1))
            } }
            assertFailsWith<SQLException> { c.rows("SELECT brikk_row_number()") }
            assertFailsWith<SQLException> { c.rows("SELECT brikk_row_number() OVER(ORDER BY brikk_row_number() OVER())") }
            assertEquals(listOf(listOf("10000","50005000")),c.rows(render("SELECT count(*),sum(n) FROM (SELECT brikk_row_number() OVER(ORDER BY i) n FROM range(10000)t(i))")))
            System.getenv("BRIKK_DUCKDB_WINDOW_CAPTURE")?.let { output ->
                val path=Path.of(output);Files.createDirectories(path.toAbsolutePath().parent)
                val text=buildJsonObject {
                    put("engine_version",c.scalar("SELECT version()"));put("source_revision","d54cbbe3df17e8e5ce68312f483b4155836112aa")
                    put("engine_source_id",c.scalar("SELECT source_id FROM pragma_version()"))
                    put("template_sha256",MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).joinToString("") { "%02x".format(it.toInt() and 255) })
                    put("allow_unsigned_extensions",false);put("executed_window_results",true)
                    put("columns",JsonArray(DUCKDB_FUNCTIONS.columns.map(::JsonPrimitive)))
                    fun table(rows:List<List<String?>>)=JsonArray(rows.map { row -> JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) })
                    put("before",table(before));put("after",table(after))
                }.toString()
                val temporary=Files.createTempFile(path.toAbsolutePath().parent,"window-capture-",".json")
                try {
                    Files.writeString(temporary,text)
                    Files.move(temporary,path.toAbsolutePath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }finally { Files.deleteIfExists(temporary) }
            }
            assertTrue(Files.exists(signed))
        } }
    }
    @Test fun loadingTheFixtureDoesNotInstallItIntoOtherConnectionsOrWeakenTamperChecks() {
        val source=template()
        RepositoryFixture.scratch { f -> f.connect().use { c ->
            val signed=publish(f,c,source)
            c.execute(f.create("window_repo"));c.execute("INSTALL brikk_window FROM window_repo");c.execute("LOAD brikk_window FROM window_repo")
            assertTrue(Files.exists(signed))
            DriverManager.getConnection("jdbc:duckdb:").use { separate ->
                assertEquals("0",separate.scalar("SELECT count(*) FROM duckdb_functions() WHERE function_name='brikk_row_number'"))
                assertFailsWith<SQLException> { separate.rows("SELECT brikk_row_number() OVER()") }
            }
            assertEquals("false",c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
        } }
        // Never rewrite a mapped shared library in place. Corrupt an uninstalled served
        // copy in a different database/trust/install directory, before any LOAD.
        RepositoryFixture.scratch { f -> f.connect().use { c ->
            val signed=publish(f,c,source)
            val corrupted=Files.readAllBytes(signed).also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
            val location=f.prefix.resolve(c.scalar("SELECT version()")).resolve(c.scalar("PRAGMA platform"))
            GZIPOutputStream(Files.newOutputStream(location.resolve("brikk_window.duckdb_extension.gz"))).use { it.write(corrupted) }
            c.execute(f.create("window_repo"))
            val tampered=assertFailsWith<SQLException> { c.execute("INSTALL brikk_window FROM window_repo") }
            assertTrue("signature" in tampered.message.orEmpty().lowercase(),tampered.message)
            assertEquals("false",c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
        } }
    }
}
