# DataFusion and Spice validation and execution

DFS-010 supplies shared consumer profiles and console contracts, plus bounded Flight SQL
execution evidence. It qualifies individual operations rather than declaring either product's
entire execution protocol supported. The release hold applied during DFS-010. The later
[SQL 0.22.0 and IntelliJ 0.3.0 releases](../sql-0.22.0.md) lift the shared-library hold,
not the transport exclusions or downstream plugin publication requirements.

## Background diagnostics

`DatafusionNativeErrorProfile(language, catalog, pipe)` uses the existing `NativeErrorProfile`,
`DataSourceVerification` and `NativeSqlValidator` contracts. Its verifier is explicitly
unavailable. Neither qualified Flight endpoint exposes a parser-only operation. Preparing a
Flight statement or issuing EXPLAIN can plan a query and invoke table functions, so the
profile never invokes the helper connection supplier or sends editor SQL to an engine.

Local parser diagnostics remain available offline and when connected. Unavailable native
verification never clears those errors or supplies an authoritative clean verdict. The
profile keeps the shared annotator's document/dependency checks, cancellation, size cap and
budget. Product mismatches are rejected at profile construction. Connection identity,
copied parser settings and Brikk-mode changes invalidate diagnostic dependencies.

When Brikk mode is enabled, a cancellable token pass reports unqualified stage names at
their UTF-16 source ranges. It does not generate SQL or evaluate functions. In native mode,
that Brikk stage policy does not claim the statement. No model/search call runs during
diagnostics or in the execution probes.

Register consumer subclasses of `NativeErrorAnnotator` and `NativeErrorPsiFilter` with the
same stable profile. The shared library registers no extensions itself.

## Native SQL and Brikk mode

Construct `DatafusionPipeDialect(language, catalog) { project -> settings.brikkPipeMode }`
with an explicit project opt-in. With it off, ordinary/native SQL follows stock Execute.
With it on, `PipeExecuteInterceptor` and the cooperative Execute configuration claim pipes
and pass them through `TranslatingScriptModel`, stock statement selection and parameter
prompting. Register the existing preview and run-to-stage actions/intentions against the
same profile. Copied relation facts from DFS-009 supply pipeline input-column completion.

The preview says the SQL is Brikk-generated and native parser verification is unavailable.
Known mismatched server versions or parser settings refuse lowering. Unknown connection
metadata does not turn local lowering into a native verdict. The qualified stage set is:

`SELECT`, `WHERE`, `EXTEND`, `AS`, `AGGREGATE`, `DISTINCT`, `ORDER BY`, `LIMIT`, `OFFSET`, `WINDOW`.

Other parsed stages, including nested ones, are refused by `lowerForExecution` for these
products. They are not offered as runnable stages. `toExecutable` remains a general explicit
generation API; execution integrations must use the checked lowering entry point.

Native and Brikk syntax differ despite sharing `|>`. In the recorded DataFusion embedding,
seven of these SELECT-headed native pipe controls execute; DISTINCT, OFFSET and WINDOW do
not. Spice rejects all ten native controls, while all twenty generated standard-SQL controls
execute with the expected values. Tests retain duplicate rows, assert order when ORDER BY
is final, and compare native results where the native syntax is accepted.

Execution pins the console/session, data source, namespace, document revision and connection
fingerprint across prompting. The fingerprint also covers user and connection settings.
Server-owned `$n` parameters inside SQL PREPARE remain intact instead of becoming client
prompts. Named colon parameters in native EXPLAIN statements use stock parameter storage.
The later [structured DataFusion utility correction](../../vendor/data/datafusion-spice/utilities/README.md)
adds dedicated core utility nodes and strict malformed-input checks while retaining this
parameter-ownership contract. Its parser evidence does not expand transport execution claims.
Claimed failures never delegate raw pipe text to another Execute action. A smart selection
that stock preparation returns as a bare clause is rebuilt through stock model preparation
for the pinned whole-statement range. Injected fragments that cannot be expanded safely are
refused. Parameter prompting and substitution still belong to the stock console.

Two measured lowering defects were fixed:

- DataFusion-family parsing now assigns a pipe after a closed FROM subquery to the enclosing
  query. The previous extra outer SELECT could discard final ordering. The compatibility
  default for other dialect parsers is unchanged.
- An aggregate after ORDER BY reads a completed input relation; the input ORDER BY no longer
  references a column removed by the aggregate projection.

The failing originals are retained in `round10/datafusion-before-*.json`.

## Explicit EXPLAIN

Users execute authored `EXPLAIN ...` or `EXPLAIN ANALYZE ...` through the console. Parameter
storage, destination and session stay with stock Execute. ANALYZE remains in the visible SQL
and executes the query. The evidence uses only `SELECT 1` for these controls.

Do not register the helper-connection `TextExplainService` as a substitute for this path.
A helper cannot reproduce a console's prepared statements or arbitrary session settings.
There is no automatic planning/type-check action in background diagnostics.

## Driver evidence and limits

`FlightExecutionProbe.java` uses Arrow Flight SQL JDBC 19.0.0 on disposable loopback fixtures.
The captures preserve raw rows, update counts, exceptions and cancellation outcomes. An
`accepted` operation means the JDBC method returned, not that an empty update count proves
the SQL executed correctly.

