# SQLGlot catch-up to a00dc2e

- **Target:** `a00dc2e5c489214bea53bacbbeba8713f21ebce1` (2026-10-09 upstream main;
  latest tagged release in this range is `v30.22.0`). This target is frozen; later
  upstream changes belong in a subsequent tracker.
- **Current shipped oracle pin:** `v30.18.0-43-g3ca82489`, commit
  `3ca824895ef423f7895fb13d72357548c0f1f367`.
- **Work:** branch/worktree `sqlglot-catchup`; development version `0.19.0-SNAPSHOT`.
- **Scope:** our supported dialects and semantic-analysis surface. Query-plan
  optimizer passes, executor changes and unsupported dialects are not new features
  to add merely because upstream changed them.

Every checkbox means the implementation and focused regressions are verified, not
just that a diff was read. Already-equivalent behavior needs evidence before being
marked complete. Keep [intentional divergences](docs/brikk-extensions.md), including
DuckDB DDB-001. Never regenerate away consumer contracts or approve failures by count.

## Inference API and Engine compatibility — first batch

- [x] Add hand-owned [Engine analysis contract tests](docs/engine-analysis-contract.md)
  for partial/empty catalogs, generic input refinement, two-step strict validation,
  alias/scope/slot rules, type/nullability/quoting and published JVM bridges.
