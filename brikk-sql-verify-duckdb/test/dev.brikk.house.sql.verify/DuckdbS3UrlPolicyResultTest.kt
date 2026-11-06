package dev.brikk.house.sql.verify

import com.sun.net.httpserver.HttpServer
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.Properties
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Real HTTPFS parsing, but all I/O is a private loopback fixture with synthetic credentials. */
internal class DuckdbS3UrlPolicyResultTest {
    private fun Connection.rows(sql: String) = createStatement().use { s ->
        s.queryTimeout = 10
        s.executeQuery(sql).use { r ->
            buildList {
                while (r.next()) add((1..r.metaData.columnCount).map {
                    r.getString(it)
                })
            }
        }
    }
    private fun Connection.run(sql: String) = createStatement().use {
        it.queryTimeout = 10
        it.execute(sql)
    }
    private fun quote(value: String) = "'${value.replace("'","''")}'"
    @Test fun nativeDefaultTreatsQueryTextAsKeyWhileExplicitDeprecatedModeUsesUrlOptions() {
        val source = System.getenv("BRIKK_DUCKDB_HTTPFS_EXTENSION")
        assumeTrue(source != null, "Set BRIKK_DUCKDB_HTTPFS_EXTENSION to a trusted matching 2.0 HTTPFS fixture")
        val binary = Path.of(source!!)
        require(Files.isRegularFile(binary) && binary.fileName.toString() == "httpfs.duckdb_extension")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // Never record Authorization headers or credential values.
            requests += exchange.requestURI.toASCIIString()
            val bytes = "safe".toByteArray()
            exchange.responseHeaders.set("ETag", "\"fixture\"")
            exchange.responseHeaders.set("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
            } else {
                val range = exchange.requestHeaders.getFirst("Range")
                if (range != null) exchange.responseHeaders.set("Content-Range", "bytes 0-3/4")
                exchange.sendResponseHeaders(if (range == null) 200 else 206, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        val root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "s3-loopback-")
        server.start()
        try {
            val sql = "SELECT content FROM read_text('s3://bucket/f.txt?s3_region=us-east-1')"
            val forms = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
                assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
            var recorded = false
            for (deprecated in listOf<Boolean?>(null, false, true)) for (form in forms) {
                requests.clear()
                DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
                    setProperty("autoload_known_extensions", "false")
                    setProperty("autoinstall_known_extensions", "false")
                    setProperty("extension_directory", root.resolve("unused-install").toString())
                }).use { c ->
                    c.run("LOAD ${quote(binary.toString())}")
                    c.run("SET http_proxy=''")
                    c.run("CREATE TEMPORARY SECRET local_fixture(TYPE s3,KEY_ID 'fixture',SECRET 'not-a-real-key',REGION 'us-east-1',ENDPOINT '127.0.0.1:${server.address.port}',URL_STYLE 'path',USE_SSL false)")
                    // Unset extension option reports SQL NULL; its effective policy is
                    // proved by the request path, not guessed from that getter value.
                    assertEquals(listOf(listOf<String?>(null)), c.rows("SELECT current_setting('s3_enable_deprecated_url_parameters')"))
                    if (deprecated != null) c.run("SET s3_enable_deprecated_url_parameters=$deprecated")
                    assertIs<SerializedSql.Parsed>(c.serializeSql(form))
                    assertTrue(requests.isEmpty(), "Parse-only verification must not bind/read S3")
                    assertEquals(listOf(listOf("safe")), c.rows(form))
                    val expected = if (deprecated == true) "/bucket/f.txt" else "/bucket/f.txt%3Fs3_region%3Dus-east-1"
                    assertTrue(requests.isNotEmpty())
                    assertTrue(requests.all {
                        it == expected
                    }, requests.toString())
                    assertEquals(listOf(listOf(deprecated?.toString())), c.rows("SELECT current_setting('s3_enable_deprecated_url_parameters')"))
                    assertEquals(listOf(listOf("false")), c.rows("SELECT current_setting('allow_unsigned_extensions')"))
                    if (!recorded) {
                        val version = c.rows("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='httpfs' AND loaded").single().single()
                        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it.toInt() and 255) }
                        println("DDB20-046 engine=${c.rows("SELECT version()").single().single()} HTTPFS=$version sha256=$hash")
                        recorded = true
                    }
                }
            }
        } finally {
            server.stop(0)
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun nativeParseOnlyDiagnosticsRedactSensitiveS3ContextWithoutExecutingReaders() {
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("autoinstall_known_extensions", "false")
        }).use { c ->
            val sql = "SELECT * FROM read_text('s3://bucket/key?X-Amz-Signature=synthetic-sensitive-marker') WHERE ("
            val failure = assertIs<SerializedSql.Failed>(c.serializeSql(sql))
            val diagnostic = failure.errorIn(sql, failure.message.orEmpty())
            assertFalse("synthetic-sensitive-marker" in diagnostic.message)
            assertFalse("s3://" in diagnostic.message)
        }
    }
}
