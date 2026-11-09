# SG29-010 JSON-valued ClickHouse extraction

This bounded port reviews SQLGlot #8601, commit
`4d7d7ca94250269dc1903d7175b7b8aa96e9a480`. It follows committed SG29-009
`70a9f1c0f315ab58c96f721c9ddcc27735b70779` in the existing worktree. The global
oracle remains `v30.22.0-5-ga00dc2e5c`; SG29-011 onward is outside this request.

## Changes

The ClickHouse generator spells JSON-valued `JSONExtract` as `JSONExtractRaw`.
Scalar `JSONExtractScalar` remains `JSONExtractString`. The handwritten parser
maps native JSONExtractRaw calls to the existing JSONExtract class, so raw and
scalar calls keep distinct canonical kinds and PostgreSQL/DuckDB preview names.
No generated registry or public AST/data-class shape changes.

Both native functions share a path builder. Positive native integer indices are
converted from one-based to canonical zero-based indices. Quoted numeric keys
stay keys, rather than accidentally becoming array subscripts. Native zero,
negative and dynamic segments retain their original variadic arguments; they
must not be decremented or assigned to Boolean option slots. Large positive
integer arguments use Long rather than overflowing Int.

The native emitter leaves canonical negative from-end indices negative, escapes
literal path keys and retains empty-string keys. Root, dotted, spaced, quoted
and numeric-key paths are checked separately from array positions. Generation
and serialization preserve the owned AST and source positions, including a
quoted Unicode identifier.

A handwritten ClickHouse typing overlay gives JSONExtractRaw's canonical node
the native String-compatible VARCHAR return type. ClickHouse returns String or
Nullable(String), not a SQL JSON storage value. The nullability sidecar remains
conservative. Generated typing tables are unchanged; public JVM signatures and
data-class constructor/copy shapes are unchanged.

## Execution boundaries

ClickHouse returns the empty string for a missing path, the string `null` for a
JSON null under raw extraction, and SQL NULL for a nullable SQL input. Those
are not PostgreSQL/DuckDB JSON extraction's missing/null and result-type
contracts. Correcting the raw function name does not qualify arbitrary
cross-engine execution.

Foreign/base JSONExtract nodes therefore retain corrected ClickHouse preview
SQL with a finding, but refuse executable generation. Native raw nodes carry a
serialized provenance marker; strict outbound generation to another engine
also refuses, including after a serde round trip. Native ClickHouse raw
extraction remains executable on ClickHouse. Existing unrelated executable
safeguards are unchanged.

PostgreSQL and DuckDB tests execute finite outbound previews for concrete literal
paths, checking JSON result types and values, while independently asserting
strict API refusal. No native backend error is treated as an equivalent result.
Native zero/negative/dynamic segment preservation does not qualify those
variadic forms as valid outbound previews. Object-ordinal integer lookup,
wildcards and arbitrary dynamic JSONPath translation are not generalized here.

ClickHouse 26.10.2.103 also converts numeric, Boolean and composite values to
text through JSONExtractString. Native controls record that measured behavior,
not an assumed older-version empty-string rule. Raw JSON strings retain their
JSON quotes; scalar strings do not. JSON null remains distinct as well. This
item does not add general cross-engine qualification for scalar extraction.

## Evidence and safety

`native-results.json` records actual original/generated ClickHouse rows and
native result types for objects, arrays, strings, numbers, Booleans, missing
members, JSON null, SQL NULL, array bounds, numeric/empty/dotted/quoted keys,
root and dynamic extraction. Foreign-source preview executions and strict API
refusals are labeled separately from native round-trip execution.

`upstream-controls.json` records frozen v30.29 generator controls. The numeric
quoted-key fix deliberately differs from upstream's coercion, with native
ClickHouse evidence. These controls are not a global oracle refresh.

`native-coverage-review.json` compares all 24,606 pre/post origin/SQL/status/type
tuples, inventory, local dispositions, engine, JDBC digest and complete settings.
All remain unchanged. No parity ledger, corpus, existing audit baseline or
fixture is rewritten for this item.

The test-only `tools/probe_clickhouse_json.py` uses the Java public-facade bridge
and the task-private `brikk-sg010-clickhouse` container. It checks network
isolation and refuses host bind mounts or published ports. It starts no service
and executes only synthetic SELECT controls. The capture records the immutable
image and exact native version. The private container was stopped and
automatically removed after capture. No host kernel, trust store or user
database is changed.

The starting commit/hashes and verified coverage report are saved under
`build/sg29-010/`. Logs use `/var/tmp/jayson/opencode/sg29-010-*.log`; failed red,
compilation and initial native-control iterations remain alongside final runs.
The initial numeric scalar expectation failed despite original/generated native
results agreeing. It was corrected to the measured 26.10.2.103 behavior, not
counted as successful capture evidence.

The owner authorized commit/push of this verified item before sequential work
on SG29-011 through SG29-013. The earlier commit/push covered work through
SG29-009. Publication and consumer adoption remain separate decisions.

## Validation record

- All modules build. The core suite passes 1,788 tests without failures, skips
  or assumption aborts. Seven new core methods cover AST kinds, literal/dynamic
  paths, index bases, escaped keys, serde, source positions, conservative native
  typing and cross-engine execution refusals.
- Other JVM modules pass 749 tests with zero failures and 72 existing optional
  assumption aborts. Two new native result methods execute PostgreSQL/DuckDB
  previews and distinguish their JSON types and missing/null results from the
  ClickHouse String contract. No new backend skip is added.
- The final shared IntelliJ gate passes all 827 tests on `261.25134.95`, using
  the existing genuine signed-window fixture. No exclusions or IDE API changes
  were introduced. Validation completed November 4, 2026.
- Python discovery passes 228 tests with one optional skip out of 229.
- The native ClickHouse 26.10.2.103 capture records 26 original/generated
  value/type pairs, four explicitly preview-only native controls and three
  strict outbound refusals. `final-facade-replay.json` reproduces all statements
  and dispositions through the Java 17 consumer facade and records class hashes.
- All 2,856 compiled core classes and 143 metadata classes retain Java 17
  bytecode, major version 61. No new public class or data-class shape is needed.
- Final source hashes are retained in `build/sg29-010/final-source-manifest.json`.
  No global-pin, generated registry/table, core fixture or IntelliJ production
  source changed. The existing exact native-audit baseline remains enforced.
