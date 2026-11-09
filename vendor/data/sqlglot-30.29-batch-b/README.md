# SQLGlot 30.29 batch B evidence

SG29-005 through SG29-007 implement precedence/grouping fixes on top of the
uncommitted Batch A work. The global oracle remains
`v30.22.0-5-ga00dc2e5c`; the reviewed upstream target is SQLGlot `v30.29.0`,
`2fe25297af5b0f44f5c9e5d03e1b296424b7acbe`.

## What changed

- IS predicates have explicit per-profile binding policy. Comparison/range
  operands get parentheses where the destination might otherwise bind them
  differently. PostgreSQL, DuckDB, ClickHouse, Spark and the separate
  DataFusion/Spice profiles use low IS precedence. Hive and the MySQL family
  retain their distinct RHS policies. Generic/BigQuery/Presto/Trino keep the
  upstream generic HIGH policy; this does not certify invalid unparenthesized
  comparison/IS chains as native SQL.
- The old public parser entry points and default bridges remain available.
  Deferred IS parsing calls the existing virtual `parseRange(Expression)` hook;
  its one-shot state is consumed before nested operands and restored on errors.
  Tests exercise a pre-existing-style subclass override and parser reuse.
- Chained IS rendering retains each node's negation, rather than flattening the
  chain with the outer node's operator. DataFusion/Spice negate the outer AST
  predicate rather than replace the first `IS` in a rendered string. IS Boolean
  fallbacks in targets without native support refuse executable generation when
  a non-null Boolean operand is not proved. Preview compatibility is separate.
- INTERSECT binds above UNION/EXCEPT except in the unversioned Hive profile.
  Scope traversal and left-grouped rendering are iterative. Explicit grouping,
  nested modifiers, CTE ownership and duplicate outputs stay intact. VALUES
  pagination uses the existing portable SELECT wrapper, preserving its own arm's
  type information without inventing a NOT NULL guarantee. Its nullability
  remains unknown where the existing sidecar has no rule.
- ClickHouse's last-arm pagination stays on that arm; only global SETTINGS and
  FORMAT move to the outer set. Native ClickHouse controls check this placement.
- MySQL logical AND binds above XOR, which binds above OR. Its bitwise XOR binds
  above multiplication; shifts, AND and OR have separate tiers. StarRocks agrees
  on tight `!`, but Doris does not: native Doris `!1 + 1` parses as `!(1 + 1)`.
  The policies are therefore not inherited indiscriminately.
- Cross-target bitwise/arithmetic operands keep their AST grouping. Nested XOR
  uses each target's renderer, and PostgreSQL/Presto/DuckDB Boolean lowerings
  preserve compound operands under NOT. The nullable truth-table controls use
  Boolean `<>` as an independent reference, not DuckDB's integer-only XOR().
- StarRocks 4.1.4 has no infix logical XOR rule. Doris 4.1.5 and StarRocks 4.1.4
  reject symbolic `<<`/`>>`. Executable generation refuses those unqualified
  translations; previews retain findings. No new functional shift translation
  or width/signedness equivalence is claimed.

## Pinned references and limits

`source-provenance.json` records SHA-256 hashes for immutable Doris 4.1.5 and
StarRocks 4.1.4 grammars. It also checks the registry checksum/source files of
sqlparser 0.62.0 against the existing DataFusion/Spice reference lockfiles. Its
Generic and PostgreSQL binding powers independently justify low IS precedence.
That is source-backed parser evidence, not a DataFusion/Spice runtime execution.

`cases.json` contains only finite, reviewed SELECT controls. The capture bridge,
`tools/SqlglotPrecedenceProbe.java`, compiles against the published 0.23.0 facade
and runs against the new classes. It uses the public `SqlFragment.toExecutable`
API, not an internal generator-state setter. The capture tool verifies private
container names, network isolation, absence of host bind mounts and published
ports before querying anything.

`native-results.json` records source queries, ASTs, previews, actual executable
SQL or explicit refusal, exact image IDs/references, native versions and results.
MySQL/StarRocks results are backend-executed rows. The MySQL session mode is
captured; HIGH_NOT_PRECEDENCE and other non-default mode emulation are not added.

