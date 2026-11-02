package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import dev.brikk.house.sql.intellij.dialect.DatafusionFamilyDialect
import dev.brikk.house.sql.intellij.dialect.PgFamilyElementFactory
import dev.brikk.house.sql.intellij.lexer.DatafusionFamilyLexer
import dev.brikk.house.sql.intellij.lexer.StatementBoundedSqlLexer
import java.util.concurrent.ConcurrentHashMap

/** Register a consumer's no-argument subclass for exactly its DataFusion or Spice language ID. */
public open class DatafusionFamilyParserDefinition(private val dialect: DatafusionFamilyDialect) : SqlParserDefinitionBase() {
    private val fileType = fileTypes.computeIfAbsent(dialect) { SqlFileElementType("${it.id.uppercase()}_SQL_FILE", it) }

    override fun createElementFactory(): SqlElementFactoryBase = PgFamilyElementFactory()
    override fun createLexer(project: Project?): Lexer = StatementBoundedSqlLexer({ DatafusionFamilyLexer(dialect.profile) }, dialect.profile.coreDialect)
    override fun createParser(project: Project?): PsiParser = DatafusionFamilyParser(dialect.profile)
    override fun getFileNodeType(): IFileElementType = fileType

    private companion object {
        val fileTypes = ConcurrentHashMap<DatafusionFamilyDialect, SqlFileElementType>()
    }
}
