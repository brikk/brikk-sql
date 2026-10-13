package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.intellij.completion.configureDialect
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.verify.ParseOnlyDuckdbVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.duckdb.DuckDBDriver

/** A validator that answers from a script: [answer] maps each statement's SQL to its result. */
class ScriptedValidator(var answer: (String) -> VerifyResult = { VerifyResult.Accepted }) : NativeSqlValidator {
    val asked = mutableListOf<String>()
    override val timing = ValidationTiming.Lowering
    override fun validate(sql: String, file: PsiFile?): NativeVerdict = error("verify only")
    override fun verify(sql: String, file: PsiFile?): VerifyResult = synchronized(asked) { asked += sql }.let { answer(sql) }
}

/** Rejects every statement containing `bad` at its first `bad`, and accepts the rest. */
fun rejectBad(sql: String): VerifyResult = sql.indexOf("bad").takeIf { it >= 0 }
    ?.let { VerifyResult.Rejected(VerifyError.atOffset(sql, "no bad here", it, it + 3)) } ?: VerifyResult.Accepted

class TestProfile(
    override val language: Language,
    override val dialect: String,
    override val label: String = "Engine",
) : NativeErrorProfile {
    var validator: NativeSqlValidator? = ScriptedValidator(::rejectBad)
    override var localParser = false
    override var reportsPsiErrors = false
    override var injectionHosts = emptySet<Language>()
    override var unit = VerificationUnit.Statement
    override var sizeCap: Int? = null
    override var debounce: Duration = Duration.ZERO
    override var budget: Duration? = null
    var extra: List<SqlDiagnostic> = emptyList()
    var revision = 0L
    override fun modificationCount(file: PsiFile) = revision
    var custom: (String, SqlStatementRange) -> List<SqlDiagnostic>? = { _, _ -> null }
    override fun validator(file: PsiFile) = validator
    override fun fileDiagnostics(file: PsiFile, script: String) = extra
    override fun statementDiagnostics(script: String, statement: SqlStatementRange) = custom(script, statement)
}

private val postgres get() = Language.findLanguageByID("PostgreSQL")!!
private val mysql get() = Language.findLanguageByID("MySQL")!!

val postgresProfile = TestProfile(postgres, "postgres")
val mysqlProfile = TestProfile(mysql, "mysql", "Injected")

class PostgresErrorAnnotator : NativeErrorAnnotator(postgresProfile)
class MysqlErrorAnnotator : NativeErrorAnnotator(mysqlProfile)
class PostgresPsiFilter : NativeErrorPsiFilter(postgresProfile)
class MysqlPsiFilter : NativeErrorPsiFilter(mysqlProfile)

class NativeErrorAnnotatorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        for (profile in listOf(postgresProfile, mysqlProfile)) {
            profile.validator = ScriptedValidator(::rejectBad)
            profile.localParser = false
            profile.reportsPsiErrors = false
            profile.injectionHosts = emptySet()
            profile.unit = VerificationUnit.Statement
            profile.sizeCap = null
            profile.debounce = Duration.ZERO
            profile.budget = null
            profile.extra = emptyList()
            profile.revision = 0
            profile.custom = { _, _ -> null }
        }
    }

    private val validator get() = postgresProfile.validator as ScriptedValidator

    private fun registerFilter(filter: HighlightErrorFilter = PostgresPsiFilter()) =
        project.extensionArea.getExtensionPoint<HighlightErrorFilter>(HighlightErrorFilter.EP_NAME.name).registerExtension(filter, testRootDisposable)

    private var registered = false

    private fun highlight(): List<HighlightInfo> {
        DaemonCodeAnalyzer.getInstance(project).restart()
        return myFixture.doHighlighting()
    }

    private fun annotate(language: Language, annotator: NativeErrorAnnotator) =
        ExternalLanguageAnnotators.INSTANCE.addExplicitExtension(language, annotator, testRootDisposable)

    private fun open(text: String) {
        myFixture.configureDialect("PostgreSQL", text)
        if (!registered) annotate(postgres, PostgresErrorAnnotator())
        registered = true
    }

    /** The engine's diagnostics, as the text they mark and their message, from a fresh pass as a plugin's restart gives. */
    private fun marks(prefix: String = "Engine"): List<Pair<String, String>> = highlight()
        .filter { it.description?.startsWith(prefix) == true }
        .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) to it.description }

    fun testARejectionIsMarkedWhereTheEngineSaid() {
        open("SELECT 1;\nSELECT bad FROM t;\nSELECT 2;")
        assertEquals(listOf("bad" to "Engine: no bad here"), marks())
        assertEquals(listOf("SELECT 1", "\nSELECT bad FROM t", "\nSELECT 2"), validator.asked)
    }

    fun testAcceptanceAndNoVerdictMarkNothing() {
        for (answer in listOf(VerifyResult.Accepted, VerifyResult.Unverified("cannot serialize"), VerifyResult.Unavailable("no engine"))) {
            postgresProfile.validator = ScriptedValidator { answer }
            open("SELECT * FROM t WHERE = 1;")
            assertEquals(answer.toString(), listOf<Pair<String, String>>(), marks())
        }
    }

    fun testTheLocalParserIsTheFirstLineAndTheEngineDecides() {
        postgresProfile.localParser = true
        val text = "SELECT * FROM t WHERE = 1;"
        postgresProfile.validator = null
        open(text)
        assertTrue(marks().single().second.startsWith("Engine (brikk-sql): "))
        // Native acceptance hides the local false positive; a native rejection replaces it.
        postgresProfile.validator = ScriptedValidator { VerifyResult.Accepted }
        open(text)
        assertEquals(listOf<Pair<String, String>>(), marks())
        postgresProfile.validator = ScriptedValidator { VerifyResult.Rejected(VerifyError.atOffset(it, "engine says", it.indexOf('='))) }
        open(text)
        // The engine proved the text before its error parses, so only local errors at or after it remain.
        val (engine, local) = marks().partition { it.second.startsWith("Engine: ") }
        assertEquals(listOf("=" to "Engine: engine says"), engine)
        assertTrue(local.all { text.indexOf(it.first, text.indexOf('=')) >= text.indexOf('=') })
        // No verdict keeps the local error.
        postgresProfile.validator = ScriptedValidator { VerifyResult.Unavailable("down") }
        open(text)
        assertTrue(marks().single().second.startsWith("Engine (brikk-sql): "))
    }

    fun testAnEnginePositionGivenAsLineAndColumnIsPlaced() {
        postgresProfile.validator = ScriptedValidator { sql ->
            val bad = sql.indexOf("bad")
            if (bad < 0) VerifyResult.Accepted
            else VerifyResult.Rejected(VerifyError("by line", line = sql.take(bad).count { it == '\n' } + 1, column = bad - sql.lastIndexOf('\n', bad) ))
        }
        open("SELECT 1;\nSELECT 1,\n  bad;")
        assertEquals(listOf("bad" to "Engine: by line"), marks())
    }

    fun testTheLocalParserIsOffByDefault() {
        postgresProfile.validator = null
        open("SELECT * FROM t WHERE = 1;")
        assertEquals(listOf<Pair<String, String>>(), marks())
    }

    /** The IDE parser's errors as shown: PostgreSQL's PSI errors, from the platform or reported by the annotator. */
    private fun psiErrors(): List<HighlightInfo> = highlight().filter {
        it.severity == HighlightSeverity.ERROR && it.description?.startsWith("Engine") != true && it.description != "other annotator"
    }

    fun testTheIdesParserErrorsFollowTheEnginesVerdictWhenReported() {
        val text = "SELECT 1 FROM t QUALIFY x;\nSELECT 2 FROM bad QUALIFY y;\nSELECT 3 FROM t QUALIFY z;"
        postgresProfile.validator = ScriptedValidator { sql -> if ("3" in sql) VerifyResult.Unavailable("down") else rejectBad(sql) }
        open(text)
        val platform = psiErrors()
        assertTrue("PostgreSQL's parser rejects QUALIFY: $platform", platform.size >= 3)
        registerFilter()
        postgresProfile.reportsPsiErrors = true
        open(text)
        val second = text.indexOf("SELECT 2")
        val third = text.indexOf("SELECT 3")
        val shown = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        // Accepted: none; rejected: the engine's error instead; no verdict: the IDE parser's errors.
        assertTrue("$shown", shown.none { it.startOffset < second })
        val bad = text.indexOf("bad")
        val rejected = shown.filter { it.startOffset in second until third }
        assertEquals("Engine: no bad here", rejected.first().description)
        // Before the engine's first error its parser succeeded; the IDE parser's later errors stay.
        assertEquals(platform.filter { it.startOffset in bad until third }.map { it.description }, rejected.drop(1).map { it.description })
        assertEquals(platform.filter { it.startOffset >= third }.map { it.description to it.startOffset }, shown.filter { it.startOffset >= third }.map { it.description to it.startOffset })
    }

    fun testOtherAnnotatorsErrorsStayInAcceptedStatements() {
        registerFilter()
        postgresProfile.reportsPsiErrors = true
        open("SELECT 1 FROM t QUALIFY x;")
        com.intellij.lang.LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, { element, holder ->
            if (element is PsiFile) holder.newAnnotation(HighlightSeverity.ERROR, "other annotator").range(TextRange(0, 6)).create()
        }, testRootDisposable)
        val shown = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("other annotator"), shown.map { it.description })
    }

    fun testASecondPassAtTheSameRevisionReusesTheVerdicts() {
        open("SELECT bad;")
        assertEquals(listOf("bad" to "Engine: no bad here"), marks())
        assertEquals(listOf("bad" to "Engine: no bad here"), marks())
        assertEquals(1, validator.asked.size)
        // A changed dependency asks again: the profile's count, then a new validator instance.
        postgresProfile.revision++
        marks()
        assertEquals(2, validator.asked.size)
        val replacement = ScriptedValidator(::rejectBad)
        postgresProfile.validator = replacement
        marks()
        assertEquals(1, replacement.asked.size)
    }

    fun testAnUnavailableEngineIsAskedAgain() {
        var available = false
        postgresProfile.validator = ScriptedValidator { if (available) rejectBad(it) else VerifyResult.Unavailable("connecting") }
        open("SELECT bad;")
        assertEquals(listOf<Pair<String, String>>(), marks())
        available = true
        assertEquals(listOf("bad" to "Engine: no bad here"), marks())
        assertEquals(2, validator.asked.size)
    }

    fun testFileDiagnosticsAreReadOnEveryPass() {
        open("SELECT 1;")
        assertEquals(listOf<Pair<String, String>>(), marks())
        postgresProfile.extra = listOf(SqlDiagnostic(DiagnosticSeverity.ERROR, "Engine (server): failed", 0, 6, DiagnosticSource.OTHER))
        assertEquals(listOf("SELECT" to "Engine (server): failed"), marks())
        assertEquals(1, validator.asked.size)
    }

    fun testAResultIsAppliedOnlyToItsRevision() {
        open("SELECT bad;")
        val annotator = PostgresErrorAnnotator()
        val outcome = annotator.doAnnotate(annotator.collectInformation(myFixture.file))
        assertEquals(listOf("no bad here"), recorded(annotator, outcome))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "  ") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(listOf<String>(), recorded(annotator, outcome))
    }

    /** Messages [annotator] creates when applying [outcome] to the current file. */
    private fun recorded(annotator: NativeErrorAnnotator, outcome: NativeErrorAnnotator.Outcome?): List<String> {
        val messages = mutableListOf<String>()
        lateinit var builder: AnnotationBuilder
        builder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationBuilder::class.java)) { _, method, _ ->
            if (method.name == "create") Unit else builder
        } as AnnotationBuilder
        val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, args ->
            if (method.name == "newAnnotation") messages += (args[1] as String).removePrefix("Engine: ")
            builder
        } as AnnotationHolder
        annotator.apply(myFixture.file, outcome, holder)
        return messages
    }

    fun testCancellationPropagatesUnchanged() {
        val canceled = ProcessCanceledException()
        val stopped = CancellationException("stop")
        for (failure in listOf<RuntimeException>(canceled, stopped)) {
            postgresProfile.validator = ScriptedValidator { throw failure }
            open("SELECT 1;")
            val annotator = PostgresErrorAnnotator()
            val snapshot = annotator.collectInformation(myFixture.file)
            val thrown = runCatching { annotator.doAnnotate(snapshot) }.exceptionOrNull()
            assertSame(failure, thrown)
        }
    }

    fun testAFailingValidatorGivesNoVerdict() {
        postgresProfile.validator = ScriptedValidator { throw IllegalStateException("validator bug") }
        open("SELECT bad;")
        assertEquals(listOf<Pair<String, String>>(), marks())
    }

    fun testTheSizeCapAndBudgetSkipVerification() {
        postgresProfile.sizeCap = 10
        open("SELECT bad;\nSELECT 1234567890 bad;")
        assertEquals(listOf("bad" to "Engine: no bad here"), marks())
        assertEquals(listOf("SELECT bad"), validator.asked)
        postgresProfile.sizeCap = null
        postgresProfile.budget = Duration.ZERO
        postgresProfile.validator = ScriptedValidator(::rejectBad)
        open("SELECT bad;")
        assertEquals(listOf<Pair<String, String>>(), marks())
        assertEquals(listOf<String>(), validator.asked)
    }

    fun testASlowEngineIsCutOffAtTheBudgetAndLocalResultsShow() {
        postgresProfile.localParser = true
        postgresProfile.budget = 300.milliseconds
        val text = "SELECT * FROM t WHERE = 1;"
        // One verifier ignores cancellation and is abandoned; the other stops when canceled.
        for (slow in listOf<(String) -> VerifyResult>(
            { Thread.sleep(3_000); VerifyResult.Accepted },
            { while (true) { ProgressManager.checkCanceled(); Thread.sleep(5) }; @Suppress("UNREACHABLE_CODE") VerifyResult.Accepted },
        )) {
            postgresProfile.validator = ScriptedValidator(slow)
            open(text)
            val started = System.nanoTime()
            val shown = marks()
            assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
            assertTrue("$shown", shown.single().second.startsWith("Engine (brikk-sql): "))
        }
    }

    fun testTheDebounceWaitsAndEndsOnCancellation() {
        postgresProfile.debounce = 300.milliseconds
        open("SELECT bad;")
        val annotator = PostgresErrorAnnotator()
        val snapshot = annotator.collectInformation(myFixture.file)
        val started = System.nanoTime()
        annotator.doAnnotate(snapshot)
        val elapsed = System.nanoTime() - started
        assertTrue("waited ${elapsed / 1_000_000} ms", elapsed >= 300.milliseconds.inWholeNanoseconds)
        // An edit cancels the pass's indicator while it waits.
        val indicator = EmptyProgressIndicator()
        val thrown = runCatching {
            ProgressManager.getInstance().runProcess({ indicator.cancel(); annotator.doAnnotate(snapshot) }, indicator)
        }.exceptionOrNull()
        assertTrue("$thrown", thrown is ProcessCanceledException)
    }

    fun testWholeFileVerificationAndPluginHooks() {
        postgresProfile.unit = VerificationUnit.File
        postgresProfile.extra = listOf(SqlDiagnostic(DiagnosticSeverity.ERROR, "Engine (server): failed", 0, 6, DiagnosticSource.OTHER))
        open("SELECT 1;\nSELECT bad;")
        assertSameElements(marks(), "SELECT" to "Engine (server): failed", "bad" to "Engine: no bad here")
        assertEquals(listOf("SELECT 1;\nSELECT bad;"), validator.asked)
        postgresProfile.unit = VerificationUnit.Statement
        postgresProfile.extra = emptyList()
        postgresProfile.custom = { script, statement ->
            if ("|>" in script.substring(statement.start, statement.end)) listOf(SqlDiagnostic(DiagnosticSeverity.ERROR, "Engine PIPE: refused", statement.start + 1, statement.start + 5, DiagnosticSource.OTHER)) else null
        }
        postgresProfile.validator = ScriptedValidator(::rejectBad)
        open("SELECT bad;\nFROM t |> bad;")
        assertSameElements(marks(), "bad" to "Engine: no bad here", "FROM" to "Engine PIPE: refused")
        assertEquals(listOf("SELECT bad"), validator.asked)
    }

    fun testAnAdvisoryValidatorWarns() {
        postgresProfile.validator = object : NativeSqlValidator by ScriptedValidator(::rejectBad) {
            override val advisory = true
        }
        open("SELECT bad;")
        assertEquals(HighlightSeverity.WARNING, myFixture.doHighlighting().single { it.description == "Engine: no bad here" }.severity)
    }

    private var injectedRegistered = false

    /** Opens [hostText], a PostgreSQL file, with every string literal joined into one injected MySQL fragment. */
    private fun openInjected(hostText: String, frankenstein: Boolean = false, prefix: String? = null, suffix: String? = null) {
        myFixture.configureDialect("PostgreSQL", hostText)
        val manager = InjectedLanguageManager.getInstance(project)
        manager.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (manager.isInjectedFragment(context.containingFile)) return
                val literals = PsiTreeUtil.findChildrenOfType(context.containingFile, SqlStringLiteralExpression::class.java).toList()
                if (context != literals.firstOrNull()) return
                registrar.startInjecting(mysql)
                for (literal in literals) registrar.addPlace(prefix, suffix, literal as PsiLanguageInjectionHost, TextRange(1, literal.textLength - 1))
                if (frankenstein) registrar.frankensteinInjection(true)
                registrar.doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        manager.dropFileCaches(myFixture.file)
        mysqlProfile.injectionHosts = setOf(postgres)
        if (!injectedRegistered) annotate(postgres, MysqlErrorAnnotator())
        injectedRegistered = true
    }

    /** Errors shown in the injected fragment that are neither the engine's nor the host's. */
    private fun fragmentErrors(): List<Pair<String?, Int>> = highlight()
        .filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("Injected") != true && it.startOffset > 8 }
        .map { it.description to it.startOffset }

    fun testTheFilterHidesNothingWhileTheAnnotatorDoesNotReport() {
        open("SELECT 1 FROM t QUALIFY x;")
        val platform = psiErrors()
        assertTrue(platform.isNotEmpty())
        registerFilter()
        assertEquals(platform.map { it.description to it.startOffset }, psiErrors().map { it.description to it.startOffset })
    }

    fun testAnInjectedFragmentsPsiErrorsAreReportedOnceAndFollowTheVerdict() {
        mysqlProfile.validator = ScriptedValidator { VerifyResult.Unavailable("down") }
        val text = "SELECT 'SELECT 1 FROM t QUALIFY x y';"
        openInjected(text)
        val platform = fragmentErrors()
        assertTrue("MySQL's parser rejects the fragment: $platform", platform.isNotEmpty())
        mysqlProfile.reportsPsiErrors = true
        registerFilter(MysqlPsiFilter())
        assertEquals(platform, fragmentErrors())
        mysqlProfile.validator = ScriptedValidator { VerifyResult.Accepted }
        assertEquals(listOf<Pair<String?, Int>>(), fragmentErrors())
    }

    fun testAFragmentInAnUndeclaredHostKeepsThePlatformsPsiErrors() {
        val text = "SELECT 'SELECT 1 FROM t QUALIFY x y';"
        openInjected(text)
        val platform = fragmentErrors()
        mysqlProfile.injectionHosts = emptySet()
        mysqlProfile.reportsPsiErrors = true
        mysqlProfile.validator = ScriptedValidator { VerifyResult.Accepted }
        registerFilter(MysqlPsiFilter())
        assertEquals(platform, fragmentErrors())
        assertEquals(listOf<String>(), (mysqlProfile.validator as ScriptedValidator).asked)
    }

    fun testAFrankensteinFragmentIsNotCheckedAndKeepsThePlatformsPsiErrors() {
        mysqlProfile.validator = ScriptedValidator { VerifyResult.Accepted }
        openInjected("SELECT 'SELECT 1 FROM t QUALIFY x y bad';", frankenstein = true)
        val platform = fragmentErrors()
        mysqlProfile.reportsPsiErrors = true
        registerFilter(MysqlPsiFilter())
        assertEquals(platform, fragmentErrors())
        assertEquals(listOf<String>(), (mysqlProfile.validator as ScriptedValidator).asked)
    }

    fun testInjectedSqlIsMarkedAtItsHostText() {
        // Escaped quotes and a CR LF escape before the error, a supplementary character, and two fragments.
        openInjected("SELECT E'SELECT \\'𝄞\\';\\r\\nSELECT 1 ', ' + bad';")
        assertEquals(listOf("bad" to "Injected: no bad here"), marks("Injected"))
        val asked = (mysqlProfile.validator as ScriptedValidator).asked
        assertEquals(listOf("SELECT '𝄞'", "\r\nSELECT 1  + bad"), asked)
    }

    fun testAnErrorOnAnEscapeMarksItsWholeHostText() {
        mysqlProfile.validator = ScriptedValidator { sql ->
            VerifyResult.Rejected(VerifyError.atOffset(sql, "on quote", sql.indexOf('\''), sql.indexOf('\'') + 1))
        }
        openInjected("SELECT E'SELECT \\'x';")
        assertEquals(listOf("\\'" to "Injected: on quote"), marks("Injected"))
    }

    fun testAnErrorInAPrefixOrSuffixMarksTheNearestEditableText() {
        for ((word, expected) in listOf("SELECT" to "c", "FROM" to "d")) {
            mysqlProfile.validator = ScriptedValidator { sql -> VerifyResult.Rejected(VerifyError.atOffset(sql, "outside", sql.indexOf(word), sql.indexOf(word) + word.length)) }
            openInjected("SELECT 'col bad';", prefix = "SELECT ", suffix = " FROM t")
            assertEquals(word, listOf(expected to "Injected: outside"), marks("Injected"))
            assertEquals(listOf("SELECT col bad FROM t"), (mysqlProfile.validator as ScriptedValidator).asked)
        }
    }

    fun testEachConsumerActsOnlyOnItsOwnLanguageAndEachRangeOnce() {
        openInjected("SELECT bad, 'SELECT bad';")
        annotate(postgres, PostgresErrorAnnotator())
        // A consumer of the base SQL language finds no file of exactly its language here.
        annotate(com.intellij.sql.psi.SqlLanguage.INSTANCE, object : NativeErrorAnnotator(TestProfile(com.intellij.sql.psi.SqlLanguage.INSTANCE, "postgres", "Base")) {})
        val infos = myFixture.doHighlighting().filter { it.description?.contains("no bad here") == true }
        assertSameElements(infos.map { it.description to it.startOffset }, "Engine: no bad here" to 7, "Injected: no bad here" to 20)
        // Each consumer keeps its own results on the shared host file.
        highlight()
        assertEquals(1, validator.asked.size)
        assertEquals(1, (mysqlProfile.validator as ScriptedValidator).asked.size)
    }

    fun testARealDuckdbVerifierEndToEnd() {
        ParseOnlyDuckdbVerifier(DuckDBDriver()).use { verifier ->
            postgresProfile.validator = NativeSqlValidator.of(verifier)
            val text = "SELECT 1;\nSELECT * FROM t WHERE x = = 1;\nSELECT 2;"
            open(text)
            val error = highlight().single { it.description?.startsWith("Engine: ") == true }
            assertTrue(error.description, "syntax error" in error.description)
            // DuckDB reports the second '=', and the mark covers exactly it.
            assertEquals(text.indexOf("= 1"), error.startOffset)
            assertEquals("=", text.substring(error.startOffset, error.endOffset))
        }
    }
}