Doris uses the real 4.1.5 FE parser and rewrite pass with
`enable_fold_constant_by_be=false`. Only a literal actually produced by that pass
is a constant-value control. Unfolded expressions and set plans remain parser/
plan-only observations, never a paired row-result success. Backend startup was
blocked by the host `vm.max_map_count` requirement. That guard was not bypassed,
and no host kernel setting or user database was changed.

`clickhouse-results.json` separately checks finite native 26.10.2.103 results for
IS, INTERSECT, final-arm limits and global SETTINGS/FORMAT. It does not qualify
another ClickHouse version or an experimental profile.

No two SQL errors count as equivalent results. StarRocks infix XOR failures are
explicit syntax-rejection controls. Unqualified target operations must be refused
by the public executable API rather than sent to an engine as invalid SQL.
Finite value matches do not certify general coercion, widths, metadata, volatile
operand evaluation or arbitrary cross-dialect equivalence.

## Regression gates

- Core hand-owned tests: `IsPrecedenceCatchupTest`, `SetPrecedenceCatchupTest`,
  `MysqlPrecedenceCatchupTest` and `PrecedenceAbiTest`.
- Real private DuckDB 2.0.0/d54cbbe3df controls:
  `SqlglotPrecedenceResultTest` in `brikk-sql-verify-duckdb`.
- Real embedded PostgreSQL 18.4 controls: `PrecedencePostgresResultTest` in
  `brikk-sql-oracle`. These test Boolean result types, generated columns,
  nullable XOR inputs, set rows, scoped modifiers and typed VALUES.
- Capture classification tests: `tools/test_probe_sqlglot_precedence.py` checks
  that a frontend plan cannot be mislabeled as backend row execution and that
  ordered results are not compared as unordered bags.

DuckDB JDBC 2.0.0.0 cannot bind named engine parameter identifiers through its
positional setters. The binding regression preserves that limitation explicitly.
It checks the actual emitted names, verified parameter ranges and first-occurrence
order, then uses a test-only span-based adaptation to numeric IDs for executable
binding controls. Repeated names keep one identity. No production binder or
executor is introduced.

## Frozen-pin disposition

Exactly one new scope ledger row explains the old pin's incorrect left-associated
tree for `SELECT x FROM t UNION SELECT y FROM u INTERSECT SELECT z FROM v`.
The corrected tree is `Union(t, Intersect(u,v))`, and real PostgreSQL/DuckDB
controls use temporary t/u/v tables with rows 1/2/2 to distinguish result rows
1,2 from the prior grouping's single row 2. The exact assertion ID/signature is
retained in `scope-corpus/known-failures.json`, with SG29-006 provenance.

No other existing ledger reason/signature is changed. Generated tables, AST
manifests, corpora and the global pin are not refreshed. The old hand-owned
factorial control's MySQL `!TRUE` spelling now includes the parentheses required
by the approved MySQL NOT-precedence fix; its DuckDB/native refusal checks remain.

The broad DuckDB native-syntax inventory is a separate audit, not a parity
ledger. Its fingerprint includes each origin's generated SQL as well as native
status/type. Before admitting a revised exact baseline, replay the unchanged
coverage harness against the published 0.23.0 core/metadata artifacts and require
that it reproduces the historical native/local fingerprints. Compare every
origin, generated SQL, status and inventory file hash against the new report.
Retain the old baseline and reviewed delta; do not turn the comparison into a
count allowance, skip inputs or approve two native errors as equivalence.

The completed replay reproduces both historical fingerprints under identical
module working directory, native engine, JDBC hash and complete settings.
`native-coverage-delta.json` retains the exact 11 changed origins in four SQL
forms. Five nested-XOR emissions change from rejected to accepted; six inputs
remain accepted with corrected or explicit grouping. The other 24,595 origins
and every local disposition are unchanged. Replacing only the scope ledger's
file hash with its HEAD hash reconstructs the whole historical inventory hash.

The exact summary is enforced by
`brikk-sql-verify-duckdb/testResources/native-coverage-baseline.json`, which always holds the
current baseline; earlier baselines are in git history. `exactCoverageDeltaFormsKeepTheirFrozenAstMeaning`
executes the exact frozen dumps, including the compound XOR's nullable
comparison combinations, generic bitwise tree and Spark BIT_GET value. These
are additional result controls, not a conversion of syntax acceptance into a
parity or semantic-equivalence claim.

