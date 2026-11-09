package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenType
import kotlin.test.*

class MainIntegrationRegressionTest {
    @Test fun numericWordBoundariesRetainExactTokenRanges() {
        for (number in listOf("13", "1.5", ".5", "1e3", "1E+3", "1e-3", ".5e2")) {
            for ((word, type) in listOf("END" to TokenType.END, "ELSE" to TokenType.ELSE, "EXCEPT" to TokenType.EXCEPT)) {
                for (suffix in listOf(word, word.lowercase())) {
                    val source = "/* π 😀 */$number$suffix"
                    val tokens = Tokenizer().tokenize(source)
                    val leadingDot = number.startsWith('.')
                    val types = if (leadingDot) listOf(TokenType.DOT, TokenType.NUMBER, type) else listOf(TokenType.NUMBER, type)
                    val slices = if (leadingDot) listOf(".", number.drop(1), suffix) else listOf(number, suffix)
                    assertEquals(types, tokens.map { it.tokenType }, source)
                    assertEquals(slices, tokens.map { source.substring(it.start, it.end + 1) }, source)
                    for ((before, after) in tokens.zipWithNext()) assertEquals(before.end + 1, after.start, source)
                }
            }
        }
    }

    @Test fun mysqlPlainKeyNodesStayPlainWhilePrefixAndDirectionHaveSeparateNodes() {
        for (key in listOf("PRIMARY KEY", "UNIQUE", "KEY k")) {
            val sql = "CREATE TABLE t (a VARCHAR(64), b INT, $key (a, a(16), a ASC, a(16) DESC))"
            val tree = Dialects.MYSQL.parseOne(sql)
            val parts = when (key) {
                "PRIMARY KEY" -> tree.findAll<PrimaryKey>().single().expressionsArg
                "UNIQUE" -> (tree.findAll<UniqueColumnConstraint>().single().thisArg as Schema).expressionsArg
                else -> tree.findAll<IndexColumnConstraint>().single().expressionsArg
            }
            if (key == "PRIMARY KEY") assertIs<Identifier>(parts[0]) else assertIs<Column>(parts[0])
            assertIs<ColumnPrefix>(parts[1])
            val ascending = assertIs<Ordered>(parts[2])
            if (key == "PRIMARY KEY") assertIs<Identifier>(ascending.thisArg) else assertIs<Column>(ascending.thisArg)
            assertEquals(false, ascending.args["desc"])
            val descending = assertIs<Ordered>(parts[3])
            assertIs<ColumnPrefix>(descending.thisArg)
            assertEquals(true, descending.args["desc"])
        }
    }

    @Test fun nestedValuesJoinGenerationDoesNotMutateCallerOwnedTrees() {
        val sql = "SELECT * FROM (VALUES (1)) t1(a) LEFT JOIN (VALUES (1)) t2(b) JOIN (VALUES (1)) t3(c) ON t2.b = t3.c ON t1.a = t2.b"
        val tree = Dialects.DUCKDB.parseOne(sql)
        val before = Serde.dump(tree)
        for (lower in listOf(false, true)) {
            val generator = object : Generator() { override val valuesAsTable get() = !lower }
            val generated = generator.generate(tree, copy = false)
            assertContains(generated, "AS t2")
            assertTrue(generated.indexOf("AS t2") < generated.indexOf("ON t2.b"), generated)
            assertEquals(before, Serde.dump(tree))
            for (node in tree.walk()) {
                for (child in node.iterExpressions()) assertSame(node, child.parent)
            }
        }
    }
}
