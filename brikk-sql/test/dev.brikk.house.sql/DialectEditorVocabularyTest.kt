package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialectEditorVocabularyTest {
    private val dialects = listOf("duckdb", "trino", "doris").map(Dialects::forName)
    private val word = Regex("[A-Za-z][A-Za-z0-9_]*")

    @Test
    fun editorKeywordsAreSingleWordKeywords() {
        for (dialect in dialects) {
            val keywords = dialect.editorKeywords
            assertTrue(keywords.all { word.matches(it) && it in dialect.tokenizerConfig.keywords }, "${dialect.name}: $keywords")
            assertTrue(keywords.containsAll(listOf("SELECT", "FROM", "WHERE", "JOIN")), dialect.name)
            assertTrue(keywords.none { it in setOf("SETS", "VARYING", "SECURITY") }, "${dialect.name}: $keywords")
        }
        assertTrue("POSITIONAL" in Dialects.DUCKDB.editorKeywords)
        assertFalse("POSITIONAL" in Dialects.TRINO.editorKeywords)
    }

    @Test
    fun typeNamesAreKeywordSpellingsTheParserReadsAsTypes() {
        for (dialect in dialects) {
            val names = dialect.typeNames
            assertTrue(names.containsAll(listOf("VARCHAR", "BIGINT", "DOUBLE PRECISION")), "${dialect.name}: $names")
            assertFalse("SELECT" in names, dialect.name)
            for (name in names) {
                val parsed = listOf("CAST(x AS $name)", "CAST(x AS $name(INT))").any { sql ->
                    runCatching { dialect.parseOne(sql).find<Cast>()?.args?.get("to") is DataType }.getOrDefault(false)
                }
                assertTrue(parsed, "${dialect.name}: $name")
            }
        }
        assertTrue("LARGEINT" in Dialects.DORIS.typeNames)
        assertFalse("LARGEINT" in Dialects.TRINO.typeNames)
    }
}
