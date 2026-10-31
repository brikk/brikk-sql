package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.*
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.LanguageDocumentation
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.extensions.*
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.*
import dev.brikk.house.sql.intellij.docs.DatafusionDocumentationProvider
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.relation.*
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.scope.*
import java.util.UUID

class DatafusionTestEditorProfile(language: DatafusionFamilyDialect) : DatafusionEditorProfile(language) {
    val fake = FakeFacts(RelationFacts())
    var version: String? = null
    override val providers = listOf(fake)
    override fun serverVersion(file: PsiFile) = version
}

class DfFunctions : SqlCompletionContributor(DatafusionFixtureDialect.editorProfile)
class SpFunctions : SqlCompletionContributor(SpiceFixtureDialect.editorProfile)
class DfFilter : SqlBuiltinPrototypeFilter(DatafusionFixtureDialect.editorProfile)
class SpFilter : SqlBuiltinPrototypeFilter(SpiceFixtureDialect.editorProfile)
class DfArguments : DatafusionArgumentCompletion(DatafusionFixtureDialect.editorProfile)
class SpArguments : DatafusionArgumentCompletion(SpiceFixtureDialect.editorProfile)
class DfColumns : ExternalRelationCompletionContributor(DatafusionFixtureDialect.editorProfile)
class SpColumns : ExternalRelationCompletionContributor(SpiceFixtureDialect.editorProfile)
class DfResolve : ExternalRelationResolveExtension(DatafusionFixtureDialect.editorProfile)
class SpResolve : ExternalRelationResolveExtension(SpiceFixtureDialect.editorProfile)
class DfRelations : ExternalRelationAnnotator(DatafusionFixtureDialect.editorProfile)
class SpRelations : ExternalRelationAnnotator(SpiceFixtureDialect.editorProfile)
class DfRelationSuppressor : ExternalRelationInspectionSuppressor(DatafusionFixtureDialect.editorProfile)
class SpRelationSuppressor : ExternalRelationInspectionSuppressor(SpiceFixtureDialect.editorProfile)
class DfFunctionsCheck : DatafusionFunctionAnnotator(DatafusionFixtureDialect.editorProfile)
class SpFunctionsCheck : DatafusionFunctionAnnotator(SpiceFixtureDialect.editorProfile)
class DfGoto : ExternalRelationGotoDeclarationHandler(DatafusionFixtureDialect.editorProfile)
class SpGoto : ExternalRelationGotoDeclarationHandler(SpiceFixtureDialect.editorProfile)

