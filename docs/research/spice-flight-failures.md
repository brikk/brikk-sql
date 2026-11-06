# Spice Flight SQL failure investigation

Investigated on 2026-11-02 using the checksum-pinned Spice v2.4.0+models executable and
Arrow Flight SQL JDBC 19.0.0. A standalone Java probe reproduces the failures without
Brikk or IntelliJ classes. The raw Flight controls use the same released driver jar's
shaded Flight client, bypassing its JDBC statement dispatch.

The two reported limitations contain **five distinct faults or incompatibilities**:

| Finding | Owner/path | Observed effect |
| --- | --- | --- |
| SFI-01 | Spice prepared-statement schema error handling | Missing-column errors become empty schemas |
| SFI-02 | Spice prepared-update protocol handler | Binds but does not execute; returns the wrong protobuf message |
| SFI-03 | Spice SQL EXECUTE schema advertisement | Reports zero output columns for an EXECUTE that returns rows |
| SFI-04 | Spice parameter-schema construction | Marks every parameter non-nullable, including nullable inputs |
| SFI-05 | Arrow JDBC parameter binder cleanup | Failed binding can orphan client-side Arrow allocations |

These explain DFS-010-T1 and T2. The investigation does not change their support exclusions,
the driver/runtime pins, or production behavior. T3 and T4 are outside this investigation.

## 1. Misleading update count zero

### SFI-01: a real planning error is treated as missing type information

For `SELECT missing_column FROM sample`, Spice's `CreatePreparedStatement` action catches
the planning error. Its substring allowlist includes `No field named`, alongside genuine
parameter type-inference failures. It returns an empty result schema and no parameter schema
even though this query has no parameters to bind later.

The same suppression exists in `GetFlightInfo` for a prepared query. The direct
`CommandStatementQuery` path instead returns the actual missing-column error.

This is narrower than swallowing all SQL errors. The probe gets ordinary errors for an
unknown table, unknown function, malformed SQL and an invalid integer cast. Both
`SELECT missing_column FROM sample` and `SELECT no_such_column` reproduce the false success.

Source: [Spice prepare fallback][spice-prepare] and [prepared GetFlightInfo][spice-info].

### SFI-02: the update RPC is a parameter-binding implementation, not an execution implementation

Arrow JDBC 19.0.0 classifies a prepared statement with zero result fields as UPDATE/IS_DML.
This also applies to ordinary JDBC `Statement` calls: the driver first creates a Flight
prepared statement. It dispatches the empty-schema case to `CommandPreparedStatementUpdate`.

Spice's handler decodes the incoming parameter batch, stores it in an updated handle and
returns `DoPutPreparedStatementResult`. It never runs the SQL on that path. Its separate
prepared-update `DoGet` handler contains execution logic, but the JDBC client does not call
that handler after this update RPC.

The client expects `DoPutUpdateResult.record_count`, an int64 in protobuf field 1.
Instead it receives `DoPutPreparedStatementResult.prepared_statement_handle`, bytes in field 1.
The recorded reply begins with `0a`, field 1 with a length-delimited wire type. The expected
record count uses a varint wire type. Protobuf retains the unexpected field as unknown and
leaves `record_count` at its default zero. No decoding exception is required for this mismatch.

Thus the observed chain is:

```text
missing-column error
  -> Spice returns empty prepared schema
  -> JDBC chooses prepared UPDATE
  -> Spice only binds and returns a handle
  -> client decodes that handle as an update reply
  -> default record_count = 0
```

The zero is not an affected-row count from running the SQL.

The same problem affects legitimate no-result statements on this route. In one explicit
session, the probe sends SQL PREPARE through the update RPC, receives zero, then finds the
named statement does not exist. A JDBC DEALLOCATE also returns zero while the named statement
still exists. Executing DEALLOCATE through the raw query path actually removes it.

Ordinary `Statement.executeQuery` is not a workaround. It follows the same driver dispatch
and then throws `Statement did not return a result set`, losing the original missing-column error.

Sources: [JDBC classification][arrow-type], [JDBC dispatch][arrow-dispatch],
[Spice update binding/reply][spice-update], [Arrow update reply decoding][arrow-update].

### SFI-03: SQL EXECUTE has a second, independent schema problem

The raw Flight query path proves the runtime can perform named SQL PREPARE/EXECUTE/DEALLOCATE:

