package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.annotateNullability
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbRegexOperatorsTest {
    @Test fun nativeOperatorsSurviveCopySerdeAndGenerationWithoutBecomingFunctionsOrOuterNegation() {
        for (op in listOf("~", "!~", "~*", "!~*")) {
            for (right in listOf("'b'", "ANY(['b', 'z'])", "ALL(['b', NULL])")) {
                val sql = "SELECT 'abc' $op $right AS result"
                val tree = parseDuckdbNativeOne(sql)
                val operator = tree.find<DuckdbRegexOperator>()!!
                assertEquals(op.startsWith('!'), operator.args["negate"] == true)
                assertEquals(op.endsWith('*'), operator.args["insensitive"] == true)
                assertNull(tree.find<Not>())
                for (copy in listOf(tree, tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                    val generated = copy.sql("duckdb")
                    assertTrue(" $op " in generated, generated)
                    assertFalse("REGEXP_" in generated, generated)
                    assertEquals(generated, parseDuckdbNativeOne(generated).sql("duckdb"))
                }
                assertNotNull(SqlFragment(sql, "duckdb").ast.find<DuckdbRegexOperator>())
                assertEquals(tree.sql("duckdb"), SqlFragment(sql, "duckdb").toExecutable("duckdb").sql)
            }
        }
        assertNotNull(parseDuckdbNativeOne("SELECT NOT ('abc' ~ 'b')").find<Not>())
        assertFails { parseDuckdbNativeOne("SELECT 1; SELECT 2") }
    }

    @Test fun parityParsingStaysExplicitlySeparateFromNativeExecutionAndAuthoredFunctions() {
        assertEquals("SELECT REGEXP_FULL_MATCH(x, y)", Dialects.DUCKDB.parseOne("SELECT x ~ y").sql("duckdb"))
        assertEquals("SELECT x ~ y", SqlFragment("SELECT x ~ y", "duckdb").toExecutable("duckdb").sql)
        for (call in listOf("REGEXP_FULL_MATCH(x, y)", "REGEXP_MATCHES(x, y)", "REGEXP_FULL_MATCH(x, y, 'i')")) {
            val tree = parseDuckdbNativeOne("SELECT $call")
            assertNull(tree.find<DuckdbRegexOperator>())
            assertEquals("SELECT $call", tree.sql("duckdb"))
        }
        for (sql in listOf("EXPLAIN SELECT 'abc' ~ 'b'", "SHOW (SELECT 'abc' !~* 'b')",
            "PREPARE p AS SELECT 'abc' ~ 'b'", "WITH t AS (SELECT 'abc' ~ 'b' AS m) SELECT * FROM t",
            "FROM (SELECT 'abc' AS s) |> EXTEND s ~ 'b' AS m |> SELECT m")) {
            assertNotNull(SqlFragment(sql, "duckdb").ast.find<DuckdbRegexOperator>(), sql)
            val generated = SqlFragment(sql, "duckdb").toExecutable("duckdb").sql
            assertFalse("REGEXP_FULL_MATCH" in generated, generated)
        }
    }

    @Test fun parametersTypesNullsAndCrossTargetRefusalsUseTheNativeContract() {
        val sql = "SELECT ? !~* ANY([?, ?]), ? ~ ?"
        assertEquals(sql.indices.filter { sql[it] == '?' }, parseParameterOccurrences(sql, "duckdb").map { it.range.start })
        assertEquals(5, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql.count { it == '?' })
        val executable = SqlFragment(sql, "duckdb").toExecutable("duckdb")
        assertEquals(executable.sql, assertNotNull(executable.sourceMap).output)
        for ((body, nullable) in listOf("'abc' ~ 'b'" to false, "NULL !~ 'b'" to true, "'abc' ~ NULL" to true)) {
            val query = "SELECT $body AS result"
            val tree = parseDuckdbNativeOne(query)
            val operator = tree.find<DuckdbRegexOperator>()!!
            annotateTypes(tree, dialect = Dialects.DUCKDB)
            assertTrue(operator.isType(DType.BOOLEAN))
            assertEquals(nullable, annotateNullability(tree, dialect = Dialects.DUCKDB).nullableOf(operator))
            assertEquals("BOOLEAN", SqlFragment(query, "duckdb").outputShape().columns.single().type)
            assertEquals(nullable, SqlFragment(query, "duckdb").outputShape().columns.single().nullable)
            assertEquals("BOOLEAN", RelationScope.analyze(query, "duckdb", RelationFacts()).output!!.columns.single().sqlType)
        }
        val quantified = parseDuckdbNativeOne("SELECT NULL ~ ANY([]::VARCHAR[])")
        assertNull(annotateNullability(quantified, dialect = Dialects.DUCKDB).nullableOf(quantified.find<DuckdbRegexOperator>()!!))
        for (target in listOf("postgres", "trino", "doris", "clickhouse", "bigquery", "mysql")) {
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
            assertTrue(SqlFragment(sql, "duckdb").transpileTo(target).unsupportedMessages.any { "Session-dependent" in it })
        }
    }

    @Test fun regexColumnSelectorsStayOperatorsButDoNotInventBooleanColumnShapes() {
        for (sql in listOf("SELECT * ~ 'b' FROM t", "SELECT * ~* 'B' FROM t")) {
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
            assertFails { SqlFragment(sql, "duckdb").outputShape() }
            assertFails { SqlFragment(sql, "duckdb").columnDependencies() }
            assertFails { dev.brikk.house.sql.optimizer.qualify(parseDuckdbNativeOne(sql), dialect = Dialects.DUCKDB) }
            assertFailsWith<UnsupportedError> { RelationScope.analyze(sql, "duckdb", RelationFacts()) }
        }
        // A star inside a scalar aggregate is not a regex column-name selector.
        val scalar = "SELECT CAST(COUNT(*) AS VARCHAR) ~ '[0-9]+' AS result FROM t"
        assertEquals("BOOLEAN", SqlFragment(scalar, "duckdb").outputShape().columns.single().type)
        assertEquals(false, SqlFragment(scalar, "duckdb").outputShape().columns.single().nullable)
        assertEquals("BOOLEAN", RelationScope.analyze(scalar, "duckdb", RelationFacts()).output!!.columns.single().sqlType)
    }
}
