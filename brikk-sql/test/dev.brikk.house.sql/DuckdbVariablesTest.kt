package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DuckdbVariablesTest {
    @Test
    fun dollarParametersStayParametersRatherThanBecomingGetvariableCalls() {
        for (sql in listOf("SELECT \$threshold + \$1 + ?", "SELECT \$\"my var\",\$\"1\"", "SET VARIABLE \"my var\"=9", "RESET VARIABLE \"my var\"")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val rendered = tree.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
        }
        assertIs<Placeholder>(Dialects.DUCKDB.parseOne("SELECT \$threshold").selects.single())
        val fragment = SqlFragment("SELECT \$threshold", "duckdb")
        assertEquals(listOf("threshold"), fragment.scalarParams.map { it.name })
        assertEquals(ParamStyle.NAMED_AT, fragment.scalarParams.single().style)
        assertTrue("GETVARIABLE" !in fragment.toExecutable("duckdb").sql)
        assertTrue(SqlFragment("SELECT :client", "duckdb").toExecutable("trino").unsupportedMessages.isEmpty())
    }

    @Test
    fun prepareAndExecuteBodiesAreStructuredAndParameterRangesRemainAuthored() {
        val sql = "PREPARE compute AS SELECT \$multiplier * \$x AS result"
        val tree = Dialects.DUCKDB.parseOne(sql)
        assertTrue(tree !is Command, "PREPARE parameters must not disappear inside opaque command text")
        assertEquals(mapOf("this" to true, "expression" to true), tree.argTypes)
        assertEquals(mapOf("this" to true, "expressions" to false), DuckdbExecute().argTypes)
        assertEquals(mapOf("this" to true), DuckdbDeallocate().argTypes)
        assertEquals(mapOf("this" to true), DuckdbResetVariable().argTypes)
        assertEquals(2, tree.findAll<Placeholder>().count())
        assertFailsWith<dev.brikk.house.sql.optimizer.SqlglotError> {
            dev.brikk.house.sql.optimizer.lineageAll(sql, dialect = Dialects.DUCKDB)
        }
        assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
        assertEquals(tree, Serde.load(Serde.dump(tree)))
        val occurrences = parseParameterOccurrences(sql, "duckdb")
        assertEquals(listOf("\$multiplier", "\$x"), occurrences.map { sql.substring(it.range.start, it.range.end) })
        for (text in listOf("EXECUTE compute", "EXECUTE compute(multiplier:=3)", "EXECUTE \"quoted stmt\"(\"my var\":=11)",
            "DEALLOCATE PREPARE compute", "PREPARE put AS INSERT INTO t VALUES (\$x)")) {
            val statement = Dialects.DUCKDB.parseOne(text)
            assertTrue(statement !is Command, text)
            assertEquals(statement, Serde.load(Serde.dump(statement)))
            val rendered = statement.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
        }
        val script = "SET VARIABLE x=1; PREPARE p AS SELECT \$x,';'; EXECUTE p"
        val statements = Dialects.DUCKDB.parse(script).filterNotNull().filterNot { it is Semicolon }
        assertEquals(3, statements.size)
        assertEquals(1, statements[1].findAll<Placeholder>().count())
    }

    @Test
    fun onlyNamedDollarParametersAreEligibleForEngineSessionDefaults() {
        val sql = "SELECT \$threshold,\$1,?,\$\"my var\",\$\"1\",:client,'\$ignored ?',\$\$ \$hidden :no \$\$"
        assertEquals(listOf("threshold", "my var"), duckdbSessionDefaultCandidates(sql).map { it.name })
        assertEquals(sql.replace(":client", "0      "), maskNamedParameters(sql, "duckdb"))
        val script = "SET VARIABLE threshold=10; SELECT \$threshold,\$1,?,:client; RESET VARIABLE threshold; SELECT \$threshold"
        assertEquals(listOf("client"), SqlScriptParameters.parse(script, "duckdb").occurrences.map { it.name })
        assertEquals(listOf(null, "client"), SqlScriptParameters.parse(script, "duckdb", positional = true).occurrences.map { it.name })
    }

    @Test
    fun defaultEligibilityIsNotAClaimThatAVariableExistsAndDoesNotInferItsType() {
        val sql = "SELECT \$unset AS result"
        assertEquals(listOf("unset"), duckdbSessionDefaultCandidates(sql).map { it.name })
        assertEquals("UNKNOWN", SqlFragment(sql, "duckdb").outputShape().columns.single().type)
        assertTrue(duckdbSessionDefaultCandidates("SELECT getvariable('unset')").isEmpty())
        assertTrue(duckdbSessionDefaultCandidates("SELECT '\$unset',\$tag\$ \$unset \$tag\$").isEmpty())
    }

    @Test
    fun nativeSessionExecutionAndDefaultLookupRefuseUnprovedCrossTargetTranslation() {
        for (sql in listOf("SELECT \$threshold", "PREPARE compute AS SELECT \$x", "EXECUTE compute", "RESET VARIABLE x", "SET VARIABLE x=1")) {
            for (target in listOf("trino", "postgres", "doris")) {
                assertFailsWith<UnsupportedError>("$target: $sql") { SqlFragment(sql, "duckdb").toExecutable(target) }
            }
        }
    }
}