- PREPARE in session A followed by EXECUTE in session A returns `8`.
- The same name fails in session B.
- EXECUTE fails after an actual DEALLOCATE in session A.

However, EXECUTE advertises an empty result schema, both through ordinary GetFlightInfo and
CreatePreparedStatement, while its DoGet stream has one BIGINT column and the row `8`.
Even when the statement already exists in the same explicit `x-session-id`, ordinary JDBC
EXECUTE still returns update count zero because of this empty schema and SFI-02.

Two controls return the row when forced through the query path: the raw Flight prepared
client and the JDBC driver's `ArrowFlightInfoStatement` escape hatch. The latter is a
deprecated driver-specific diagnostic control, not a proposed consumer implementation.

Source inspection explains the split. Schema retrieval returns the analyzed logical plan's
schema. Actual execution later handles Statement plans through the session context's
`execute_logical_plan`, resolving the stored prepared body and producing its result.
Correcting SFI-02 alone would not fix the empty schema or JDBC's query/update classification.

Sources: [Spice schema derivation][spice-schema] and [statement execution][spice-execute].

## 2. Null parameters and client allocation leaks

### SFI-04: parameter nullability is hardcoded false by Spice

Spice's `parameter_schema_for_plan` reads the parameter types, sorts them by parameter number,
and creates each field with `Field::new(name, typ, false)`. The final argument is nullable.
It does not derive an input nullability constraint from the expression using the parameter.

The wire schemas report non-nullable parameters for all three controls:

```sql
SELECT CAST($1 AS VARCHAR) AS txt, CAST($2 AS BIGINT) AS n;
SELECT CAST($1 AS BIGINT) IS NULL AS is_null;
SELECT COALESCE(CAST($1 AS BIGINT), 42) AS n;
```

The mixed control advertises Utf8View and Int64 parameter fields. JDBC parameter metadata
reports nullability `2`, meaning unknown, while the driver's binder checks the stricter Arrow
field directly.

A correction to the earlier shorthand matters: **`setNull` itself succeeds.** So does
`setObject(index, null)`. The exception occurs at execution, when the JDBC binder converts
the stored values into Arrow vectors. It rejects the null locally with
`Can't set null on non-nullable parameter`, before attaching or sending the parameter batch.

The engine is capable of handling these nulls. In a diagnostic raw-Flight control, only the
input field's nullable flag changes to true; the parameter remains an Arrow null value, not
an SQL literal substitution. The CAST query returns SQL NULL and the COALESCE query returns
`42`. This isolates incorrect parameter metadata from actual runtime null support. It does
not establish that every SQL position or constraint permits NULL.

Sources: [Spice parameter-schema builder][spice-parameters] and [JDBC null check][arrow-null].

### SFI-05: the binder loses ownership of allocations when a later value fails

Arrow's `AvaticaParameterBinder` creates a VectorSchemaRoot, fills vectors in parameter order,
then calls `preparedStatement.setParameters(root)` only after the whole loop succeeds.
There is no exception cleanup around that loop. If a later value fails, earlier allocations
exist but the prepared statement never acquires the root. Closing the statement therefore
cannot reach those allocations.

Measured controls on one JDBC connection:

| Binding case | Setter calls | Execution | Allocation retained after statement close |
| --- | --- | --- | --- |
| Single null | Return normally | Rejects locally | 0 bytes |
| Null first, string second | Return normally | Rejects before allocating the string | 0 bytes |
| String first, null second | Return normally | Rejects the second value | 1,056 bytes per attempt |
| Three repetitions of that failure | Return normally | All reject | 1,056, then 2,112, then 3,168 bytes |
| String first, incompatible string for BIGINT second | Return normally | Type conversion fails | 1,040 bytes |
| Two successful non-null executions | Return normally | Correct rows | 0 bytes after each statement closes |

`clearParameters()` and statement `close()` return normally but do not recover the failed
batch. A subsequent SELECT still works. Connection close reports the accumulated allocator
leak. These are client allocation counters, not Spice server memory measurements. Byte counts
depend on input types/values and are observations, not a universal per-failure bound.

A server-free control instantiates the same binder against a fake prepared-statement owner.
It reproduces the exception, positive retained allocation and absent ownership transfer.
Closing that owner leaves the allocation; retrieving and closing the orphan root for probe
cleanup returns the allocator to zero. This proves a driver cleanup fault independently of
Spice. The incompatible-type control also shows that fixing Spice's nullability alone does
not fix the general failure-cleanup path.

