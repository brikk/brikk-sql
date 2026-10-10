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

- [ ] **DDB-01 — String escaping:** shared escape changes (`5fe5606b90`, #8484),
  tokenizer backslash handling (`ac940f016a`, #8470); escaped/newline/raw literals,
  parse/generate round-trips and native results. Preserve template/UTF-16 ranges.
- [x] **DDB-02 — Timestamp timezone/precision:** drop unsupported TIMESTAMP_LTZ
  precision (`9beb769dbf`, #8403); preserve TIMESTAMP_FROM_PARTS zones
  (`d2fbb215ff`, #8434). Check source-aware generation, types, NULLs and zone results.
- [ ] **DDB-03 — Empty regex patterns:** do not prepend regex flags to an empty pattern
  (`cb5d8da1ba`, #8474). Execute affected native and translated regex forms.
  Audit: upstream changes REGEXP_COUNT/REGEXP_INSTR lowerings not yet present in
  our DuckDB generator; port/review that prerequisite rather than claim the flag
  ordering fix applies to our existing REGEXP_MATCHES/REGEXP_REPLACE paths.
- [ ] **DDB-04 — Spark array slicing:** preserve start/length semantics
  (`818483464b`, #8524), including negative/zero/boundary/NULL cases.
  Audit: our older Spark builder stores `length`, while the target uses `end` plus
  `is_length`. Reconcile the AST/parser/generator contract and source round-trip
  first; do not drop the length by adding only the final generator calculation.
- [ ] **DDB-05 — Spark CONCAT_WS arrays:** use ARRAY_TO_STRING semantics
  (`3096a591d6`, #8523); compare scalar/array mixtures and NULL elements/separators.
- [x] **DDB-06 — SAMPLE ordering:** emit USING SAMPLE before ORDER BY/LIMIT
  (`022bb41fc5`, #8550). Verify native grammar and PIPE TABLESAMPLE contexts.
- [ ] **DDB-07 — List-comprehension qualification:** bind lambda variables locally
  (`507cbf99ca`, #8557), without capturing outer columns/aliases.
- [ ] **DDB-08 — Arrays following subqueries:** retain every array element
  (`bea35b336c`, #8575; BigQuery follow-up `51864e76b4`). Test ARRAY/LIST_VALUE
  constructors and source-aware cross-dialect output.

## Shared correctness

- [ ] **CORE-01 — Set-operation pagination scope:** preserve per-arm versus outer
  LIMIT/OFFSET (`eeece5a564`, #8385), parentheses, nesting, CTEs and row results.
- [ ] **CORE-02 — Integer division:** truncate toward zero instead of relying on
  rounding casts (`f5dd70bff7`, #8532; `6aeb6a0ab7` fixes). Check positive/negative
  inputs, division by zero, NULLs, target capabilities and result types.
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

- [ ] **SR-01 — Window NULL modifiers:** preserve IGNORE/RESPECT NULLS
  (`284ea43fe7`, #8412); parser/generator/typing round-trips and native grammar.
- [ ] **SR-02 — TIME_SLICE:** dedicated TimeSlice AST parsing
  (`8abe7f8382`, #8555; `6b5191d9e3` fixes). Preserve interval/unit/boundary semantics.
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
