package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.model.ObjectKind
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlTokens
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlParameter
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDbms
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDialect
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import java.lang.reflect.Modifier

class CHouseFamilyDialectTest : CHouseFixtureTestCase() {
    private val stock = CHouseDialect.INSTANCE
    private val family = CHouseFixtureDialect

    fun testTheConsumerSuppliesTheDbms() {
        assertSame(CHouseFixtureDbms.DBMS, family.dbms)
        assertEquals("BrikkCHouseFixtureSQL", family.id)
    }

    fun testKeywordsAndBuiltinFunctionsAreTheStockOnes() {
        assertEquals(stock.keywords, family.keywords)
        assertEquals(stock.reservedKeywords, family.reservedKeywords)
        // Plain, parametric and combinator-suffixed functions, and special forms the grammar parses as such.
        val names = listOf("toDateTime", "quantile", "uniqExact", "sumIf", "avgOrNull", "countDistinctIf", "CAST", "EXTRACT", "nonexistentFn")
        assertEquals(names.map(stock.supportedFunctions::contains), names.map(family.supportedFunctions::contains))
        assertTrue(family.supportedFunctions.contains("sumIf"))
        assertEquals(stock.supportedFunctions.get("CAST")?.toString(), family.supportedFunctions.get("CAST")?.toString())
        assertEquals(stock.builtInTypes.keys, family.builtInTypes.keys)
        assertTrue(family.builtInTypes.containsKey("UInt64"))
    }

    fun testOperatorsSystemVariablesAndSeparatorsAreTheStockOnes() {
        val operators = listOf<IElementType>(SqlTokens.SQL_OP_PLUS, SqlTokens.SQL_OP_CONCAT, SqlTokens.SQL_OP_EQ, SqlTokens.SQL_OP_EQEQ,
            SqlTokens.SQL_OP_NULLSAFE_EQ, SqlTokens.SQL_OP_JSON_EXTRACT, SqlTokens.SQL_OP_ASSIGN, SqlTokens.SQL_OP_DIV_INT)
        assertEquals(operators.map(stock::isOperatorSupported), operators.map(family::isOperatorSupported))
        assertEquals("both supported and unsupported operators", 2, operators.map(stock::isOperatorSupported).distinct().size)
        assertEquals(stock.systemVariables, family.systemVariables)
        assertFalse(family.systemVariables.isEmpty())
        assertEquals(stock.statementSeparators.types.toSet(), family.statementSeparators.types.toSet())
    }

    fun testRawDataAndFormatNamesFollowTheStockRules() {
        for (text in listOf("{\"id\":1}", "(1, 'a')\n", "x;", " x")) {
            assertEquals(text, stock.isValidRawToken(SqlTokens.SQL_RAW_INPUT, text), family.isValidRawToken(SqlTokens.SQL_RAW_INPUT, text))
        }
        assertFalse(family.shallResolve(null, ObjectKind.FORMAT))
        assertTrue(family.supportsJoinUsing())
    }

    /** From 263 the console leaves a parameterized view's `{name:Type}` to the server; earlier IDEs never ask. */
    fun testServerBoundParametersFollowTheStockDialect() {
        // The platform's own call, virtual on the base dialect class; before 263 there is none.
        val platform = runCatching { SqlLanguageDialectEx::class.java.getMethod("isServerResolvedParameter", SqlParameter::class.java) }.getOrNull()
            ?: return
        configureCHouse("CREATE VIEW v AS SELECT * FROM t WHERE id = {id:UInt64}")
        val parameter = requireNotNull(PsiTreeUtil.findChildOfType(myFixture.file, SqlParameter::class.java))
        assertEquals(true, platform.invoke(stock, parameter))
        assertEquals(true, platform.invoke(family, parameter))
    }

    /** Fails when an IDE's platform dialect overrides a member this family does not delegate. */
    fun testEveryMemberTheStockDialectOverridesIsDelegated() {
        val missing = CHouseDialect::class.java.declaredMethods
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) && !it.isSynthetic && it.name != "getDbms" }
            .filter { method ->
                runCatching { CHouseFamilyDialect::class.java.getDeclaredMethod(method.name, *method.parameterTypes) }.isFailure
            }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
        assertTrue(SqlLanguageDialect::class.java.isAssignableFrom(CHouseFamilyDialect::class.java))
    }
}
