package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.metadata.SPICE_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.StatementEffect
import dev.brikk.house.sql.shape.statementEffect
import kotlin.test.*

class SpiceDialectTest {
    private fun roundTrip(source: kotlin.String): Expression {
        val ast = parseOne(source, "spice")
        assertFalse(ast is Command, source)
        val rendered = ast.sql("spice")
        assertEquals(ast, parseOne(rendered, "spice"), source)
        val loaded = Serde.load(Serde.dump(ast)) as Expression
        assertEquals(rendered, loaded.sql("spice"), source)
        return ast
    }

    @Test
    fun registryCatalogAndNamePolicyAreSeparateFromDatafusion() {
        assertIs<SpiceDialect>(Dialects.forName("SPICE"))
        assertNotSame(Dialects.DATAFUSION, Dialects.SPICE)
        assertSame(SPICE_FUNCTION_CATALOG, Dialects.SPICE.functionCatalog)
        val id = Identifier(args("this" to "MiXeD", "quoted" to false))
        assertEquals("MiXeD", Dialects.SPICE.normalizeIdentifier(id.copy()).name)
        assertEquals("mixed", Dialects.DATAFUSION.normalizeIdentifier(id.copy()).name)
    }

    @Test
    fun nativeOperatorsHaveTheirSpiceMeaning() {
        val spice = roundTrip("SELECT 5 # 3, 5 ^ 3")
        assertNotNull(spice.find<BitwiseXor>())
        assertNotNull(spice.find<Pow>())
        assertIs<BitwiseXor>(parseOne("SELECT 5 ^ 3", "datafusion").expressionsArg.single())
        assertEquals("SELECT 5 # 3, 5 ^ 3", spice.sql("spice"))
        assertEquals("SELECT power(2, power(3, 2))", roundTrip("SELECT power(2, power(3, 2))").sql("spice"))
        roundTrip("SELECT '`literal`', \"a`b\" FROM t")
    }

    @Test
    fun jsonOperatorsRetainRawKeysAndIndexes() {
        val ast = roundTrip("SELECT '{\"a\":[7]}' -> 'a' ->> 0")
        assertNotNull(ast.find<JSONExtract>())
        assertNotNull(ast.find<JSONExtractScalar>())
        assertNull(ast.find<JSONPath>())
        assertEquals("SELECT '{\"a\":[7]}' -> 'a' ->> 0", ast.sql("spice"))
        roundTrip("SELECT doc ->> key FROM t")
    }

    @Test
    fun searchAndRerankCallsKeepAllArgumentsWithoutBigqueryNodes() {
        for (sql in listOf(
            "SELECT * FROM vector_search(docs, 'query')",
            "SELECT * FROM vector_search(docs, ['a','b'], body, limit => 10)",
            "SELECT * FROM rrf(vector_search(docs, 'q', rank_weight => 20), text_search(docs, 'q', body), join_key => 'id')",
            "SELECT * FROM rerank(vector_search(docs, 'q'), document => 'body', model => 'local', limit => 5)",
        )) {
            assertNull(roundTrip(sql).find<VectorSearch>())
        }
    }

    @Test
    fun aiAndJsonExtensionsRemainNativeCalls() {
        for (sql in listOf(
            "SELECT ai_if(body, 'refund', model => 'local', on_error => 'null') FROM docs",
            "SELECT embed(['one','two'], 'local')",
            "SELECT json_get_int(doc, 'a', 'b'), json_contains(doc, 'a', 'b') FROM docs",
            "SELECT * FROM flatten_json('{\"a\":1}')",
            "SELECT * FROM json_tree('{\"a\":[1]}')",
        )) roundTrip(sql)
        assertEquals("SELECT like('abc', 'a%'), truncate(10, 101), date_add(d, 3)",
            roundTrip("SELECT like('abc', 'a%'), truncate(10, 101), date_add(d, 3)").sql("spice"))
    }

    @Test
    fun explainKeepsOptionsAndExecutionClassification() {
        for (sql in listOf(
            "EXPLAIN SELECT 1", "EXPLAIN (SELECT 1)", "EXPLAIN ANALYZE VERBOSE FORMAT pgjson SELECT 1",
            "EXPLAIN (ANALYZE, METRICS 'rows,timing', LEVEL dev, FORMAT pgjson) SELECT 1",
            "EXPLAIN (ANALYZE OFF, COSTS TRUE) SELECT 1",
        )) assertIs<SpiceExplain>(roundTrip(sql))
        assertEquals(StatementEffect.EXECUTES_QUERY, SqlFragment("EXPLAIN ANALYZE SELECT 1", "spice").statementEffect)
        assertEquals(StatementEffect.PLANS_OR_INSPECTS, SqlFragment("EXPLAIN (ANALYZE OFF) SELECT 1", "spice").statementEffect)
    }

