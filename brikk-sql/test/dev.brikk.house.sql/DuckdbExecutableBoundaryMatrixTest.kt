package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbExecutableBoundaryMatrixTest {
    @Test fun everyRegisteredForeignTargetRefusesUnqualifiedNativeFeatureSemantics() {
        val cases = linkedMapOf(
            "nearest" to "SELECT * FROM q JOIN p NEAREST 2 BY DISTANCE abs(q.x-p.x)",
            "using-key" to "WITH RECURSIVE t(x) USING KEY(x) AS (SELECT 1 UNION SELECT x+1 FROM t WHERE x<2) SELECT * FROM t",
            "secure-view" to "CREATE SECURE VIEW v AS SELECT 1",
            "recursive-view" to "CREATE RECURSIVE VIEW v(x) AS SELECT 1 UNION SELECT x+1 FROM v WHERE x<2",
            "trigger" to "CREATE TRIGGER tr AFTER INSERT ON t INSERT INTO log VALUES(1)",
            "connect" to "CONNECT 'quack:localhost' (TOKEN 'synthetic')",
            "disconnect" to "DISCONNECT",
            "variable" to "SET VARIABLE x=1",
            "prepare" to "PREPARE p AS SELECT 1",
            "variant" to "SELECT CAST(1 AS VARIANT)",
            "json-mutation" to "SELECT json_set('{}','$.x','2')",
            "grouping-unnest" to "SELECT unnest([1,2]) AS x,count(*) FROM range(1) GROUP BY x",
            "dml-cte" to "WITH changed AS (UPDATE t SET x=1 RETURNING *) SELECT * FROM changed",
            "merge" to "MERGE INTO t USING s USING(id) WHEN MATCHED THEN UPDATE RETURNING merge_action,*",
            "overlay" to "SELECT overlay('abcdef','xy',2,3)",
            "setval" to "SELECT setval('s',4)",
            "path" to "SELECT path_join('a','b')",
            "regex-options" to "SELECT regexp_matches('Ab','a','i')",
            "regex-full-options" to "SELECT regexp_full_match('Ab','ab','i')",
            "regex-extract-options" to "SELECT regexp_extract('Ab','(a)',1,'i')",
            "regex-extract-all-options" to "SELECT regexp_extract_all('Ab','(a)',1,'i')",
            "regex-replace-options" to "SELECT regexp_replace('Aa','a','x','ig')",
            "regex-operator" to "SELECT 'abc' ~ 'b'",
            "factorial" to "SELECT 3!",
            "binary" to "SELECT X'0041',B'0010'",
            "division" to "SELECT 7//2",
            "timezone" to "SELECT TIMESTAMP '2024-01-01' AT TIME ZONE 'UTC'",
            "collation" to "SELECT 'A' COLLATE nocase='a'",
            "attach-storage" to "ATTACH 'never-opened.db' AS db (STORAGE_VERSION 'v1.5.0')",
            "snapshot" to "SELECT * FROM t AT(VERSION=>3)",
            "extension" to "INSTALL AND LOAD x FROM community",
            "resource" to "CREATE EXTERNAL RESOURCE 'fixture@local' AS r",
            "explain" to "EXPLAIN(FORMAT JSON) SELECT 1",
            "show" to "SHOW threads",
            "checkpoint" to "CHECKPOINT main",
            "copy-layout" to "COPY t TO 'never-written' (PARTITION_BY(x))",
            "parquet-schema" to "SELECT * FROM read_parquet('never-read',schema:=MAP {1:{name:'x',type:'INTEGER',default_value:0}})",
            "geojson" to "COPY t TO 'never-written.geojson' (FORMAT GEOJSON)",
            "settings" to "SET identifier_case='lower_case'",
            "null-ctas" to "CREATE TABLE t AS SELECT NULL AS x",
            "deep-name" to "SELECT * FROM cat.nested.schema.table_name",
            "date-boundaries" to "SELECT date_diff('month',DATE '2026-01-31',DATE '2026-02-01')",
            "date-trunc-type" to "SELECT date_trunc('day',DATE '2026-01-31')",
        )
        val targets = Dialects.NAMES.map(Dialects::forName).distinctBy { it.name }.filter { it.name != "duckdb" }
        val failures = mutableListOf<String>()
        for ((feature, sql) in cases) {
            // Parsing is asserted independently: parser errors cannot satisfy a refusal.
            val fragment = SqlFragment(sql, "duckdb")
            assertNotNull(fragment.ast, feature)
            for (target in targets) try {
                val result = fragment.toExecutable(target.name.ifEmpty { "sqlglot" })
                failures += "$feature -> ${target.name}: emitted ${result.sql}"
            } catch (e: UnsupportedError) {
                assertTrue(e.message.orEmpty().isNotBlank(), "$feature -> ${target.name}")
            } catch (e: Exception) { failures += "$feature -> ${target.name}: unexpected ${e::class.simpleName}: ${e.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        println("DDB20-055 ${cases.size} feature families x ${targets.size} target dialects explicitly refuse")
    }
    @Test fun reviewedLossyUpstreamCandidatesRefuseRatherThanSilentlyDroppingMeaning() {
        val cases = listOf(
            "duckdb" to "SELECT INTERVAL '1.5 days'",
            "duckdb" to "SELECT json_extract('{}','$.x','$.y')",
            "postgres" to "SELECT U&'d\\0061ta'",
            "spark" to "SELECT array_insert(array(1,2),-1,3)",
            "spark" to "SELECT array_sort(array(2,1))",
            "trino" to "SELECT array_join(ARRAY[1,NULL],',','missing')",
            "trino" to "SELECT date_diff('month',DATE '2026-01-31',DATE '2026-02-01')",
        )
        for ((source, sql) in cases) {
            val fragment = SqlFragment(sql, source)
            assertNotNull(fragment.ast, sql)
            assertFailsWith<UnsupportedError>(sql) { fragment.toExecutable("duckdb") }
        }
    }
    @Test fun programmaticSqlJsonOptionsRefuseBeforeExecutableRendering() {
        for (node in listOf(JSONArray(args("expressions" to listOf(Literal.number("1")), "null_handling" to "ABSENT ON NULL")),
            JSONObject(args("null_handling" to "ABSENT ON NULL")),
            Base64Encode(args("this" to Literal.string("data"), "alphabet" to Literal.string("/+"))))) {
            val generator = Dialects.DUCKDB.generator(sourceDialect = "trino").apply { forExecution = true }
            assertFailsWith<UnsupportedError> { generator.generate(node) }
        }
    }
    @Test fun ordinarySqlAndSameTargetNativeFeaturesAreNotBlanketDisabled() {
        for (target in Dialects.NAMES.map(Dialects::forName).distinctBy { it.name }) {
            val result = SqlFragment("SELECT CAST(2 AS INTEGER)+1 AS x", "duckdb").toExecutable(target.name.ifEmpty { "sqlglot" })
            assertTrue(result.unsupportedMessages.isEmpty(), target.name)
        }
        assertEquals(1, SqlFragment("SELECT CAST(? AS INTEGER)+1 AS x", "duckdb").toExecutable("duckdb").sql.count { it == '?' })
        for (sql in listOf("SELECT 'abc' ~ 'b'", "SELECT X'0041'", "SELECT 7//2", "SELECT CAST(NULL AS INTEGER)", "SELECT overlay('abcdef','xy',2,3)"))
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty(), sql)
    }
}
