package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

/** SG29-007: fdd0e9a4/8955d752/b1fd4e9e, with native derivative controls. */
class MysqlPrecedenceCatchupTest {
    private fun projection(text: String, dialect: String): Expression =
        (parseOne("SELECT $text", dialect).expressionsArg.single() as Expression).unnest()

    @Test
    fun logicalXorBindsBetweenAndAndOr() {
        for (dialect in listOf("mysql", "doris")) {
            val xor = assertIs<Xor>(projection("TRUE XOR TRUE AND FALSE", dialect), dialect)
            assertIs<And>((xor.expressionArg as Expression).unnest())
            val or = assertIs<Or>(projection("TRUE OR TRUE XOR FALSE AND FALSE", dialect))
            assertIs<Xor>((or.expressionArg as Expression).unnest())
        }
    }

    @Test
    fun exclamationIsTightInMysqlAndStarrocksButNotInDorisValueExpressions() {
        for (dialect in listOf("mysql", "starrocks")) {
            val add = assertIs<Add>(projection("!1 + 1", dialect), dialect)
            assertIs<Not>((add.thisArg as Expression).unnest())
            assertTrue("(" in parseOne("SELECT !1 + 1", dialect).sql(dialect))
        }
        val not = assertIs<Not>(projection("!1 + 1", "doris"))
        assertIs<Add>((not.thisArg as Expression).unnest())
        for (dialect in listOf("mysql", "doris", "starrocks")) {
            assertIs<Not>(projection("NOT 1 + 1", dialect), dialect)
        }
    }

    @Test
    fun xorIsTighterThanMultiplicationAndAndIsTighterThanOr() {
        for (dialect in listOf("mysql", "doris", "starrocks")) {
            val or = assertIs<BitwiseOr>(projection("1 | 2 & 0", dialect), dialect)
            assertIs<BitwiseAnd>((or.expressionArg as Expression).unnest())
            val multiply = assertIs<Mul>(projection("1 ^ 2 * 3", dialect))
            assertIs<BitwiseXor>((multiply.thisArg as Expression).unnest())
            val before = Serde.dump(or)
            val rendered = or.sql("postgres")
            val reparsed = assertIs<BitwiseOr>(parseOne(rendered, "postgres").unnest())
            assertIs<BitwiseAnd>((reparsed.expressionArg as Expression).unnest())
            assertEquals(before, Serde.dump(or))
        }
        val shift = assertIs<BitwiseOr>(projection("1 | 2 << 1 & 2", "mysql"))
        val and = assertIs<BitwiseAnd>((shift.expressionArg as Expression).unnest())
        assertIs<BitwiseLeftShift>((and.thisArg as Expression).unnest())
    }

    @Test
    fun booleanXorTranslationsRetainNestedGroupingInsteadOfLeakingAnInfixOperator() {
        for (source in listOf("TRUE XOR TRUE AND FALSE", "TRUE AND (FALSE XOR TRUE)",
            "(TRUE OR FALSE) XOR (FALSE AND TRUE)", "NOT (TRUE XOR FALSE)", "NULL XOR TRUE")) {
            for (target in listOf("postgres", "trino", "duckdb", "clickhouse")) {
                val output = transpile("SELECT $source", "mysql", target)
                assertFalse(" XOR " in output, "$target: $output")
                parseOne(output, target)
            }
        }
    }

    @Test
    fun starrocksDoesNotSilentlyAcceptAnUnqualifiedLogicalXorTranslation() {
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT TRUE XOR FALSE", "mysql").toExecutable("starrocks")
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT TRUE AND (FALSE XOR TRUE)", "mysql").toExecutable("starrocks")
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT TRUE OR FALSE XOR TRUE", "mysql").toExecutable("starrocks")
        }
        // Source syntax compatibility is not a native-acceptance or execution claim.
        assertTrue(transpile("SELECT TRUE XOR FALSE", "mysql", "starrocks").contains("XOR"))
        for (target in listOf("doris", "starrocks")) {
            for (operator in listOf("<<", ">>")) {
                assertFailsWith<UnsupportedError> {
                    SqlFragment("SELECT 1 | 2 $operator 1 & 2", "mysql").toExecutable(target)
                }
            }
        }
    }
}
