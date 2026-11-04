package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbFactorialPrecedenceTest {
    @Test fun factorialBindsToItsOperandBeforeMultiplicationUnaryAndExponent() {
        val product = assertIs<Mul>(Dialects.DUCKDB.parseOne("2 * 3!"))
        assertEquals("3", assertIs<Factorial>(product.expressionArg).thisArg.let { assertIs<Literal>(it).name })
        val factorial = assertIs<Factorial>(Dialects.DUCKDB.parseOne("(2 * 3)!"))
        assertIs<Mul>(assertIs<Paren>(factorial.thisArg).thisArg)
        assertIs<Factorial>(assertIs<Neg>(Dialects.DUCKDB.parseOne("-3!")).thisArg)
        assertIs<Factorial>(assertIs<Pow>(Dialects.DUCKDB.parseOne("2 ^ 3!")).expressionArg)
        assertIs<Factorial>(assertIs<Cast>(Dialects.DUCKDB.parseOne("3!::VARCHAR")).thisArg)
        assertIs<Cast>(assertIs<Factorial>(Dialects.DUCKDB.parseOne("3::INTEGER!")).thisArg)
        for(sql in listOf("3!!","3!+1","3!*2","3!^2"))assertFails { Dialects.DUCKDB.parseOne(sql) }
    }
    @Test fun copySerdeGenerationParametersAndScalarFactsPreserveTheFactorialOperand() {
        for (body in listOf("2 * 3!", "(2 * 3)!", "-3!", "(-3)!", "2 ^ 3!", "3! ^ 2", "~3!", "3!::VARCHAR", "NULL!", "40!", "3 ! !")) {
            val sql = "SELECT $body AS result"
            val tree = Dialects.DUCKDB.parseOne(sql)
            for (copy in listOf(tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree, copy)
                val generated = copy.sql("duckdb")
                assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            }
        }
        val sql = "SELECT CAST(? AS INTEGER)! + CAST(? AS INTEGER)!"
        assertEquals(sql.indices.filter { sql[it]=='?' }, parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(2,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
        assertEquals("INT128",SqlFragment("SELECT 3! AS result","duckdb").outputShape().columns.single().type)
        assertEquals("INT128",RelationScope.analyze("SELECT 3! AS result","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        assertEquals(true,SqlFragment("SELECT NULL! AS result","duckdb").outputShape().columns.single().nullable)
    }
    @Test fun crossTargetExecutionRefusesUnprovedWidthsAndErrorsWithoutChangingOtherDialects() {
        for(target in listOf("trino","postgres","doris","clickhouse","bigquery","mysql"))
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT 3!","duckdb").toExecutable(target) }
        // SG29-007 preserves MySQL !'s tighter binding when spelling it as NOT.
        assertEquals("SELECT (NOT TRUE)",Dialects.MYSQL.parseOne("SELECT !TRUE").sql("mysql"))
        assertEquals("SELECT NOT REGEXP_FULL_MATCH(a, b)",Dialects.DUCKDB.parseOne("SELECT a !~ b").sql("duckdb"))
        assertTrue("!~" in SqlFragment("SELECT a !~ b","duckdb").toExecutable("duckdb").sql)
    }
}
