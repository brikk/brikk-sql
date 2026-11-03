package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbCopyLayoutResultTest {
    private fun render(sql: String) = assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb")
    private fun quote(value: String) = "'${value.replace("'", "''")}'"
    private fun literal(path: Path) = quote(path.toAbsolutePath().toString())
    private fun Connection.execute(sql: String) = createStatement().use { s ->
        s.queryTimeout = 10
        if (s.execute(sql)) s.resultSet.use { r -> while (r.next()) { /* drain once, never retry a write */ } }
    }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 10
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun scratch(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-copy-layout-")
        try { block(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private fun fixture(c: Connection) {
        c.execute("SET threads=1")
        c.execute("CREATE TABLE events(region VARCHAR,day INTEGER,v INTEGER,id INTEGER)")
        c.execute("INSERT INTO events VALUES ('us',1,2,1),('eu',1,3,2),('us',1,NULL,3),('eu',2,-1,4),('us',1,-2,5),('eu',1,1,6),('eu',2,4,7),('us',2,0,8),('us',2,NULL,9),('us',1,2,10)")
    }
    private fun files(directory: Path) = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".parquet") }.sorted().toList() }
    private fun ids(c: Connection, file: Path) = c.rows("SELECT id FROM read_parquet(${literal(file)},hive_partitioning=false,file_row_number=true) ORDER BY file_row_number ASC").map { it.single()!!.toInt() }

    @Test
    fun orderByAloneWritesActualExpressionAscDescAndNullOrderInParquetAndCsv() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for ((index, order) in listOf("v ASC NULLS LAST,id DESC", "v DESC NULLS FIRST,id ASC", "abs(v) DESC NULLS LAST,id ASC",
                "CASE WHEN v IS NULL THEN 100 ELSE v END ASC,id DESC").withIndex()) {
                val file = root.resolve("single-$generated-$index.parquet")
                val sql = "COPY events TO ${literal(file)} (ORDER_BY ($order),FORMAT parquet)"
                c.execute(if (generated) render(sql) else sql)
                val expected = c.rows("SELECT id FROM events ORDER BY $order").map { it.single()!!.toInt() }
                assertEquals(1,files(file).size)
                assertEquals(expected, ids(c, files(file).single()), sql)
                assertEquals(10, ids(c, files(file).single()).size)
            }
            val csv = root.resolve("sorted-$generated.csv")
            val sql = "COPY events TO ${literal(csv)} (FORMAT csv,HEADER,ORDER_BY (v DESC NULLS LAST,id ASC))"
            c.execute(if (generated) render(sql) else sql)
            val csvFile = Files.list(csv).use { it.filter { path -> path.toString().endsWith(".csv") }.toList().single() }
            assertEquals(listOf("7", "2", "1", "10", "6", "8", "4", "5", "3", "9"), Files.readAllLines(csvFile).drop(1).map { it.substringAfterLast(',') })
        }
    }

    @Test
    fun multiplePartitionColumnsSortWithinEachPhysicalFileAndDoNotLeakIntoTheFileSchema() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val directory = root.resolve("parts-$generated")
            val sql = "WITH copied AS (COPY (SELECT region AS area,day,v,id FROM events) TO ${literal(directory)} " +
                "(ORDER_BY (abs(v) DESC NULLS LAST,id ASC),FORMAT parquet,PARTITION_BY (area,day),RETURN_FILES)) SELECT Count,len(Files) FROM copied"
            assertEquals(listOf(listOf("10", "4")), c.rows(if (generated) render(sql) else sql))
            val paths = files(directory)
            assertEquals(4, paths.size)
            val expected = mapOf("area=eu/day=1" to listOf(2,6), "area=eu/day=2" to listOf(7,4),
                "area=us/day=1" to listOf(1,5,10,3), "area=us/day=2" to listOf(8,9))
            for (file in paths) {
                assertEquals(expected[directory.relativize(file.parent).toString()], ids(c, file))
                assertEquals(listOf("v", "id"), c.rows("DESCRIBE SELECT * FROM read_parquet(${literal(file)},hive_partitioning=false)").map { it.first() })
            }
            assertEquals(listOf(listOf("10", "10")), c.rows("SELECT count(*),count(DISTINCT id) FROM read_parquet(${literal(directory.resolve("*/*/*.parquet"))})"))
        }
    }

    @Test
    fun partitioningAloneSupportsQuotedColumnNamesNullKeysAndExplicitPartitionColumns() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET threads=1")
            c.execute("CREATE TABLE t(\"part key\" VARCHAR,day INTEGER,id INTEGER)")
            c.execute("INSERT INTO t VALUES ('x',1,1),('x',2,2),(NULL,1,3),(NULL,1,4)")
            val directory = root.resolve("null-keys-$generated")
            val sql = "COPY t TO ${literal(directory)} (FORMAT parquet,PARTITION_BY (\"part key\",day),WRITE_PARTITION_COLUMNS)"
            c.execute(if (generated) render(sql) else sql)
            assertEquals(3, files(directory).size)
            assertTrue(files(directory).any { "__HIVE_DEFAULT_PARTITION__" in it.toString() })
            assertEquals(listOf(listOf("x","1","1"),listOf("x","2","2"),listOf(null,"1","3"),listOf(null,"1","4")),
                c.rows("SELECT * FROM read_parquet(${literal(directory.resolve("*/*/*.parquet"))},hive_partitioning=false) ORDER BY id"))
        }
    }

    @Test
    fun omittedDefaultsFollowTheExecutingSessionButExplicitLastAndAscSurviveGeneration() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("SET default_order='DESC'")
            c.execute("SET default_null_order='NULLS_FIRST'")
            for ((index, order) in listOf("v,id ASC", "v ASC NULLS LAST,id ASC").withIndex()) {
                val file = root.resolve("defaults-$generated-$index.parquet")
                val sql = "COPY events TO ${literal(file)} (FORMAT parquet,ORDER_BY ($order))"
                c.execute(if (generated) render(sql) else sql)
                val expected = c.rows("SELECT id FROM events ORDER BY $order").map { it.single()!!.toInt() }
                assertEquals(expected, ids(c,file))
                assertEquals(if (index == 0) listOf(3,9,7,2,1,10,6,8,4,5) else listOf(5,4,8,6,1,10,2,7,3,9), expected)
            }
        }
    }

    @Test
    fun bareAndTextualOptionsCollationsAndNativeColumnBindingRemainDistinct() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for ((index, order) in listOf("v", "'v DESC NULLS FIRST,id ASC'", "(v)", "(__copy_input.v)").withIndex()) {
                val file = root.resolve("text-$generated-$index.parquet")
                val sql = "COPY events TO ${literal(file)} (FORMAT parquet,ORDER_BY $order)"
                c.execute(if (generated) render(sql) else sql)
                val rows = ids(c,file)
                val expected = c.rows("SELECT id FROM events ORDER BY ${if (index == 1) "v DESC NULLS FIRST" else "v"},id").map { it.single()!!.toInt() }
                // Terms without a tie-breaker do not promise a stable order among equal keys.
                assertEquals(expected.toSet(), rows.toSet())
                assertEquals(c.rows("SELECT v FROM events ORDER BY ${if (index == 1) "v DESC NULLS FIRST" else "v"}"),
                    c.rows("SELECT v FROM read_parquet(${literal(file)},file_row_number=true) ORDER BY file_row_number"))
            }
            val file = root.resolve("collation-$generated.parquet")
            val sql = "COPY (SELECT * FROM (VALUES ('B'),('a'),('C')) t(s)) TO ${literal(file)} (FORMAT parquet,ORDER_BY (s COLLATE nocase DESC))"
            c.execute(if (generated) render(sql) else sql)
            assertEquals(listOf(listOf("C"),listOf("B"),listOf("a")), c.rows("SELECT s FROM read_parquet(${literal(file)},file_row_number=true) ORDER BY file_row_number"))
            val parts = root.resolve("text-partitions-$generated")
            val partition = "COPY events TO ${literal(parts)} (FORMAT parquet,PARTITION_BY ('region','day'),ORDER_BY (id))"
            c.execute(if (generated) render(partition) else partition)
            assertEquals(4, files(parts).size)
            val qualified = root.resolve("qualified-partitions-$generated")
            val qualifiedSql = "COPY events TO ${literal(qualified)} (FORMAT parquet,PARTITION_BY events.region,ORDER_BY (id))"
            c.execute(if (generated) render(qualifiedSql) else qualifiedSql)
            assertEquals(2,files(qualified).size)
        }
    }

    @Test
    fun parserAndBinderErrorsDoNotOpenDestinationsOrTurnOrdinalsIntoColumnNames() = scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for ((index, option) in listOf("ORDER_BY v DESC", "PARTITION_BY (region DESC)",
                "ORDER_BY (v),ORDER_BY (id)", "PARTITION_BY region,PARTITIONED_BY day").withIndex()) {
                val file = root.resolve("parser-$index")
                val sql = "COPY events TO ${literal(file)} (FORMAT parquet,$option)"
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                assertFalse(Files.exists(file))
            }
            for ((index, option) in listOf("PARTITION_BY missing", "PARTITION_BY (day+1)", "ORDER_BY (missing)",
                "ORDER_BY (1)", "ORDER_BY (events.v)", "ORDER_BY (v+(SELECT 0))", "ORDER_BY abs(v)").withIndex()) {
                val file = root.resolve("binder-$index")
                val sql = "COPY events TO ${literal(file)} (FORMAT parquet,$option)"
                // COPY parses, but json_serialize_sql deliberately cannot serialize it.
                assertEquals("not implemented",assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                for (text in listOf(sql,render(sql))) {
                    assertTrue(assertFailsWith<SQLException> { c.execute(text) }.message.orEmpty().contains("Binder Error"), text)
                    assertFalse(Files.exists(file))
                }
            }
            val empty = root.resolve("empty-order")
            val emptySql = "COPY events TO ${literal(empty)} (FORMAT parquet,ORDER_BY ())"
            assertEquals("not implemented",assertIs<SerializedSql.Failed>(c.serializeSql(emptySql)).type)
            assertTrue(assertFailsWith<SQLException> { c.execute(emptySql) }.message.orEmpty().contains("Parser Error"))
            assertFalse(Files.exists(empty))
        }
    }

    @Test
    fun preparedBindingsStayInOrderAndNativeSourceAndSortEvaluationCountsArePreserved() = scratch { root ->
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("CREATE SEQUENCE copy_seq")
            c.execute("CREATE SEQUENCE sort_seq")
            val sql = "WITH copied AS (COPY (SELECT region,day,v+? AS value,id,nextval('copy_seq') AS invocation FROM events WHERE id>?) TO (?) " +
                "(FORMAT parquet,PARTITION_BY (region,day),ORDER_BY (value*? ASC NULLS LAST,id ASC,nextval('sort_seq')),RETURN_FILES)) SELECT Count,len(Files) FROM copied"
            c.prepareStatement(if (generated) render(sql) else sql).use { p ->
                assertEquals(4,p.parameterMetaData.parameterCount)
                for (iteration in 1..2) {
                    val directory = root.resolve("prepared-$generated-$iteration")
                    p.setInt(1,100); p.setInt(2,2); p.setString(3,directory.toString())
                    p.setInt(4,-1)
                    p.executeQuery().use { r -> assertTrue(r.next()); assertEquals(8,r.getInt(1)); assertEquals(4,r.getInt(2)); assertFalse(r.next()) }
                    assertEquals(listOf(listOf((iteration*8).toString())), c.rows("SELECT currval('copy_seq')"))
                    // Pinned native partitioned COPY evaluates its volatile sorting key twice
                    // per row in this plan. Do not promise once-only ORDER_BY evaluation.
                    assertEquals(listOf(listOf((iteration*16).toString())), c.rows("SELECT currval('sort_seq')"))
                    assertEquals(listOf(listOf("8","8")), c.rows("SELECT count(*),count(DISTINCT invocation) FROM read_parquet(${literal(directory.resolve("*/*/*.parquet"))})"))
                    for (file in files(directory)) {
                        val rows = c.rows("SELECT value,id FROM read_parquet(${literal(file)},hive_partitioning=false,file_row_number=true) ORDER BY file_row_number")
                        val expected = c.rows("SELECT value,id FROM read_parquet(${literal(file)},hive_partitioning=false) ORDER BY value DESC NULLS LAST,id ASC")
                        assertEquals(expected,rows)
                    }
                }
            }
        }
    }

    @Test
    fun rotationAndParallelPartitionWritesSortEachFileWithoutPromisingGlobalFileOrder() = scratch { root ->
        for (generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET threads=4")
            val directory = root.resolve("rotated-$generated")
            val sql = "COPY (SELECT i%3 AS p,(i*7919)%24000 AS v,i AS id FROM range(24000)t(i)) TO ${literal(directory)} " +
                "(FORMAT parquet,PARTITION_BY p,ORDER_BY (v DESC),ROW_GROUP_SIZE 2048,ROW_GROUPS_PER_FILE 1)"
            c.execute(if (generated) render(sql) else sql)
            assertTrue(files(directory).size > 3)
            val read = "read_parquet(${literal(directory.resolve("*/*.parquet"))},filename=true,file_row_number=true)"
            assertEquals(listOf(listOf("0","24000")),c.rows("SELECT count(*) FILTER(WHERE previous<v),count(*) FROM " +
                "(SELECT v,lag(v) OVER(PARTITION BY filename ORDER BY file_row_number) AS previous FROM $read)"))
            assertEquals(listOf(listOf("24000","24000")),c.rows("SELECT count(*),count(DISTINCT id) FROM $read"))
        }
    }

    @Test
    fun staticAnalysisAndNativeParseOnlyValidationNeitherBindOptionsNorCreateFiles() = scratch { root ->
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            fixture(c)
            c.execute("CREATE SEQUENCE audit_seq")
            val directory = root.resolve("must-not-exist")
            val sql = "COPY (SELECT region,day,nextval('audit_seq') AS value FROM events) TO ${literal(directory)} (FORMAT parquet,PARTITION_BY (region,day),ORDER_BY (value DESC))"
            assertIs<Copy>(Dialects.DUCKDB.parseOne(sql))
            assertTrue(RelationScope.analyze(sql,"duckdb",RelationFacts()).parsed)
            SqlFragment(sql,"duckdb").toExecutable("duckdb")
            val verifier = ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            val result = verifier.verify(sql)
            assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            assertFalse(Files.exists(directory))
            assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
        }
    }
}
