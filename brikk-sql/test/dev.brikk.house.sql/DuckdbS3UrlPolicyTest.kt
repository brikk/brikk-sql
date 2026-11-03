package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbS3UrlPolicyTest {
    @Test fun questionMarksCaseAndLegacyLookingParametersStayInTheAuthoredUrlNotTheBindList() {
        for(url in listOf("s3://bucket/Case/File.txt?part=1&prefix=Mixed", "s3://bucket/key?s3_region=us-east-1",
            "s3://bucket/key?X-Amz-Signature=synthetic-only&X-Amz-Credential=fixture")) {
            val sql="SELECT * FROM read_text('$url') WHERE content=?"
            val tree=parseDuckdbNativeOne(sql)
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            assertTrue(tree.findAll<Literal>().any { it.name==url })
            val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
            assertTrue("'$url'" in generated)
            assertEquals(listOf(sql.lastIndexOf('?')),parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        }
    }
    @Test fun parserAndNativeDiagnosticSanitizersNeverExposeUrlsOrCredentialTokens() {
        val url="s3://bucket/key?X-Amz-Signature=synthetic-sensitive-marker"
        val sql="SELECT * FROM read_text('$url') WHERE ("
        val error=assertFailsWith<ParseError> { SqlFragment(sql,"duckdb").ast }
        assertFalse("synthetic-sensitive-marker" in error.message.orEmpty());assertFalse("s3://" in error.message.orEmpty())
        for(info in error.errors) { assertNull(info.startContext);assertNull(info.highlight);assertNull(info.endContext);assertNotNull(info.line) }
        val message=Dialects.DUCKDB.diagnosticMessage("SELECT 1","Request failed: https://example.invalid/key?X-Amz-Signature=synthetic-sensitive-marker")
        assertFalse("synthetic-sensitive-marker" in message);assertFalse("example.invalid" in message)
        assertEquals("ordinary error",Dialects.DUCKDB.diagnosticMessage("SELECT 1","ordinary error"))
    }
}
