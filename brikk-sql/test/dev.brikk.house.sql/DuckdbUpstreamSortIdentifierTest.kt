package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbUpstreamSortIdentifierTest {
    @Test fun upstreamSortByNameLexicalFixKeepsRangesAndSerdeAfterTheCoherentRefresh() {
        val sql="INSERT INTO s.sort BY NAME SELECT ? AS n"
        assertFalse(Dialects.DUCKDB.tokenize(sql).any { it.tokenType==TokenType.SORT_BY })
        val tokens=nativeDuckdbAnalysisDialect().tokenize(sql)
        assertFalse(tokens.any { it.tokenType==TokenType.SORT_BY })
        assertEquals(sql.indexOf('?'),tokens.single { it.text=="?" }.start)
        val tree=assertIs<Insert>(parseDuckdbNativeOne(sql))
        assertEquals(tree,Serde.load(Serde.dump(tree)));assertEquals(tree,tree.copy())
        val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
        assertTrue("sort" in generated.lowercase());assertTrue("BY NAME" in generated)
        assertEquals(listOf(sql.indexOf('?')),parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertIs<Insert>(parseDuckdbNativeOne(generated))
    }
}
