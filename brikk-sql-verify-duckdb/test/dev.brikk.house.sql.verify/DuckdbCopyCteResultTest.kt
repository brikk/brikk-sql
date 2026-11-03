package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DuckdbCopyCteResultTest {
    private fun sql(text: String) = Dialects.DUCKDB.parseOne(text).sql("duckdb")
    private fun literal(path: Path) = "'${path.toAbsolutePath().toString().replace("'", "''")}'"
    private fun Connection.execute(text: String) = createStatement().use { statement ->
        if (statement.execute(text)) statement.resultSet.use { while (it.next()) { /* drain before closing */ } }
    }
    private fun Connection.rows(text: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(text).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private fun fixture(c: Connection) {
        c.execute("CREATE TABLE staging(id INTEGER,v VARCHAR)")
        c.execute("INSERT INTO staging VALUES(1,'one'),(2,'two'),(3,'three')")
    }
    private fun scratch(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-copy-cte-")
        try { block(directory) } finally {
            // Only this test's newly created, uniquely named directory and its own files.
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun copyCountFilesAndStatisticsHaveTheirActualNativeResultContracts() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("CREATE TABLE observed(n BIGINT)")
            val csv = directory.resolve("data's.csv")
            assertEquals(listOf(listOf("3")), c.rows(sql("WITH copied AS (COPY staging TO ${literal(csv)} (FORMAT CSV,HEADER)) INSERT INTO observed SELECT Count FROM copied RETURNING n")))
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM read_csv(${literal(csv)},header=true) ORDER BY id"))
            val files = directory.resolve("files.parquet")
            assertEquals(listOf(listOf("3", "1")), c.rows(sql("WITH copied AS (COPY staging TO ${literal(files)} (FORMAT PARQUET,RETURN_FILES)) SELECT Count,len(Files) FROM copied")))
            val stats = directory.resolve("stats.parquet")
            c.createStatement().use { statement ->
                statement.executeQuery(sql("WITH copied AS (COPY staging TO ${literal(stats)} (FORMAT PARQUET,RETURN_STATS)) SELECT * FROM copied")).use { result ->
                    val meta = result.metaData
                    assertEquals(listOf("filename", "count", "file_size_bytes", "footer_size_bytes", "column_statistics", "partition_keys", "extra_info"),
                        (1..meta.columnCount).map(meta::getColumnLabel))
                    assertEquals(listOf("VARCHAR", "UBIGINT", "UBIGINT", "UBIGINT", "MAP(VARCHAR, MAP(VARCHAR, VARCHAR))", "MAP(VARCHAR, VARCHAR)", "MAP(VARCHAR, VARIANT)"),
                        (1..meta.columnCount).map(meta::getColumnTypeName))
                    assertTrue(result.next())
                    assertEquals(stats.toString(), result.getString(1))
                    assertEquals(3, result.getInt(2))
                    assertTrue(result.getLong(3) > 0)
                    assertTrue(result.getLong(4) > 0)
                    assertTrue(!result.next())
                }
            }
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM read_parquet(${literal(stats)}) ORDER BY id"))
        }
    }

    @Test
    fun multipleReferencesWriteTheFileAndEvaluateItsSourceOnlyOnceForEveryHint() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE SEQUENCE export_counter START 1")
            for ((index, hint) in listOf("", "MATERIALIZED ", "NOT MATERIALIZED ").withIndex()) {
                val file = directory.resolve("once-$index.csv")
                val text = "WITH copied AS $hint(COPY (SELECT nextval('export_counter') AS n FROM range(3)) TO ${literal(file)} (FORMAT CSV,HEADER,RETURN_FILES,OVERWRITE)) SELECT a.Count,b.Count,len(a.Files),len(b.Files) FROM copied a,copied b"
                assertEquals(listOf(listOf("3", "3", "1", "1")), c.rows(sql(text)), hint)
                val last = (index + 1) * 3
                assertEquals(listOf(listOf(last.toString())), c.rows("SELECT currval('export_counter')"))
                assertEquals((last - 2..last).map { listOf(it.toString()) }, c.rows("SELECT n FROM read_csv(${literal(file)},header=true) ORDER BY n"))
            }
        }
    }

    @Test
    fun unreferencedAndEmptyConsumersStillWriteAndSeparateCopyCtesStayIndependent() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for ((index, consumer) in listOf("SELECT 42", "SELECT 42 WHERE false").withIndex()) {
                val file = directory.resolve("unreferenced-$index.csv")
                c.execute(sql("WITH copied AS (COPY staging TO ${literal(file)} (FORMAT CSV,HEADER)) $consumer"))
                assertTrue(Files.isRegularFile(file))
                assertEquals(listOf(listOf("3")), c.rows("SELECT count(*) FROM read_csv(${literal(file)},header=true)"))
            }
            val first = directory.resolve("first.csv")
            val second = directory.resolve("second.csv")
            assertEquals(listOf(listOf("3", "3")), c.rows(sql("WITH a AS (COPY staging TO ${literal(first)} (FORMAT CSV,RETURN_FILES)), b AS (COPY staging TO ${literal(second)} (FORMAT CSV,RETURN_FILES)) SELECT a.Count,b.Count FROM a,b")))
            assertTrue(Files.isRegularFile(first) && Files.isRegularFile(second))
        }
    }

    @Test
    fun nestedCopyQueryCanExportTheCopyCtesStatisticsWithoutLosingStatementBoundaries() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val data = directory.resolve("nested.parquet")
            val stats = directory.resolve("nested-stats.csv")
            val text = "COPY (WITH stats AS (COPY (SELECT * FROM staging ORDER BY id) TO ${literal(data)} (FORMAT PARQUET,RETURN_STATS)) SELECT filename,count FROM stats) TO ${literal(stats)} (FORMAT CSV,HEADER)"
            c.execute(sql(text))
            assertEquals(listOf(listOf(data.toString(), "3")), c.rows("SELECT filename,count FROM read_csv(${literal(stats)},header=true)"))
            assertEquals(listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three")), c.rows("SELECT * FROM read_parquet(${literal(data)}) ORDER BY id"))
        }
    }

    @Test
    fun preparedCopyRebuildsItsOwnOutputOnEachExecution() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val text = "WITH copied AS (COPY staging TO (?) (FORMAT CSV,HEADER,RETURN_FILES,OVERWRITE)) SELECT Count,len(Files) FROM copied"
            c.prepareStatement(sql(text)).use { prepared ->
                for (name in listOf("prepared-first.csv", "prepared-second.csv")) {
                    val file = directory.resolve(name)
                    prepared.setString(1, file.toString())
                    prepared.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(3, result.getInt(1))
                        assertEquals(1, result.getInt(2))
                        assertTrue(!result.next())
                    }
                    assertTrue(Files.isRegularFile(file))
                }
            }
        }
    }

    @Test
    fun unsupportedCopyDirectionsAndPlacementsDoNotOpenOutputPaths() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val file = directory.resolve("must-not-exist.csv")
            val body = "COPY staging TO ${literal(file)} (FORMAT CSV)"
            for (text in listOf(
                "WITH copied AS (COPY staging FROM ${literal(file)} (FORMAT CSV)) SELECT * FROM copied",
                "WITH RECURSIVE copied AS ($body) SELECT * FROM copied",
                "SELECT * FROM (WITH copied AS ($body) SELECT * FROM copied)",
                "CREATE VIEW forbidden AS WITH copied AS ($body) SELECT * FROM copied",
            )) {
                assertFailsWith<SQLException>(text) { c.execute(text) }
                assertFalse(Files.exists(file))
            }
            assertEquals(listOf(listOf("3")), c.rows("SELECT count(*) FROM staging"))
        }
    }

    @Test
    fun analysisAndConnectionBackedParserValidationNeverRunTheWriteOrExport() = scratch { directory ->
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            fixture(user)
            user.execute("CREATE SEQUENCE audit_seq START 1")
            val file = directory.resolve("not-written.csv")
            val payloads = listOf(
                "WITH changed AS (INSERT INTO staging VALUES(4,'four') RETURNING *) SELECT * FROM changed",
                "WITH copied AS (COPY (SELECT nextval('audit_seq') AS n) TO ${literal(file)} (FORMAT CSV,HEADER,RETURN_FILES)) SELECT * FROM copied",
            )
            val verifier = ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(user.duplicate()) })
            for (text in payloads) {
                assertTrue(RelationScope.analyze(text, "duckdb", RelationFacts()).parsed)
                val result = verifier.verify(text)
                assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified, result.toString())
                assertFalse(Files.exists(file))
            }
            assertEquals(listOf(listOf("3")), user.rows("SELECT count(*) FROM staging"))
            assertEquals(listOf(listOf("1")), user.rows("SELECT nextval('audit_seq')"))
        }
    }
}
