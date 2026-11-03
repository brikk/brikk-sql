package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbNativeIcuTest {
    @Test fun zoneAndCollationSyntaxSurvivesCopySerdeAndBindingWithoutHostNormalization() {
        for(sql in listOf("SELECT TIMESTAMP_NS '2024-01-01 00:00:00.123456789' AT TIME ZONE 'America/New_York' AS t",
            "SELECT CAST('2024-01-01 00:00:00.123456789+00' AS TIMESTAMPTZ_NS) AT TIME ZONE 'UTC' AS t",
            "SELECT list_contains(['héllo'],'HELLO' COLLATE \"noaccent.nocase\")",
            "SELECT list_position(['A' COLLATE nocase],'a')")) {
            val tree=parseDuckdbNativeOne(sql)
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
            assertEquals(generated,parseDuckdbNativeOne(generated).sql("duckdb"))
            for(target in listOf("trino","postgres","doris","clickhouse","bigquery","mysql"))
                assertFailsWith<UnsupportedError> { SqlFragment(sql,"duckdb").toExecutable(target) }
        }
        val sql="SELECT CAST(? AS TIMESTAMP_NS) AT TIME ZONE ?"
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(2,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
    }
    @Test fun nativeZoneResultOverloadsDoNotCollapseNanosecondsIntoMicrosecondTimestamptz() {
        for((body,type)in listOf("DATE '2024-01-01'" to "TIMESTAMPTZ_NS", "TIMESTAMP '2024-01-01'" to "TIMESTAMPTZ",
            "TIMESTAMP_NS '2024-01-01 00:00:00.123456789'" to "TIMESTAMPTZ_NS",
            "TIMESTAMPTZ '2024-01-01+00'" to "TIMESTAMP",
            "CAST('2024-01-01 00:00:00.123456789+00' AS TIMESTAMPTZ_NS)" to "TIMESTAMP_NS")) {
            assertEquals(type,SqlFragment("SELECT $body AT TIME ZONE 'UTC' AS t","duckdb").outputShape().columns.single().type,body)
        }
    }
}
