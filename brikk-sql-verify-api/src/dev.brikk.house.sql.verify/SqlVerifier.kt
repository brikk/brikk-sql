package dev.brikk.house.sql.verify

/**
 * A native-grammar verification oracle: answers "does the target engine's *own* parser
 * accept this SQL?" using the engine's authoritative parser, not brikk-sql's port of sqlglot.
 *
 * Two intended uses:
 *
 * 1. **Corpus gates**: every SQL string the generator emits for a dialect is fed through the
 *    engine's parser; rejections are real dialect bugs.
 * 2. **Editors**: transpiled or typed SQL is verified before it is shown or run, and a
 *    [VerifyResult.Rejected] carries the engine's own message and position.
 *
 * Implementations are cheap to hold on to and should be reused: cold start (parser class
 * loading, or an embedded engine boot) is paid once per instance. They never throw for invalid
 * SQL, and they let every exception that is not an engine verdict propagate, so cancellation
 * (`CancellationException`, IntelliJ's `ProcessCanceledException`) is never turned into a result.
 */
public interface SqlVerifier {
    /** Engine identifier, for example `"trino"`, `"duckdb"`, `"doris"` or `"postgres"`. */
    public val engine: String

    /**
     * True when this verifier is a re-implemented grammar rather than the engine's own parser,
     * so a [VerifyResult.Rejected] may be a false positive; consumers may treat it as a warning.
     */
    public val advisory: Boolean get() = false

    /** Verifies [sql], a single statement. */
    public fun verify(sql: String): VerifyResult

    /**
     * Verifies [sql] as a scalar expression fragment, for example `DAYNAME(x)`. Positions in
     * the result index [sql] itself, whatever wrapper the engine needed.
     */
    public fun verifyExpression(sql: String): VerifyResult
}

/** A verifier whose engine cannot run, for example because its parser failed to load. */
public class UnavailableVerifier(
    override val engine: String,
    /** Why the engine is unavailable; every result carries it. */
    public val reason: String,
) : SqlVerifier {
    override fun verify(sql: String): VerifyResult = VerifyResult.Unavailable(reason)

    override fun verifyExpression(sql: String): VerifyResult = VerifyResult.Unavailable(reason)
}
