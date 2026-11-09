package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.MysqlParser
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.*

/** SG29-005: upstream 479c4cae, plus sqlparser 0.62.0 Generic/PostgreSQL profiles. */
class IsPrecedenceCatchupTest {
    private fun projection(sql: String, dialect: String): Expression =
        (parseOne(sql, dialect).expressionsArg.single() as Expression).unnest()

    @Test
    fun postfixIsReadsTheWholeComparisonAcrossNativeProfiles() {
        for (dialect in listOf("postgres", "duckdb", "clickhouse", "mysql", "doris", "starrocks",
            "hive", "spark2", "spark", "datafusion", "spice")) {
            for (operator in listOf("=", "<>", "<", ">", "<=", ">=")) {
                val sql = "SELECT a $operator b IS NULL FROM t"
                val predicate = assertIs<Is>(projection(sql, dialect), "$dialect: $sql")
                val comparison = assertIs<Binary>((predicate.thisArg as Expression).unnest())
                assertEquals("a", (comparison.thisArg as Expression).name)
                assertEquals("b", (comparison.expressionArg as Expression).name)
                assertIs<Null>(predicate.expressionArg)
                val before = Serde.dump(predicate)
                for (target in listOf("postgres", "duckdb", "mysql", "bigquery", "trino", "datafusion")) {
                    val rendered = predicate.sql(target)
                    val reparsed = assertIs<Is>(parseOne(rendered, target).unnest(), "$target: $rendered")
                    assertIs<Binary>((reparsed.thisArg as Expression).unnest())
                }
                assertEquals(before, Serde.dump(predicate))
            }
        }
    }

    @Test
    fun explicitIsGroupingWorksInEveryProfileWithoutInventingNativeAcceptance() {
        for (dialect in listOf("", "mysql", "doris", "starrocks", "postgres", "duckdb", "clickhouse",
            "hive", "spark2", "spark", "presto", "trino", "bigquery", "datafusion", "spice")) {
            assertIs<Is>(projection("SELECT (a = b) IS NULL FROM t", dialect), dialect)
            val comparison = assertIs<EQ>(projection("SELECT a = (b IS NULL) FROM t", dialect), dialect)
            assertIs<Is>((comparison.expressionArg as Expression).unnest())
        }
        // These generic profiles retain the historical HIGH parser policy. This does not
        // claim that a real BigQuery/Trino server accepts a chain without parentheses.
        for (dialect in listOf("", "bigquery", "presto", "trino")) {
            assertIs<EQ>(projection("SELECT a = b IS NULL FROM t", dialect), dialect)
        }
    }

    @Test
    fun distinctFromRightHandSideRespectsDialectBinding() {
        for (dialect in listOf("postgres", "duckdb", "clickhouse", "spark2", "spark", "datafusion", "spice")) {
            for (operator in listOf("=", "<", ">")) {
                val predicate = assertIs<NullSafeNEQ>(projection("SELECT a IS DISTINCT FROM b $operator c", dialect), dialect)
                assertIs<Binary>((predicate.expressionArg as Expression).unnest())
            }
        }
        val hive = assertIs<EQ>(projection("SELECT a IS DISTINCT FROM b = c", "hive"))
        assertIs<NullSafeNEQ>((hive.thisArg as Expression).unnest())
        val mysql = assertIs<Is>(projection("SELECT a <=> b IS NULL", "mysql"))
        assertIs<NullSafeEQ>((mysql.thisArg as Expression).unnest())
    }

