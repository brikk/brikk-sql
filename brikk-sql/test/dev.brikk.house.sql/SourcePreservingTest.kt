package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcePreservingTest {
    @Test
    fun nativeSameDialectIsExactIncludingHintsSettingsAndTrivia() {
        for ((dialect, source) in listOf(
            "doris" to "  select /*+ SET_VAR(exec_mem_limit=1234) */ lower('AbC') as `Odd Name`; -- tail\n",
            "clickhouse" to "select lower('AbC') as MixedCase SETTINGS max_threads = 1",
            "duckdb" to "select /* keep */ 2::integer as id, '😀 |> ; :n src()' as text -- tail",
        )) {
            val result = SqlFragment(source, dialect).toSourcePreservingExecutable(dialect)
            assertEquals(source, result.sql)
            assertTrue(result.diagnostics.isEmpty())
            assertNull(result.sourceMap)
        }
    }

    @Test
    fun aliasesOfTheSameDialectStillPreserveNativeText() {
        val source = "select 2::integer as id"
        assertEquals(source, SqlFragment(source, "postgresql").toSourcePreservingExecutable("POSTGRES").sql)
    }

    @Test
    fun pipePreservesNativeSubqueryCastCommentAndBindSpelling() {
        val source = "FROM (select /* keep */ 2::integer as id) AS raw |> WHERE id = :n |> SELECT id"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertTrue("select /* keep */ 2::integer as id" in result.sql, result.sql)
        assertTrue(":n" in result.sql, result.sql)
        assertTrue(result.diagnostics.isNotEmpty())
        assertFalse(Dialects.forName("duckdb").tokenize(result.sql).any { it.tokenType == TokenType.PIPE_GT })
        assertFalse(SqlFragment(result.sql, "duckdb").ast.findAll<PipeQuery>().any())
        assertNull(result.sourceMap)
    }

    @Test
    fun onlyTheNestedPipeIsChangedInANativeParent() {
        val source = "select /* outer */ id FROM (FROM (select 1::integer AS id) AS raw |> SELECT id) AS nested -- tail"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertTrue(result.sql.startsWith("select /* outer */ id FROM ("), result.sql)
        assertTrue(result.sql.endsWith(") AS nested -- tail"), result.sql)
        assertTrue("select 1::integer AS id" in result.sql, result.sql)
        assertFalse(Dialects.forName("duckdb").tokenize(result.sql).any { it.tokenType == TokenType.PIPE_GT })
    }

    @Test
    fun recursiveWithAndSiblingNativeTextRemainInTheirOriginalScope() {
        val source = "with recursive seq(n) as (select 1 union all select n + 1 from seq where n < 3), " +
            "native as (select /* untouched */ 2::integer as id), " +
            "p as (FROM native |> WHERE id = 2 |> SELECT id) select id from p"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertTrue(result.sql.startsWith("with recursive seq(n) as (select 1 union all select n + 1 from seq where n < 3), "), result.sql)
        assertTrue("native as (select /* untouched */ 2::integer as id)" in result.sql, result.sql)
        assertTrue(result.sql.endsWith("select id from p"), result.sql)
    }

    @Test
    fun fromFirstIsNativeInDuckdbButNormalizedForPostgresAndDoris() {
        val source = "FROM (select /* native */ 1 AS id) AS raw where id = 1 -- tail"
        assertEquals(source, SqlFragment(source, "duckdb").toSourcePreservingExecutable().sql)
        for (dialect in listOf("postgres", "doris")) {
            val result = SqlFragment(source, dialect).toSourcePreservingExecutable()
            assertTrue(result.sql.startsWith("SELECT * FROM "), result.sql)
            assertTrue("select /* native */ 1 AS id" in result.sql, result.sql)
            assertTrue("where id = 1" in result.sql, result.sql)
        }
    }

    @Test
    fun punctuationInsideLiteralsAndCommentsNeverBecomesAnEdit() {
        val source = "select '😀 |> ; :n src()' as value /* |> ; :n */; -- :n src() |> ;"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable(embedded = true)
        assertEquals(source.replace("/* |> ; :n */;", "/* |> ; :n */") + "\n", result.sql)
        assertTrue(result.parameterOccurrences.isEmpty())
        assertTrue(result.sql.endsWith('\n'))
    }

    @Test
    fun parameterOccurrencesAreParserProvedAndUtf16Accurate() {
        val source = "select '😀 :n' as txt, struct_pack(n := :n) as obj, :n as x, ? as y -- :n"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertEquals(listOf(":n", ":n", "?"), result.parameterOccurrences.map { source.substring(it.range.start, it.range.end) })
        assertEquals(listOf("n", "n", null), result.parameterOccurrences.map { it.name })
        for (occurrence in result.parameterOccurrences.filter { it.name != null }) {
            val name = occurrence.nameRange!!
            assertEquals("n", source.substring(name.start, name.end))
            assertEquals(':', source[name.start - 1])
        }
    }

    @Test
    fun crossDialectTranslationNeverReusesOriginalDialectCasts() {
        val source = "FROM (select 2::integer as id) AS raw |> SELECT id"
        val fragment = SqlFragment(source, "duckdb")
        val translated = fragment.toSourcePreservingExecutable("doris")
        assertEquals(fragment.toExecutable("doris", trackSourceMap = false).sql, translated.sql)
        assertTrue(translated.diagnostics.single().message.contains("cross-dialect"))
        assertFalse("::integer" in translated.sql)
    }

    @Test
    fun astEditsAreNotSilentlyDiscarded() {
        val fragment = SqlFragment("select id from t", "duckdb")
        fragment.ast.findAll<Identifier>().first().set("this", "changed")
        assertFailsWith<UnsupportedError> { fragment.toSourcePreservingExecutable() }
    }

    @Test
    fun settingsThatWouldCrossAPipeBoundaryAreRefused() {
        val source = "select lower('AbC') AS v FROM t SETTINGS max_threads = 1 |> SELECT v"
        assertFailsWith<UnsupportedError> {
            SqlFragment(source, "clickhouse").toSourcePreservingExecutable()
        }
    }

    @Test
    fun nativeSettingsInsideAnUnchangedSubqueryCanBeRetained() {
        val native = "select lower('AbC') as v SETTINGS max_threads = 1"
        val result = SqlFragment("FROM ($native) AS raw |> SELECT v", "clickhouse").toSourcePreservingExecutable()
        assertTrue(native in result.sql, result.sql)
    }

    @Test
    fun unsafeExistingPipeNamespaceGuardsStillRefuse() {
        assertFailsWith<UnsupportedError> {
            SqlFragment("FROM a |> JOIN b ON a.id = b.id |> LIMIT 1 |> WHERE a.id = 1", "duckdb")
                .toSourcePreservingExecutable()
        }
    }

    @Test
    fun changedHintOwningSelectIsRefusedInsteadOfSilentlyMovingTheHint() {
        assertFailsWith<UnsupportedError> {
            SqlFragment("select /*+ SET_VAR(exec_mem_limit=1234) */ * FROM t |> WHERE id = 1", "doris")
                .toSourcePreservingExecutable()
        }
    }

    @Test
    fun hintsInUnchangedNativeSubqueriesInsideAPipeRemainIntact() {
        val native = "select /*+ SET_VAR(exec_mem_limit=1234) */ CAST(2 AS INT) as id"
        val result = SqlFragment("FROM ($native) AS raw |> WHERE id = 2 |> SELECT id", "doris")
            .toSourcePreservingExecutable()
        assertTrue(native in result.sql, result.sql)
    }

    @Test
    fun nestedFromFirstNormalizationDoesNotRegenerateTheNativeParent() {
        val source = "select /* outer */ id FROM (FROM (select 1 AS id) AS raw where id = 1) AS nested"
        val result = SqlFragment(source, "doris").toSourcePreservingExecutable()
        assertEquals("select /* outer */ id FROM (SELECT * FROM (select 1 AS id) AS raw where id = 1) AS nested", result.sql)
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun namedParameterRangesDistinguishAliasesCastsAndDialectSyntax() {
        for ((dialect, source, expected) in listOf(
            Triple("duckdb", "select n: 1, struct_pack(n := :n), 2::integer, :n", listOf(":n", ":n")),
            Triple("duckdb", "select \$n, '😀 \$n'", listOf("\$n")),
            Triple("postgres", "select %(n)s, :n, '😀 :n'", listOf("%(n)s", ":n")),
            Triple("doris", "select @n, :n", listOf("@n", ":n")),
            Triple("clickhouse", "select {n:Int32}, :n", listOf("{n:Int32}", ":n")),
        )) {
            val result = SqlFragment(source, dialect).toSourcePreservingExecutable()
            assertEquals(expected, result.parameterOccurrences.map { source.substring(it.range.start, it.range.end) }, dialect)
            for (occurrence in result.parameterOccurrences) {
                val range = occurrence.nameRange!!
                assertEquals("n", source.substring(range.start, range.end))
            }
        }
    }

    @Test
    fun multipleNestedPipesArePatchedByCoordinatesNotRepeatedTextMatching() {
        val nested = "FROM (select 1::integer AS id) AS raw |> SELECT id"
        val source = "select /* parent */ a.id, b.id FROM ($nested) AS a CROSS JOIN ($nested) AS b"
        val result = SqlFragment(source, "duckdb").toSourcePreservingExecutable()
        assertTrue(result.sql.startsWith("select /* parent */ a.id, b.id FROM ("), result.sql)
        assertFalse(Dialects.forName("duckdb").tokenize(result.sql).any { it.tokenType == TokenType.PIPE_GT })
        assertEquals(2, "select 1::integer AS id".toRegex().findAll(result.sql).count())
    }
}
