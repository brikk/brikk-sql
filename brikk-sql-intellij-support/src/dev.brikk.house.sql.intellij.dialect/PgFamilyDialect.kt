package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.dialects.postgres.PgDialectBase
import com.intellij.sql.dialects.postgres.PgTokens
import com.intellij.sql.psi.SqlScopeProcessor
import org.jetbrains.annotations.ApiStatus

/**
 * A dialect on the PostgreSQL grammar whose operators, system variables and builtin functions are
 * the platform PostgreSQL dialect's. The default tokens helper would look for function definitions
 * next to the subclass and find none, losing special forms such as `CAST(x AS t)`.
 *
 * The engine's own functions and types resolve too when [builtins] are given; pair them with
 * [PgFamilyElementFactory] so its table functions' rows are typed.
 */
@ApiStatus.Experimental
public abstract class PgFamilyDialect(id: String) : PgDialectBase(id) {
    /**
     * The engine's functions and types beyond the platform PostgreSQL dialect's; null resolves only those.
     * Return one instance, created lazily (`by lazy`): the profiles it holds may refer back to the dialect.
     */
    public open val builtins: EngineBuiltins? get() = null

    override fun isOperatorSupported(op: IElementType?): Boolean = PgDialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = PgDialect.INSTANCE.systemVariables

    override fun createTokensHelper(): TokensHelper =
        TokensHelper(PgTokens::class.java, SqlFunctionsUtil.loadFunctionDefinition(PgDialect.INSTANCE))

    override fun processUnqualifiedResolve(processor: SqlScopeProcessor, state: ResolveState, ref: PsiReference): Boolean =
        builtins?.process(this, processor, state, ref) != false && super.processUnqualifiedResolve(processor, state, ref)
}