    @Test
    fun preparedStatementsHaveStructuredNamesTypesBodiesAndValues() {
        val ast = assertIs<SpicePrepare>(roundTrip("PREPARE p(INT, DOUBLE) AS SELECT $1 + $2"))
        assertEquals(2, ast.expressionsArg.size)
        assertEquals(2, ast.walk().filterIsInstance<Parameter>().count())
        assertIs<SpicePrepare>(roundTrip("PREPARE p AS SELECT $1"))
        assertIs<SpiceExecute>(roundTrip("EXECUTE p(1, 2.5)"))
        assertIs<SpiceExecute>(roundTrip("EXECUTE p()"))
        assertEquals(StatementEffect.MODIFIES_STATE, SqlFragment("PREPARE p AS SELECT 1", "spice").statementEffect)
        assertEquals(StatementEffect.UNKNOWN, SqlFragment("EXECUTE p()", "spice").statementEffect)
    }

    @Test
    fun introspectionAndSettingsAreStructured() {
        for (sql in listOf("SHOW TABLES", "SHOW ALL", "SHOW FUNCTIONS LIKE '%json%'",
            "SHOW COLUMNS FROM spice.public.sample", "SHOW FULL COLUMNS FROM sample", "SHOW datafusion.execution.batch_size")) {
            assertIs<SpiceShow>(roundTrip(sql))
        }
        assertIs<SpiceReset>(roundTrip("RESET datafusion.execution.batch_size"))
        roundTrip("SET datafusion.execution.batch_size = 1024")
    }

    @Test
    fun createClusterAndDmlKeepTheirStructuredBodies() {
        assertNotNull(roundTrip("CREATE TABLE t (id INT) CLUSTER BY (id)").find<ClusterProperty>())
        roundTrip("CREATE TABLE t CLUSTER BY (id) AS SELECT 1 AS id")
        assertEquals("COPY (SELECT 1) TO 'output.csv' STORED AS CSV",
            roundTrip("COPY (SELECT 1) TO 'output.csv' STORED AS CSV").sql("spice"))
        assertEquals("CREATE EXTERNAL TABLE t (id INT) STORED AS CSV LOCATION 'input.csv'",
            roundTrip("CREATE EXTERNAL TABLE t (id INT) STORED AS CSV LOCATION 'input.csv'").sql("spice"))
        for (sql in listOf("INSERT INTO t VALUES (1)", "UPDATE t SET id = 2 WHERE id = 1",
            "DELETE FROM t WHERE id = 1", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET id = s.id")) {
            roundTrip(sql)
        }
    }

    @Test
    fun clientQuestionBindsAreDifferentFromJsonContainment() {
        val ast = roundTrip("SELECT ? FROM t WHERE doc ? 'a'")
        assertEquals(1, ast.walk().filterIsInstance<Placeholder>().count())
        assertEquals(1, ast.walk().filterIsInstance<JSONBContainsTopKey>().count())
    }

    @Test
    fun unsupportedProfilesAndMalformedUtilitiesFailExplicitly() {
        for (sql in listOf("SELECT * EXCEPT (a) FROM t", "SELECT * REPLACE (1 AS a) FROM t",
            "SELECT * ILIKE '%a%' FROM t", "PREPARE p() AS SELECT 1", "PREPARE p(INT) AS",
            "EXPLAIN ANALYZE", "EXPLAIN (BUFFERS) SELECT 1", "EXPLAIN (FORMAT json) SELECT 1",
            "SHOW COLUMNS FROM", "SELECT * FROM vector_search(docs,, 'q')", "CREATE TABLE `t` (id INT) CLUSTER BY (id)")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "spice") }
        }
    }

    @Test
    fun unprovedNativeCallsAndUtilitiesCannotLeakIntoOtherEngines() {
        for (source in listOf("SELECT ai('hello')", "SELECT * FROM vector_search(docs, 'q')",
            "SELECT truncate(10, 101)", "SELECT doc ->> 'a' FROM t", "PREPARE p AS SELECT 1")) {
            val ast = roundTrip(source)
            for (target in listOf("datafusion", "postgres", "bigquery")) {
                assertFailsWith<UnsupportedError>("$target: $source") { ast.sql(target) }
                assertFailsWith<UnsupportedError> { (Serde.load(Serde.dump(ast)) as Expression).sql(target) }
            }
        }
        assertEquals("SELECT ABS(x) FROM t", parseOne("SELECT abs(x) FROM t", "spice").sql("postgres"))
    }
}
