package dev.brikk.house.sql.scope

import dev.brikk.house.sql.testResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Editors ask at every caret of half-typed SQL: no prefix of the corpus statements, and no caret in them, may fail. */
class RelationScopeRobustnessTest {
    private val statements = testResource("relation-corpus/cases.jsonl").lines().filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject.getValue("sql").jsonPrimitive.content }
    private val facts = RelationFacts(
        slots = listOf("src", "rhs", "nullable").associateWith { RelationFact(listOf(ColumnFact("id"), ColumnFact("code"))) },
        tables = mapOf("physical" to RelationFact(listOf(ColumnFact("id")))),
        calls = mapOf("transform" to RelationFact(knowledge = Knowledge.PARTIAL)),
    )

    @Test
    fun everyPrefixAndCaretIsAnswered() {
        val failures = ArrayList<String>()
        for (dialect in listOf("duckdb", "trino", "doris", "postgres", "bigquery")) for (sql in statements) {
            for (end in 0..sql.length) {
                val prefix = sql.substring(0, end)
                runCatching { RelationScope.analyze(prefix, dialect, facts) }.onFailure { failures += "analyze [$dialect] $prefix: $it" }
                runCatching { RelationScope.complete(prefix, dialect, facts, end) }.onFailure { failures += "complete [$dialect] $prefix: $it" }
            }
            for (offset in sql.indices) {
                runCatching { RelationScope.complete(sql, dialect, facts, offset) }.onFailure { failures += "complete [$dialect] $offset $sql: $it" }
            }
        }
        assertEquals(emptyList(), failures.take(20))
    }
}
