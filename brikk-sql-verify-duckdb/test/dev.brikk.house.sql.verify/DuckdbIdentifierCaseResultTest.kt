package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/** 2.0.0 d54cbbe3df settings/setting_preserve_identifier_case.test; no user connections. */
internal class DuckdbIdentifierCaseResultTest {
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun Connection.run(sql: String, generated: Boolean) = createStatement().use { it.execute(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql) }
    @Test fun modesPreserveActualCatalogAndMacroSpellingWhileReferenceResolutionStaysInsensitive() {
        for (mode in DuckdbIdentifierCase.entries) for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.run("SET preserve_identifier_case='${mode.settingValue}'", generated)
            c.run("CREATE SCHEMA MySchema", generated)
            c.run("CREATE TABLE MySchema.MixedTable(ArG INTEGER,\"QuotedCol\" INTEGER)", generated)
            c.run("INSERT INTO MySchema.MixedTable VALUES(1,2)", generated)
            c.run("CREATE MACRO MySchema.MiXeD(ArG) AS aRg + 1", generated)
            val schema = mode.foldUnquoted("MySchema")
            val table = mode.foldUnquoted("MixedTable")
            val column = mode.foldUnquoted("ArG")
            assertEquals(listOf(listOf(schema, table, column), listOf(schema, table, "QuotedCol")), c.rows("SELECT schema_name,table_name,column_name FROM duckdb_columns() WHERE NOT internal ORDER BY column_index"))
            val actualNames = c.metaData.getColumns("memory", schema, table, null).use { r -> buildList { while (r.next()) add(r.getString("COLUMN_NAME")) } }
            assertEquals(listOf(column, "QuotedCol"), actualNames)
            val query = "SELECT m.aRg AS MiXeDAlias,m.\"quotedcol\" AS \"QuotedAlias\" FROM MySchema.MixedTable AS M"
            val folded = parseDuckdbNativeOne(query).withDuckdbIdentifierCase(mode).sql("duckdb")
            for (sql in listOf(query, SqlFragment(query, "duckdb").toExecutable("duckdb").sql, folded)) c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                assertEquals(listOf(mode.foldUnquoted("MiXeDAlias"), "QuotedAlias"), (1..2).map { r.metaData.getColumnLabel(it) })
                assertTrue(r.next())
                    assertEquals(1, r.getInt(1))
                    assertEquals(2, r.getInt(2))
            } }
            val facts = RelationFacts(tables = mapOf("$schema.$table" to RelationFact(actualNames.map { ColumnFact(it, "INTEGER") })))
            assertEquals(listOf(mode.foldUnquoted("MiXeDAlias"), "QuotedAlias"), RelationScope.analyze(folded, "duckdb", facts).output!!.columns.map { it.name })
            val functions = c.createStatement().use { s -> s.executeQuery(DUCKDB_FUNCTIONS.sql).use { r -> buildList {
                while (r.next()) add(DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { r.getString(it+1) }))
            } } }
            val macro = DuckdbDump("2.0.0", functions).userFunctions().single { it.schema == schema && it.def.name.equals("mixed", true) }
            assertEquals(mode.foldUnquoted("MiXeD"), macro.def.name)
            assertEquals(listOf(column), macro.def.overloads.single().argNames)
            assertEquals(listOf(listOf("2")), c.rows("SELECT MySchema.mIxEd(1)"))
        }
    }
    @Test fun stringSettingMetadataBooleanAliasesErrorsAndResetKeepNativeTypesAndValues() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val setting = c.createStatement().use { s -> s.executeQuery(DUCKDB_SETTINGS.sql).use { r -> buildList {
                while (r.next()) add(DUCKDB_SETTINGS.decode(DUCKDB_SETTINGS.columns.indices.map { r.getString(it+1) }))
            }.single { it.name == "preserve_identifier_case" } } }
            assertEquals("VARCHAR", setting.inputType)
            assertEquals("GLOBAL", setting.scope)
            for (value in listOf("true", "1", "'t'", "'y'", "'yes'", "false", "0", "'f'", "'n'", "'no'", "'uppercase'")) {
                c.run("SET preserve_identifier_case=$value", generated)
                val actual = c.rows("SELECT current_setting('preserve_identifier_case')").single().single()!!
                assertEquals(DuckdbIdentifierCase.fromSetting(value.trim('\'')), DuckdbIdentifierCase.fromSetting(actual))
            }
            for (value in listOf("'bogus'", "NULL", "'yesnt'")) assertFailsWith<SQLException> { c.run("SET preserve_identifier_case=$value", generated) }
            c.run("RESET preserve_identifier_case", generated)
            assertEquals(listOf(listOf("preserve_case")), c.rows("SELECT current_setting('preserve_identifier_case')"))
        }
    }
    @Test fun localLambdaBindingsUnicodeIdentifiersAndStringDataAreNotGloballyLowercased() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (mode in DuckdbIdentifierCase.entries) {
                c.run("SET preserve_identifier_case='${mode.settingValue}'", true)
                val query = "SELECT to_json(list_transform([1,2],LAMBDA ArG: aRg+1)) AS MiXeD,'MyString' AS \"Literal\""
                val folded = parseDuckdbNativeOne(query).withDuckdbIdentifierCase(mode).sql("duckdb")
                assertEquals(listOf(listOf("[2,3]", "MyString")), c.rows(query))
                assertEquals(c.rows(query), c.rows(folded))
                c.run("CREATE OR REPLACE TABLE unicode_names(Ä INTEGER,ä INTEGER)", true)
                c.run("INSERT INTO unicode_names VALUES(1,2)", true)
                assertEquals(listOf(listOf("1", "2")), c.rows("SELECT Ä,ä FROM unicode_names"))
                assertTrue("'NoSuch/Mixed/File.csv'" in parseDuckdbNativeOne("SELECT 'NoSuch/Mixed/File.csv' AS Path").withDuckdbIdentifierCase(mode).sql("duckdb"))
            }
        }
    }
}
