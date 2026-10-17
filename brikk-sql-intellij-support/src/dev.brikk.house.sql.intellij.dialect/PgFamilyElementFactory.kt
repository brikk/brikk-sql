package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.types.DasTableType
import com.intellij.database.types.DasType
import com.intellij.database.types.DasTypeSystemBase
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.postgres.PgElementFactory
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.impl.SqlDasTypeAwareDeepRecursiveElement
import com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl
import com.intellij.sql.psi.impl.nestedDasType

/**
 * [stock]'s elements, whose function calls take an engine table function's row from the file's
 * [PgFamilyDialect.builtins] ([EngineBuiltins]); every other call is typed as the platform types it. Return it
 * from the dialect's parser definition.
 */
public class PgFamilyElementFactory(private val stock: SqlElementFactoryBase = PgElementFactory()) : SqlElementFactoryBase by stock {
    override fun createElementNode(type: IElementType): CompositeElement? =
        if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) EngineFunctionCall(type) else stock.createElementNode(type)
}

private class EngineFunctionCall(type: IElementType) : SqlFunctionCallExpressionImpl(type) {
    // The platform's order, whose implementation Kotlin cannot call as super (a member extension): a builtin
    // prototype's type; where 263 asks the dialect for a table function's type, the engine's row; the resolved
    // routine's type; the callable expression's table type.
    override suspend fun DeepRecursiveScope<SqlDasTypeAwareDeepRecursiveElement, DasType>.computeDasType(): DasType {
        val call = this@EngineFunctionCall
        val name = nameElement
        val prototype = SqlFunctionsUtil.chooseTheBestPrototype(call, true) ?: (name?.resolveSymbol() as? BuiltinFunction)?.prototypes?.singleOrNull()
        if (prototype != null) return createBuiltinFunctionReturnType(prototype, name, parameterList, call)
        val dialect = containingFile?.language as? PgFamilyDialect
        dialect?.builtins?.tableType(dialect, call)?.let { return it }
        if (name != null) return createFunctionReturnType(name, call)
        return callableExpression?.let { nestedDasType(it) } as? DasTableType ?: DasTypeSystemBase.UNKNOWN
    }
}
