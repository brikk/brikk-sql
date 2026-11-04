package dev.brikk.house.sql

import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONPathSubscript
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseJsonPath
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** SG29-003, upstream 8a524dfa; Kotlin's index representation is bounded to Int. */
class JsonPathIndexCatchupTest {
    @Test
    fun invalidAndOverflowingIndexesRaiseParseErrorNotNumberFormatException() {
        for (index in listOf("1e2", "1E2", "1e+2", "-1e2", "-", "2147483648", "-2147483649", "9".repeat(200))) {
            val path = "\$[$index]"
            val error = assertFailsWith<ParseError>(path) { parseJsonPath(path) }
            assertContains(error.message.orEmpty(), "Invalid integer index: $index")
            assertContains(error.message.orEmpty(), path)
        }
        for (path in listOf("\$[1.2]", "\$[0x10]", "\$[]", "\$[1e2:3]", "\$[1,2e3]", "\$[0:2:1e2]")) {
            assertFailsWith<ParseError>(path) { parseJsonPath(path) }
        }
    }

    @Test
    fun validIndexesIncludingIntBoundsKeepTheirAstAndSerde() {
        for (index in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val path = parseJsonPath("\$[$index]")
            assertEquals(index, assertIs<JSONPathSubscript>(path.expressionsArg.last()).thisArg)
            assertEquals(path, Serde.loadExpression(Serde.dump(path)))
        }
        for (path in listOf("\$[0:4:2]", "\$[-2:-1]", "\$[0,2,-1]", "\$['1e2']", "\$[\"2147483648\"]")) {
            assertEquals(parseJsonPath(path), Serde.loadExpression(Serde.dump(parseJsonPath(path))))
        }
    }

    @Test
    fun sqlEntryPointsKeepInvalidPathsAsRawLiteralsUnderExistingFallbackPolicy() {
        for (dialect in listOf("", "duckdb", "bigquery", "mysql", "doris", "starrocks", "spark", "trino", "postgres")) {
            for (path in listOf("\$[1e2]", "\$[2147483648]", "\$[-]")) {
                val tree = parseOne("SELECT JSON_EXTRACT('{}', '$path')", dialect)
                val extraction = tree.findAll<JSONExtract>().single()
                assertEquals(path, assertIs<Literal>(extraction.args["expression"]).name, dialect)
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
                assertContains(tree.sql(dialect), path)
            }
        }
        // Existing DuckDB pointer and back-of-list syntax never goes through the index parser.
        for (path in listOf("/a/0", "\$[#-1]")) {
            val extraction = parseOne("SELECT JSON_EXTRACT('{}', '$path')", "duckdb").findAll<JSONExtract>().single()
            assertEquals(path, assertIs<Literal>(extraction.args["expression"]).name)
        }
    }
}
