package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.parseParameterOccurrences
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlinx.serialization.json.*
import kotlin.test.*

internal class DuckdbPegDifferentialTest {
    private fun connection() = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
    })
    private fun Connection.rows(sql: String) = createStatement().use { s ->
        s.queryTimeout = 10
        s.executeQuery(sql).use { r ->
            buildList {
                while (r.next()) add((1..r.metaData.columnCount).map {
                    r.getString(it)
                })
            }
        }
    }
    private fun forms(sql: String): List<String> {
        val tree = parseDuckdbNativeOne(sql)
        assertEquals(tree, tree.copy())
        assertEquals(tree, Serde.load(Serde.dump(tree)))
        return listOf(sql, tree.copy().sql("duckdb"), Serde.loadExpression(Serde.dump(tree)).sql("duckdb"), SqlFragment(sql, "duckdb").toExecutable("duckdb").sql)
    }
    @Test fun ambiguityControlsKeepResultsAcrossGenerationCopyAndSerde() {
        connection().use { c ->
            val cases = listOf(
                "SELECT answer: (1+2)*3" to listOf(listOf("9")),
                "SELECT t.x FROM t: (SELECT 42 AS x)" to listOf(listOf("42")),
                "FROM (VALUES(2),(1)) t(x) SELECT x ORDER BY ALL" to listOf(listOf("1"), listOf("2")),
                "PIVOT (SELECT 'a' k,2 v) ON k USING sum(v)" to listOf(listOf("2")),
                "SELECT k,sum(v) n FROM (VALUES('a',1),('a',2)) t(k,v) GROUP BY ALL ORDER BY ALL" to listOf(listOf("a", "3")),
                "SELECT * EXCLUDE(secret) REPLACE(x+1 AS x) FROM (VALUES(1,9)) t(x,secret)" to listOf(listOf("2")),
                "SELECT * EXCEPT(secret) FROM (VALUES(1,9)) t(x,secret)" to listOf(listOf("1")),
                "SELECT `odd name` FROM (SELECT 3 AS \"odd name\")" to listOf(listOf("3")),
                "SELECT (JSON '{\"x\":2}' -> 'x') = 2" to listOf(listOf("true")),
                "SELECT a.x FROM (VALUES(1),(2)) a(x) SEMI JOIN (VALUES(2)) b(x) USING(x)" to listOf(listOf("2")),
                "SELECT a.x FROM (VALUES(1),(2)) a(x) ANTI JOIN (VALUES(2)) b(x) USING(x)" to listOf(listOf("1")),
                "SELECT * FROM ((VALUES(1)) a(x) INNER JOIN (VALUES(1)) b(x) USING(x))" to listOf(listOf("1")),
                "SELECT * FROM (((SELECT 3 AS x)))" to listOf(listOf("3")),
            )
            for ((sql, expected) in cases) for (form in forms(sql)) {
                val serialized = c.serializeSql(form)
                if (sql.startsWith("PIVOT")) assertIs<VerifyResult.Unverified>(serialized.toParseOnlyResult(form))
                else assertIs<SerializedSql.Parsed>(serialized, form)
                assertEquals(expected, c.rows(form), form)
            }
        }
    }
    @Test fun jsonArrowPrecedenceAndDeprecatedLambdaPolicyDoNotBecomeAParseAcceptanceClaim() {
        connection().use { c ->
            val low = "SELECT JSON '{\"x\":2}' -> 'x' = 2"
            val high = "SELECT (JSON '{\"x\":2}' -> 'x') = 2"
            fun nativeRoot(sql: String): JsonObject {
                val query = serializeSqlQuery(sql)
                val document = c.rows(query).single().single()!!
                return Json.parseToJsonElement(document).jsonObject.getValue("statements").jsonArray.single().jsonObject
                    .getValue("node").jsonObject.getValue("select_list").jsonArray.single().jsonObject
            }
            assertEquals("LAMBDA", nativeRoot(low).getValue("class").jsonPrimitive.content)
            assertEquals("COMPARISON", nativeRoot(high).getValue("class").jsonPrimitive.content)
            for (form in forms(low)) {
                assertIs<SerializedSql.Parsed>(c.serializeSql(form))
                assertTrue(assertFailsWith<SQLException> { c.rows(form) }.message.orEmpty().contains("Binder Error"), form)
            }
            val arrow = "SELECT list_sum(list_transform([1,2],x -> x+1))"
            assertIs<SerializedSql.Parsed>(c.serializeSql(arrow))
            assertTrue(assertFailsWith<SQLException> { c.rows(arrow) }.message.orEmpty().contains("Deprecated lambda arrow"))
            c.createStatement().use { it.execute("SET lambda_syntax='ENABLE_SINGLE_ARROW'") }
            for (form in forms(arrow)) assertEquals(listOf(listOf("5")), c.rows(form))
            for (form in forms("SELECT list_sum(list_transform([1,2],LAMBDA x: x+1))")) assertEquals(listOf(listOf("5")), c.rows(form))
        }
    }
    @Test fun positionalNamedAndNumberedMarkersKeepTheirBoundOrderAndPrefixAliasDistinction() {
        connection().use { c ->
            for (sql in listOf("SELECT result: ? + ? * ?", "SELECT result: \$1 + \$2 * \$3", "SELECT result: \$a + \$b * \$c")) {
                assertEquals(3, parseParameterOccurrences(sql, "duckdb").size)
                for (form in forms(sql)) {
                    assertIs<SerializedSql.Parsed>(c.serializeSql(form))
                    c.prepareStatement(form).use { p ->
                        assertEquals(3, p.parameterMetaData.parameterCount)
                        p.setInt(1, 2)
                        p.setInt(2, 3)
                        p.setInt(3, 4)
                        if ("\$a" in sql) {
                            // This JDBC pin exposes only positional setters, which do not
                            // bind named engine identifiers. Parse acceptance is still real.
                            assertTrue(assertFailsWith<SQLException> { p.executeQuery() }.message.orEmpty().contains("Parameter argument/count mismatch"), form)
                        } else p.executeQuery().use { r ->
                            assertTrue(r.next())
                            assertEquals("14", r.getString(1))
                            assertEquals("result", r.metaData.getColumnLabel(1))
                        }
                    }
                }
            }
        }
    }
    @Test fun internalJoinWordsAreAliasesInBareJoinSyntaxAndMalformedAmbiguitiesAreRejected() {
        connection().use { c ->
            for (word in listOf("MARK", "SINGLE")) {
                val sql = "SELECT $word.x FROM (VALUES(1)) $word(x) JOIN (VALUES(1)) b(x) ON $word.x=b.x"
                // Use a base table so the engine's serialization exposes alias and kind.
                val document = c.rows(serializeSqlQuery("SELECT * FROM a $word JOIN b ON $word.x=b.x")).single().single()!!
                val join = Json.parseToJsonElement(document).jsonObject.getValue("statements").jsonArray.single().jsonObject
                    .getValue("node").jsonObject.getValue("from_table").jsonObject
                assertEquals("INNER", join.getValue("join_type").jsonPrimitive.content)
                assertEquals(word, join.getValue("left").jsonObject.getValue("alias").jsonPrimitive.content)
                for (form in forms(sql)) assertEquals(listOf(listOf("1")), c.rows(form), form)
            }
            for (sql in listOf("SELECT answer:", "SELECT * EXCLUDE() FROM t", "SELECT * REPLACE(1 AS) FROM t", "SELECT ((1)",
                "SELECT list_transform([1],LAMBDA x:)", "SELECT * FROM t GROUP BY ALL, x")) {
                val result = c.serializeSql(sql).toParseOnlyResult(sql)
                assertIs<VerifyResult.Rejected>(result, sql)
            }
        }
    }
}