    @Test
    fun postfixComparisonsNegationRangesAndLongChainsKeepTheirGrouping() {
        for (dialect in listOf("postgres", "duckdb", "datafusion", "spice")) {
            assertIs<EQ>(projection("SELECT a IS NULL = b", dialect), dialect)
            val negated = projection("SELECT a = b IS NOT NULL", dialect)
            val isNull = if (negated is Not) assertIs<Is>(negated.thisArg) else assertIs<Is>(negated)
            if (negated !is Not) assertEquals(true, isNull.args["negate"])
            assertIs<EQ>((isNull.thisArg as Expression).unnest())
            for (predicate in listOf("NULL IN (0)", "NULL NOT IN (0)", "NULL BETWEEN 2 AND 3", "NULL NOT BETWEEN 2 AND 3")) {
                val tree = projection("SELECT NULL IS DISTINCT FROM $predicate", dialect)
                assertIs<NullSafeNEQ>(tree)
                val rhs = (tree.expressionArg as Expression).unnest()
                assertIs<Predicate>(if (rhs is Not) rhs.thisArg else rhs)
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
            }
            val sql = "SELECT a" + " IS NULL".repeat(1000)
            val tree = parseOne(sql, dialect)
            assertEquals(sql, tree.sql(dialect))
            for (chain in listOf("SELECT NULL IS NOT NULL IS FALSE", "SELECT NULL IS NULL IS NOT TRUE",
                "SELECT NULL IS NOT NULL IS NOT FALSE")) {
                val parsed = parseOne(chain, dialect)
                val rendered = parsed.sql(dialect)
                assertEquals(rendered, parseOne(rendered, dialect).sql(dialect), rendered)
                assertEquals(parsed, Serde.loadExpression(Serde.dump(parsed)))
            }
        }
    }

    @Test
    fun generatedColumnsReadACompleteBooleanExpression() {
        val sql = "CREATE TABLE t (a INT, b BOOLEAN GENERATED ALWAYS AS (a = 1 IS NULL) STORED)"
        val tree = parseOne(sql, "postgres")
        val generated = tree.findAll<ComputedColumnConstraint>().single()
        val isNull = assertIs<Is>((generated.thisArg as Expression).unnest())
        assertIs<EQ>((isNull.thisArg as Expression).unnest())
        val rendered = tree.sql("postgres")
        assertEquals(rendered, parseOne(rendered, "postgres").sql("postgres"))
    }

    @Test
    fun correctedIsProducesANonnullBooleanShapeWithoutSchemaInference() {
        for (dialect in listOf("postgres", "duckdb", "datafusion", "spice")) {
            val column = SqlFragment("SELECT NULL = 1 IS NULL AS v", dialect).outputShape().columns.single()
            assertTrue(column.type in setOf("BOOLEAN", "BOOL"), "$dialect: $column")
            assertEquals(false, column.nullable, dialect)
        }
    }

    @Test
    fun unsupportedBooleanIsFallbackCannotTurnNullableFalseIntoSqlNull() {
        for (target in listOf("starrocks", "presto", "trino")) {
            for (predicate in listOf("IS TRUE", "IS FALSE", "IS NOT TRUE", "IS NOT FALSE")) {
                val generator = Dialects.forName(target).generator(sourceDialect = "postgres").apply { forExecution = true }
                assertFailsWith<UnsupportedError> {
                    generator.generate(parseOne("SELECT CAST(NULL AS BOOLEAN) $predicate", "postgres"))
                }
            }
            val generator = Dialects.forName(target).generator(sourceDialect = "postgres").apply { forExecution = true }
            generator.generate(parseOne("SELECT (NULL IS NULL) IS TRUE", "postgres"))
        }
        // Guarding unsupported targets must not reject native IS Boolean predicates.
        Dialects.POSTGRES.generator().apply { forExecution = true }.generate(parseOne("SELECT NULL IS FALSE", "postgres"))
    }

    @Test
    fun oldRangeOverrideIsStillInvokedForDeferredIsAndStateResetsAfterFailure() {
        class Observer : MysqlParser() {
            var sawDeferredIs = false
            var fail = false
            override fun parseRange(this_: Expression?): Expression? {
                if (this_ != null && currToken.tokenType == TokenType.IS) {
                    sawDeferredIs = true
                    if (fail) error("observer")
                }
                return super.parseRange(this_)
            }
        }
        val parser = Observer()
        val sql = "SELECT a = b IS NULL"
        parser.fail = true
        assertFailsWith<IllegalStateException> { parser.parse(Dialects.MYSQL.tokenize(sql), sql) }
        assertTrue(parser.sawDeferredIs)
        parser.fail = false
        val parsed = parser.parse(Dialects.MYSQL.tokenize(sql), sql).single()!!
        assertIs<Is>((parsed.expressionsArg.single() as Expression).unnest())
    }
}
