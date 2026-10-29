package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbVariantResultTest {
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.check(sql: String, expected: List<List<String?>>, qualified: Boolean = false) {
        assertEquals(expected, rows(sql), "native: $sql")
        val tree = Dialects.DUCKDB.parseOne(sql)
        val rendered = if (qualified) qualify(tree, dialect = Dialects.DUCKDB,
            schema = mapOf("events" to mapOf("id" to "INT", "payload" to "VARIANT"))) else tree
        assertEquals(expected, rows(rendered.sql("duckdb")), "generated: $sql")
    }
    private fun fixture(c: Connection) {
        c.execute("CREATE TABLE events(id INT,payload VARIANT)")
        c.execute("INSERT INTO events VALUES (1,'{\"Name\":\"Ada\",\"name\":\"lower\",\"a\":[10,20],\"n\":null}'::JSON::VARIANT), (2,'{\"Name\":\"Bob\"}'::JSON::VARIANT), (3,NULL)")
    }
    private fun scratch(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
        val directory = Files.createTempDirectory(parent, "duckdb-variant-")
        try { block(directory) } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test
    fun varcharAndJsonCastsProduceDifferentVariantValuesAndRuntimeTypes() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT variant_type('42'::VARIANT),variant_type('42'::JSON::VARIANT),variant_type(42::VARIANT),to_json('{\"x\":1}'::VARIANT),to_json('{\"x\":1}'::JSON::VARIANT)",
                listOf(listOf("VARCHAR", "UINT64", "INT32", "\"{\\\"x\\\":1}\"", "{\"x\":1}")))
            c.check("SELECT TRY_CAST('not a number'::VARIANT AS INT)", listOf(listOf(null)))
            assertFailsWith<SQLException> { c.rows("SELECT CAST('not a number'::VARIANT AS INT)") }
        }
    }

    @Test
    fun nativeAndQualifiedFieldsKeepCaseOneBasedIndexesAndLiteralFieldNames() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for (qualified in listOf(false, true)) {
                c.check("SELECT e.payload.Name::VARCHAR,e.payload.name::VARCHAR,e.payload.NAME::VARCHAR,e.payload.a[2]::INT FROM events e WHERE id=1",
                    listOf(listOf("Ada", "lower", null, "20")), qualified)
                c.check("SELECT (payload::VARIANT).Name::VARCHAR,variant_extract(payload,'Name')::VARCHAR,variant_extract(payload,'a[2]')::VARCHAR FROM events WHERE id=1",
                    listOf(listOf("Ada", "Ada", null)), qualified)
            }
            c.check("SELECT ([10,20]::VARIANT)[1]::INT,variant_extract([10,20]::VARIANT,2)::INT", listOf(listOf("10", "20")))
            c.check("WITH q AS (SELECT CAST(payload AS VARIANT) AS p FROM events WHERE id=1) SELECT p.Name::VARCHAR FROM q", listOf(listOf("Ada")), qualified = true)
            assertFailsWith<SQLException> { c.rows("SELECT ([10,20]::VARIANT)[0]") }
        }
    }

    @Test
    fun missingAndVariantNullShareSqlNullTestsButRemainDistinctByExistenceAndRuntimeType() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.check("SELECT payload.n IS NULL,payload.missing IS NULL,variant_exists(payload,'n'),variant_exists(payload,'missing'),variant_type(payload,'n'),variant_type(payload,'missing') FROM events WHERE id=1",
                listOf(listOf("true", "true", "true", "false", "VARIANT_NULL", null)), qualified = true)
            c.check("SELECT variant_type(NULL::VARIANT),variant_typeof(NULL::VARIANT),to_json(NULL::VARIANT)", listOf(listOf(null, "VARIANT_NULL", null)))
        }
    }

    @Test
    fun listPathOverloadsContainmentAndNumericComparisonsUseVariantRatherThanJsonRules() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.check("SELECT variant_type(payload,['Name','missing']),variant_exists(payload,['Name','NAME']),variant_keys(payload,['a','missing']),variant_extract_string(payload,['Name','missing']) FROM events WHERE id=1",
                listOf(listOf("[VARCHAR, NULL]", "[true, false]", "[[], NULL]", "[Ada, NULL]")))
            c.check("SELECT variant_contains([1,2]::VARIANT,1::VARIANT),1::VARIANT=1.0::VARIANT,1::VARIANT='1'::VARIANT,9007199254740992::BIGINT::VARIANT=9007199254740993::BIGINT::VARIANT",
                listOf(listOf("true", "true", "false", "false")))
            c.check("SELECT i FROM range(-2,2) t(i) ORDER BY i::VARIANT", (-2..1).map { listOf(it.toString()) })
        }
    }

    @Test
    fun aggregateObjectRejectsDuplicateAndNullKeysAndArrayMacroHasNativeAggregateLimits() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT to_json(variant_group_object(k,v::VARIANT)) FROM (VALUES ('a',1),('b',NULL)) t(k,v)", listOf(listOf("{\"a\":1,\"b\":null}")))
            c.check("SELECT to_json(variant_group_array(i)) FROM (SELECT i FROM (VALUES (2),(NULL),(1)) t(i) ORDER BY i NULLS LAST)", listOf(listOf("[1,2,null]")))
            c.check("SELECT variant_group_array(i) FROM (SELECT 1 i WHERE false)", listOf(listOf(null)))
            for (sql in listOf("SELECT variant_group_object(k,v::VARIANT) FROM (VALUES ('a',1),('a',2)) t(k,v)",
                "SELECT variant_group_object(NULL,1::VARIANT)", "SELECT variant_group_array(i ORDER BY i) FROM range(2) t(i)")) {
                assertFailsWith<SQLException>(sql) { c.rows(sql) }
            }
        }
    }

    @Test
    fun checkpointAndParquetRoundTripsPreserveCaseNullMissingAndUnshreddedOverlay() = scratch { directory ->
        val database = directory.resolve("state.duckdb")
        val url = "jdbc:duckdb:$database"
        val read = "SELECT id,payload.Name::VARCHAR,variant_exists(payload,'n'),variant_exists(payload,'missing'),payload.a[2]::INT FROM events ORDER BY id"
        val expected = DriverManager.getConnection(url).use { c ->
            c.execute("SET variant_minimum_shredding_size=0")
            fixture(c)
            val rows = c.rows(read)
            c.execute("CHECKPOINT")
            assertEquals(rows, c.rows(read))
            rows
        }
        DriverManager.getConnection(url).use { c ->
            assertEquals(expected, c.rows(read))
            for ((index, options) in listOf("", ",SHREDDING {'payload':'STRUCT(\"Name\" VARCHAR,n INTEGER)'}").withIndex()) {
                val file = directory.resolve("data-$index.parquet").toString().replace("'", "''")
                c.execute(Dialects.DUCKDB.parseOne("COPY events TO '$file' (FORMAT PARQUET$options)").sql("duckdb"))
                assertEquals(expected, c.rows(read.replace("FROM events", "FROM read_parquet('$file')")))
                assertEquals(listOf(listOf("VARIANT")), c.rows("SELECT typeof(payload) FROM read_parquet('$file') LIMIT 1"))
                assertTrue(Files.isRegularFile(directory.resolve("data-$index.parquet")))
            }
        }
    }
}
