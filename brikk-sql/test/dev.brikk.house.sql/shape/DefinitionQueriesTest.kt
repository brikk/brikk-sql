package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefinitionQueriesTest {
    private fun sql(dialect: String, kind: DefinedObject, vararg path: String) = definitionQuery(dialect, kind, path.toList())?.sql

    @Test
    fun dorisShowsCreateWithOrWithoutACatalog() {
        assertEquals("SHOW CREATE TABLE `internal`.`db`.`t`", sql("doris", DefinedObject.TABLE, "internal", "db", "t"))
        assertEquals("SHOW CREATE TABLE `db`.`t`", sql("doris", DefinedObject.TABLE, "db", "t"))
        assertEquals("SHOW CREATE VIEW `internal`.`db`.`v`", sql("doris", DefinedObject.VIEW, "internal", "db", "v"))
        assertEquals("SHOW CREATE VIEW `db`.`v`", sql("doris", DefinedObject.VIEW, "db", "v"))
        assertEquals("SHOW CREATE TABLE `hive`.`db`.`v`", sql("doris", DefinedObject.VIEW, "hive", "db", "v"), "an external catalog's view is shown as a table")
        assertEquals("SHOW CREATE DATABASE `hive`.`db`", sql("doris", DefinedObject.SCHEMA, "hive", "db"))
        assertEquals("SHOW CREATE CATALOG `hive`", sql("doris", DefinedObject.CATALOG, "hive"))
        assertEquals("SHOW CREATE TABLE `d``b`.`t`", sql("doris", DefinedObject.TABLE, "d`b", "t"))
        assertNull(sql("doris", DefinedObject.TABLE, "t"))
        assertNull(sql("doris", DefinedObject.MACRO, "db", "m"))
    }

    @Test
    fun dorisReadsTheCreateColumn() {
        val query = checkNotNull(definitionQuery("doris", DefinedObject.TABLE, listOf("db", "t")))
        assertEquals("CREATE TABLE `t` (k INT)", query.definition(listOf(linkedMapOf("table" to "t", "create table" to "CREATE TABLE `t` (k INT)"))))
        assertEquals("CREATE VIEW v AS SELECT 1", query.definition(listOf(linkedMapOf("view" to "v", "x" to "CREATE VIEW v AS SELECT 1", "y" to "utf8"))))
        assertEquals("body", query.definition(listOf(linkedMapOf("name" to "t", "text" to "body"))), "the second column")
        assertNull(query.definition(emptyList()))
    }

    @Test
    fun trinoShowsCreateFullyQualified() {
        assertEquals("SHOW CREATE TABLE \"memory\".\"s\".\"t\"", sql("trino", DefinedObject.TABLE, "memory", "s", "t"))
        assertEquals("SHOW CREATE VIEW \"memory\".\"s\".\"v\"", sql("trino", DefinedObject.VIEW, "memory", "s", "v"))
        assertEquals("SHOW CREATE MATERIALIZED VIEW \"c\".\"s\".\"m\"", sql("trino", DefinedObject.MATERIALIZED_VIEW, "c", "s", "m"))
        assertEquals("SHOW CREATE SCHEMA \"memory\".\"s\"", sql("trino", DefinedObject.SCHEMA, "memory", "s"))
        assertEquals("SHOW CREATE TABLE \"c\".\"s\".\"a\"\"b\"", sql("trino", DefinedObject.TABLE, "c", "s", "a\"b"))
        assertNull(sql("trino", DefinedObject.TABLE, "s", "t"), "needs the catalog")
        assertNull(sql("trino", DefinedObject.CATALOG, "memory"))
        val query = checkNotNull(definitionQuery("trino", DefinedObject.TABLE, listOf("c", "s", "t")))
        assertEquals("CREATE TABLE c.s.t (\n   x bigint\n)", query.definition(listOf(mapOf("create table" to "CREATE TABLE c.s.t (\n   x bigint\n)"))))
    }

    @Test
    fun duckdbReadsItsCatalogTables() {
        assertEquals(
            "SELECT sql FROM system.main.duckdb_tables() WHERE database_name = 'lake' AND schema_name = 'web' AND table_name = 'it''s'",
            sql("duckdb", DefinedObject.TABLE, "lake", "web", "it's"),
        )
        assertEquals(
            "SELECT sql FROM system.main.duckdb_views() WHERE database_name = 'memory' AND schema_name = 'main' AND view_name = 'v' AND NOT internal",
            sql("duckdb", DefinedObject.VIEW, "memory", "main", "v"),
        )
        assertNull(sql("duckdb", DefinedObject.TABLE, "main", "t"), "needs the database")
        for (kind in listOf(DefinedObject.TABLE, DefinedObject.VIEW, DefinedObject.SEQUENCE, DefinedObject.INDEX, DefinedObject.MACRO)) {
            val text = checkNotNull(sql("duckdb", kind, "memory", "main", "x"))
            // A user macro shadows an unqualified call, and DuckDB's own macros call others: only one qualified built-in table function is called.
            val calls = Regex("""([A-Za-z_.]+)\(""").findAll(text).map { it.groupValues[1] }.toList()
            assertEquals(1, calls.size, "$kind: $text")
            assertTrue(calls.single().matches(Regex("system\\.main\\.duckdb_(tables|views|sequences|indexes|functions)")), "$kind: $text")
        }
        assertNull(sql("duckdb", DefinedObject.CATALOG, "lake"))
        assertNull(sql("duckdb", DefinedObject.SCHEMA, "lake", "web"))
        assertNull(sql("postgres", DefinedObject.TABLE, "db", "s", "t"))
    }

    @Test
    fun duckdbMacrosAreRebuiltWithTheirOverloads() {
        val query = checkNotNull(definitionQuery("duckdb", DefinedObject.MACRO, listOf("memory", "web", "add")))
        val rows = listOf(
            mapOf("function_type" to "macro", "parameters" to listOf("a", "b"), "macro_definition" to "(a + b)"),
            mapOf("function_type" to "macro", "parameters" to listOf("a", "b, c", "d\"e"), "macro_definition" to "((a + b) + c)"),
        )
        assertEquals(
            "-- DuckDB does not report macro parameter defaults; any are omitted here.\nCREATE MACRO \"web\".\"add\"(\"a\", \"b\") AS (a + b), (\"a\", \"b, c\", \"d\"\"e\") AS ((a + b) + c);",
            query.definition(rows),
        )
        val table = checkNotNull(definitionQuery("duckdb", DefinedObject.MACRO, listOf("memory", "My Schema", "rows")))
        assertEquals(
            "-- DuckDB does not report macro parameter defaults; any are omitted here.\nCREATE MACRO \"My Schema\".\"rows\"(\"n\") AS TABLE SELECT * FROM \"range\"(n);",
            table.definition(listOf(mapOf("function_type" to "table_macro", "parameters" to listOf("n"), "macro_definition" to "SELECT * FROM \"range\"(n)"))),
        )
        assertNull(query.definition(emptyList()))
    }
}
