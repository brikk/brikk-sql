package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.sql.psi.SqlStatement
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.shape.parseParameterOccurrences
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

private object DatafusionTestDbms {
    val dbms = Dbms.create("DATAFUSION_BRIKK", "DataFusion", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("datafusion"))
}
private object SpiceTestDbms {
    val dbms = Dbms.create("SPICE_BRIKK", "Spice", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("spice"))
}
object DatafusionFixtureDialect : DatafusionFamilyDialect(DatafusionIdeProfile.DATAFUSION) {
    override fun getDbms(): Dbms = DatafusionTestDbms.dbms
}
object SpiceFixtureDialect : DatafusionFamilyDialect(DatafusionIdeProfile.SPICE) {
    override fun getDbms(): Dbms = SpiceTestDbms.dbms
}

/** Real consumer-shaped languages, without registering any production extension in the shared jar. */
class DatafusionIdeTest : BasePlatformTestCase() {
    private val dialects get() = listOf(DatafusionFixtureDialect, SpiceFixtureDialect)

    override fun setUp() {
        super.setUp()
        for (dialect in dialects) {
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(dialect, DatafusionFamilyParserDefinition(dialect), testRootDisposable)
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(dialect, object : SqlSyntaxHighlighterFactory.Base(dialect) {}, testRootDisposable)
        }
    }

