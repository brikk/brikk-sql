package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.dialects.clickhouse.CHouseParser
import com.intellij.sql.dialects.clickhouse.CHouseParserDefinition
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus

/**
 * Parsing for a `CHouseFamilyDialect` on the platform ClickHouse grammar. PSI comes from the
 * platform ClickHouse element factory, reached through a platform definition's public
 * `getElementFactory`: its own PSI for the grammar's nodes, and the platform's for shared nodes such
 * as the lenient `SQL_STATEMENT` of `parseLenientStatement`. Register a no-arg subclass as
 * `lang.parserDefinition` of [dialect]; override [createBaseLexer] or [createParser] to add
 * dialect rules.
 */
@ApiStatus.Experimental
public open class CHouseFamilyParserDefinition(dialect: SqlLanguageDialect) : SqlParserDefinitionBase() {
    private val fileNodeType = fileNodeTypes.computeIfAbsent(dialect) { SqlFileElementType("${it.id.uppercase()}_SQL_FILE", it) }

    override fun createElementFactory(): SqlElementFactoryBase = CHouseParserDefinition().elementFactory

    override fun createLexer(project: Project?): Lexer = createBaseLexer()

    /** The substrate lexer: the platform ClickHouse lexer, or one wrapping it. */
    protected open fun createBaseLexer(): Lexer = CHouseLexer()

    override fun createParser(project: Project?): PsiParser = CHouseParser()

    override fun getFileNodeType(): IFileElementType = fileNodeType

    private companion object {
        /** One file node type per dialect, however many definitions are created. */
        private val fileNodeTypes = ConcurrentHashMap<SqlLanguageDialect, SqlFileElementType>()
    }
}
