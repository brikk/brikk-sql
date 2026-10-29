package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.pipeRunnableStageKeywords
import dev.brikk.house.sql.parser.pipeStageKeywords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each dialect's evidence of the PIPE stages its engine runs as lowered sits with the dialect. */
class PipeRunnableStagesTest {
    private val everywhere = setOf(
        "AGGREGATE", "AS", "DISTINCT", "EXTEND", "LIMIT", "OFFSET", "ORDER BY", "SELECT", "WHERE", "WINDOW",
        "JOIN", "INNER JOIN", "CROSS JOIN", "LEFT JOIN", "LEFT OUTER JOIN", "RIGHT JOIN", "RIGHT OUTER JOIN",
        "FULL JOIN", "FULL OUTER JOIN", "SEMI JOIN", "ANTI JOIN",
        "UNION ALL", "UNION DISTINCT", "INTERSECT", "INTERSECT ALL", "INTERSECT DISTINCT", "EXCEPT", "EXCEPT ALL", "EXCEPT DISTINCT",
    )

    private val evidence = mapOf(
        "trino" to everywhere + setOf("TABLESAMPLE", "UNION"),
        "duckdb" to everywhere + setOf("CALL", "DROP", "SET", "RENAME", "PIVOT", "UNPIVOT", "TABLESAMPLE", "UNION", "ASOF JOIN", "NATURAL JOIN", "POSITIONAL JOIN"),
        "doris" to everywhere + setOf("DROP", "SET", "UNION", "ASOF JOIN"),
        "clickhouse" to everywhere + setOf("DROP", "SET", "TABLESAMPLE", "ALL JOIN", "ANY JOIN", "ASOF JOIN", "ARRAY JOIN", "NATURAL JOIN"),
    )

    @Test
    fun recordedDialectsKeepOnlyTheStagesTheirEngineRuns() {
        for ((dialect, runs) in evidence) {
            assertEquals(runs, Dialects.forName(dialect).pipeRunnableStages, dialect)
            assertEquals(pipeStageKeywords(dialect).filter { it in runs }, pipeRunnableStageKeywords(dialect), dialect)
            assertTrue("STRAIGHT_JOIN" !in pipeRunnableStageKeywords(dialect), dialect)
        }
    }

    @Test
    fun aDialectWithoutEvidenceKeepsEveryParsedStage() {
        for (dialect in listOf("bigquery", "presto", "postgres")) {
            assertNull(Dialects.forName(dialect).pipeRunnableStages, dialect)
            assertEquals(pipeStageKeywords(dialect), pipeRunnableStageKeywords(dialect), dialect)
        }
    }
}