    private fun open(sql: String, dialect: DatafusionFamilyDialect): PsiFile {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("native-${UUID.randomUUID()}.sql", dialect, sql))
        return myFixture.file
    }

    private fun binds(file: PsiFile) = PsiTreeUtil.collectElements(file) { it.node?.elementType == SqlElementTypes.SQL_PARAMETER_REFERENCE }

    private fun errors(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        .map { "${it.textRange}: ${it.errorDescription}" }

    fun testIndependentIdentitiesDoNotClaimPostgresGenericOrFlight() {
        assertEquals(4, SqlDialectRegistry.VERSION)
        for (dialect in dialects) {
            val profile = dialect.profile
            val entry = requireNotNull(SqlDialectRegistry.forCoreDialect(profile.coreDialect))
            assertSame(entry, SqlDialectRegistry.forLanguage(dialect))
            assertSame(entry, SqlDialectRegistry.forDbms(dialect.dbms))
            assertSame(entry, SqlDialectRegistry.forMarker(profile.coreDialect.uppercase()))
            assertEquals(setOf(profile.languageId), entry.languageIds)
            assertEquals(setOf(profile.dbmsId), entry.dbmsIds)
        }
        assertEquals("postgres", SqlDialectRegistry.forLanguage(PgDialect.INSTANCE)?.coreDialect)
        assertNull(SqlDialectRegistry.forLanguageId("GenericSQL"))
        assertNull(SqlDialectRegistry.forLanguageId("FlightSQL"))
        assertEquals("trino", SqlDialectRegistry.forLanguageId("TrinoSQL")?.coreDialect)
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("pg.sql", PgDialect.INSTANCE, "SELECT 1"))
        val pg = myFixture.file
        assertSame(PgDialect.INSTANCE, pg.language)
        assertFalse(pg.language.isKindOf(SpiceFixtureDialect))
        assertFalse(pg.language.isKindOf(DatafusionFixtureDialect))
    }

    fun testCommonQueriesKeepPgFunctionPsiAndCastSpecialForms() {
        val sql = "SELECT CAST(id AS BIGINT), sum(id) OVER (PARTITION BY name ORDER BY id), regexp_like(name, 'x', 'i') FROM t WHERE id > 0"
        for (dialect in dialects) {
            val file = open(sql, dialect)
            assertEmpty(errors(file).toString(), errors(file))
            assertTrue(PsiTreeUtil.findChildrenOfType(file, SqlFunctionCallExpression::class.java).size >= 2)
            assertEquals(sql, file.text)
        }
    }

    fun testNativeSyntaxHasWholeStatementsWithoutErrorSuppression() {
        for (dialect in dialects) for (sql in COMMON + if (dialect === SpiceFixtureDialect) SPICE else DATAFUSION) {
            val file = open("$sql; SELECT 7 AS tail;", dialect)
            assertEmpty("${dialect.id}: $sql\n${errors(file)}", errors(file))
            val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java).filter { it.parent !is SqlStatement }
            assertEquals("${dialect.id}: $sql", listOf(sql, "SELECT 7 AS tail"), statements.map { it.text.trim().removeSuffix(";") })
        }
    }

    fun testPinnedSpiceParserCorpusRetainsAcceptedSyntaxInTheIde() {
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("vendor/data/datafusion-spice/round4/parser-native.json")) }
        val capture = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/round4/parser-native.json"))).jsonObject
        val native = capture.getValue("profiles").jsonObject.getValue("spice-2.4.0-parser").jsonObject.getValue("cases").jsonArray
        val inputs = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/round4/cases.json"))).jsonObject.getValue("cases").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("sql").jsonPrimitive.content }
        val accepted = native.filter { it.jsonObject.getValue("original").jsonObject.getValue("parse").jsonObject.getValue("status").jsonPrimitive.content == "accepted" }
        assertEquals(104, accepted.size)
        val failures = accepted.flatMap { row ->
            val id = row.jsonObject.getValue("id").jsonPrimitive.content
            errors(open(inputs.getValue(id), SpiceFixtureDialect)).map { "$id: $it" }
        }
        assertEmpty(failures.joinToString("\n"), failures)
    }

    fun testRejectedAndIncompleteSyntaxStaysVisibleAndDoesNotHideFollowingStatements() {
        for (dialect in dialects) for (sql in listOf("SELECT concat(a,,b)", "SELECT CAST(1 AS)", "SELECT (", "SELECT E'\\uD800'")) {
            val file = open("$sql; SELECT 7 AS tail;", dialect)
            assertFalse("${dialect.id}: $sql", errors(file).isEmpty())
            assertEquals("$sql; SELECT 7 AS tail;", file.text)
        }
        for (sql in listOf("SELECT * REPLACE (1 AS a) FROM t", "SELECT * EXCEPT(a) FROM t", "CREATE TABLE `t` (id INT)", "EXPLAIN (FORMAT json) SELECT 1")) {
            assertFalse(sql, errors(open(sql, SpiceFixtureDialect)).isEmpty())
        }
    }

    fun testLexerRestartsPreserveCommentsStringsOperatorsAndUtf16() {
        val sql = "-- 😀 header\r\nSELECT E'a;\\nb', X'4142', \$\$a;b\$\$, \"MiXeD\", $1, ?; /* outer /* inner */ kept */ SELECT 5 ^ 3;"
        for (dialect in dialects) {
            val definition = DatafusionFamilyParserDefinition(dialect)
            assertRestartsMatchFullLex({ definition.createLexer(project) }, sql)
            val tokens = definition.createLexer(project).lexAll(sql)
            assertTrue(tokens.all { it.state >= 0 })
            assertEquals(sql, tokens.joinToString("") { sql.substring(it.start, it.end) })
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(dialect, project, null)
            assertTrue(highlighter.getTokenHighlights(SqlTokens.SQL_STRING_TOKEN).isNotEmpty())
            assertTrue(highlighter.getTokenHighlights(SqlTokens.SQL_BLOCK_COMMENT).isNotEmpty())
        }
    }

    fun testFallbackBindsKeepOccurrencesAndJsonQuestionIsNotAParameter() {
        val sql = "SELECT '😀', [1,2], $1, $12, $1, ?, doc ? 'key', '\$8 ?', \$\$\$9 ?\$\$ FROM t /* $3 ? */"
        val file = open(sql, SpiceFixtureDialect)
        assertEmpty(errors(file).toString(), errors(file))
        val expected = parseParameterOccurrences(sql, "spice").map { TextRange(it.range.start, it.range.end) }
        val actual = binds(file).map { it.textRange }
        assertEquals(expected, actual)
    }

    fun testFormattingPreservesNativeMeaningAndBoundaries() {
        for (dialect in dialects) for (sql in listOf(COMMON[0], COMMON[1],
            "SELECT [1], \$Tag\$ a;\n\$1 ? /* text */ \$Tag\$ AS v",
            if (dialect === SpiceFixtureDialect) SPICE[0] else DATAFUSION[0])) {
            val file = open(sql, dialect)
            val core = Dialects.forName(dialect.profile.coreDialect)
            val before = core.parseOne(sql)
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
            assertEmpty(file.text + errors(file), errors(file))
            assertEquals(file.text, before, core.parseOne(file.text))
            assertEquals(1, SqlScriptSnapshot.parse(file.text, dialect.profile.coreDialect).statements.size)
        }
    }

    fun testInjectedNativeSqlUsesItsOwnLanguageAndHostOffsets() {
        for (dialect in dialects) {
            val text = "SELECT 'SELECT ''😀'' AS label, [1,2], $1'"
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("host-${UUID.randomUUID()}.sql", PgDialect.INSTANCE, text))
            val hostFile = myFixture.file
            val literal = PsiTreeUtil.findChildOfType(hostFile, SqlStringLiteralExpression::class.java)!!
            val manager = InjectedLanguageManager.getInstance(project)
            manager.registerMultiHostInjector(object : MultiHostInjector {
                override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                    if (context !== literal) return
                    registrar.startInjecting(dialect).addPlace(null, null, literal, TextRange(1, literal.textLength - 1)).doneInjecting()
                }
                override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
            }, testRootDisposable)
            val injected = manager.getInjectedPsiFiles(literal)!!.single().first.containingFile
            assertSame(dialect, injected.language)
            assertEmpty(errors(injected).toString(), errors(injected))
            val bind = binds(injected).single()
            val mapped = manager.injectedToHost(injected, bind.textRange)
            assertEquals("$1", hostFile.text.substring(mapped.startOffset, mapped.endOffset))
            assertSame(PgDialect.INSTANCE, hostFile.language)
        }
    }

    fun testLargeScriptsAndEveryIncompletePrefixRetainText() {
        for (dialect in dialects) {
            val statement = "SELECT [1,2], $1 AS value;\r\n"
            val sql = statement.repeat(400)
            val file = open(sql, dialect)
            assertEmpty(errors(file).take(5).toString(), errors(file))
            // IntelliJ documents normalize CRLF at load; raw CRLF lexer offsets are checked above.
            assertEquals(sql.replace("\r\n", "\n"), file.text)
            val sample = "SELECT vector_search(docs, '😀', limit => 3)"
            for (end in 1..sample.length) {
                // A virtual file encodes UTF-8: a prefix ends at a code point, not inside a surrogate pair.
                if (sample[end - 1].isHighSurrogate() && sample.getOrNull(end)?.isLowSurrogate() == true) continue
                assertEquals(sample.take(end), open(sample.take(end), dialect).text)
            }
        }
    }

    private companion object {
        val COMMON = listOf(
            "SELECT [1,2], TRY_CAST('x' AS INT), \$\$a;b\$\$ AS text_value",
            "SELECT like('spice.ai', 'spice%'), map('a', 1), struct(1 AS a)",
            "SELECT * FROM vector_search(docs, 'q', limit => 3)",
            "SELECT * FROM vector_search(docs, 'q', limit /* option */ => 3)",
            "SELECT * FROM t WHERE name LIKE ('a%')",
            "SELECT $1, ? FROM t WHERE id = $1",
            "SELECT id, row_number() OVER (ORDER BY id) AS n FROM t QUALIFY n = 1",
            "COPY (SELECT 1) TO 'file.csv' STORED AS CSV",
            "FROM t |> WHERE id > 0 |> SELECT id",
        )
        val DATAFUSION = listOf("SELECT * EXCEPT (b) FROM t", "SELECT 5 ^ 3 AS xor_value")
        val SPICE = listOf(
            "EXPLAIN (ANALYZE OFF, FORMAT pgjson) SELECT 1",
            "PREPARE p(INT) AS SELECT $1 + 1", "EXECUTE p(2)", "SHOW FUNCTIONS LIKE '%json%'",
            "RESET datafusion.execution.batch_size", "CREATE TABLE t (id INT) CLUSTER BY (id)",
            "SELECT doc -> 'a' ->> 0, doc ? 'a', 5 # 3, 2 ^ 3 FROM t",
        )
    }
}