## Reproduction

Run the ordinary build and core suite before the native suites. Reuse the
genuine signed-window fixture from Batch A for the shared IDE gate.

```sh
./kotlin build
./kotlin test -m brikk-sql --jvm-args='-Xmx1g -XX:ActiveProcessorCount=2'
./kotlin test -m brikk-sql-verify-duckdb -m brikk-sql-oracle --include-classes='*SqlglotPrecedenceResultTest' --include-classes='*PrecedencePostgresResultTest'
```

To repeat the Docker captures, provision only the dedicated network-isolated
test containers named by `tools/probe_sqlglot_precedence.py`, using the immutable
references in `image-provenance.json`. The tool refuses host bind mounts or
published ports. Build `tools/SqlglotPrecedenceProbe.java` with Java 17 against
the published 0.23.0 core JAR. Its runtime classpath must contain the compiled
probe, new core/metadata classes, Kotlin standard library and serialization
runtime dependencies. Then run:

```sh
python3 tools/probe_sqlglot_precedence.py --java /path/to/jdk17/bin/java --classpath "$CP" --output build/precedence-native-review.json
python3 tools/probe_sqlglot_precedence.py --java /path/to/jdk17/bin/java --classpath "$CP" --clickhouse-only --output build/precedence-clickhouse-review.json
```

The capture tool starts/stops no services. This batch's containers were stopped
and removed after capture. No test container, host port, kernel-setting change,
user connection or trust-store change remains from the probes.

The code remains uncommitted in `sqlglot-30-29-batch-a`. Later batches,
commits/pushes, publication and downstream adoption require further direction.

## Validation record

- All modules build. The final core suite passes 1,767 tests with zero failures,
  skips or assumption aborts. Twenty new core regression methods cover the three
  approved SG29 items; Batch A's tests and the Engine/default-bridge contracts
  remain in the same full run.
- The reviewed remaining JVM run passes 741 tests with zero failures and 72
  optional-engine/fixture assumption aborts. Nine new native regression methods
  cover private DuckDB 2.0.0 and embedded PostgreSQL 18.4, including the exact
  reviewed coverage-delta forms. The run also exercises the existing genuine
  signed-window fixture.
- Python discovery passes 228 tests with one opt-in skip out of 229, using the
  unchanged a00dc2e5c reference. Source/classification tests do not pretend to
  execute an unavailable native engine.
- The final shared IntelliJ gate passes all 827 tests on `261.25134.95`, using
  the genuine signed-window capture. No test exclusions, new IntelliJ internal
  calls or increased experimental API usage were introduced. Other IDE versions
  and downstream plugins were not qualified here.
- The Docker capture records 76 source controls, five explicit StarRocks syntax
  rejections and 213 target dispositions: 161 finite value matches, 12 explicit
  unqualified refusals and 40 parser/plan controls without paired row proof.
  Doris FE constant folds are distinguished from actual MySQL/StarRocks backend
  execution. Four further controls execute on ClickHouse 26.10.2.103.
- The Java bridge compiles against the published 0.23.0 facade and successfully
  exercises the new classes on Temurin 17.0.21+9. All 2,856 compiled core classes
  and 143 metadata classes retain Java 17 bytecode, major version 61.
- Final source hashes are retained in
  `build/sqlglot-batch-b/source-manifest.json`. The original Batch A file hashes
  and diff are retained separately in the same directory. No IntelliJ production
  source or external API call site changes in this batch.

Logs under `/var/tmp/jayson/opencode/` use the prefix `sqlglot-batch-b-`, including
`red.log`, `focused*.log`, `core-*.log`, `native-jvm.log`, `native-cli*.log`,
`legacy-coverage-replay*.log`, `build.log`, `python.log`, `other-jvm*.log` and
`ide.log`. Failed iterations are retained: an initially invalid Boolean XOR()
reference in DuckDB, the known named-binding driver limitation, backend/FE
fixture startup constraints and the old inventory fingerprint are not relabeled
as successful runs. The same-working-directory legacy replay establishes the
historical settings comparison; the earlier root-directory replay remains as a
separate retained attempt.
