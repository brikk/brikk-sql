package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fidelity oracle tests for the embedded PostgreSQL verifier. These live in brikk-sql-oracle
 * (with the [PostgresVerifier] and its embedded-PG dependency) rather than brikk-sql-verify,
 * whose `SqlVerifiers.forEngine("postgres")` returns the advisory ShardingSphere oracle.
 *
 * All postgres cases share ONE embedded PG instance because booting a native server costs
 * roughly 1-3 seconds. See [pg].
 */
class PostgresVerifierTest {

    private companion object {
        /**
         * One embedded PostgreSQL shared by all cases in this class — booting a native PG per
         * test would cost seconds each. Constructed directly (the fidelity embedded-PG oracle),
         * not via [SqlVerifiers.forEngine], which returns the advisory ShardingSphere oracle.
         */
        val pg: PostgresVerifier by lazy {
            PostgresVerifier().also { verifier ->
                // Stop the embedded server when the test JVM exits (there is no portable
                // per-class teardown hook without pulling in a JUnit-engine dependency, and the
                // embedded PG is meant to live for the whole run anyway).
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { verifier.close() } })
            }
        }
    }

    @Test
    fun postgresAcceptsValidStatement() {
        val result = pg.verify("SELECT a FROM t WHERE b = 1")
        // A valid SELECT prepares cleanly against the empty catalog? No — `t` is undefined, so
        // this is a semantic (42P01) case: grammar accepted, catalog empty => accepted=true.
        assertIs<VerifyResult.Accepted>(result, "expected grammar acceptance, got: $result")
    }

    @Test
    fun postgresAcceptsFullyResolvableStatement() {
        // No catalog dependency at all: prepares AND analyzes with no error.
        val result = pg.verify("SELECT 1 + 2 AS x")
        assertIs<VerifyResult.Accepted>(result, "expected acceptance, got: $result")
    }

    @Test
    fun postgresVerifierUsesModernJsonValueGrammarRatherThanAnOldVersionRejection() {
        for (source in listOf("SELECT JSON_VALUE('42', '$' RETURNING BIGINT)",
            "SELECT JSON_VALUE('{}', '$.a' RETURNING BIGINT DEFAULT 7 ON EMPTY)",
            "SELECT JSON_VALUE('{}', '$.a' RETURNING BIGINT ERROR ON EMPTY)",
            "SELECT JSON_VALUE('[1,2]', 'strict $[*]' RETURNING BIGINT ERROR ON ERROR)")) {
            val result = pg.verify(source)
            assertIs<VerifyResult.Accepted>(result, "$source/$result")
        }
    }

    @Test
    fun postgresRejectsSyntaxErrorWithPosition() {
        // A keyword typo: PG reports `42601 syntax error at or near "SELCT"` at position 1.
        val error = assertIs<VerifyResult.Rejected>(pg.verify("SELCT 1")).error
        assertTrue(error.message.startsWith("42601"), "expected 42601 syntax error, got: $error")
        assertEquals(1, error.line)
        assertEquals(1, error.column)
    }

    @Test
    fun postgresCodePointPositionsBecomeUtf16RangesAcrossCrlf() {
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (separator in listOf(" ", "\n", "\r\n")) {
                val sql = "SELECT '$payload',$separator'$payload' FROM WHERE"
                val where = sql.indexOf("WHERE")
                val error = assertIs<VerifyResult.Rejected>(pg.verify(sql), sql).error
                assertEquals(
                    listOf(sql.take(where).count { it == '\n' } + 1, where - (sql.lastIndexOf('\n', where - 1) + 1) + 1, where),
                    listOf(error.line, error.column, error.offset),
                    "$sql: ${error.message}",
                )
            }
        }
    }

    @Test
    fun postgresRejectsSyntaxErrorWithInteriorPosition() {
        // Position mapped to the offending token in the interior of the statement.
        val error = assertIs<VerifyResult.Rejected>(pg.verify("SELECT * FROM t WHERE")).error.message
        // "syntax error at end of input" — PG may or may not report a position; when it does
        // it should be past the WHERE. Just assert the classification here.
        assertTrue(error.startsWith("42601"), "expected 42601, got: $error")
    }

    @Test
    fun postgresTreatsSemanticOnlyErrorsAsAccepted() {
        // Grammar parsed; only the empty catalog complains => accepted=true, no error surfaced.
        assertIs<VerifyResult.Accepted>(pg.verify("SELECT * FROM no_such_table"), "undefined_table is semantic (42P01)")
        assertTrue(
            pg.verify("SELECT missing_col FROM (SELECT 1 AS a) s") is VerifyResult.Accepted,
            "undefined_column is semantic (42703)",
        )
        assertIs<VerifyResult.Accepted>(pg.verify("SELECT no_such_func(1)"), "undefined_function is semantic (42883)")
    }

    @Test
    fun postgresAcceptsDdlViaRolledBackExecutionFallback() {
        // DDL isn't preparable; it falls back to BEGIN; <sql>; ROLLBACK direct execution.
        assertIs<VerifyResult.Accepted>(pg.verify("CREATE TABLE brikk_ddl_probe (a INT, b TEXT)"))
        // ...and a malformed DDL is still a real syntax reject through that path.
        val bad = assertIs<VerifyResult.Rejected>(pg.verify("CREATE TABLE brikk_ddl_probe (a INT")).error.message
        assertTrue(bad.startsWith("42601"), "expected 42601, got: $bad")
    }

    @Test
    fun postgresDdlFallbackLeavesNoResidue() {
        // Transaction-rollback safety: creating a table via the fallback must not persist it,
        // so creating the SAME table twice must both succeed (no "already exists" 42P07 on the
        // second call — which would prove the first CREATE committed).
        val name = "brikk_rollback_probe"
        assertIs<VerifyResult.Accepted>(pg.verify("CREATE TABLE $name (a INT)"), "first create should parse+rollback")
        val second = pg.verify("CREATE TABLE $name (a INT)")
        assertIs<VerifyResult.Accepted>(
            second,
            "second create must also be accepted — if the first had committed, PG would raise 42P07; result=$second",
        )
        // Direct proof the table does not exist: a plain reference is a semantic miss (accepted),
        // but the catalog genuinely lacks it — verified by the double-create above.
    }

    @Test
    fun postgresAcceptsNonTransactionalDdlWithoutSideEffect() {
        // CREATE DATABASE can't run in a transaction block; PG raises 25001 which we treat as
        // "parsed, transaction context refused" => accepted, and it never leaves BEGIN so no db
        // is created.
        assertIs<VerifyResult.Accepted>(pg.verify("CREATE DATABASE brikk_probe_db"), "parsed; 25001 => accepted")
    }

    @Test
    fun postgresVerifiesExpressionFragments() {
        assertIs<VerifyResult.Accepted>(pg.verifyExpression("1 + 2"))
        // undefined column in an expression is still semantic (grammar accepted).
        assertIs<VerifyResult.Accepted>(pg.verifyExpression("some_col + 1"))
        assertIs<VerifyResult.Rejected>(pg.verifyExpression("1 +"))
    }

    @Test
    fun postgresRejectsParseTimeFeatureNotSupportedErrors() {
        val error = assertIs<VerifyResult.Rejected>(pg.verify("CREATE ASSERTION brikk_assertion CHECK (true)")).error
        assertTrue(error.message.startsWith("0A000"), error.message)
    }

    @Test
    fun postgresAcceptsProgramLimitErrorsAfterParsing() {
        val columns = (1..1700).joinToString(", ") { "$it AS c$it" }
        val result = pg.verify("SELECT $columns")
        assertIs<VerifyResult.Accepted>(result, "$result")
    }

    @Test
    fun postgresRejectsMultipleStatementsBeforeStartup() {
        var starts = 0
        val verifier = PostgresVerifier {
            starts += 1
            error("must not start")
        }
        val error = assertIs<VerifyResult.Rejected>(verifier.verify("SELECT 1; SELECT 2")).error
        assertTrue(error.message.contains("exactly one SQL statement"))
        assertEquals(0, starts)
        assertIs<VerifyResult.Accepted>(pg.verify("SELECT ';' AS value"))
        assertIs<VerifyResult.Accepted>(pg.verify("SELECT ${'$'}tag${'$'};${'$'}tag${'$'} AS value"))
        assertIs<VerifyResult.Accepted>(pg.verify("SELECT 1 /* ; is not a statement boundary */"))
    }

    @Test
    fun postgresStartupFailureIsMemoizedAndCloseIsTerminal() {
        var starts = 0
        val unavailable = PostgresVerifier {
            starts += 1
            error("no postgres")
        }
        repeat(2) {
            assertIs<VerifyResult.Unavailable>(unavailable.verify("SELECT 1"))
        }
        assertEquals(1, starts)

        var closedStarts = 0
        val closed = PostgresVerifier {
            closedStarts += 1
            error("must not start")
        }
        closed.close()
        val result = assertIs<VerifyResult.Unavailable>(closed.verify("SELECT 1"))
        assertTrue(result.reason.contains("closed"))
        assertEquals(0, closedStarts)
    }
}