- [x] **INF-01:** reconcile upstream `infer_schema` removal (`b8333a81f0`, #8552).
  Preserve parameterized `qualify` / `qualifyColumns` and nullable legacy policy;
  add upstream-shaped overloads without the parameter using no-inference behavior.
  Avoid ambiguous Kotlin defaults; preserve old JVM descriptors and default masks.
  Explicitly route shape discovery, lineage and star-expansion/preparation through
  compatible legacy policy. Test enabled, disabled, nullable and new-signature paths.
- [x] **INF-02:** reconcile the associated alias-expansion ordering and lineage-specific
  single-source fallback. Upstream's required schema argument is not permission to
  break schema-less Brikk lineage/shape APIs. Verify Engine-shaped cases and exact
  corpus gates separately.

  Implemented: disabled/new-signature qualification uses upstream alias order;
  true/null preserves legacy ordering and the optional-schema lineage facade. Old
  JVM signatures/default bridges and Resolver construction remain compatible.
  Shape/preparation/lineage wrappers explicitly choose null. All existing corpus
  gates pass against the unchanged shipped oracle pin; target-fixture refresh remains below.

## DuckDB

- [x] **DDB-01 — String escaping:** shared escape changes (`5fe5606b90`, #8484),
  tokenizer backslash handling (`ac940f016a`, #8470); escaped/newline/raw literals,
  parse/generate round-trips and native results. Preserve template/UTF-16 ranges.
  Shared numeric decoder handles byte-valued UTF-8, octal wrapping, Unicode scalar/
  surrogate pairs, unknown/truncated escapes and empty fixed escape values. Native
  DuckDB/PostgreSQL/ClickHouse profiles and control/backslash generation are wired
  through hand-owned overlays; published TokenizerConfig constructors remain intact.
  Live DuckDB rows and PostgreSQL 18.4 source/generated rows pass. DuckDB NUL text
  uses CHR(0), because its escape-string parser rejects NUL/byte-UTF8 inputs with
  `pg_verifymbstr NOT IMPLEMENTED`; those rejections are explicitly tested. LF/CRLF/
  CR, supplementary text, template ranges and generated source mapping are covered.
  Six exact PostgreSQL old-pin discrepancies (two source cases in three gates)
  are marked temporary `oracle-pin-drift`; no live ClickHouse execution is claimed.
- [x] **DDB-02 — Timestamp timezone/precision:** drop unsupported TIMESTAMP_LTZ
  precision (`9beb769dbf`, #8403); preserve TIMESTAMP_FROM_PARTS zones
  (`d2fbb215ff`, #8434). Check source-aware generation, types, NULLs and zone results.
- [ ] **DDB-03 — Empty regex patterns:** do not prepend regex flags to an empty pattern
  (`cb5d8da1ba`, #8474). Execute affected native and translated regex forms.
  Audit: upstream changes REGEXP_COUNT/REGEXP_INSTR lowerings not yet present in
  our DuckDB generator; port/review that prerequisite rather than claim the flag
  ordering fix applies to our existing REGEXP_MATCHES/REGEXP_REPLACE paths.
- [x] **DDB-04 — Spark array slicing:** preserve start/length semantics
  (`818483464b`, #8524), including negative/zero/boundary/NULL cases.
  Parser now uses `end` plus `is_length`; native Spark round-trips, serde and legacy
  `length` AST loading remain supported. DuckDB result checks cover negative starts,
  zero lengths, empty/NULL inputs, dynamic bounds and wide inclusive ends. Runtime
  NULL/error guards preserve invalid non-NULL Spark counts (extension #35).
- [x] **DDB-05 — Spark CONCAT_WS arrays:** use ARRAY_TO_STRING semantics
  (`3096a591d6`, #8523); compare scalar/array mixtures and NULL elements/separators.
  Shared Hive/Spark flatten flag and companion Trino route (#8391) are ported.
  Native DuckDB calls remain native. DuckDB result tests cover scalars, typed array
  columns, empty/NULL arrays and separators. Unknown mixed argument types and
  row-dependent DuckDB delimiters are diagnosed; Trino grammar is verified.
- [x] **DDB-06 — SAMPLE ordering:** emit USING SAMPLE before ORDER BY/LIMIT
  (`022bb41fc5`, #8550). Verify native grammar and PIPE TABLESAMPLE contexts.
- [ ] **DDB-07 — List-comprehension qualification:** bind lambda variables locally
  (`507cbf99ca`, #8557), without capturing outer columns/aliases.
- [ ] **DDB-08 — Arrays following subqueries:** retain every array element
  (`bea35b336c`, #8575; BigQuery follow-up `51864e76b4`). Test ARRAY/LIST_VALUE
  constructors and source-aware cross-dialect output.

## Shared correctness

- [x] **CORE-01 — Set-operation pagination scope:** preserve per-arm versus outer
  LIMIT/OFFSET (`eeece5a564`, #8385), parentheses, nesting, CTEs and row results.
  Branch/nested-set modifiers remain scoped; targets needing an outer SELECT
  (ClickHouse) retain pagination and CTE visibility there. The shared TOP parser
  hook distinguishes a final branch TOP from trailing set LIMIT/FETCH without
  adding a T-SQL dialect. Related full-join rewrite modifier placement is corrected
  instead of hiding a new failure in the MySQL parity ledger (extension #37).
- [x] **CORE-02 — Integer division:** truncate toward zero instead of relying on
  rounding casts (`f5dd70bff7`, #8532; `6aeb6a0ab7` fixes). Check positive/negative
  inputs, division by zero, NULLs, target capabilities and result types.
  Fallbacks use BIGINT and explicit truncation for rounding-cast targets. Proven
  integer source types participate in lowering without recursive typed division;
  safe-divisor guards keep that type evidence. MySQL/Doris/StarRocks native DIV
  precedence and MySQL TRUNCATE's required scale are corrected. DuckDB rows and
  live Trino 483/MySQL 8.4.10 results pass. One exact Hive-to-Presto old-pin mismatch
  is recorded as temporary `oracle-pin-drift`, to remove on target fixture refresh.
- [x] **CORE-03 — Identifier escaping:** escape delimiter-escape characters
  (`d5d436bc32`, #8533), including backticks, double quotes and embedded backslashes.
- [ ] **CORE-04 — Star/USING output order:** FROM source ordering (`ee7567c86d`,
  #8529), USING columns first (`aa64321744`, #8536), nested joins (`5b7b413451`,
  #8530). Pin output column order, types, duplicate-name behavior and outer joins.
- [ ] **CORE-05 — Correlated UNNEST resolution:** resolve projection types in child
  scopes (`9f2f4cd5a2` / `6683ad28a4`, #8571), with TVF/PIVOT column-resolution
  changes (`30958d6152`, #8472). Test nested structs, stars, aliases, shadowing,
  correlations and no-schema conservatism.

## Other supported dialects

- [x] **SR-01 — Window NULL modifiers:** preserve IGNORE/RESPECT NULLS
  (`284ea43fe7`, #8412); parser/generator/typing round-trips and native grammar.
  FIRST_VALUE/LAST_VALUE/LAG/LEAD keep IGNORE NULLS after their first argument;
  offsets/defaults are retained. RESPECT NULLS uses the native default. Actual
  StarRocks 4.1.4 execution proves the window row results.
- [x] **SR-02 — TIME_SLICE:** dedicated TimeSlice AST parsing
  (`8abe7f8382`, #8555; `6b5191d9e3` fixes). Preserve interval/unit/boundary semantics.
  Source-aware DuckDB translation explicitly preserves StarRocks' 0001 origin
  (extension #36); default DuckDB origins differ for three-day/four-year periods.
  Live StarRocks and emitted DuckDB results agree for FLOOR/CEIL, exact boundaries,
  minute/hour/day/month/year periods and NULL inputs. Native parser/serde round-trips
  are covered; unknown boundary names refuse instead of silently becoming FLOOR.
- [ ] **TRINO-01 — Temporal precision:** timestamp/time literal precision
  (`e1e0d2e52f`, #8538; all-temporal follow-up `862fd55467`, #8564).
- [ ] **CH-01 — Calendar functions:** ISO calendar directions (`0ceaf1319e`, #8494),
  toDayOfMonth/toDayOfYear (`61fc386dc7`, #8473), with week/year boundary tests.
- [x] **CH-02 — Bitwise aggregates:** groupBitAnd/Or/Xor mapping
  (`025a2f46f9`, #8556); retain operand/aggregate kinds and typing.
  Canonical, PostgreSQL and DuckDB source aggregate nodes render to the real
  ClickHouse catalog names, including window use; source ASTs remain unchanged.
  No live ClickHouse execution is claimed by this mapping gate.
- [ ] **MYSQL-01 — Parser/tokenizer:** native tokenizer rules/escapes (`db3931c8ba`,
  #8465; `15a77f1d89`, #8471), INSERT VALUE (`4b01d2ba95`, #8437), table options
  (`47b94d424a`, #8503), DROP KEY/CHECK/SIGNED (`51587ffdec`, #8515), option placement
  (`97a2fa2ed6`, #8553), ASCII/UNICODE (`921445341b`, #8561), WEIGHT_STRING AS
  (`af9915fa4c`, #8544), and CHARACTER SET cast lengths (`93aa16deb9`, #8559).
  Audit common ALTER parsing dependencies as a separate sub-batch; do not expand scope
  to unsupported dialects. Native IF/DATE_ADD/DATE_SUB round-trip changes
  (`1a782c55a8`, #8409; `1d0e3f3486`, #8417) also need reconciliation.
- [ ] **PG-01 — JSON_VALUE:** improve PostgreSQL generation
  (`a00dc2e5c4`, #8579); path/scalar/default/error/null behavior and target grammar.

## Integration and oracle refresh

- [ ] Audit dependencies exposed by these ports (AST fields, tokenizer tables,
  typing metadata, alias/scope changes) at the frozen target; keep the scope boundary.
  Ensure generated tokenizer tooling emits numeric escape/byte/unknown-escape policy
  before retiring StringEscapeProfiles overlays; retain TokenizerConfig's old ABI.
- [ ] Regenerate AST/tokenizer/typing and behavioral fixtures in a controlled batch.
  Bump `FixturePinSyncTest.SQLGLOT_PIN` only with a coherent fixture refresh;
  leave the shipped pin unchanged while individual ports are under review.
- [ ] Review actual ledger differences by assertion ID/signature, retain curated
  reasons, remove only fixed/stale actionable entries, and preserve intentional ones.
- [ ] Run build, full Kotlin tests, offline release tests, Engine compatibility gates,
  and native DuckDB/Doris/Trino probes as applicable.
- [ ] Verify the peer compiler/runtime against an explicitly authorized candidate
  release before final integration; SQL-side tests do not replace Kotlin plugin tests.
- [ ] Commit/publish only when requested. Do not alter `main` or peer source during
  this worktree task.

## Progress log

- 2026-10-10: baseline Engine gate added (18 methods, 1,108 total Kotlin tests passing,
  one optional native skip; nine release tests passing). Both deliberate inference
  regressions are detected and were reverted. Starting INF-01.
- 2026-10-10: inference split and ABI-compatible constructor/overloads implemented;
  DuckDB zoned timestamp parts/LTZ precision and SAMPLE ordering ported with real
  DuckDB result checks; identifier escape characters ported with six-dialect name
  round-trips; ClickHouse bitwise aggregate name mapping verified. Other boxes remain
  open. The reference checkout is at the frozen target, but shipped fixture stamps/pin
  remain unchanged.
- First-batch verification: build and **1,119 Kotlin tests** pass, one optional
  native integration test skipped; nine offline release tests pass. Existing
  known-failure ledgers are unchanged. Remaining requested work is not complete.
- First batch committed/pushed as `72c476b`; branch CI passed.
- Second batch: DDB-04/05 and SR-01/02 implemented, including a pinned opt-in live
  StarRocks gate. The generated arg-type/tokenizer manifests are deliberately not
  hand-edited; their coherent target refresh remains part of integration below.
- Second-batch verification: build and **1,135 Kotlin tests** pass with the live
  StarRocks gates enabled; the optional chDB integration test remains skipped.
  Nine offline release tests pass; known-failure ledgers remain unchanged. Native
  StarRocks 4.1.4 image `faf7ce9c24d9...` was run in an isolated container with no
  host ports or volumes. The container was removed after verification.
- Second batch committed/pushed as `1834230`.
- Third batch: CORE-01 implemented and tested. Full suite: **1,140 Kotlin tests
  pass**, with three optional native tests skipped (chDB and the two already-proved
  live StarRocks gates); nine release tests pass. No ledger entries were added or
  signatures changed. Remaining requested items and target-fixture refresh are open.
- Fourth batch: CORE-02 ported with source typing and native precedence regressions.
  Build and **1,153 Kotlin tests** pass with opt-in Trino/MySQL execution enabled;
  three optional native tests skip (chDB and the two StarRocks gates). Nine offline
  release tests pass. Only one ledger row was added: the reviewed exact old-pin
  Hive-to-Presto rounding-cast discrepancy, independently pinned to a00dc2e and
  proved in Trino 483. No existing assertion signatures/reasons were changed.
  Shipped fixtures/pin remain unchanged. Isolated Trino/MySQL containers were
  created without host ports/volumes and removed after verification.
- Fifth batch: DDB-01 implemented and verified. Build and **1,167 Kotlin tests**
  pass with opt-in PostgreSQL execution enabled; five optional native tests skip
  (chDB, two StarRocks gates, and the two previously-proved Trino/MySQL gates).
  Nine offline release tests pass. Six exact old-pin rows were added for the two
  PostgreSQL literals in parser, annotated AST and old-dump generator gates. Each
  was checked against frozen a00dc2e; invalid original UTF-8 is not claimed native-
  equivalent. No existing ledger signatures/reasons or generated fixtures changed.
  PostgreSQL 18.4 image `4efc78b0491e...` ran without host ports/volumes; its isolated
  container was removed after verification. Shipped pin remains unchanged.