Sources: [binder ownership transfer][arrow-bind], [prepared root cleanup][arrow-clear] and
[connection allocator close][arrow-close]. Reflection in the probe is limited to allocation
counters and releasing the server-free control's orphan root. It is not shared-library code.

## What would need fixing

1. Spice should propagate real schema/planning errors rather than treating missing columns as
   deferred parameter inference. SFI-01 needs a negative missing-column regression test.
2. Spice's prepared-update DoPut must implement execution and return `DoPutUpdateResult`, with
   genuine execution errors and counts. Binding alone plus a handle reply is the query-binding
   protocol, not the prepared-update protocol. Test actual state change, not just RPC success.
3. Spice must advertise the result schema of the stored SQL EXECUTE body without executing it
   during metadata retrieval. Test schema equality and session ownership independently.
4. Spice should advertise parameter nullability accurately. The CAST/IS NULL/COALESCE controls
   demonstrate why an unconditional non-null flag is wrong.
5. Arrow JDBC needs exception-safe cleanup of a binder root until ownership transfers. Cover
   null rejection and late type-conversion failures, including repeated failures on one connection.

These are investigation conclusions, not applied fixes. Updating a driver pin alone is not
proved sufficient, and changing Brikk parsing cannot repair these protocol/ownership paths.
Keep the existing JDBC exclusions until the relevant fixes pass a new end-to-end qualification.
The previous SQL SET rejection remains separate runtime-policy evidence; this investigation
does not claim to change it. No production plugin changes were made.

Filed upstream on 2026-11-02: SFI-01 [spiceai/spiceai#15944](https://github.com/spiceai/spiceai/issues/15944),
SFI-02 [#15946](https://github.com/spiceai/spiceai/issues/15946), SFI-03 [#15948](https://github.com/spiceai/spiceai/issues/15948),
SFI-04 [#15950](https://github.com/spiceai/spiceai/issues/15950) and SFI-05
[apache/arrow-java#1439](https://github.com/apache/arrow-java/issues/1439). [Issue texts](flight-issue-drafts/README.md).

## Reproduction and evidence

Run the standalone probe against the disposable pinned Spice executable. Set
`REVIEW_DIR` to a new private output directory; it is not a checked-in capture.

```sh
python3 tools/probe_spice_flight_failures.py \
  --spiced /path/to/checksum-verified/spiced \
  --java /path/to/java25 \
  --java-option=--sun-misc-unsafe-memory-access=allow \
  --output "$REVIEW_DIR/spice-flight-failures.json"
python3 -m unittest discover -s tools -p 'test_datafusion_flight_investigation.py'
```

The probe compiles for Java 17, pins and verifies the driver jar, and bounds its subprocess to
120 seconds. Java 25 matches the recorded runtime; `java.nio` is opened by the runner. The
fixture is isolated loopback with the existing local Spicepod and no model calls. All connections
and the server process are disposable. Use a new output path; captures are never overwritten.

Evidence: [`flight-investigation/observations.json`](../../vendor/data/datafusion-spice/flight-investigation/observations.json)
contains 26 groups, including stack traces, raw protobuf bytes, Arrow schemas and allocation
counters. [`source-evidence.json`](../../vendor/data/datafusion-spice/flight-investigation/source-evidence.json)
records nine pinned source files with hashes and line-numbered excerpts. The Arrow sources were
byte-compared with the Maven Central 19.0.0 source jars. No current-main source is used as proof
for the released runtime.

[spice-prepare]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_query.rs#L210-L239
[spice-info]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_query.rs#L284-L346
[spice-update]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_update.rs#L179-L219
[spice-schema]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L2056-L2084
[spice-execute]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L1634-L1671
[spice-parameters]: https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L2098-L2142
[arrow-type]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/client/ArrowFlightSqlClientHandler.java#L443-L485
[arrow-dispatch]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/ArrowFlightMetaImpl.java#L206-L225
[arrow-update]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql/src/main/java/org/apache/arrow/flight/sql/FlightSqlClient.java#L1393-L1419
[arrow-bind]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/utils/AvaticaParameterBinder.java#L71-L108
[arrow-null]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/utils/AvaticaParameterBinder.java#L118-L138
[arrow-clear]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql/src/main/java/org/apache/arrow/flight/sql/FlightSqlClient.java#L1242-L1258
[arrow-close]: https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/ArrowFlightConnection.java#L213-L245
