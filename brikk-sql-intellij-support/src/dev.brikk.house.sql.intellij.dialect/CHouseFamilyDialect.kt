package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbDataSource
import com.intellij.database.symbols.DasSymbol
import com.intellij.database.types.DasType
import com.intellij.database.util.TreePattern
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlExpression
import com.intellij.sql.psi.SqlParameter
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.refactoring.extractFunction.SqlExtractFunctionHelper
import java.lang.reflect.Method

/**
 * A dialect on the platform ClickHouse grammar that behaves as the platform ClickHouse dialect: its
 * tokens and builtin functions (special forms and combinator suffixes included), types, operators,
 * system variables, statement separators and resolution rules; a raw data leaf is never reparsed
 * on its own. The subclass
 * supplies `getDbms`; keep its `Dbms` outside the dialect object, whose base constructor already
 * asks for it. Pair it with `CHouseFamilyParserDefinition`.
 *
 * The platform dialect is final, so every member it overrides is delegated to it here; those run
 * as the platform dialect, as stock ClickHouse files do. A dbms with
 * `<extensionFallback fallbackDbms="CLICKHOUSE">` keeps the type system and model they read.
 */
public abstract class CHouseFamilyDialect(id: String) : SqlLanguageDialectBase(id) {
    private val stock: CHouseDialect get() = CHouseDialect.INSTANCE

    override fun createTokensHelper(): TokensHelper = stock.tokensHelper

    override fun addTypes(types: MutableMap<String, BuiltinFunction.Type>) {
        types.putAll(stock.builtInTypes)
    }

    override fun isOperatorSupported(op: IElementType?): Boolean = stock.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = stock.systemVariables

    override fun getStatementSeparators(): TokenSet = stock.statementSeparators

    /**
     * Never reparses a raw data leaf on its own: an edit inside data can move statement boundaries
     * (a `;` alone on a line, a blank line), which only reparsing the file finds.
     */
    override fun isValidRawToken(type: IElementType, text: CharSequence): Boolean = false

    override fun isSpecialFunctionReference(elementType: IElementType): Boolean = stock.isSpecialFunctionReference(elementType)

    override fun supportsJoinUsing(): Boolean = stock.supportsJoinUsing()

    override fun getExpectedFunctionLocations(expression: SqlExpression?): Set<BuiltinFunction.Location> =
        stock.getExpectedFunctionLocations(expression)

    override fun getExtractFunctionHelper(): SqlExtractFunctionHelper = stock.extractFunctionHelper

    override fun getBaseImports(dataSource: DbDataSource?, dsNames: Array<out ObjectName?>?): TreePattern =
        stock.getBaseImports(dataSource, dsNames)

    override fun processReservedEntitiesWithType(name: String?, place: PsiElement, resolve: Boolean, processor: SqlScopeProcessor): Boolean =
        stock.processReservedEntitiesWithType(name, place, resolve, processor)

    override fun allowGlobalTableResolve(place: PsiElement): Boolean = stock.allowGlobalTableResolve(place)

    override fun shouldHide(symbol: DasSymbol?, place: PsiElement?): Boolean = stock.shouldHide(symbol, place)

    override fun <T : MutableCollection<ObjectKind>> getParentDbTypes(result: T, type: ObjectKind): T = stock.getParentDbTypes(result, type)

    override fun isResolveTargetAccepted(
        symbol: DasSymbol?, type: ObjectKind, expectedTypes: Set<ObjectKind>, place: PsiElement?, strict: Boolean, isCompletion: Boolean,
    ): Boolean = stock.isResolveTargetAccepted(symbol, type, expectedTypes, place, strict, isCompletion)

    override fun isResolveTargetNotAccepted(
        symbol: DasSymbol?, type: ObjectKind, expectedTypes: Set<ObjectKind>, place: PsiElement?, strict: Boolean, isCompletion: Boolean,
    ): Boolean = stock.isResolveTargetNotAccepted(symbol, type, expectedTypes, place, strict, isCompletion)

    override fun shouldQuotedTypeReferenceBeChecked(ref: String): Boolean = stock.shouldQuotedTypeReferenceBeChecked(ref)

    override fun shallResolve(o: SqlReferenceExpression?, type: ObjectKind): Boolean = stock.shallResolve(o, type)

    override fun processUnqualifiedResolve(processor: SqlScopeProcessor, state: ResolveState, ref: PsiReference): Boolean =
        stock.processUnqualifiedResolve(processor, state, ref)

    override fun processQualifiedResolve(processor: SqlScopeProcessor, state: ResolveState, ref: SqlReference, qualifierType: DasType): Boolean =
        stock.processQualifiedResolve(processor, state, ref, qualifierType)

    override fun processTypeExtra(type: SqlTableType, processor: SqlScopeProcessor, state: ResolveState, place: PsiElement): Boolean =
        stock.processTypeExtra(type, processor, state, place)

    /**
     * Whether the server, not the IDE, binds [parameter], such as `{name:Type}`. Overrides the
     * platform member from 263, where the console stops prompting for those; earlier IDEs never
     * call it. It is absent from the 261 API this library compiles against, so it is called
     * reflectively on the platform dialect.
     */
    public open fun isServerResolvedParameter(parameter: SqlParameter): Boolean =
        serverResolvedParameter?.invoke(stock, parameter) == true

    private companion object {
        private val serverResolvedParameter: Method? =
            runCatching { CHouseDialect::class.java.getMethod("isServerResolvedParameter", SqlParameter::class.java) }.getOrNull()
    }
}
