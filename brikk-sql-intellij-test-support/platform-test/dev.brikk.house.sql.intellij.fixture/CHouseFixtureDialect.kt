package dev.brikk.house.sql.intellij.fixture

import com.intellij.database.Dbms
import com.intellij.icons.AllIcons
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.dialects.clickhouse.CHouseParser
import com.intellij.sql.dialects.clickhouse.CHouseParserDefinition
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.dialect.CHouseFamilyDialect
import dev.brikk.house.sql.intellij.parser.parseLenientStatement
import dev.brikk.house.sql.intellij.parser.wordAt
import java.util.UUID

/** A ClickHouse-family dialect built only from support classes, the way a plugin builds its own. */
object CHouseFixtureDialect : CHouseFamilyDialect("BrikkCHouseFixtureSQL") {
    override fun getDbms(): Dbms = CHouseFixtureDbms.DBMS
}

object CHouseFixtureDbms {
    val DBMS: Dbms = Dbms.create("BRIKK_CHOUSE_FIXTURE", "Brikk ClickHouse fixture", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("brikkchousefixture"))
}

/** Parses SHOW statements leniently, as a plugin does for statements the grammar lacks. */
class CHouseFixtureParser : CHouseParser() {
    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean =
        if (builder.wordAt(0) == "SHOW") builder.parseLenientStatement() else super.parseSqlStatement(builder, level)
}

class CHouseFixtureParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = CHouseParserDefinition().elementFactory
    override fun createLexer(project: Project?): Lexer = CHouseLexer()
    override fun createParser(project: Project?): PsiParser = CHouseFixtureParser()
    override fun getFileNodeType(): IFileElementType = CHOUSE_FIXTURE_FILE
}

private val CHOUSE_FIXTURE_FILE = SqlFileElementType("BRIKK_CHOUSE_FIXTURE_SQL_FILE", CHouseFixtureDialect)

class CHouseFixtureHighlighterFactory : SqlSyntaxHighlighterFactory.Base(CHouseFixtureDialect)

/** A light test whose ClickHouse-family fixture dialect parses and highlights through the support classes. */
abstract class CHouseFixtureTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(CHouseFixtureDialect, CHouseFixtureParserDefinition(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(CHouseFixtureDialect, CHouseFixtureHighlighterFactory(), testRootDisposable)
    }

    /** Opens [text] in a fixture-dialect file. */
    protected fun configureCHouse(text: String) {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("chouse-${UUID.randomUUID()}.sql", CHouseFixtureDialect, text))
        check(myFixture.file.language == CHouseFixtureDialect) { "${myFixture.file.language} is not the fixture dialect" }
    }

    /** The file's top-level statements. */
    protected fun statements(): List<SqlStatement> = PsiTreeUtil.getChildrenOfTypeAsList(myFixture.file, SqlStatement::class.java)
}
