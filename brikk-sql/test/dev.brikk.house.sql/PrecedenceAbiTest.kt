package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.dialects.MysqlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class PrecedenceAbiTest {
    @Test
    fun parserEntryPointsAndTheirExistingDefaultBridgesKeepTheirJvmShape() {
        val parser = Parser::class.java
        val expression = Expression::class.java
        val bool = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        for (method in listOf("parseEquality", "parseComparison", "parseConjunction", "parseBitwise")) {
            assertEquals(expression, parser.getMethod(method).returnType)
        }
        for (method in listOf("parseRange", "parseIs")) parser.getMethod(method, expression)
        parser.getMethod("parseRange\$default", parser, expression, int, Any::class.java)
        parser.getMethod("parseSetOperation", expression, bool)
        parser.getMethod("parseSetOperation\$default", parser, expression, bool, int, Any::class.java)
        MysqlParser::class.java.getMethod("parseRange", expression)
    }
}
