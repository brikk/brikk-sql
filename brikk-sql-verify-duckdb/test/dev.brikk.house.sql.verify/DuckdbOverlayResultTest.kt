package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import kotlin.test.*

internal class DuckdbOverlayResultTest {
    private fun render(sql: String) = assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb")
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.scalar(sql: String) = rows(sql).single().single()!!
    private fun Connection.check(sql: String, expected: List<List<String?>>) {
        assertEquals(expected, rows(sql), "native: $sql")
        assertEquals(expected, rows(render(sql)), "generated: $sql")
    }
    private fun modes(block: (Connection, Boolean) -> Unit) {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { block(it, generated) }
    }
    private fun quote(value: String) = "'${value.replace("'", "''")}'"

    @Test
    fun standardCommaAndQuotedNamedFormsReturnTheSameNativeVarcharResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((source, replacement, start, count, expected) in listOf(
                listOf("abcdef", "XX", "3", null, "abXXef"), listOf("Txxxxas", "hom", "2", "4", "Thomas"),
                listOf("hello", "xyz", "2", null, "hxyzo"), listOf("abc", "XY", "2", "0", "aXYbc"),
                listOf("abcdef", "Z", "3", "10", "abZ"), listOf("", "abc", "1", null, "abc"),
                listOf("hello", "", "3", null, "hello"), listOf("", "", "1", null, ""),
                listOf("abc", "X", "10", null, "abcX"), listOf("abc", "XY", "5", null, "abcXY"),
            )) {
                val s = quote(source!!); val r = quote(replacement!!)
                c.check("SELECT overlay($s PLACING $r FROM $start${count?.let { " FOR $it" }.orEmpty()})", listOf(listOf(expected)))
                c.check("SELECT overlay($s,$r,$start${count?.let { ",$it" }.orEmpty()})", listOf(listOf(expected)))
            }
            c.check("SELECT \"overlay\"(count:=0,start:=2,replacement:='XY',string:='abc')", listOf(listOf("aXYbc")))
            c.check("SELECT \"overlay\"(start:=2,replacement:='XY',string:='abcdef')", listOf(listOf("aXYdef")))
            c.check("SELECT \"overlay\"(count:=NULL,start:=2,replacement:='X',string:='abc')", listOf(listOf(null)))
            c.createStatement().use { s -> s.executeQuery("SELECT overlay(NULL,'X',1),overlay('abc','X',1,NULL)").use { r ->
                assertEquals(listOf("VARCHAR", "VARCHAR"), (1..2).map { r.metaData.getColumnTypeName(it) })
            } }
        }
    }

    @Test
    fun zeroNegativeAndExtremeBigintPositionsAndCountsKeepNativeSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((start, count, expected) in listOf(
                Triple("1", null, "resource"), Triple("0", null, "resou3rce"), Triple("-1", null, "resou33rce"),
                Triple("-100", null, "resou33333rce"), Triple("1", "-1", "resource"), Triple("1", "-100", "resource"),
            )) c.check("SELECT overlay('33333rce','resou',$start${count?.let { ",$it" }.orEmpty()})", listOf(listOf(expected)))
            c.check("SELECT overlay('abcdef' PLACING 'XY' FROM 0 FOR 2),overlay('abcdef' PLACING 'XY' FROM -1 FOR 2)", listOf(listOf("XYbcdef", "XYabcdef")))
            c.check("SELECT overlay('abc','X',9223372036854775807,9223372036854775807),overlay('abc','X',-9223372036854775808,9223372036854775807)", listOf(listOf("abcX", "Xabc")))
        }
    }

    @Test
    fun unicodeIsIndexedByCodePointWithoutByteOrGraphemeRewrites() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((source, replacement, start, count, expected) in listOf(
                listOf("café", "X", "4", null, "cafX"), listOf("hello🌍world", "X", "6", "1", "helloXworld"),
                listOf("日本語テスト", "XX", "3", null, "日本XXスト"), listOf("éX", "Z", "2", "1", "eZX"),
                listOf("👩‍💻X", "Z", "2", "1", "👩Z💻X"), listOf("abcd", "é", "2", null, "aéd"),
                listOf("abc", "🦆", "2", null, "a🦆c"),
            )) c.check("SELECT overlay(${quote(source!!)} PLACING ${quote(replacement!!)} FROM $start${count?.let { " FOR $it" }.orEmpty()})", listOf(listOf(expected)))
        }
    }

    @Test
    fun everySqlNullOperandAndVectorizedRowsPreserveValidityAndReturnType() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("SELECT overlay(NULL,'X',2),overlay('abc',NULL,2),overlay('abc','X',NULL),overlay('abc','X',2,NULL),overlay(NULL,NULL,NULL,NULL)", listOf(listOf(null, null, null, null, null)))
            c.execute("CREATE TABLE strings AS SELECT i,CASE WHEN i%7=0 THEN NULL ELSE 'café🌍' END AS s,CASE WHEN i%11=0 THEN NULL ELSE 'X' END AS r FROM range(5000)t(i)")
            c.check("SELECT count(*),count(overlay(s,r,4)),count(*) FILTER(WHERE overlay(s,r,4)='cafX🌍') FROM strings",
                listOf(listOf("5000", "3895", "3895")))
            c.check("SELECT overlay(s PLACING '__' FROM 2 FOR 2) FROM (VALUES ('abcdef'),('hello'),('xy'),(NULL)) t(s) ORDER BY s NULLS LAST",
                listOf(listOf("a__def"), listOf("h__lo"), listOf("x__"), listOf(null)))
        }
    }

    @Test
    fun exactNativeOverloadsBinderErrorsAndQualifiedUserFunctionsAreKeptSeparate() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT parameter_types,return_type FROM duckdb_functions() WHERE function_name='overlay' AND database_name='system' AND internal").use { r ->
                val signatures = buildSet { while (r.next()) { assertEquals("VARCHAR", r.getString(2)); add((r.getArray(1).array as Array<*>).map { it.toString() }) } }
                assertEquals(DUCKDB_METADATA.default.functions["overlay"]!!.overloads.map { it.argTypes }.toSet(), signatures)
            } }
            for (sql in listOf("SELECT overlay('abc')", "SELECT overlay('abc','X')", "SELECT overlay('abc','X',2,1,99)",
                "SELECT overlay(42,'X',1)", "SELECT overlay('abc','X',1.5)", "SELECT overlay('abc' PLACING 'X' FROM 1=1)")) {
                assertIs<SerializedSql.Parsed>(c.serializeSql(sql), sql)
                for (text in listOf(sql, render(sql))) {
                    val error = assertFailsWith<SQLException> { c.rows(text) }
                    assertTrue("Binder Error" in error.message.orEmpty(), sql)
                }
            }
            for (sql in listOf("SELECT overlay('abc','X' FROM 2)", "SELECT overlay('abc' PLACING 'X',2)",
                "SELECT overlay(string:='abc',replacement:='X',start:=2)"))
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
            c.execute("CREATE SCHEMA custom")
            c.execute("CREATE MACRO custom.overlay(a,b,c) AS 42")
            c.check("SELECT custom.overlay('abc','X',2)", listOf(listOf("42")))
            assertEquals("UNKNOWN", SqlFragment("SELECT custom.overlay('abc','X',2) AS value", "duckdb").outputShape().columns.single().type)
        }
    }

    @Test
    fun preparedBindingsAndVolatileOperandsAreNotReorderedDuplicatedOrDefaultExpanded() = modes { c, generated ->
        for (sql in listOf("SELECT overlay(?,?,?)", "SELECT overlay(? PLACING ? FROM ? FOR ?)")) {
            c.prepareStatement(if (generated) render(sql) else sql).use { p ->
                val count = if (sql.contains(" FOR ")) 4 else 3
                assertEquals(count, p.parameterMetaData.parameterCount)
                p.setString(1, "café"); p.setString(2, "X"); p.setLong(3, 4)
                if (count == 4) p.setNull(4, Types.BIGINT)
                p.executeQuery().use { r -> assertTrue(r.next()); assertEquals(if (count == 4) null else "cafX", r.getString(1)); assertEquals("VARCHAR", r.metaData.getColumnTypeName(1)) }
            }
        }
        for (name in listOf("source_seq", "replacement_seq", "start_seq", "count_seq")) c.execute("CREATE SEQUENCE $name")
        val query = "SELECT overlay(nextval('source_seq')::VARCHAR PLACING nextval('replacement_seq')::VARCHAR FROM nextval('start_seq') FOR nextval('count_seq'))"
        assertEquals(listOf(listOf("1")), c.rows(if (generated) render(query) else query))
        for (name in listOf("source_seq", "replacement_seq", "start_seq", "count_seq")) assertEquals("1", c.scalar("SELECT last_value FROM duckdb_sequences() WHERE sequence_name='$name'"))
        c.execute("CREATE SEQUENCE omitted_count")
        val omitted = "SELECT overlay('abcdef',nextval('omitted_count')::VARCHAR,3)"
        assertEquals(listOf(listOf("ab1def")), c.rows(if (generated) render(omitted) else omitted))
        assertEquals("1", c.scalar("SELECT last_value FROM duckdb_sequences() WHERE sequence_name='omitted_count'"))
    }
}
