package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DuckdbJsonMutationResultTest {
    private fun literal(text: String) = "'${text.replace("'", "''")}'"
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val count = result.metaData.columnCount
            buildList { while (result.next()) add((1..count).map { result.getString(it) }) }
        }
    }
    private fun Connection.check(expression: String, expected: String?) {
        val sql = "SELECT $expression AS result"
        assertEquals(listOf(listOf(expected)), rows(sql), "authored: $expression")
        assertEquals(listOf(listOf(expected)), rows(Dialects.DUCKDB.parseOne(sql).sql("duckdb")), "generated: $expression")
    }
    private fun Connection.reject(expression: String, message: String = "Error") {
        val sql = "SELECT $expression"
        for (text in listOf(sql, Dialects.DUCKDB.parseOne(sql).sql("duckdb"))) {
            val error = assertFailsWith<SQLException>(text) { rows(text) }
            assertTrue(message in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun setInsertReplaceAndRemoveHaveDifferentExistingAndMissingPathBehavior() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((name, existing, missing) in listOf(
                Triple("json_set", "{\"a\":9}", "{\"a\":1,\"b\":9}"),
                Triple("json_insert", "{\"a\":1}", "{\"a\":1,\"b\":9}"),
                Triple("json_replace", "{\"a\":9}", "{\"a\":1}"),
            )) {
                c.check("$name('{\"a\":1}','$.a','9')", existing)
                c.check("$name('{\"a\":1}','/b','9')", missing)
            }
            c.check("json_set('{}','$.a.b.c','1')", "{\"a\":{\"b\":{\"c\":1}}}")
            c.check("json_insert('{}','$.a.b','1')", "{\"a\":{\"b\":1}}")
            c.check("json_replace('{}','$.a.b','1')", "{}")
            c.check("json_remove('{\"a\":1,\"b\":2}','$.a')", "{\"b\":2}")
            c.check("json_remove('{\"a\":1}','$.missing')", "{\"a\":1}")
            c.check("json_set('{\"a\":1}','$.a.b','2')", "{\"a\":1}")
        }
    }

    @Test
    fun jsonPathsPointersQuotedKeysAndEscapesAreNotInterchangedOrReindexed() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((path, expected) in listOf("$.\"a.b\"" to "{\"a.b\":9}", "/a~1b/~0x" to "{\"a/b\":{\"~x\":9}}",
                "/" to "{\"\":9}", "日本" to "{\"日本\":9}")) c.check("json_set('{}',${literal(path)},'9')", expected)
            c.check("json_set(${literal("{\"a\\\"b\":1,\"a\\\\\":2}")},${literal("$.\"a\\\"b\"")},'9')", "{\"a\\\"b\":9,\"a\\\\\":2}")
            c.check("json_remove('{\"a/b\":1,\"c\":2}','/a~1b')", "{\"c\":2}")
            c.check("json_set('{\"a\":1}','$','{\"b\":2}')", "{\"b\":2}")
            c.check("json_insert('{\"a\":1}','$','2')", "{\"a\":1}")
            c.check("json_replace('{\"a\":1}','','2')", "2")
            c.check("json_remove('{\"a\":1}','$')", null)
            c.check("json_remove('[1,2]','')", null)
        }
    }

    @Test
    fun arraysUseZeroBasedAndReverseIndexesAppendOnlyAtTheEndAndShiftOnRemoval() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (path in listOf("$[2]", "$[#]", "$[#-0]", "/-")) {
                c.check("json_set('[1,2]',${literal(path)},'3')", "[1,2,3]")
                c.check("json_insert('[1,2]',${literal(path)},'3')", "[1,2,3]")
                c.check("json_replace('[1,2]',${literal(path)},'3')", "[1,2]")
            }
            for (name in listOf("json_set", "json_insert", "json_replace")) c.check("$name('[1,2]','$[5]','9')", "[1,2]")
            c.check("json_set('[1,2,3]','$[-1]','9')", "[1,2,9]")
            c.check("json_replace('[1,2,3]','$[#-2]','9')", "[1,9,3]")
            c.check("json_insert('[1,2]','$[0]','9')", "[1,2]")
            c.check("json_remove('[1,2,3]','$[1]')", "[1,3]")
            c.check("json_remove('[]','$[#-1]')", "[]")
        }
    }

    @Test
    fun valuesAreJsonDocumentsNotImplicitlyQuotedSqlStringsAndSqlNullDiffersFromJsonNull() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (name in listOf("json_set", "json_insert", "json_replace")) {
                c.check("$name('{\"x\":null}','$.x','null')", "{\"x\":null}")
                c.check("$name(NULL,'$.x','1')", null)
                c.check("$name('{}',NULL,'1')", null)
                c.check("$name('{}','$.x',NULL)", null)
                c.reject("$name('{}','$.x','hello')", "Malformed JSON")
                c.reject("$name('{}','$.x',1)", "Binder Error")
            }
            c.check("json_set('{}','$.x','\"hello\"')", "{\"x\":\"hello\"}")
            c.check("json_set('{}','$.x','[1,null,true]'::JSON)", "{\"x\":[1,null,true]}")
            c.check("json_remove(NULL,'$.a')", null)
            c.check("json_remove('{}',NULL)", null)
            c.check("json_normalize('null')", "null")
            c.check("json_strip_nulls('null')", "null")
        }
    }

    @Test
    fun duplicateObjectMembersArePreservedOrCollapsedAccordingToTheSpecificOperation() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("json_set('{\"a\":1,\"a\":2}','$.a','9')", "{\"a\":9}")
            c.check("json_insert('{\"a\":1,\"a\":2}','$.a','9')", "{\"a\":1,\"a\":2}")
            c.check("json_replace('{\"a\":1,\"a\":2}','$.a','9')", "{\"a\":9}")
            c.check("json_remove('{\"a\":1,\"a\":2}','$.a')", "{}")
            c.check("json_normalize('{\"b\":1,\"a\":2,\"b\":3}')", "{\"a\":2,\"b\":1,\"b\":3}")
            c.check("json_strip_nulls('[null,{\"a\":null,\"a\":1},{}]')", "[null,{\"a\":1},{}]")
        }
    }

    @Test
    fun unsupportedListsMultiplePathPairsFlagsAndWildcardsAreNativeErrorsNotOtherDialectOverloads() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (name in listOf("json_set", "json_insert", "json_replace")) {
                c.reject("$name('{}','$.a','1','$.b','2')", "Binder Error")
                c.reject("$name('{}',['$.a','$.b'],['1','2'])", "Binder Error")
                c.reject("$name('{}','$.a[*]','1')", "not supported")
                c.reject("$name('{}','\$not_a_path','1')", "JSON path error")
            }
            c.reject("json_remove('{}','$.a','$.b')", "Binder Error")
            c.reject("json_remove('{}',['$.a','$.b'])", "Binder Error")
            c.reject("json_strip_nulls('{}',false)", "Binder Error")
            c.reject("json_deep_merge('{}')", "Binder Error")
            c.reject("json_remove('{}','$.a[*]')", "not supported")
            for (name in listOf("json_normalize", "json_strip_nulls")) c.reject("$name('invalid')", "Malformed JSON")
            c.reject("json_merge_patch_diff('{}','invalid')", "Malformed JSON")
            c.reject("json_deep_merge('{}','invalid')", "Malformed JSON")
            c.check("TRY(json_set('{}','$[#-','1'))", null)
        }
    }

    @Test
    fun deepMergeSkipsJsonNullWhileMergePatchDeletesAndSqlNullIntermediateCanBeReplaced() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("json_merge_patch('{\"a\":{\"x\":1,\"y\":2}}','{\"a\":{\"y\":null,\"z\":3}}')", "{\"a\":{\"x\":1,\"z\":3}}")
            c.check("json_deep_merge('{\"a\":{\"x\":1,\"y\":2}}','{\"a\":{\"y\":null,\"z\":3}}')", "{\"a\":{\"x\":1,\"y\":2,\"z\":3}}")
            c.check("json_deep_merge('{\"a\":1}','{\"missing\":null}')", "{\"a\":1}")
            c.check("json_deep_merge('42','{\"a\":null}')", "{\"a\":null}")
            c.check("json_deep_merge('[1,2]','[3]')", "[3]")
            c.check("json_deep_merge('42','null')", "42")
            c.check("json_deep_merge('{\"a\":1}','{\"a\":null}','{\"a\":2}')", "{\"a\":2}")
            c.check("json_deep_merge(NULL,'{\"a\":1}')", "{\"a\":1}")
            c.check("json_deep_merge('{\"a\":1}',NULL)", null)
            c.check("json_deep_merge('{\"a\":1}',NULL,'{\"c\":3}')", "{\"c\":3}")
        }
    }

    @Test
    fun mergePatchDiffRoundTripsRepresentableTargetsButCannotIntroduceJsonNullMembers() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((old, modified) in listOf(
                "{\"a\":1,\"b\":2,\"c\":3}" to "{\"a\":1,\"b\":99,\"d\":4}",
                "{\"a\":{\"x\":1,\"y\":2}}" to "{\"a\":{\"x\":1}}", "{\"a\":[1,2]}" to "{\"a\":[3]}",
                "42" to "{\"a\":1}", "{\"a\":1}" to "42", "[1,2]" to "[1,2]",
            )) c.check("json_normalize(json_merge_patch(${literal(old)},json_merge_patch_diff(${literal(old)},${literal(modified)})))=json_normalize(${literal(modified)})", "true")
            c.check("json_merge_patch_diff('{\"a\":1,\"b\":2,\"c\":3}','{\"a\":1,\"b\":99,\"d\":4}')", "{\"c\":null,\"b\":99,\"d\":4}")
            c.check("json_merge_patch_diff('{\"a\":null}','{\"a\":null}')", "{}")
            c.check("json_merge_patch_diff(NULL,'{\"a\":1}')", "{\"a\":1}")
            c.check("json_merge_patch_diff('{\"a\":1}',NULL)", null)
            c.check("json_merge_patch_diff('{}','{\"a\":null}')", "{\"a\":null}")
            c.check("json_merge_patch('{}',json_merge_patch_diff('{}','{\"a\":null}'))", "{}")
        }
    }

    @Test
    fun normalizationSortsObjectKeysRecursivelyPreservesArrayOrderAndIsNotJsonTypingOrDeduplication() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("json_normalize('{\"z\":[3,1,{\"b\":2,\"a\":1}],\"a\":null}')", "{\"a\":null,\"z\":[3,1,{\"a\":1,\"b\":2}]}")
            c.check("json_normalize('{\"2\":2,\"10\":10,\"1\":1,\"ä\":0}')", "{\"1\":1,\"10\":10,\"2\":2,\"ä\":0}")
            c.check("json_normalize('{\"n\":1e2,\"d\":1.00}')", "{\"d\":1.0,\"n\":100.0}")
            c.check("json_normalize(json_normalize('{\"b\":1,\"a\":2}'))", "{\"a\":2,\"b\":1}")
            c.check("json_normalize(NULL)", null)
        }
    }

    @Test
    fun strippingNullsKeepsNullArrayElementsEmptyContainersAndScalarRoots() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("json_strip_nulls('{\"a\":[null,{\"x\":1,\"y\":null},{\"z\":null}],\"b\":null}')", "{\"a\":[null,{\"x\":1},{}]}")
            c.check("json_strip_nulls('{\"a\":{\"b\":{\"c\":null}}}')", "{\"a\":{\"b\":{}}}")
            for (scalar in listOf("42", "true", "\"hello\"", "null", "[]", "{}")) c.check("json_strip_nulls(${literal(scalar)})", scalar)
            c.check("json_strip_nulls(NULL)", null)
        }
    }

    @Test
    fun namedArgumentsAndColumnPathsKeepTheirRolesThroughQualificationAndPreparedExecution() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("json_set(value:='1',path:='$.a',json:='{}')", "{\"a\":1}")
            c.check("json_merge_patch_diff(new:='{\"a\":2}',old:='{\"a\":1}')", "{\"a\":2}")
            c.check("json_deep_merge(json2:='{\"a\":1}',json1:=NULL)", "{\"a\":1}")
            c.createStatement().use { s ->
                s.execute("CREATE TABLE documents(doc JSON,path VARCHAR,value JSON)")
                s.execute("INSERT INTO documents VALUES ('{\"a\":1}','/b','2'), ('[1,2]','$[#-1]','9'), (NULL,'$.a','1')")
            }
            val sql = "SELECT json_set(value:=value,path:=path,json:=doc) AS result FROM documents ORDER BY path"
            val expected = c.rows(sql)
            assertEquals(listOf(listOf(null), listOf("[1,9]"), listOf("{\"a\":1,\"b\":2}")), expected)
            val tree = qualify(Dialects.DUCKDB.parseOne(sql), dialect = Dialects.DUCKDB,
                schema = mapOf("documents" to mapOf("doc" to "JSON", "path" to "VARCHAR", "value" to "JSON")))
            assertEquals(expected, c.rows(tree.sql("duckdb")))
            for (text in listOf("SELECT json_set(?,?,?)", Dialects.DUCKDB.parseOne("SELECT json_set(?,?,?)").sql("duckdb"))) {
                c.prepareStatement(text).use { statement ->
                    for ((path, value, result) in listOf(Triple("$.a", "1", "{\"a\":1}"), Triple("/b", "\"text\"", "{\"b\":\"text\"}"))) {
                        statement.setString(1, "{}")
                        statement.setString(2, path)
                        statement.setString(3, value)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(result, rows.getString(1)); assertFalse(rows.next()) }
                    }
                    statement.setNull(3, Types.VARCHAR)
                    statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(null, rows.getString(1)); assertTrue(rows.wasNull()) }
                }
            }
        }
    }

    @Test
    fun actualReturnTypesAndLiveSignaturesMatchThePinnedCatalog() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val expressions = listOf("json_set('{}','$.a','1')", "json_insert('{}','$.a','1')", "json_replace('{}','$.a','1')",
                "json_remove('{}','$.a')", "json_deep_merge('{}','{}')", "json_merge_patch_diff('{}','{}')", "json_normalize('{}')", "json_strip_nulls('{}')")
            c.createStatement().use { statement ->
                statement.executeQuery(Dialects.DUCKDB.parseOne("SELECT ${expressions.joinToString(",")}").sql("duckdb")).use { result ->
                    assertEquals(listOf("JSON", "JSON", "JSON", "JSON", "JSON", "JSON", "VARCHAR", "JSON"), (1..8).map(result.metaData::getColumnTypeName))
                }
            }
            val names = listOf("json_deep_merge", "json_insert", "json_merge_patch_diff", "json_normalize", "json_remove", "json_replace", "json_set", "json_strip_nulls")
            assertEquals(listOf(
                listOf("json_deep_merge", "[JSON, JSON]", "JSON", "JSON"), listOf("json_insert", "[JSON, VARCHAR, JSON]", "JSON", null),
                listOf("json_merge_patch_diff", "[JSON, JSON]", "JSON", null), listOf("json_normalize", "[JSON]", "VARCHAR", null),
                listOf("json_remove", "[JSON, VARCHAR]", "JSON", null), listOf("json_replace", "[JSON, VARCHAR, JSON]", "JSON", null),
                listOf("json_set", "[JSON, VARCHAR, JSON]", "JSON", null), listOf("json_strip_nulls", "[JSON]", "JSON", null),
            ), c.rows("SELECT function_name,parameter_types::VARCHAR,return_type,varargs FROM duckdb_functions() WHERE function_name IN (${names.joinToString(",", transform = ::literal)}) ORDER BY function_name"))
        }
    }
}
