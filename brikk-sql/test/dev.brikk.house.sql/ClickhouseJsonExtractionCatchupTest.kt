package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

/** SG29-010, #8601. JSON-valued extraction is not JSONExtractString. */
class ClickhouseJsonExtractionCatchupTest {
    @Test fun jsonValuedAndScalarNodesGenerateDifferentNativeFunctions() {
        assertEquals("SELECT JSONExtractRaw(payload, 'amount'), JSONExtractString(payload, 'amount')",
            transpile("SELECT payload -> 'amount', payload ->> 'amount'", "postgres", "clickhouse"))
        for (source in listOf("mysql", "duckdb", "bigquery")) {
            val output = transpile("SELECT JSON_EXTRACT(payload, '$.a[0].b')", source, "clickhouse")
            assertEquals("SELECT JSONExtractRaw(payload, 'a', 1, 'b')", output, source)
        }
    }

    @Test fun nativeRawCallsParseIntoJsonNodesAndRoundtripLiteralPaths() {
        for (call in listOf("JSONExtractRaw(payload, 'a', 1, 'b')", "JSONExtractRaw(payload)",
            "JSONExtractRaw(payload, 'a.b')", "JSONExtractRaw(payload, '12')", "JSONExtractRaw(payload, 'a b')",
            "JSONExtractRaw(payload, '')")) {
            val tree = parseOne(call, "clickhouse")
            assertIs<JSONExtract>(tree)
            assertIs<JSONPath>(tree.expressionArg)
            assertEquals(call, tree.sql("clickhouse"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
        assertIs<JSONExtractScalar>(parseOne("JSONExtractString(payload, 'a')", "clickhouse"))
        val escaped = parseOne("JSONExtractRaw(payload, 'a\\'b')", "clickhouse")
        assertEquals(escaped, parseOne(escaped.sql("clickhouse"), "clickhouse"))
        val output = transpile("SELECT JSONExtractRaw(payload, 'a'), JSONExtractString(payload, 'a')", "clickhouse", "postgres")
        assertEquals("SELECT JSON_EXTRACT_PATH(payload, 'a'), JSON_EXTRACT_PATH_TEXT(payload, 'a')", output)
    }

    @Test fun numericKeysAndNativeArrayIndicesDoNotBecomeEachOther() {
        for (name in listOf("JSONExtractRaw", "JSONExtractString")) {
            val key = parseOne("$name(payload, '12')", "clickhouse")
            assertEquals("12", key.findAll<JSONPathKey>().single().name)
            assertTrue(key.findAll<JSONPathSubscript>().none())
            val index = parseOne("$name(payload, 1)", "clickhouse")
            assertEquals(0L, (index.findAll<JSONPathSubscript>().single().thisArg as Number).toLong())
            for (value in listOf("0", "-1", "9223372036854775807")) {
                val sql = "$name(payload, $value)"
                assertEquals(sql, parseOne(sql, "clickhouse").sql("clickhouse"))
            }
        }
        assertEquals("SELECT JSONExtractRaw(payload, 'a', -1)",
            transpile("SELECT JSON_EXTRACT(payload, '$.a[-1]')", "mysql", "clickhouse"))
    }

    @Test fun dynamicVariadicSegmentsKeepEveryArgumentAndTheirOrder() {
        for (name in listOf("JSONExtractRaw", "JSONExtractString")) {
            val source = "$name(payload, path, 2, 'tail')"
            val tree = parseOne(source, "clickhouse")
            assertEquals(source, tree.sql("clickhouse"))
            assertEquals(listOf("2", "tail"), tree.expressionsArg.filterIsInstance<Expression>().map { it.name })
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }

    @Test fun copyFalseSerdeAndQuotedUnicodeSourcePositionsKeepTheOwnedTree() {
        val tree = parseOne("SELECT JSONExtractRaw(`😀payload`, 'a b', 1)", "clickhouse")
        val before = Serde.dump(tree)
        val parents = tree.walk().map { it to it.parent }.toList()
        val generator = Dialects.CLICKHOUSE.generator(sourceDialect = "clickhouse").apply { trackSpans = true }
        val output = generator.generate(tree, copy = false)
        val identifier = tree.findAll<Identifier>().first { it.name == "😀payload" }
        val emitted = output.indexOf("😀payload")
        assertTrue(emitted >= 0, output)
        assertEquals(assertNotNull(SourceMap.sourcePosOf(identifier)).start,
            assertNotNull(assertNotNull(generator.lastSourceMap).sourcePosition(emitted)).start)
        assertEquals(before, Serde.dump(tree))
        for ((node, parent) in parents) assertSame(parent, node.parent)
        assertEquals(output, Serde.loadExpression(before).sql("clickhouse"))
    }

    @Test fun rawMissingValueAndResultTypeDifferencesCannotBeClaimedAsExecutableEquivalence() {
        for (target in listOf("postgres", "duckdb", "mysql")) {
            assertFailsWith<UnsupportedError> {
                SqlFragment("SELECT JSONExtractRaw(payload, 'a')", "clickhouse").toExecutable(target)
            }
        }
        for (source in listOf("", "postgres", "mysql", "duckdb")) {
            assertFailsWith<UnsupportedError> {
                SqlFragment("SELECT JSON_EXTRACT(payload, '$.a')", source).toExecutable("clickhouse")
            }
        }
        SqlFragment("SELECT JSONExtractRaw(payload, 'a')", "clickhouse").toExecutable("clickhouse")
        assertContains(transpile("SELECT JSON_EXTRACT(payload, '$.a')", "mysql", "clickhouse"), "JSONExtractRaw")
    }

    @Test fun nativeStringInputHasAStringShapeRatherThanAnInventedJsonStorageType() {
        assertEquals("VARCHAR", SqlFragment("SELECT JSONExtractRaw('{}', 'missing') AS v", "clickhouse")
            .outputShape().columns.single().type)
        val tree = Serde.loadExpression(Serde.dump(parseOne("JSONExtractRaw(payload, 'arr', -1)", "clickhouse")))
        val generator = Dialects.POSTGRES.generator(sourceDialect = "clickhouse").apply { forExecution = true }
        assertFailsWith<UnsupportedError> { generator.generate(tree) }
        assertEquals("JSONExtractRaw(payload, 'arr', -1)", tree.sql("clickhouse"))
    }
}
