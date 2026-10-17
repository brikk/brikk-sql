package dev.brikk.house.sql

import dev.brikk.house.sql.ast.PipeJoin
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeSetOperation
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.pipeStageKeywords
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipeStageKeywordsTest {
    private val dialects = listOf("duckdb", "trino", "doris", "bigquery")

    @Test
    fun keywordsCoverTransformJoinAndSetOperationStages() {
        for (dialect in dialects) {
            val keywords = pipeStageKeywords(dialect)
            for (expected in listOf("WHERE", "SELECT", "EXTEND", "AGGREGATE", "ORDER BY", "LIMIT", "JOIN",
                "LEFT JOIN", "LEFT OUTER JOIN", "CROSS JOIN", "UNION ALL", "EXCEPT DISTINCT")) {
                assertTrue(expected in keywords, "$dialect lacks $expected: $keywords")
            }
            assertTrue(keywords.size == keywords.toSet().size, "$dialect: $keywords")
        }
    }

    @Test
    fun everyJoinAndSetOperationPhraseParsesAsThatStage() {
        for (dialect in dialects) {
            val source = Dialects.forName(dialect)
            val phrases = pipeStageKeywords(dialect).filter {
                it.endsWith("JOIN") || it.substringBefore(' ') in setOf("UNION", "INTERSECT", "EXCEPT")
            }
            for (phrase in phrases) {
                val stage = listOf("FROM t |> $phrase u ON a = b", "FROM t |> $phrase u", "FROM t |> $phrase (SELECT 1)")
                    .firstNotNullOfOrNull { sql ->
                        runCatching { (source.parseOne(sql) as PipeQuery).expressionsArg.single() }.getOrNull()
                    }
                assertTrue(stage is PipeJoin || stage is PipeSetOperation, "$dialect: $phrase parsed as $stage")
            }
        }
    }

    @Test
    fun vocabularyFollowsEachDialectsTables() {
        assertTrue("POSITIONAL JOIN" in pipeStageKeywords("duckdb"))
        assertFalse("POSITIONAL JOIN" in pipeStageKeywords("trino"))
        assertTrue("UNION" in pipeStageKeywords("trino"))
        assertFalse("UNION" in pipeStageKeywords("bigquery"))
    }
}
