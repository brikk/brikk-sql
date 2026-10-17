package dev.brikk.house.sql.intellij.relation

import com.intellij.database.types.DasType
import com.intellij.database.types.DasTypeSystemBase
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.mysql.MysqlElementFactory
import com.intellij.sql.dialects.mysql.MysqlParserDefinition
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.dialects.postgres.PgParserDefinition
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.impl.SqlDasTypeAwareDeepRecursiveElement
import com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl

/**
 * Types slot calls of [profile]'s language (PostgreSQL or MySQL) with [RelationTableTypes.of] until
 * [until] ends, as Doris's element factory does on MySQL's.
 */
fun installNativeSlotTyping(until: Disposable, profile: TestRelationProfile = pgRelations) {
    val base = if (profile.language == mysql) MysqlParserDefinition() else PgParserDefinition()
    LanguageParserDefinitions.INSTANCE.addExplicitExtension(profile.language, object : SqlParserDefinitionBase() {
        override fun createElementFactory(): SqlElementFactoryBase = if (profile.language == mysql) {
            object : MysqlElementFactory() {
                override fun createElementNode(type: IElementType): CompositeElement? =
                    if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) SlotCall(type, profile) else super.createElementNode(type)
            }
        } else {
            object : PgElementFactory() {
                override fun createElementNode(type: IElementType): CompositeElement? =
                    if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) SlotCall(type, profile) else super.createElementNode(type)
            }
        }

        override fun createLexer(project: Project?): Lexer = base.createLexer(project)

        override fun createParser(project: Project?): PsiParser = base.createParser(project)

        override fun getFileNodeType(): IFileElementType = base.fileNodeType
    }, until)
}

private class SlotCall(type: IElementType, private val profile: RelationScopeProfile) : SqlFunctionCallExpressionImpl(type) {
    override suspend fun DeepRecursiveScope<SqlDasTypeAwareDeepRecursiveElement, DasType>.computeDasType(): DasType =
        RelationTableTypes.of(this@SlotCall, profile) ?: DasTypeSystemBase.UNKNOWN
}
