package dev.brikk.house.sql.intellij.fixture

import com.intellij.database.Dbms
import com.intellij.database.console.session.DatabaseSession
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.dialects.postgres.PgParser
import com.intellij.sql.psi.SqlTokens
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.dialect.PgFamilyDialect
import dev.brikk.house.sql.intellij.lexer.CastTargetMask
import dev.brikk.house.sql.intellij.lexer.MaskingSqlLexer
import dev.brikk.house.sql.intellij.parser.parseColonParameter
import dev.brikk.house.sql.intellij.parser.parseLenientStatement
import dev.brikk.house.sql.intellij.parser.wordAt
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeStatementGate
import java.util.UUID

/** A small dialect built only from support classes, the way a plugin builds its own. */
object FixtureDialect : PgFamilyDialect("BrikkFixtureSQL") {
    override fun getDbms(): Dbms = FixtureDbms.DBMS
}

/** Apart from the dialect, whose base constructor already asks for its dbms. */
object FixtureDbms {
    val DBMS: Dbms = Dbms.create("BRIKK_FIXTURE", "Brikk fixture", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("brikkfixture"))
}

/**
 * Rules in the shape of the plugins' own: a routine body after FUNCTION, a star EXCLUDE list, a
 * STRUCT type after `::`, `AT LOCAL`, brace literals, and TRY_CAST targets.
 */
class FixtureLexer : MaskingSqlLexer(PgLexer(), CastTargetMask()) {
    private var routine = false

    override fun resetStatement() {
        routine = false
    }

    override fun onNote(token: String) {
        if (token == "FUNCTION") routine = true
    }

    override fun rule(base: Lexer, upper: String): Boolean {
        when {
            upper == "BEGIN" && routine && parenDepth == 0 -> collapseRoutineBody(base)
            upper == "EXCLUDE" && lastMeaningful == "*" && nextIs(base, "(") -> maskWithGroup(base)
            upper == "STRUCT" && lastMeaningful == "::" && nextIs(base, "(") -> collapseWithGroup(base, SqlTokens.SQL_IDENT, "TYPE")
            upper == "AT" && wordsFollow(base, "LOCAL") -> maskWords(base, 2)
            upper == "{" -> {
                consumeBalanced(base, "{", "}")
                span(base, SqlTokens.SQL_STRING_TOKEN, "}")
            }
            else -> return false
        }
        return true
    }
}

/** PIPE in fixture files, claiming FROM-first statements when [claimFromFirst] is set. */
object FixturePipeDialect : PipeDialect {
    override val dialect = "trino"
    override val label = "Fixture PIPE"
    override val vetoProperty = "brikk.fixture.pipes"
    override val language: Language = FixtureDialect
    var enabled = true
    var claimFromFirst = false
    override fun isProjectEnabled(project: Project) = enabled
    override fun ownsSession(session: DatabaseSession) = false
}

class FixtureParser : PgParser(false) {
    private val gate = PipeStatementGate(FixturePipeDialect)

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean = when {
        gate.parse(builder, claim = FixturePipeDialect.claimFromFirst && builder.wordAt(0) == "FROM") -> true
        builder.wordAt(0) == "SHOW" -> builder.parseLenientStatement()
        else -> super.parseSqlStatement(builder, level)
    }

    override fun parseParameterOrVariableReference(builder: PsiBuilder, allowVariable: Boolean): Boolean =
        builder.parseColonParameter() || super.parseParameterOrVariableReference(builder, allowVariable)
}

class FixtureParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = PgElementFactory()
    override fun createLexer(project: Project?): Lexer = FixtureLexer()
    override fun createParser(project: Project?): PsiParser = FixtureParser()
    override fun getFileNodeType(): IFileElementType = FIXTURE_FILE
}

private val FIXTURE_FILE = SqlFileElementType("BRIKK_FIXTURE_SQL_FILE", FixtureDialect)

class FixtureHighlighterFactory : SqlSyntaxHighlighterFactory.Base(FixtureDialect)

/** A light test whose fixture dialect parses and highlights through the support classes. */
abstract class FixtureTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        FixturePipeDialect.enabled = true
        FixturePipeDialect.claimFromFirst = false
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FixtureDialect, FixtureParserDefinition(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(FixtureDialect, FixtureHighlighterFactory(), testRootDisposable)
    }

    /** Opens [text] in a fixture-dialect file; `<caret>` places the caret. */
    protected fun configureFixture(text: String) {
        val file = LightVirtualFile("fixture-${UUID.randomUUID()}.sql", FixtureDialect, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file)
        text.indexOf("<caret>").takeIf { it >= 0 }?.let(myFixture.editor.caretModel::moveToOffset)
        check(myFixture.file.language == FixtureDialect) { "${myFixture.file.language} is not the fixture dialect" }
    }
}