class DatafusionEditorFeaturesTest : BasePlatformTestCase() {
    private val dialects get() = listOf(DatafusionFixtureDialect, SpiceFixtureDialect)
    override fun setUp() {
        super.setUp()
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete }
        for (dialect in dialects) {
            val profile = dialect.editorProfile as DatafusionTestEditorProfile
            profile.fake.facts = FACTS
            profile.fake.revision++
            profile.fake.pending = false
            profile.fake.targets = { _, _ -> null }
            profile.fake.refusesWriteAccess = true
            profile.version = null
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(dialect, DatafusionFamilyParserDefinition(dialect), testRootDisposable)
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(dialect, object : SqlSyntaxHighlighterFactory.Base(dialect) {}, testRootDisposable)
            LanguageDocumentation.INSTANCE.addExplicitExtension(dialect, DatafusionDocumentationProvider(profile), testRootDisposable)
            val classes = if (dialect === SpiceFixtureDialect) listOf(SpArguments::class.java, SpFilter::class.java, SpColumns::class.java, SpFunctions::class.java)
                else listOf(DfArguments::class.java, DfFilter::class.java, DfColumns::class.java, DfFunctions::class.java)
            val descriptor = DefaultPluginDescriptor(PluginId.getId("brikk.datafusion.editor.test"), javaClass.classLoader)
            for (type in classes) CompletionContributor.EP.point.registerExtension(CompletionContributorEP(dialect.id, type.name, descriptor), LoadingOrder.FIRST, testRootDisposable)
        }
        SqlResolveExtension.EP_NAME.point.registerExtension(PgFamilyResolveExtension(), testRootDisposable)
        SqlResolveExtension.EP_NAME.point.registerExtension(DfResolve(), testRootDisposable)
        SqlResolveExtension.EP_NAME.point.registerExtension(SpResolve(), testRootDisposable)
        for (suppressor in listOf(DfRelationSuppressor(), SpRelationSuppressor()))
            SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(suppressor, testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(DatafusionFixtureDialect, DfRelations(), testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(SpiceFixtureDialect, SpRelations(), testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(DatafusionFixtureDialect, DfFunctionsCheck(), testRootDisposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(SpiceFixtureDialect, SpFunctionsCheck(), testRootDisposable)
    }

    private fun open(sql: String, dialect: DatafusionFamilyDialect = SpiceFixtureDialect) {
        val caret = sql.indexOf("<caret>")
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("editor-${UUID.randomUUID()}.sql", dialect, sql.replace("<caret>", "")))
        if (caret >= 0) myFixture.editor.caretModel.moveToOffset(caret)
    }
    private fun complete(sql: String, dialect: DatafusionFamilyDialect = SpiceFixtureDialect): List<LookupElement> {
        open(sql, dialect)
        return myFixture.completeBasic().orEmpty().toList()
    }
    private fun functions(sql: String, dialect: DatafusionFamilyDialect = SpiceFixtureDialect) = complete(sql, dialect).mapNotNull { it.`object` as? FunctionPresentation }

    fun testFunctionKindsNativeNamesAndInheritedFiltering() {
        assertTrue(functions("SELECT * FROM vec<caret>").any { it.name == "vector_search" && it.kind == FunctionKind.TABLE_VALUED })
        assertTrue(functions("SELECT vec<caret>").none { it.name == "vector_search" })
        assertTrue(functions("SELECT * FROM rrf(vec<caret>").any { it.name == "vector_search" })
        assertTrue(functions("SELECT * FROM rrf(vector_search(docs,'q'), text_<caret>").any { it.name == "text_search" })
        assertTrue(functions("SELECT * FROM docs WHERE su<caret>").none { it.kind == FunctionKind.AGGREGATE })
        for (dialect in dialects) {
            assertTrue(complete("SELECT pg_sleep<caret>", dialect).none { it.lookupString.equals("pg_sleep", true) && it.`object` is BuiltinFunction.Prototype })
            assertTrue(functions("SELECT try_ca<caret>('1' AS INT)", dialect).any { it.name.equals("TRY_CAST", true) })
            assertTrue(functions("SELECT * FROM docs WHERE row_<caret>", dialect).none { it.kind == FunctionKind.WINDOW })
        }
    }

    fun testVersionAndAvailabilityLabelsDoNotInventSignatures() {
        val item = complete("SELECT ai_dec<caret>(body, '{}') FROM docs").single { it.`object` is FunctionPresentation && it.lookupString == "ai_decide" }
        val presentation = LookupElementPresentation.renderElement(item)
        assertTrue(presentation.tailText.orEmpty(), presentation.tailText.orEmpty().contains("spice 2.4.0"))
        assertTrue(presentation.tailText.orEmpty().contains("signature unknown"))
        assertTrue(presentation.tailText.orEmpty().contains("model configuration"))
        assertNull((item.`object` as FunctionPresentation).signature)
        SpiceFixtureDialect.editorProfile.version = "2.4.0"
        val exact = complete("SELECT ai_dec<caret>").first { it.lookupString == "ai_decide" }
        assertFalse(LookupElementPresentation.renderElement(exact).tailText.orEmpty().contains("fallback"))
    }

    fun testNamedLabelsInsertOneArrowAndKeepExistingValue() {
        val items = complete("SELECT * FROM vector_search(docs,'q', lim<caret>)")
        val item = items.single { it.lookupString == "limit" && LookupElementPresentation.renderElement(it).typeText == "vector_search argument" }
        assertEquals(items.map { it.lookupString }.toString(), 1, items.count { it.lookupString.equals("limit", true) })
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT * FROM vector_search(docs,'q', limit => )", myFixture.editor.document.text)
        val existing = complete("SELECT * FROM vector_search(docs,'q', lim<caret> => 5)")
            .single { it.lookupString == "limit" && LookupElementPresentation.renderElement(it).typeText == "vector_search argument" }
        myFixture.lookup.currentItem = existing
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT * FROM vector_search(docs,'q', limit => 5)", myFixture.editor.document.text)
        val commented = complete("SELECT * FROM vector_search(docs,'q', lim<caret> /* option */ => 5)")
            .single { it.lookupString == "limit" && LookupElementPresentation.renderElement(it).typeText == "vector_search argument" }
        myFixture.lookup.currentItem = commented
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT * FROM vector_search(docs,'q', limit /* option */ => 5)", myFixture.editor.document.text)
        assertTrue(complete("SELECT * FROM vector_search(docs,'q', limit => 5, lim<caret>)").none { LookupElementPresentation.renderElement(it).typeText == "vector_search argument" })
    }

    fun testDatasetsArgumentsNestedFieldsAndDynamicOutputsComplete() {
        assertTrue(complete("SELECT * FROM vector_search(do<caret>, 'q')").any { it.lookupString == "docs" })
        assertTrue(complete("SELECT * FROM vector_search(docs, 'q', bo<caret>)").any { it.lookupString == "body" })
        val columns = complete("SELECT s.<caret> FROM vector_search(docs,'q') s").map { it.lookupString }
        assertEquals(1, columns.count { it == "id" })
        assertTrue(columns.toString(), columns.containsAll(listOf("body", "_score")))
        assertTrue(complete("SELECT d.payload.<caret> FROM docs d").any { it.lookupString == "name" })
        assertTrue(complete("SELECT CAST(1 AS STR<caret>)", DatafusionFixtureDialect).any { it.lookupString == "STRING" })
        assertTrue(complete("WITH q AS (SELECT ai_decide(body, '{\"urgent\":{\"type\":\"noul\",\"instructions\":\"Urgent?\"}}') AS decision FROM docs) SELECT q.decision.<caret> FROM q").any { it.lookupString == "urgent" })
    }

    fun testCaseSensitiveRelationsAndQuotedColumnInsertion() {
        val profile = SpiceFixtureDialect.editorProfile
        profile.fake.facts = RelationFacts(tables = mapOf("Cat.Docs" to RelationFact(listOf(ColumnFact("MiXeD", "VARCHAR"))), "cat.other" to RelationFact()))
        profile.fake.revision++
        val datasets = complete("SELECT * FROM vector_search(Cat.<caret>, 'q')").map { it.lookupString }
        assertTrue(datasets.toString(), "Docs" in datasets)
        assertFalse("other" in datasets)
        val column = complete("SELECT d.mi<caret> FROM Cat.Docs d").single { it.lookupString == "MiXeD" }
        myFixture.lookup.currentItem = column
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT d.\"MiXeD\" FROM Cat.Docs d", myFixture.editor.document.text)
    }

    fun testCallSpecificFactsSurviveMergeAndPendingDoesNotLeakStaleResults() {
        val sql = "SELECT s.<caret> FROM vector_search(docs,'q') s"
        val text = sql.replace("<caret>", "")
        val profile = SpiceFixtureDialect.editorProfile
        profile.fake.facts = FACTS.copy(callSites = mapOf(text.indexOf("vector_search") to RelationFact(listOf(ColumnFact("exact_only", "INT", provenance = "call.column")))))
        profile.fake.revision++
        assertTrue(complete(sql).any { it.lookupString == "exact_only" })
        val target = myFixture.addFileToProject("call-column.txt", "exact column")
        profile.fake.targets = { _, provenance -> if (provenance == "call.column") target else null }
        val query = "SELECT s.exact_only FROM vector_search(docs,'q') s"
        profile.fake.facts = FACTS.copy(callSites = mapOf(query.indexOf("vector_search") to profile.fake.facts.callSites.values.single()))
        profile.fake.revision++
        open(query)
        val at = query.indexOf("exact_only")
        assertEquals(listOf(target), SpGoto().getGotoDeclarationTargets(myFixture.file.findElementAt(at), at, myFixture.editor).orEmpty().toList())
        profile.fake.pending = true
        assertTrue(complete(sql).none { it.lookupString == "exact_only" })
    }

    fun testFunctionDocsUseEvidenceAndLeaveColumnNamesAlone() {
        open("SELECT ai_decide(body, '{}', model => 'local') FROM docs")
        val docs = DatafusionDocumentationProvider(SpiceFixtureDialect.editorProfile)
        val offset = myFixture.file.text.indexOf("ai_decide")
        val target = docs.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(offset), offset)!!
        val html = docs.generateDoc(target, null)!!
        assertTrue(html, html.contains("Signature unknown"))
        assertTrue(html.contains("2.4.0"))
        assertTrue(html.contains("model configuration"))
        assertEquals(listOf("https://spiceai.org/docs/reference/sql/ai"), docs.getUrlFor(target, null))
        val body = myFixture.file.text.indexOf("body")
        assertNull(docs.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(body), body))
        open("SELECT sqrt(4)", DatafusionFixtureDialect)
        val df = DatafusionDocumentationProvider(DatafusionFixtureDialect.editorProfile)
        val sqrt = myFixture.file.text.indexOf("sqrt")
        val known = df.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(sqrt), sqrt)!!
        assertTrue(df.generateDoc(known, null)!!.contains("Float32"))
        assertFalse(df.generateDoc(known, null)!!.contains("Signature unknown"))
        myFixture.editor.caretModel.moveToOffset(sqrt + 2)
        val managed = DocumentationManager.getInstance(project).findTargetElement(myFixture.editor, myFixture.file)
        assertNotNull(managed)
        assertEquals(listOf("https://datafusion.apache.org/user-guide/sql/scalar_functions.html"), DocumentationManager.getProviderFromElement(managed!!).getUrlFor(managed, null))
    }

    fun testInjectedDocsAndNavigationKeepDecodedOffsets() {
        val text = "SELECT 'SELECT ''😀'' AS label, s.body FROM vector_search(docs, ''q'') s'"
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("injected-${UUID.randomUUID()}.sql", PgDialect.INSTANCE, text))
        val literal = PsiTreeUtil.findChildOfType(myFixture.file, SqlStringLiteralExpression::class.java)!!
        val manager = InjectedLanguageManager.getInstance(project)
        manager.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (context !== literal) return
                registrar.startInjecting(SpiceFixtureDialect).addPlace(null, null, literal, TextRange(1, literal.textLength - 1)).doneInjecting()
            }
            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        val injected = manager.getInjectedPsiFiles(literal)!!.single().first.containingFile
        val target = myFixture.addFileToProject("injected-body.txt", "body definition")
        SpiceFixtureDialect.editorProfile.fake.targets = { _, id -> if (id == "docs.body") target else null }
        SpiceFixtureDialect.editorProfile.fake.revision++
        val body = injected.text.indexOf("body")
        assertEquals(listOf(target), SpGoto().getGotoDeclarationTargets(injected.findElementAt(body), body, myFixture.editor).orEmpty().toList())
        val name = injected.text.indexOf("vector_search")
        val docs = DatafusionDocumentationProvider(SpiceFixtureDialect.editorProfile)
        val element = docs.getCustomDocumentationElement(myFixture.editor, injected, injected.findElementAt(name), name)!!
        assertTrue(docs.getUrlFor(element, null)!!.single().contains("search"))
        val mapped = manager.injectedToHost(injected, TextRange(body, body + 4))
        assertEquals("body", text.substring(mapped.startOffset, mapped.endOffset))
    }

    fun testNativeFunctionsAndLabelsResolveAndPhysicalColumnsNavigate() {
        open("SELECT s.body FROM vector_search(docs, 'q', limit => 3) s")
        val call = PsiTreeUtil.findChildOfType(myFixture.file, SqlFunctionCallExpression::class.java)!!
        assertNotNull(call.nameElement!!.resolveSymbol())
        val labels = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlReferenceExpression::class.java).filter { it.text == "limit" }
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.all { it.resolveSymbol() != null })
        val target = myFixture.addFileToProject("body.txt", "body definition")
        SpiceFixtureDialect.editorProfile.fake.targets = { _, id -> if (id == "docs.body") target else null }
        SpiceFixtureDialect.editorProfile.fake.revision++
        val at = myFixture.file.text.indexOf("body")
        val destinations = SpGoto().getGotoDeclarationTargets(myFixture.file.findElementAt(at), at, myFixture.editor)
        assertEquals(listOf(target), destinations.orEmpty().toList())
    }

    fun testDiagnosticsAreConservativeWithUnknownAndPartialMetadata() {
        for (name in listOf("SqlResolveInspection", "SqlSignatureInspection", "SqlTypeInspection")) {
            val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".$name") }
            myFixture.enableInspections(ep.instantiateTool() as LocalInspectionTool)
        }
        open("SELECT sqrt(1,2)", DatafusionFixtureDialect)
        assertTrue(myFixture.doHighlighting().any { it.description.orEmpty().contains("expects 1 arguments") })
        open("SELECT sqrt([1,2])", DatafusionFixtureDialect)
        assertTrue(myFixture.doHighlighting().any { it.description.orEmpty().contains("numeric argument") })
        open("SELECT vector_search(docs,'q')")
        assertTrue(myFixture.doHighlighting().any { it.description.orEmpty().contains("scalar registration") })
        open("SELECT s.unknown FROM vector_search(docs,'q') s")
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).none { it.description.orEmpty().contains("unknown", true) })
        open("SELECT id FROM docs d CROSS JOIN docs e")
        assertTrue(myFixture.doHighlighting().any { it.description.orEmpty().contains("Ambiguous column") })
        open("SELECT sqrt(1,2)")
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).none { it.description.orEmpty().contains("argument", true) })
    }

    fun testLocalAliasRenameKeepsStringLiteralsAndNamedLabels() {
        open("WITH q AS (SELECT 1 AS model) SELECT model, 'model', ai_if('x','y', model => 'model') FROM q")
        val alias = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlAsExpression::class.java).first { it.name == "model" }
        RenameProcessor(project, alias, "renamed", false, false).run()
        assertTrue(myFixture.file.text, myFixture.file.text.contains("AS renamed", true))
        assertTrue(myFixture.file.text.contains("SELECT renamed", true))
        assertTrue(myFixture.file.text.contains("'model'"))
        assertTrue(myFixture.file.text.contains("model => 'model'"))
        open("SELECT [id, 2] AS result FROM docs ORDER BY result")
        val arrayAlias = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlAsExpression::class.java).first { it.name == "result" }
        RenameProcessor(project, arrayAlias, "values_out", false, false).run()
        assertTrue(myFixture.file.text, myFixture.file.text.contains("AS values_out", true))
        assertTrue(myFixture.file.text.contains("ORDER BY values_out", true))
    }

    private companion object {
        val FACTS = RelationFacts(tables = mapOf("docs" to RelationFact(listOf(
            ColumnFact("id", "BIGINT", origin = ColumnOrigin.PHYSICAL_TABLE),
            ColumnFact("body", "VARCHAR", origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "docs.body"),
            ColumnFact("payload", "Struct(\"name\": Utf8)", origin = ColumnOrigin.PHYSICAL_TABLE)
        ))))
    }
}
