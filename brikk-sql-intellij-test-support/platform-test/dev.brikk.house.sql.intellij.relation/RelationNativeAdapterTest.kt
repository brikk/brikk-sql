package dev.brikk.house.sql.intellij.relation

import com.intellij.database.types.DasType
import com.intellij.database.types.DasTypeSystemBase
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.dialects.postgres.PgParser
import com.intellij.sql.dialects.postgres.PgParserDefinition
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.impl.SqlDasTypeAwareDeepRecursiveElement
import com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

/**
 * The native adapter as a plugin installs it, a function-call element typed by
 * [RelationTableTypes.of], with the provider's columns declared in a host language (here a named
 * word of a text file, found by word like a Kotlin property): references resolve to the
 * declarations, so the platform finds and renames them.
 */
class RelationNativeAdapterTest : RelationTestCase() {
    private lateinit var declarations: Map<String, Declaration>

    /** A host-language declaration of [name] at [offset] of [file]. */
    private class Declaration(private val file: PsiFile, private val offset: Int, private var name: String) :
        LightElement(file.manager, PlainTextLanguage.INSTANCE), PsiNamedElement {
        override fun getName(): String = name

        override fun setName(name: String): PsiElement = also { this.name = name }

        override fun getContainingFile(): PsiFile = file

        override fun getTextRange(): TextRange = TextRange.from(offset, name.length)

        override fun isWritable(): Boolean = true

        override fun isValid(): Boolean = true

        override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

        override fun toString(): String = "Declaration $name"
    }

    override fun setUp() {
        super.setUp()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(postgres, object : SqlParserDefinitionBase() {
            override fun createElementFactory(): SqlElementFactoryBase = object : PgElementFactory() {
                override fun createElementNode(type: IElementType): CompositeElement? =
                    if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) SlotCall(type) else super.createElementNode(type)
            }

            override fun createLexer(project: Project?): Lexer = PgLexer()

            override fun createParser(project: Project?): PsiParser = PgParser(false)

            override fun getFileNodeType(): IFileElementType = PgParserDefinition.PG_SQL_FILE
        }, testRootDisposable)
        val file = myFixture.addFileToProject("Declarations.txt", "id kind")
        declarations = listOf("id", "kind").associateWith { Declaration(file, file.text.indexOf(it), it) }
        pgRelations.fake.targets = { _, provenance -> declarations[provenance.removePrefix("decl:")] }
    }

    private class SlotCall(type: IElementType) : SqlFunctionCallExpressionImpl(type) {
        override suspend fun DeepRecursiveScope<SqlDasTypeAwareDeepRecursiveElement, DasType>.computeDasType(): DasType =
            RelationTableTypes.of(this@SlotCall, pgRelations) ?: DasTypeSystemBase.UNKNOWN
    }

    /** Opens [text] as a project file, which project-wide searches see. */
    private fun openInProject(text: String) {
        val file = myFixture.addFileToProject("query.sql", text)
        setSqlDialectMapping(project, file.virtualFile, postgres as SqlLanguageDialect, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        check(myFixture.file.language == postgres)
    }

    private fun resolved(word: String): PsiElement? =
        myFixture.file.findReferenceAt(Regex("\\b$word\\b").find(myFixture.file.text)!!.range.first)?.resolve()

    fun testReferencesResolveToTheProvidersDeclarationsStably() {
        open("SELECT e.id, e.kind FROM events() e")
        assertEquals(declarations["id"], resolved("id")?.navigationElement)
        assertEquals(declarations["kind"], resolved("kind")?.navigationElement)
        assertTrue(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("id"))!!.isReferenceTo(declarations.getValue("id")))
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpressionImpl::class.java).single()
        assertEquals(RelationTableTypes.of(call, pgRelations)?.getColumnElement(0), RelationTableTypes.of(call, pgRelations)?.getColumnElement(0))
    }

    fun testFindUsagesOfADeclarationFindsTheSql() {
        openInProject("SELECT e.id FROM events() e WHERE e.id > 0")
        val usages = ReferencesSearch.search(checkNotNull(declarations["id"])).findAll().filter { it.element.containingFile == myFixture.file }
        assertEquals(2, usages.size)
    }

    fun testRenamingADeclarationRenamesTheSql() {
        openInProject("SELECT e.id FROM events() e")
        myFixture.renameElement(checkNotNull(declarations["id"]), "key")
        assertEquals("SELECT e.key FROM events() e", myFixture.editor.document.text)
    }

    fun testWithoutADeclarationAColumnLeadsToTheCall() {
        pgRelations.fake.targets = { _, _ -> null }
        open("SELECT e.id FROM events() e")
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpressionImpl::class.java).single()
        val column = RelationTableTypes.of(call, pgRelations)?.getColumnElement(0)
        assertEquals(column, RelationTableTypes.of(call, pgRelations)?.getColumnElement(0))
        assertEquals(call, column?.navigationElement)
        assertEquals(call, resolved("id")?.navigationElement)
    }
}
