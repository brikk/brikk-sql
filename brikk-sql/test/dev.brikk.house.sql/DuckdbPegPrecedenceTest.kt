package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbPegPrecedenceTest {
    @Test fun nativeSingleArrowBindsBelowEqualityWithoutChangingTheHistoricalParser() {
        val sql="SELECT JSON '{\"x\":2}' -> 'x' = 2"
        val native=parseDuckdbNativeOne(sql)
        val extraction=assertIs<JSONExtract>(native.expressionsArg.single())
        assertIs<EQ>(extraction.expressionArg)
        assertIs<EQ>(Dialects.DUCKDB.parseOne(sql).expressionsArg.single())
        assertEquals(native,Serde.load(Serde.dump(native)))
        val high=SqlFragment("SELECT (JSON '{\"x\":2}' -> 'x') = 2","duckdb").toExecutable("duckdb").sql
        val low=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
        assertNotEquals(high,low)
        assertIs<JSONExtract>(parseDuckdbNativeOne(low).expressionsArg.single())
        assertIs<EQ>(parseDuckdbNativeOne(high).expressionsArg.single())
        assertIs<EQ>(parseDuckdbNativeOne("SELECT JSON '{\"x\":2}' ->> 'x' = '2'").expressionsArg.single())
    }
    @Test fun chainedArrowsRemainLeftAssociatedAndBooleanPathsStayOnTheRight() {
        val tree=parseDuckdbNativeOne("SELECT JSON '{\"x\":{\"y\":1}}' -> 'x' -> 'y'")
        assertIs<JSONExtract>(assertIs<JSONExtract>(tree.expressionsArg.single()).thisArg)
        val boolean=parseDuckdbNativeOne("SELECT JSON '{\"x\":2}' -> 'x' = 2 OR false")
        assertIs<Or>(assertIs<JSONExtract>(boolean.expressionsArg.single()).expressionArg)
        assertEquals(boolean,Serde.load(Serde.dump(boolean)))
    }
}
