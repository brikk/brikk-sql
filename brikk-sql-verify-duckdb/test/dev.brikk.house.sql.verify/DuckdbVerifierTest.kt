package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

internal class DuckdbVerifierTest {
    @Test
    fun duckdb20DocumentedSyntaxIsAcceptedByTheFinalPegParser() {
        val statements = listOf(
            "WITH updated AS (UPDATE orders SET status = 'shipped' RETURNING *) SELECT count(*) FROM updated",
            "SELECT * FROM users q INNER JOIN products p APPROX NEAREST 2 BY SIMILARITY " +
                "array_cosine_similarity(q.embedding, p.embedding)",
            "CREATE SCHEMA finance.reports",
            "SELECT json_set(doc, '$.x', '2'), json_insert(doc, '$.y', '3'), " +
                "json_replace(doc, '$.z', '4'), json_remove(doc, '$.old') FROM t",
            "SELECT * FROM addresses ORDER BY city OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
            "SELECT unnest(tags), count(*) FROM t GROUP BY unnest(tags)",
            "MERGE INTO people USING updates ON people.id = updates.id " +
                "WHEN MATCHED THEN UPDATE SET name = updates.name WHEN NOT MATCHED THEN INSERT BY NAME",
            "UPDATE people SET name = updates.name FROM updates WHERE people.id = updates.id",
            "SELECT list_transform([1, 2, 3], lambda x: x + 1)",
            "CREATE TRIGGER trg AFTER INSERT ON t INSERT INTO audit VALUES (1)",
            "CONNECT 'postgres://localhost/db'",
            "DISCONNECT",
        )
        DuckdbVerifier().use { verifier ->
            for (sql in statements) assertIs<VerifyResult.Accepted>(verifier.verify(sql), sql)
        }
    }

    @Test
    fun serializedLocationsSupportLegacyCodePointsAndPegParserUtf8Bytes() {
        val sql = "SELECT '😀' FROM WHERE"
        val where = sql.indexOf("WHERE")
        val codePoint = sql.codePointCount(0, where)
        val byte = sql.take(where).encodeToByteArray().size

        assertEquals(
            VerifyError("bad", line = 1, column = where + 1, offset = where),
            SerializedSql.Failed("parser", "bad", codePoint, null, "{}").errorIn(sql, "bad"),
        )
        assertEquals(
            VerifyError("bad", line = 1, column = where + 1, offset = where, endOffset = where + 5),
            SerializedSql.Failed("parser", "bad", byte, "[$byte,5]", "{}").errorIn(sql, "bad"),
        )
    }

    @Test
    fun infrastructureFailuresAreUnavailableNotAccepted() {
        assertIs<VerifyResult.Unavailable>(DuckdbVerifier { throw SQLException("Connection was closed") }.verify("SELECT 1"))

        for (prefix in listOf("Binder Error", "Catalog Error", "Not implemented Error")) {
            assertIs<VerifyResult.Accepted>(classifyDuckdbPrepareFailure("$prefix: detail"))
        }
        val parser = assertIs<VerifyResult.Rejected>(classifyDuckdbPrepareFailure("Parser Error: bad\nLINE 2: 123 invalid"))
        assertEquals(VerifyError("Parser Error: bad\nLINE 2: 123 invalid", line = 2), parser.error)
        assertIs<VerifyResult.Unverified>(classifyDuckdbPrepareFailure("IO Error: disk unavailable"))
    }

    @Test
    fun cancellationPropagatesFromBothChannels() {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, assertFailsWith<CancellationException> { DuckdbVerifier { throw cancelled }.verify("SELECT 1") })

        val real = DriverManager.getConnection("jdbc:duckdb:")
        val cancellingPrepare = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") throw cancelled
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
        DuckdbVerifier { cancellingPrepare }.use { verifier ->
            assertSame(cancelled, assertFailsWith<CancellationException> { verifier.verify("CREATE TABLE t (a INT)") })
        }
    }

    @Test
    fun codePointPositionsBecomeUtf16RangesAcrossCrlf() {
        DuckdbVerifier().use { verifier ->
            for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
                for (separator in listOf(" ", "\n", "\r\n")) {
                    val sql = "SELECT '$payload',$separator'$payload' FROM WHERE"
                    val where = sql.indexOf("WHERE")
                    val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error
                    assertEquals(
                        listOf(sql.take(where).count { it == '\n' } + 1, where - (sql.lastIndexOf('\n', where - 1) + 1) + 1, where),
                        listOf(error.line, error.column, error.offset),
                        "$sql: ${error.message}",
                    )
                }
            }
        }
    }

    @Test
    fun nonSelectStatementsFallBackToPrepare() {
        DuckdbVerifier().use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)"))
            assertIs<VerifyResult.Rejected>(verifier.verify("CREATE TABLE t (a INT"))
            assertIs<VerifyResult.Accepted>(verifier.verifyExpression("x -> '$.family'"))
        }
    }
}