Spice v2.4.0 supports the measured `?`, `$1` and repeated `$1` JDBC bindings. Numbered bindings
also preserve apostrophes, supplementary characters, multiple arguments and rebinding. The
DataFusion execution fixture supports the measured typed `$n` bindings and nullable values.

The new `flight_execution.rs` fixture keeps a context per handshake, separates utility
statement schemas from query schemas, implements typed parameter batches, and streams query
results lazily. Typed parameter inference is deliberately limited to direct CAST expressions.
It resolves SQL EXECUTE into its bound plan, without collecting rows during preparation.
SQL PREPARE/EXECUTE works in one connection, fails in another, and fails after DEALLOCATE.
SET now returns normally in this execution variant and changes only its connection's setting;
the older fixture's result-schema mismatch remains in the DFS-009 capture.
The DFS-009 fixture and captures are unchanged; its failed execution qualification is retained
as `datafusion-legacy-embedding-execution.json`.

These concrete limitations must remain in the consumer handoff:

- **DFS-010-T1.** Spice's SQL PREPARE/EXECUTE path is not qualified. EXECUTE returns no result
  set, and generic `Statement.execute` reports an update count of zero. The same misleading
  zero occurs for the missing-column SELECT control. Do not interpret this as successful
  validation or advertise reliable SQL PREPARE session continuity on this transport.
  The tested SQL SET is explicitly rejected with `Statements are not allowed: SetVariable`.
- **DFS-010-T2.** Spice reports the tested parameter as non-nullable, so JDBC execution fails
  when its client binder processes a null value; `setNull` itself returns normally.
  Failing a null bind after a string bind also produces an Arrow allocator leak on connection
  close in the separate failure connection. The capture records the close failure.
- **DFS-010-T3.** The bounded DataFusion embedding does not normalize JDBC `?` into DataFusion's
  numbered placeholders. Its `?` control fails, while `$n` works. Other embeddings must qualify
  their own parameter handling; PostgreSQL syntax says nothing about wire support.
- **DFS-010-T4.** Bounded cancellation returns CANCELLED and permits a subsequent SELECT on
  both final fixtures. The earlier cross-join stress controls left subsequent work unresponsive
  or required the process watchdog. Those failures remain captured. Client cancellation is not
  proof that arbitrary remote computation has stopped. The test processes are always terminated.

No HTTP request or helper connection is treated as the same SQL session. No automatic
protocol substitution or driver dependency upgrade is introduced by this round.

The later [root-cause investigation](spice-flight-failures.md) isolates the T1 schema suppression,
incorrect prepared-update reply and SQL EXECUTE schema issues in Spice, plus T2's hardcoded
parameter nullability and independent Arrow JDBC error-path allocation leak. It records
raw-Flight and server-free controls without changing the supported JDBC claims.

## Error positions and source freshness

`DatafusionFlightErrors.syntaxError` recognizes the captured native parser coordinate suffix,
including DataFusion's `ParserError(...)` wrapper. It converts code-point columns to UTF-16.
Positionless errors remain positionless; schema errors and text embedded in SQL are not
searched for an approximate token location.

`authoredRange` passes native coordinates to a `PipeRunStatement`'s source map. It does not
convert them twice. Parameter expansion can remove that exact map, and an already captured
`TranslatingScriptModel` map now returns null after a later edit or editor disposal. Consumers
can show the original driver message when no exact authored range exists.

## Reproduction

```sh
cargo build --locked --manifest-path tools/datafusion-reference/flight/Cargo.toml \
  --bin brikk-datafusion-flight-execution --target-dir build/datafusion-reference -j 2
python3 tools/probe_flight_execution.py --java /path/to/java21 \
  --classpath-file build/tasks/_brikk-sql-intellij-support_testIntellijPlatform@brikk-sql-intellij-tooling/run-BUILD/test.log.classpath \
  --output build/review/spice-execution.json
python3 tools/probe_flight_execution.py --java /path/to/java21 \
  --classpath-file build/tasks/_brikk-sql-intellij-support_testIntellijPlatform@brikk-sql-intellij-tooling/run-BUILD/test.log.classpath \
  --datafusion-flight build/datafusion-reference/debug/brikk-datafusion-flight-execution \
  --output build/review/datafusion-execution.json
```

The probe opens `java.nio`; Java 25 additionally needs
`--java-option=--sun-misc-unsafe-memory-access=allow`. Use the already compiled platform
classpath so the probe executes this branch's actual lowering implementation. No production
service launches this fixture or probe.

## Validation

- Full core suite: 1,360 tests passed, including the new execution evidence and nested-pipe controls.
- Full shared IDE suite on 261.25134.95: 808 tests passed.
- Final focused suite, including the subsequent native PREPARE/EXPLAIN parameter fixes:
  56 tests passed on each of 261.25134.95, 262.10968.63 and 263.5701.42.
- Python evidence checks: 29 passed. Twenty lowered stage controls execute with expected
  values over Flight SQL. Negative native syntax and driver outcomes remain in the captures.
- API containment passes without new internal/experimental use or allowances. The binary
  audit in `/tmp/opencode/dfs010/` records eight new IntelliJ member references and 26
  increased class/member-reference counts.
