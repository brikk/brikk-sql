package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ClickHouse `{name:Type}` server parameters are reported apart from IDE `:name` binds. */
class ClickhouseTypedParametersTest {

    private fun spans(sql: String, occurrences: List<ParameterOccurrence>) =
        occurrences.map { Triple(it.style, it.name, sql.substring(it.range.start, it.range.end)) }

    private val mixed = "SELECT {a:UInt32}, :b, f(g({d: Array(String)}), :b) FROM t " +
        "WHERE s = '{c:UInt8} :x' /* {e:UInt8} :y */ AND n = {a:UInt32} -- {z:UInt8} :z"

    private val expected = listOf(
        Triple(ParamStyle.NAMED_TYPED, "a", "{a:UInt32}"),
        Triple(ParamStyle.NAMED_COLON, "b", ":b"),
        Triple(ParamStyle.NAMED_TYPED, "d", "{d: Array(String)}"),
        Triple(ParamStyle.NAMED_COLON, "b", ":b"),
        Triple(ParamStyle.NAMED_TYPED, "a", "{a:UInt32}"),
    )

    @Test
    fun strictDiscoveryReportsBothStylesOutsideStringsAndComments() {
        assertEquals(expected, spans(mixed, parseParameterOccurrences(mixed, "clickhouse")))
        val name = parseParameterOccurrences(mixed, "clickhouse").first().nameRange!!
        assertEquals("a", mixed.substring(name.start, name.end))
    }

    @Test
    fun editorDiscoveryReportsBothStylesAcrossStatements() {
        val script = "$mixed\n;SELECT {t:Identifier}, :q; SELECT {open:UInt8"
        val result = SqlScriptParameters.parse(script, "clickhouse")
        assertEquals(
            expected + Triple(ParamStyle.NAMED_TYPED, "t", "{t:Identifier}") + Triple(ParamStyle.NAMED_COLON, "q", ":q"),
            spans(script, result.occurrences),
        )
    }

    @Test
    fun maskingAndScalarParametersSkipOrSeparateTypedParameters() {
        val masked = maskNamedParameters(mixed, "clickhouse")
        assertEquals(mixed.length, masked.length)
        assertEquals(2, Regex("""\{a:UInt32}""").findAll(masked).count())
        assertTrue(":b" !in masked && "{d: Array(String)}" in masked)
        assertEquals(
            listOf(ParamStyle.NAMED_TYPED to "a", ParamStyle.NAMED_COLON to "b", ParamStyle.NAMED_TYPED to "d"),
            SqlFragment(mixed, "clickhouse").scalarParams.map { it.style to it.name },
        )
    }

    @Test
    fun loweringKeepsTypedParametersAsWritten() {
        val pipe = "FROM {tbl:Identifier} |> WHERE x = {a:UInt32} AND y = :b |> SELECT f({d: Nullable(DateTime64(3))}) AS v"
        val substituted = pipe.replace(":b", "1")
        val lowered = assertIs<PipeLowering.Ok>(lowerForExecution(substituted, "clickhouse")).sql
        for (parameter in listOf("{tbl:Identifier}", "{a:UInt32}", "{d: Nullable(DateTime64(3))}")) {
            assertTrue(parameter in lowered, "$parameter lost in $lowered")
        }
    }

    @Test
    fun changedTypedParametersRenderCanonically() {
        val dialect = Dialects.forName("clickhouse")
        val tree = dialect.parseOne("SELECT {a:UInt32}")
        val placeholder = assertIs<Placeholder>(tree.find(Placeholder::class))
        placeholder.set("kind", DataType.build(DType.TEXT))
        assertEquals("SELECT {a: Nullable(String)}", dialect.generate(tree))
    }

    @Test
    fun otherDialectsKeepBraceColonsAsTheyWere() {
        assertEquals(listOf(Triple(ParamStyle.NAMED_COLON, "p", ":p")), spans("SELECT {'k': :p}", SqlScriptParameters.parse("SELECT {'k': :p}", "duckdb").occurrences))
        assertEquals(emptyList(), SqlScriptParameters.parse("SELECT MAP{a:UInt32}", "doris").occurrences)
    }
}
