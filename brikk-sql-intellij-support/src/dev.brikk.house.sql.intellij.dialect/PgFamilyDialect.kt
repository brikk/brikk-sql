package dev.brikk.house.sql.intellij.dialect

import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.dialects.postgres.PgDialectBase
import com.intellij.sql.dialects.postgres.PgTokens
import org.jetbrains.annotations.ApiStatus

/**
 * A dialect on the PostgreSQL grammar whose operators, system variables and builtin functions are
 * the platform PostgreSQL dialect's. The default tokens helper would look for function definitions
 * next to the subclass and find none, losing special forms such as `CAST(x AS t)`.
 */
@ApiStatus.Experimental
public abstract class PgFamilyDialect(id: String) : PgDialectBase(id) {
    override fun isOperatorSupported(op: IElementType?): Boolean = PgDialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = PgDialect.INSTANCE.systemVariables

    override fun createTokensHelper(): TokensHelper =
        TokensHelper(PgTokens::class.java, SqlFunctionsUtil.loadFunctionDefinition(PgDialect.INSTANCE))
}
