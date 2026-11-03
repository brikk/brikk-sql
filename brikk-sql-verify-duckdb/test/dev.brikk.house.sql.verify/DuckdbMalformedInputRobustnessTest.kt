package dev.brikk.house.sql.verify

import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

internal class DuckdbMalformedInputRobustnessTest {
    @Test fun sizeDepthNulAndMalformedUnicodeRefuseBeforeOpeningAnyHelper() {
        val opens=AtomicInteger()
        val verifier=ConnectionDuckdbVerifier({ opens.incrementAndGet();error("helper must not open for over-budget input") })
        for(sql in listOf("SELECT '"+"x".repeat(MAX_DUCKDB_PARSE_CHARACTERS)+"'",
            "SELECT "+"(".repeat(MAX_DUCKDB_PARSE_NESTING+1),"SELECT "+"[".repeat(MAX_DUCKDB_PARSE_NESTING+1),
            "SELECT "+"/*".repeat(MAX_DUCKDB_PARSE_NESTING+1),"SELECT 'a\u0000b'","-- \u0000", "SELECT '\uD800'","SELECT '\uDC00'"))
            assertIs<VerifyResult.Unverified>(verifier.verify(sql))
        assertEquals(0,opens.get())
    }
    @Test fun quotesCommentsDollarStringsAndMarkerNamesDoNotConsumeStructuralDepth() {
        val payload="(".repeat(1000)
        for(sql in listOf("SELECT '$payload'", "SELECT E'it\\'s $payload'", "SELECT \$\$$payload\$\$",
            "SELECT \$tag\$$payload\$tag\$", "SELECT 1 /* $payload */", "SELECT 1 -- $payload\n", "SELECT \$name", "SELECT \$1"))
            assertNull(duckdbParseInputIssue(sql),sql.take(80))
        assertNull(duckdbParseInputIssue("SELECT "+"(".repeat(MAX_DUCKDB_PARSE_NESTING)+"1"+")".repeat(MAX_DUCKDB_PARSE_NESTING)))
        assertNull(duckdbParseInputIssue("SELECT '😀🚀é中'"))
        assertNotNull(duckdbParseInputIssue("SELECT 1 -- trivia\r"+"(".repeat(MAX_DUCKDB_PARSE_NESTING+1)))
        val canceled=CancellationException("typing changed")
        assertSame(canceled,assertFailsWith<CancellationException> { duckdbParseInputIssue("SELECT "+"x".repeat(8000)) { throw canceled } })
    }
    @Test fun embeddedBudgetRefusalDoesNotOpenCloseOrReconnectTheEngine() {
        val real=org.duckdb.DuckDBDriver();val opens=AtomicInteger()
        val driver=object:java.sql.Driver by real {
            override fun connect(url:String,info:java.util.Properties):java.sql.Connection {
                opens.incrementAndGet();return real.connect(url,info)
            }
        }
        ParseOnlyDuckdbVerifier(driver).use { verifier ->
            val limited="SELECT "+"(".repeat(MAX_DUCKDB_PARSE_NESTING+1)
            assertIs<VerifyResult.Unverified>(verifier.verify(limited));assertEquals(0,opens.get())
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"));assertEquals(1,opens.get())
            assertIs<VerifyResult.Unverified>(verifier.verify(limited))
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 2"));assertEquals(1,opens.get())
        }
    }
    @Test fun boundedMalformedAndBacktrackingHeavyInputsStayInAnIsolatedNativeProcess() {
        val malformed=listOf("SELECT", "SELECT ('😀' +)","SELECT 1 /* unfinished", "SELECT 'unfinished", "SELECT * FROM (VALUES(1)) t(x) JOIN",
            "SELECT "+"(".repeat(128)+"1", "SELECT list_transform([1], LAMBDA x, x, x:)",
            "SELECT CASE "+"WHEN true THEN ".repeat(128)+"END")
        val limited=listOf("SELECT "+"abs(".repeat(1100)+"1"+")".repeat(1100),"SELECT "+"(".repeat(8192))
        val inputs=(malformed+limited+"SELECT 1").mapIndexed { index,sql -> NativeCorpusAudit.Input("robustness/$index",sql) }
        val run=NativeCorpusAudit.probe("robustness-200",NativeCorpusAudit.currentJar(),inputs,"v2.0.0","d54cbbe3df")
        for(sql in malformed)assertEquals("rejected",run.verdicts.getValue(sql).status,sql.take(100))
        assertEquals("unverified",run.verdicts.getValue(limited[0]).status,"valid deep expressions hit an engine budget, not a grammar rejection")
        assertEquals("rejected",run.verdicts.getValue(limited[1]).status,"unmatched parentheses are malformed, not valid deep expressions")
        assertEquals("accepted",run.verdicts.getValue("SELECT 1").status,"parser must still serve after malformed inputs")
        NativeCorpusAudit.report("robustness.json",kotlinx.serialization.json.buildJsonObject {
            put("engine",NativeCorpusAudit.metadata(run))
            put("counts",NativeCorpusAudit.counts(inputs,run))
            put("outcomes",kotlinx.serialization.json.buildJsonArray {
                for(input in inputs)add(kotlinx.serialization.json.buildJsonObject {
                    val v=run.verdicts.getValue(input.sql)
                    put("origin",kotlinx.serialization.json.JsonPrimitive(input.origin))
                    put("sql_sha256",kotlinx.serialization.json.JsonPrimitive(NativeCorpusAudit.hash(input.sql.toByteArray())))
                    put("status",kotlinx.serialization.json.JsonPrimitive(v.status))
                    put("type",kotlinx.serialization.json.JsonPrimitive(v.type))
                    put("message",kotlinx.serialization.json.JsonPrimitive(v.message))
                })
            })
        })
    }
    @Test fun engineDepthAndMemoryLimitsAreUnverifiedRatherThanSyntaxRejections() {
        for((type,message)in listOf("parser" to "Max expression depth limit of 1000 exceeded", "out of memory" to "failed to allocate", "interrupt" to "query interrupted")) {
            val failure=SerializedSql.Failed(type,message,null,null,"{}")
            assertIs<VerifyResult.Unverified>(failure.toParseOnlyResult("SELECT 1"))
        }
        val syntax=SerializedSql.Failed("parser","syntax error at end of input",null,null,"{}")
        assertIs<VerifyResult.Rejected>(syntax.toParseOnlyResult("SELECT ("))
    }
    @Test fun unicodeLocationsAndMalformedTransportAreNotReinterpretedAsAcceptance() {
        val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(DriverManager.getConnection("jdbc:duckdb:")) })
        val sql="SELECT '😀é中'\r\nFROM WHERE"
        val error=assertIs<VerifyResult.Rejected>(verifier.verify(sql)).error
        assertEquals(sql.indexOf("WHERE"),error.offset);assertEquals(sql.indexOf("WHERE")+5,error.endOffset)
        for(response in listOf(null,"", "{", "{}", "{\"error\":false}","{\"error\":true,\"error_type\":\"parser\"}")) {
            val closes=AtomicInteger()
            val helper=object:DuckdbQueryConnection {
                override fun queryString(sql:String,timeoutSeconds:Int)=response
                override fun cancel()=Unit
                override fun close() { closes.incrementAndGet() }
            }
            assertIs<VerifyResult.Unavailable>(ConnectionDuckdbVerifier({ helper }).verify("SELECT 1"))
            assertEquals(1,closes.get())
        }
        assertIs<VerifyResult.Unavailable>(ConnectionDuckdbVerifier({ throw SQLException("remote parser process exited") }).verify("SELECT 1"))
        assertIs<VerifyResult.Unavailable>(ConnectionDuckdbVerifier({ throw UnsatisfiedLinkError("missing driver") }).verify("SELECT 1"))
        assertNull(serializedSqlDocument(" ".repeat(MAX_DUCKDB_PARSE_RESPONSE_CHARACTERS)+"{\"error\":false,\"statements\":[]}"))
        assertNull(serializedSqlDocument("[".repeat(10000)+"0"+"]".repeat(10000)))
        assertTrue(duckdbParserResponseWithinBudget("{\"message\":\""+"[".repeat(10000)+"\"}"))
    }
    @Test fun stuckQueriesAndBlockingCancelDoNotGrowWorkersOrBlockTheCaller() {
        val release=CountDownLatch(1);val closed=CountDownLatch(2);val opens=AtomicInteger();val cancelCalls=CountDownLatch(2)
        val verifier=ConnectionDuckdbVerifier(connect={
            opens.incrementAndGet()
            object:DuckdbQueryConnection {
                override fun queryString(sql:String,timeoutSeconds:Int):String {
                    while(release.count>0)try { release.await() }catch(_:InterruptedException) { /* deliberately broken transport */ }
                    return "{\"error\":false,\"statements\":[]}"
                }
                override fun cancel() { cancelCalls.countDown();release.await() }
                override fun close() { closed.countDown() }
            }
        },timeoutSeconds=1)
        try {
            repeat(2) { assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1")) }
            assertTrue(cancelCalls.await(5,TimeUnit.SECONDS))
            val start=System.nanoTime()
            assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000,"capacity refusal must not wait on a hung backend")
            assertEquals(2,opens.get())
        }finally { release.countDown() }
        assertTrue(closed.await(5,TimeUnit.SECONDS))
    }
}
