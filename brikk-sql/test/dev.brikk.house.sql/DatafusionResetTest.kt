package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DatafusionReset
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.SpiceReset
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.StatementEffect
import dev.brikk.house.sql.shape.statementEffect
import kotlin.test.*

class DatafusionResetTest {
    @Test fun configurationNamesHaveStructuredComponentsAndRoundTripThroughSerde() {
        val cases = mapOf(
            "RESET datafusion.execution.batch_size" to listOf("datafusion", "execution", "batch_size"),
            "RESET ALL" to listOf("ALL"),
            "RESET \"MiXeD\".\"a.b\"" to listOf("MiXeD", "a.b"),
            "RESET 'datafusion.execution.batch_size'" to listOf("datafusion.execution.batch_size"),
            "RESET E'a\\tb'" to listOf("a\tb"),
            "RESET a b" to listOf("a", "b"),
            "RESET time zone" to listOf("time", "zone"),
            "RESET \"\"" to listOf(""),
            "RESET \"😀\"" to listOf("😀"),
            "RESET a /* comment */ . b" to listOf("a", "b"),
        )
        for ((source, names) in cases) {
            val tree = assertIs<DatafusionReset>(parseOne(source, "datafusion"))
            assertEquals(names, tree.expressionsArg.filterIsInstance<Identifier>().map { it.name }, source)
            val generated = tree.sql("datafusion")
            assertEquals(tree, parseOne(generated, "datafusion"), source)
            val loaded = Serde.load(Serde.dump(tree)) as Expression
            assertEquals(generated, loaded.sql("datafusion"))
            assertEquals(StatementEffect.MODIFIES_STATE, SqlFragment(source, "datafusion").statementEffect)
            for (target in listOf("postgres", "duckdb", "spice")) {
                assertFailsWith<UnsupportedError>("$source -> $target") { loaded.sql(target) }
            }
        }
        assertEquals("RESET datafusion.execution.batch_size", parseOne("RESET datafusion.execution.batch_size", "datafusion").sql("datafusion"))
        assertIs<SpiceReset>(parseOne("RESET datafusion.execution.batch_size", "spice"))
    }

    @Test fun malformedNamesAndStatementTailsAreNotAcceptedPrefixes() {
        for (source in listOf("RESET", "RESET .a", "RESET a.", "RESET a..b", "RESET 1", "RESET a = 1",
            "RESET a,b", "RESET :name", "RESET $1", "RESET a - b", "RESET N'x'", "RESET X'41'", "RESET a; SELECT (",
            "RESET BEGIN EXECUTE x(1)")) {
            assertFailsWith<ParseError>(source) { SqlFragment(source, "datafusion").ast }
        }
    }
}
