package dev.brikk.house.sql
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbParquetWriteOptionsTest {
    @Test fun writerValueShapesPreserveCopySerdeOptionOrderAndVariantRatherThanJson() {
        for(options in listOf("COMPRESSION zstd,COMPRESSION_LEVEL 1,ROW_GROUP_SIZE 2048,DATA_PAGE_SIZE_LIMIT 1024",
            "PARQUET_VERSION V2,DICTIONARY_SIZE_LIMIT 0,WRITE_BLOOM_FILTER false,BLOOM_FILTER_FALSE_POSITIVE_RATIO 0.1",
            "FIELD_IDS {v:0},KV_METADATA {source:'Brikk',n:42},SHREDDING {v:'STRUCT(a INTEGER)'}",
            "WRITE_TIMESTAMP_AS_INT96 true,TIMESTAMP_IS_ADJUSTED_TO_UTC 'TRUE',GEOPARQUET_VERSION NONE")) {
            val sql="COPY (SELECT CAST({'a':42} AS VARIANT) AS v) TO 'never-written.parquet' (FORMAT parquet,$options)"
            val tree=assertIs<Copy>(Dialects.DUCKDB.parseOne(sql))
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val rendered=tree.sql("duckdb");assertEquals(rendered,Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            assertTrue("VARIANT" in rendered);assertFalse(" AS JSON" in rendered)
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
    }
    @Test fun bindOrderAndInvalidNativeOptionsAreNotRewrittenOrSilentlyRemoved() {
        val sql="COPY (SELECT ? AS x) TO (?) (FORMAT parquet,COMPRESSION ?,COMPRESSION_LEVEL ?,DATA_PAGE_SIZE_LIMIT ?)"
        assertEquals(sql.indices.filter { sql[it]=='?' },dev.brikk.house.sql.shape.parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(5,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
        val invalid="COPY (SELECT 42 AS x) TO 'none.parquet' (FORMAT parquet,DATA_PAGE_SIZE_LIMIT 'not-bytes',SHREDDING 'auto')"
        assertTrue("not-bytes" in SqlFragment(invalid,"duckdb").toExecutable("duckdb").sql)
        assertTrue("-1" in SqlFragment("COPY (SELECT 42 AS x) TO 'none.parquet' (FORMAT parquet,DICTIONARY_SIZE_LIMIT -1)","duckdb").toExecutable("duckdb").sql)
        for(target in listOf("postgres","trino","doris","clickhouse","spark"))
            assertFailsWith<UnsupportedError> { SqlFragment(invalid,"duckdb").toExecutable(target) }
    }
}
