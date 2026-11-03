package dev.brikk.house.sql.verify

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

internal class DuckdbParquetWriteResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun quote(path:Path)="'${path.toAbsolutePath().toString().replace("'","''")}'"
    private fun Connection.exec(sql:String)=createStatement().use { s -> if(s.execute(sql))s.resultSet.use { while(it.next()){} };Unit }
    private fun Connection.rows(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun scratch(block:(Path)->Unit) {
        val root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),"opencode")),"duckdb-parquet-write-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun codecsLevelsDictionaryBloomFieldIdsAndMetadataWriteRealParquetFiles()=scratch { root ->
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=1")
            for(codec in listOf("uncompressed","snappy","gzip","zstd","brotli","lz4","lz4_raw")) {
                val file=root.resolve("$codec-$generated.parquet")
                val sql="COPY (SELECT i,CAST(i%7 AS VARCHAR) AS s FROM range(4096)t(i)) TO ${quote(file)} (FORMAT parquet,COMPRESSION $codec${if(codec=="zstd")",COMPRESSION_LEVEL 1"else""},PARQUET_VERSION V2,FIELD_IDS {i:0,s:1},KV_METADATA {source:'Brikk',n:42},DICTIONARY_SIZE_LIMIT 0,STRING_DICTIONARY_PAGE_SIZE_LIMIT 4096,WRITE_BLOOM_FILTER false,BLOOM_FILTER_FALSE_POSITIVE_RATIO 0.1,ROW_GROUP_SIZE 2048)"
                c.exec(if(generated)render(sql)else sql)
                assertEquals(listOf(listOf("4096","8386560")),c.rows("SELECT count(*),sum(i) FROM read_parquet(${quote(file)})"))
                val compression=c.rows("SELECT DISTINCT compression FROM parquet_metadata(${quote(file)})").map { it.single() }.toSet()
                assertEquals(setOf(if(codec.startsWith("lz4"))"LZ4_RAW" else codec.uppercase()),compression)
                assertEquals(listOf(listOf("i","0"),listOf("s","1")),c.rows("SELECT name,field_id FROM parquet_schema(${quote(file)}) WHERE name IN ('i','s') ORDER BY name"))
                assertEquals(listOf(listOf("Brikk")),c.rows("SELECT CAST(value AS VARCHAR) FROM parquet_kv_metadata(${quote(file)}) WHERE CAST(key AS VARCHAR)='source'"))
            }
        }
    }
    @Test fun pageSizeCapChangesPhysicalPageOverheadWithoutInventingAHardPerPageBound()=scratch { root ->
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=1")
            val small=root.resolve("small-$generated.parquet");val normal=root.resolve("normal-$generated.parquet")
            for((file,extra)in listOf(small to ",DATA_PAGE_SIZE_LIMIT 4096",normal to "")) {
                val sql="COPY (SELECT i,'val_'||CAST(i%400 AS VARCHAR) s FROM range(100000)t(i)) TO ${quote(file)} (FORMAT parquet,COMPRESSION uncompressed$extra)"
                c.exec(if(generated)render(sql)else sql)
                assertEquals(listOf(listOf("100000","4999950000","0")),c.rows("SELECT count(*),sum(i),count(*) FILTER(WHERE s<>'val_'||CAST(i%400 AS VARCHAR)) FROM read_parquet(${quote(file)})"))
            }
            assertEquals(listOf(listOf("true")),c.rows("SELECT (SELECT sum(total_compressed_size) FROM parquet_metadata(${quote(small)}))>(SELECT sum(total_compressed_size) FROM parquet_metadata(${quote(normal)}))"))
        }
    }
    @Test fun remainingWriterShapesAliasesAndGeoParquetModesAreNativeOptionsNotReadArguments()=scratch { root ->
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=1");c.exec("SET preserve_insertion_order=false")
            for((index,options)in listOf("CODEC zstd,FIELD_IDS auto,CHUNK_SIZE 2048",
                "ROW_GROUP_SIZE_BYTES '16KB'","ROW_GROUP_SIZE 2048,ROW_GROUPS_PER_FILE 1").withIndex()) {
                val file=root.resolve("shapes-$generated-$index.parquet")
                val sql="COPY (SELECT i FROM range(4096)t(i)) TO ${quote(file)} (FORMAT parquet,$options)"
                c.exec(if(generated)render(sql)else sql)
                assertEquals(listOf(listOf("4096","8386560")),c.rows("SELECT count(*),sum(i) FROM read_parquet(${quote(file)})"))
            }
            for(mode in listOf("NONE","V1","V2","BOTH")) {
                val file=root.resolve("geo-$generated-$mode.parquet")
                val sql="COPY (SELECT CAST('POINT(1 2)' AS GEOMETRY) geom) TO ${quote(file)} (FORMAT parquet,GEOPARQUET_VERSION '$mode')"
                c.exec(if(generated)render(sql)else sql)
                assertEquals(listOf(listOf("POINT (1 2)")),c.rows("SELECT ST_AsText(CAST(geom AS GEOMETRY)) FROM read_parquet(${quote(file)})"))
            }
            val encrypted=root.resolve("must-not-encrypt.parquet")
            val sql="COPY (SELECT 42 AS i) TO ${quote(encrypted)} (FORMAT parquet,ENCRYPTION_CONFIG {footer_key:'not-defined'})"
            val error=assertFailsWith<SQLException> { c.exec(if(generated)render(sql)else sql) }
            assertTrue("key" in error.message.orEmpty().lowercase(),error.message)
            assertFalse(Files.exists(encrypted)) // No real encryption key is provisioned by this syntax audit.
        }
    }
    @Test fun variantAndShreddedVariantStayVariantAndTimestampOptionsChangeNativePhysicalMetadata()=scratch { root ->
        for(generated in listOf(false,true))DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for((index,extra)in listOf("",",SHREDDING {v:'STRUCT(a INTEGER,b VARCHAR[])'}").withIndex()) {
                val file=root.resolve("variant-$generated-$index.parquet")
                val sql="COPY (SELECT CAST({'a':42,'b':['x','y']} AS VARIANT) v) TO ${quote(file)} (FORMAT parquet$extra)"
                c.exec(if(generated)render(sql)else sql)
                assertEquals(listOf(listOf("VARIANT","42","[x, y]")),c.rows("SELECT typeof(v),CAST(v.a AS INTEGER),CAST(v.b AS VARCHAR[]) FROM read_parquet(${quote(file)})"))
            }
            for(flag in listOf("AUTO","TRUE","FALSE")) {
                val file=root.resolve("utc-$generated-$flag.parquet")
                val sql="COPY (SELECT TIMESTAMP '2024-01-02 03:04:05' d) TO ${quote(file)} (FORMAT parquet,TIMESTAMP_IS_ADJUSTED_TO_UTC '$flag')"
                c.exec(if(generated)render(sql)else sql)
                val expected=if(flag=="TRUE")"1" else "0"
                assertEquals(listOf(listOf("true")),c.rows("SELECT logical_type LIKE '%isAdjustedToUTC=$expected%' FROM parquet_schema(${quote(file)}) WHERE name='d'"))
            }
            val file=root.resolve("int96-$generated.parquet")
            val sql="COPY (SELECT TIMESTAMP '2024-01-02 03:04:05.123456' d) TO ${quote(file)} (FORMAT parquet,WRITE_TIMESTAMP_AS_INT96 true)"
            c.exec(if(generated)render(sql)else sql)
            assertEquals(listOf(listOf("INT96",null,null)),c.rows("SELECT type,logical_type,converted_type FROM parquet_schema(${quote(file)}) WHERE name='d'"))
            assertEquals(listOf(listOf("2024-01-02 03:04:05.123456")),c.rows("SELECT CAST(d AS VARCHAR) FROM read_parquet(${quote(file)})"))
        }
    }
    @Test fun unsupportedValuesAndReadOnlyOptionsRemainBinderErrorsBeforeFilesAreWritten()=scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for((index,option)in listOf("DATA_PAGE_SIZE_LIMIT 0","DATA_PAGE_SIZE_LIMIT 104857601","DATA_PAGE_SIZE_LIMIT '1MB'",
                "SHREDDING 'auto'","SHREDDING 'INTEGER'","SHREDDING {missing:'INTEGER'}","SHREDDING {v:42}",
                "COMPRESSION bogus","COMPRESSION snappy,COMPRESSION_LEVEL 1","DICTIONARY_SIZE_LIMIT -1","STRING_DICTIONARY_PAGE_SIZE_LIMIT 0",
                "BLOOM_FILTER_FALSE_POSITIVE_RATIO 0","TIMESTAMP_IS_ADJUSTED_TO_UTC 'bogus'","PARQUET_VERSION V3","GEOPARQUET_VERSION V9",
                "BINARY_AS_STRING true","FILE_ROW_NUMBER true","INT96_AS struct").withIndex()) {
                val file=root.resolve("invalid-$index.parquet")
                val sql="COPY (SELECT CAST(42 AS VARIANT) v) TO ${quote(file)} (FORMAT parquet,$option)"
                // COPY JSON serialization is unavailable, but these options reach native binding.
                val original=assertFailsWith<SQLException>(option) { c.exec(sql) }
                val regenerated=assertFailsWith<SQLException>(option) { c.exec(render(sql)) }
                assertEquals(original.message!!.substringBefore(':').trim(),regenerated.message!!.substringBefore(':').trim(),option)
                assertFalse(Files.exists(file),option)
            }
        }
    }
    @Test fun preparedWriterParametersExecuteOnceAndEditorValidationNeverWritesOrBindsTheOptions()=scratch { root ->
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            val file=root.resolve("prepared.parquet")
            val sql="COPY (SELECT CAST(? AS BIGINT) x) TO (?) (FORMAT parquet,COMPRESSION ?,COMPRESSION_LEVEL ?,DATA_PAGE_SIZE_LIMIT ?)"
            c.prepareStatement(render(sql)).use { p ->
                assertEquals(5,p.parameterMetaData.parameterCount);p.setLong(1,42);p.setString(2,file.toString());p.setString(3,"zstd");p.setLong(4,1);p.setLong(5,4096)
                p.execute() // Top-level COPY can expose only an update count. Never retry it via executeQuery.
            }
            assertEquals(listOf(listOf("42")),c.rows("SELECT x FROM read_parquet(${quote(file)})"))
            c.exec("CREATE SEQUENCE audit_seq")
            val never=root.resolve("not-written.parquet")
            val audit="COPY (SELECT nextval('audit_seq') AS x) TO ${quote(never)} (FORMAT parquet,DATA_PAGE_SIZE_LIMIT nextval('audit_seq'))"
            RelationScope.analyze(audit,"duckdb",RelationFacts());SqlFragment(audit,"duckdb").toExecutable("duckdb")
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) });val result=verifier.verify(audit)
            assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            assertFalse(Files.exists(never));assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
        }
    }
}
