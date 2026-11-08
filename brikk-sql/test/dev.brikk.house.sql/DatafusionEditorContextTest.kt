package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.datafusionFunctionDiagnostics
import dev.brikk.house.sql.metadata.DATAFUSION_METADATA
import dev.brikk.house.sql.metadata.SPICE_METADATA
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DatafusionEditorContextTest {
    @Test
    fun catalogComponentsKeepDotsAndEscapedQuotes() {
        val facts = RelationFacts(tables = mapOf(
            "\"cat.one\".\"public\".\"a.b\"" to RelationFact(listOf(ColumnFact("dotted", "Int64"))),
            "\"cat.one\".\"public\".\"a\"\"b\"" to RelationFact(listOf(ColumnFact("quoted", "Utf8"))),
            "\"other\".\"public\".\"a.b\"" to RelationFact(listOf(ColumnFact("other", "Int64"))),
        ), searchPath = listOf("\"cat.one\".\"public\""))
        for (dialect in listOf("datafusion", "spice")) {
            assertEquals(listOf("dotted"), RelationScope.complete("SELECT t. FROM \"a.b\" t", dialect, facts, 9).items.map { it.name })
            assertEquals(listOf("quoted"), RelationScope.complete("SELECT t. FROM \"a\"\"b\" t", dialect, facts, 9).items.map { it.name })
            val overlapping = RelationFacts(tables = linkedMapOf(
                "cat.public.sample" to RelationFact(listOf(ColumnFact("model_column"))),
                "\"cat\".\"public\".\"sample\"" to RelationFact(listOf(ColumnFact("harvest_column"))),
            ))
            assertEquals(listOf("model_column"), RelationScope.complete("SELECT t. FROM sample t", dialect, overlapping, 9).items.map { it.name })
        }
    }

    @Test
    fun nestedSearchContextsAndUsedLabelsAreLocalToEachCall() {
        fun site(sql: String) = datafusionCallSite(sql, sql.length, "spice")!!
        assertEquals(DatafusionArgumentRole.SEARCH_INPUT, site("SELECT * FROM rrf(vector_search(docs,'q'), ").role)
        assertEquals(DatafusionArgumentRole.DATASET, site("SELECT * FROM rrf(vector_search(").role)
        val column = site("SELECT * FROM vector_search(cat.docs, 'q', column => ")
        assertEquals(DatafusionArgumentRole.COLUMN, column.role)
        assertEquals("cat.docs", column.firstArgument)
        val after = site("SELECT * FROM vector_search(docs, ['q','r'], limit => 5, ")
        assertEquals(setOf("limit"), after.usedNames)
        assertEquals(3, after.argument)
        assertTrue(after.atArgumentStart)
        assertNull(datafusionCallSite("SELECT f('still typing", 21, "spice"))
        assertNull(datafusionCallSite("SELECT schema.vector_search(", 28, "spice"))
    }

    @Test
    fun completionUsesDatasetRolesAndKnownStructFields() {
        val facts = RelationFacts(tables = mapOf("docs" to RelationFact(listOf(ColumnFact("body", "VARCHAR"), ColumnFact("payload", "Struct(\"name\": Utf8)")))))
        fun complete(marked: String): ScopeCompletion {
            val at = marked.indexOf('|')
            return RelationScope.complete(marked.replace("|", ""), "spice", facts, at)
        }
        assertEquals(PositionKind.RELATION, complete("SELECT * FROM vector_search(do|, 'q')").position)
        assertTrue(complete("SELECT * FROM vector_search(do|, 'q')").items.any { it.name == "docs" })
        assertTrue(complete("SELECT * FROM vector_search(docs, 'q', bo|)").items.any { it.name == "body" })
        assertEquals(listOf("name"), complete("SELECT d.payload.| FROM docs d").items.map { it.name })
        assertTrue(complete("SELECT d.payload.| FROM unknown d").items.isEmpty())
        val qualified = "SELECT * FROM vector_search(Cat.)"
        val scoped = RelationScope.complete(qualified, "spice", RelationFacts(tables = mapOf("Cat.docs" to RelationFact(), "cat.other" to RelationFact())), qualified.indexOf(".)") + 1)
        assertEquals(listOf("docs"), scoped.items.map { it.name })
    }

    @Test
    fun cteCompletionKeepsNamesThatDifferOnlyByQuotedCase() {
        val sql = "WITH \"A\" AS (SELECT 1 AS x), a AS (SELECT 2 AS y) SELECT * FROM "
        fun ctes(dialect: String) = RelationScope.complete(sql, dialect, RelationFacts(), sql.length).items
            .filter { it.kind == CompletionKind.RELATION }.map { it.name }
        assertEquals(listOf("a", "A"), ctes("datafusion"))
        assertEquals(listOf("a", "A"), ctes("spice"))
        assertEquals(listOf("a"), ctes("postgres"))
    }

    @Test
    fun onlyAuthoritativeSignaturesAndStubContextProduceDiagnostics() {
        fun df(sql: String) = datafusionFunctionDiagnostics(sql, "datafusion", DATAFUSION_METADATA.default)
        assertTrue(df("SELECT sqrt(1,2)").single().message.contains("expects 1"))
        assertTrue(df("SELECT sqrt([1,2])").single().message.contains("numeric argument"))
        assertTrue(df("SELECT sqrt(NULL)").isEmpty())
        assertTrue(df("SELECT abs(1,2)").isEmpty(), "unknown signature must not become a one-argument assertion")
        assertTrue(df("SELECT public.sqrt(1,2)").isEmpty())
        assertTrue(df("SELECT my_udf(1,2)").isEmpty())
        fun spice(sql: String) = datafusionFunctionDiagnostics(sql, "spice", SPICE_METADATA.default)
        assertTrue(spice("SELECT sqrt(1,2)").isEmpty(), "representative samples are not complete signatures")
        assertTrue(spice("SELECT vector_search(docs,'q')").single().message.contains("scalar registration"))
        assertTrue(spice("SELECT * FROM rrf(vector_search(docs,'q'), text_search(docs,'q'))").isEmpty())
        val sql = "SELECT '😀'; SELECT sqrt(1,2)"
        val error = df(sql).single()
        assertEquals("sqrt", sql.substring(error.start, error.end))
    }
}
