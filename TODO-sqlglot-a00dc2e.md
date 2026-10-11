# SQLGlot catch-up to a00dc2e

- **Target:** `a00dc2e5c489214bea53bacbbeba8713f21ebce1` (2026-10-09 upstream main;
  latest tagged release in this range is `v30.22.0`). This target is frozen; later
  upstream changes belong in a subsequent tracker.
- **Current branch oracle pin:** `v30.22.0-5-ga00dc2e5c`, commit
  `a00dc2e5c489214bea53bacbbeba8713f21ebce1`. Refreshed coherently in the
  integration batch; this worktree has not been published or merged to main.
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
  gates pass against the coherently refreshed target oracle pin (integration below).

## DuckDB

- [x] **DDB-01 — String escaping:** shared escape changes (`5fe5606b90`, #8484),
  tokenizer backslash handling (`ac940f016a`, #8470); escaped/newline/raw literals,
  parse/generate round-trips and native results. Preserve template/UTF-16 ranges.
  Shared numeric decoder handles byte-valued UTF-8, octal wrapping, Unicode scalar/
  surrogate pairs, unknown/truncated escapes and empty fixed escape values. Native
  DuckDB/PostgreSQL/ClickHouse profiles and control/backslash generation are wired
  through generated policies; published TokenizerConfig constructors remain intact.
  Live DuckDB rows and PostgreSQL 18.4 source/generated rows pass. DuckDB NUL text
  uses CHR(0), because its escape-string parser rejects NUL/byte-UTF8 inputs with
  `pg_verifymbstr NOT IMPLEMENTED`; those rejections are explicitly tested. LF/CRLF/
  CR, supplementary text, template ranges and generated source mapping are covered.
  Six exact PostgreSQL old-pin discrepancies (two source cases in three gates)
  were removed at coherent refresh; no live ClickHouse string execution is claimed.
- [x] **DDB-02 — Timestamp timezone/precision:** drop unsupported TIMESTAMP_LTZ
  precision (`9beb769dbf`, #8403); preserve TIMESTAMP_FROM_PARTS zones
  (`d2fbb215ff`, #8434). Check source-aware generation, types, NULLs and zone results.
- [x] **DDB-03 — Empty regex patterns:** do not prepend regex flags to an empty pattern
  (`cb5d8da1ba`, #8474). Execute affected native and translated regex forms.
  Prerequisite REGEXP_COUNT/REGEXP_INSTR lowerings are ported; canonical zero-result
  checks use the original pattern before flags. Canonical runtime NULL checks come first.
  Source-aware PostgreSQL/Trino count semantics and PostgreSQL/MySQL INSTR empty-
  pattern behavior are retained, including source NULL/error/overflow rules.
  PostgreSQL newline modes are translated explicitly. Whole-match occurrence and
  dynamic start/end options execute with BIGINT results. Unsupported flags/groups,
  PostgreSQL restarted-anchor uncertainty and nonempty zero-width positions are
  diagnosed/refused rather than silently claimed equivalent (extension #40).
  Actual DuckDB, PostgreSQL 18.4, MySQL 8.4.10 and Trino 483 checks pass. Native
  DuckDB MATCHES/REPLACE/EXTRACT_ALL behavior remains native. No Snowflake dialect
  is added; no live Spark or arbitrary cross-regex-engine equivalence is claimed.
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
- [x] **DDB-07 — List-comprehension qualification:** bind lambda variables locally
  (`507cbf99ca`, #8557), without capturing outer columns/aliases.
  Item/index declarations are identifiers; body/filter locals are separated before
  alias/column qualification. Iterators/free columns, nested shadowing, query boundaries,
  proven table-field precedence, old serialized declarations and scope caches are covered.
  Native binding-sensitive casing (including methods/nested lambdas) is retained.
  DuckDB whole-row aliases survive schema-less legacy inference without becoming
  invented alias.alias columns. Engine preparation/validation, types/nullability and
  source ranges remain conservative. Four reviewed declaration-class pin-drift rows
  were removed at refresh; all old inference signatures and policies remain intact (extension #41).
- [x] **DDB-08 — Arrays following subqueries:** retain every array element
  (`bea35b336c`, #8575; BigQuery follow-up `51864e76b4`). Test ARRAY/LIST_VALUE
  constructors and source-aware cross-dialect output.
  Shared DuckDB/BigQuery rendering only promotes a sole direct query to ARRAY(query).
  Scalar bracket/LIST_VALUE constructors retain explicit value-constructor intent,
  including NULL elements versus empty row arrays; nested functions/structs and later
  elements survive. Parser/serde/rendering and actual DuckDB results pass. Portable
  BigQuery outputs are executed in DuckDB; no live BigQuery result proof is claimed.
  Value-constructor metadata and false defaults were refreshed coherently with
  the target fixtures (extensions #42/#50).

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
  live Trino 483/MySQL 8.4.10 results pass. The exact Hive-to-Presto old-pin mismatch
  was removed on target fixture refresh.
- [x] **CORE-03 — Identifier escaping:** escape delimiter-escape characters
  (`d5d436bc32`, #8533), including backticks, double quotes and embedded backslashes.
- [x] **CORE-04 — Star/USING output order:** FROM source ordering (`ee7567c86d`,
  #8529), USING columns first (`aa64321744`, #8536), nested joins (`5b7b413451`,
  #8530). Pin output column order, types, duplicate-name behavior and outer joins.
  Scope references retain source walk order; all nested join wrapper levels expand
  before alias assignment, with rebuilt scopes. Dialect policies retain USING-list,
  left-table/MySQL-right-side and DuckDB in-place ordering. Qualified stars follow
  native raw/hidden/merged-key behavior; repeated stars keep all their fields.
  Logical merged expressions preserve chained outer-join values and DuckDB key types.
  Unknown/duplicate-source bare stars and comma groups keep native USING/NATURAL;
  StarRocks FULL USING stars retain native slot behavior rather than unsafe expansion.
  Native DuckDB/PostgreSQL/MySQL/Trino/StarRocks headers and rows pass (extension #43).
  Eight exact old-pin ordering rows and seven intentional native-first rows were
  individually reviewed; existing ledger reasons/signatures remain untouched.
- [x] **CORE-05 — Correlated UNNEST resolution:** resolve projection types in child
  scopes (`9f2f4cd5a2` / `6683ad28a4`, #8571), with TVF/PIVOT column-resolution
  changes (`30958d6152`, #8472). Test nested structs, stars, aliases, shadowing,
  correlations and no-schema conservatism.
  Annotated projection types precede physical schema tracing; UNKNOWN computations
  cannot borrow same-named underlying fields. Direct qualified pass-throughs retain
  memoized schema evidence. Outer resolvers are cached and preserve inference policy;
  permitted multi-level correlations stop at nearer UNKNOWN/unknown-source bindings.
  Selected/lateral aliases shadow inherited CTE declarations during type annotation.
  Nested struct fields, unaliased/element-qualified stars, alias lists, TVFs, Spark
  lateral fields and CTE PIVOT outputs have hand-owned regressions. Native DuckDB
  headers/types/rows and Trino 483 rows pass; portable BigQuery output executes in
  DuckDB (no live BigQuery proof). Set-operation UNNEST discovery remains conservative,
  matching the target's explicit deferral instead of guessing an arm's schema.
  Both parameterized and parameter-free paths and Engine contracts pass (extension #44).

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
- [x] **TRINO-01 — Temporal precision:** timestamp/time literal precision
  (`e1e0d2e52f`, #8538; all-temporal follow-up `862fd55467`, #8564).
  Zone promotion precedes temporal literal hooks; all literal precisions 0..12 and
  explicit cast parameters survive parse/serde/typing/rendering. Presto removes
  unsupported parameters and reports precision loss; Trino retains them. Native
  Trino 483 values/types, NULLs and precision-sensitive DATE_ADD pass. Twelve exact
  reviewed pin-drift rows were removed at coherent refresh; existing ledger reasons unchanged.
- [x] **CH-01 — Calendar functions:** ISO calendar directions (`0ceaf1319e`, #8494),
  toDayOfMonth/toDayOfYear (`61fc386dc7`, #8473), with week/year boundary tests.
  One-argument ISO calls parse canonically; ISO year generation and companion DuckDB
  EXTRACT(ISOYEAR) rendering are wired. Extra mode/timezone args remain Anonymous
  native calls. Existing month/year-day generator mappings are verified equivalent.
  chDB 26.5.1.1 and DuckDB boundary/leap/NULL results pass; no new ledger rows.
- [x] **CH-02 — Bitwise aggregates:** groupBitAnd/Or/Xor mapping
  (`025a2f46f9`, #8556); retain operand/aggregate kinds and typing.
  Canonical, PostgreSQL and DuckDB source aggregate nodes render to the real
  ClickHouse catalog names, including window use; source ASTs remain unchanged.
  No live ClickHouse execution is claimed by this mapping gate.
- [x] **MYSQL-01 — Parser/tokenizer:** native tokenizer rules/escapes (`db3931c8ba`,
  #8465; `15a77f1d89`, #8471), INSERT VALUE (`4b01d2ba95`, #8437), table options
  (`47b94d424a`, #8503), DROP KEY/CHECK/SIGNED (`51587ffdec`, #8515), option placement
  (`97a2fa2ed6`, #8553), ASCII/UNICODE (`921445341b`, #8561), WEIGHT_STRING AS
  (`af9915fa4c`, #8544), and CHARACTER SET cast lengths (`93aa16deb9`, #8559).
  Audit common ALTER parsing dependencies as a separate sub-batch; do not expand scope
  to unsupported dialects. Native IF/DATE_ADD/DATE_SUB round-trip changes
  (`1a782c55a8`, #8409; `1d0e3f3486`, #8417) also need reconciliation.
  Common ALTER prerequisite sub-batch verified: mixed operations (#8419), optional
  actions (#8539), options/actions in any order (#8565), bare MySQL DROP (#8432),
  keyword-column typing and partition option separation. Live MySQL/PostgreSQL
  schemas match original SQL; unsupported tails stay whole Commands. Six exact
  options-vs-actions pin-drift rows were removed at refresh. MySQL-specific items complete:
  comment boundary/newline policies preserve constructor ABI and derivative policies;
  native escapes include NUL/SUB and preserve pattern escapes. INSERT VALUE is scoped
  to INSERT sources. Charset aliases/ASCII/UNICODE, SIGNED, DROP KEY/CHECK, table/index
  options, WEIGHT_STRING AS, charset cast lengths, IF and DATE_ADD/SUB are verified.
  Native MySQL schemas/rows/HEX values pass; three additional exact pin-drift rows
  were removed at refresh. WeightString is now in the generated manifest; its temporary
  target-only contract is retired. Native Doris shared lexical/IF/date/property probes
  now pass on live Doris 4.1.4.1, with version, nullable-Boolean hotfix and VARIANT/
  TIMESTAMPTZ checks. See [native evidence](docs/doris-native-catchup-verification.md).
  No publication requested.
- [x] **PG-01 — JSON_VALUE:** improve PostgreSQL generation
  (`a00dc2e5c4`, #8579); path/scalar/default/error/null behavior and target grammar.
  PostgreSQL retains raw native literal/dynamic/CASE/concatenated JSONPATH expressions.
  RETURNING parses full types, including lengths/precision/zones (also corrected in
  MySQL/Trino). Native PostgreSQL 18.4 values/types, defaults, NULLs and explicit
  SQLSTATE errors pass. Native return evidence supplies declared/TEXT shape types
  without path type/nullability guesses. Two exact Trino old-pin type-parser rows
  were removed at refresh; source ASTs and existing published signatures remain intact.

## Integration and oracle refresh

- [x] Audit dependencies exposed by these ports (AST fields, tokenizer tables,
  typing metadata, alias/scope changes) at the frozen target; keep the scope boundary.
  Ensure generated tokenizer tooling emits numeric escape/byte/unknown-escape policy
  before retiring StringEscapeProfiles overlays; retain TokenizerConfig's old ABI.
  Regenerate Array.argTypes with value_constructor and expand sparse false defaults
  only with the corresponding target behavioral fixtures.
  Refresh Alter.actions optionality and remove its narrow parser validation overlay
  only with target AST metadata and corresponding options-only ALTER fixtures.
  Include WeightString in the generated manifest and remove its stale-detecting
  target-only contract; emit comment policies before retiring escape/comment overlays.
  Generator prerequisites are verified: numeric/byte/unknown/surrogate and comment
  policies are emitted through ABI-preserving config methods; new numeric/temporal
  typing helpers are explicit and tested (unknown upstream helpers still refuse).
  Qualification fixture generation handles target no-inference signatures and
  per-case schemas; old fixtures keep their old policy until coherent regeneration.
- [x] Regenerate AST/tokenizer/typing and behavioral fixtures in a controlled batch.
  Bump `FixturePinSyncTest.SQLGLOT_PIN` only with a coherent fixture refresh;
  leave the shipped pin unchanged while individual ports are under review.
- [x] Review actual ledger differences by assertion ID/signature, retain curated
  reasons, remove only fixed/stale actionable entries, and preserve intentional ones.
- [x] Run build, full Kotlin tests, offline release tests, Engine compatibility gates,
  and native DuckDB/Doris/Trino probes as applicable.
  Build, 1,283 Kotlin tests, 38 compatibility/editor methods, two generator tests,
  three candidate-gate safety tests and 16 release tests pass. Native DuckDB/Doris/
  PostgreSQL/MySQL/Trino and chDB calendar tests run; embedded PG fidelity uses 18.4.0.
  Four optional tests skip (Java chDB and three StarRocks tests proved in prior rounds).
  The subsequent native-refresh round below upgrades Doris execution to 4.1.4.1;
  core/metadata JAR hashes are unchanged, preserving the earlier engine/peer evidence.
- [x] Verify the peer compiler/runtime against an explicitly authorized private
  candidate before final integration; SQL-side tests do not replace Kotlin plugin tests.
  Exact core/metadata JARs from `dd1ea09` pass 163 peer tests, relocated CLI/IDE plugin
  assembly, the smoke build and ten exact IDE-compiler fixtures; peer source/main
  remain unchanged. See [candidate evidence](docs/engine-candidate-verification.md).
- [x] Commit/push the requested rounds on `sqlglot-catchup`. No publication or merge
  to `main` is authorized; neither was performed. Peer source remains untouched.

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
- Sixth batch: DDB-03 and its missing lowerings implemented. Build and **1,180
  Kotlin tests** pass with opt-in PostgreSQL/MySQL/Trino execution enabled (including
  the earlier string/division gates); three optional tests skip (chDB/two StarRocks
  gates). Nine offline release tests pass. Existing ledgers, generated fixtures and
  shipped pin are unchanged in this batch. Isolated source-engine containers ran
  without host ports/volumes and were removed after verification.
- 2026-10-11: seventh batch completes DDB-07/08. Build and **1,191 Kotlin tests**
  pass; ten optional source-engine/native gates skip (live containers are not
  configured; optional chDB remains absent). DuckDB JDBC result tests run normally. Nine
  offline release tests pass. Four exact temporary pin-drift rows were added for
  the two declaration AST cases in parser/annotation gates, checked against a00dc2e;
  pre-existing signatures/reasons and the shipped fixtures/pin remain unchanged.
  All eight requested DuckDB boxes are now verified within their stated bounds;
  shared correctness, other dialects and coherent target integration remain open.
- 2026-10-11: eighth batch completes CORE-04 within the conservative boundaries
  above. Build and **1,212 Kotlin tests** pass with all four cached source-engine
  containers enabled; only optional chDB skips. Nine release tests pass. The Engine
  compatibility gate now contains 24 methods. Full mismatch texts were reviewed:
  eight ordering pin-drift entries await refresh; six merged-expression and one
  unresolved-star lineage entries are intentional safeguards. No generated fixture
  stamps/pin or pre-existing ledger signatures/reasons changed. Isolated containers
  had no host ports/volumes and were removed after verification.
- 2026-10-11: ninth batch completes CORE-05 within the boundaries above. Build and
  **1,216 Kotlin tests** pass with Trino's opt-in gates enabled; eleven other optional
  native/source-engine tests skip. Nine release tests pass. The Engine compatibility
  gate now contains 26 methods. No ledger entries, reasons, signatures, generated
  fixtures or shipped pin changed. Trino 483 reused the cached pinned image in an
  isolated container without host ports/volumes, removed after verification.
  Final offline rerun: 1,212 pass, fifteen optional gates skip; non-projected
  SEMI/ANTI join predicates retain their real source types under the visibility fix.
- Tenth batch (TRINO-01): build, 1,237 Kotlin tests and 16 offline release tests
  pass with Trino gates enabled (eleven optional skips). Shipped pin unchanged.
- Eleventh batch (CH-01): build, 1,240 Kotlin tests and 16 offline release tests
  pass with calendar chDB and Trino gates enabled (eleven other optional skips).
- MySQL prerequisite sub-batch: build, 1,251 Kotlin tests and 16 offline release
  tests pass with MySQL/PostgreSQL/Trino/calendar probes enabled (four optional skips).
- MySQL-specific batch: build, 1,258 Kotlin tests and 16 offline release tests pass
  with MySQL/PostgreSQL/Trino/calendar gates enabled (four optional skips). Shared
  editor ranges and constructor contracts pass. Three exact additional old-pin rows
  are reviewed; no pre-existing reasons/signatures or shipped fixture stamps changed.
- PG-01 batch: build, 1,263 Kotlin tests, 37 compatibility/editor contract methods
  and 16 offline release tests pass, with native PostgreSQL/MySQL/Trino/calendar
  gates enabled (four optional skips). All requested dialect boxes are implemented;
  generated dependency audit, coherent fixtures and downstream verification remain.
- Integration prerequisite batch: build, 1,266 Kotlin tests, two generator tests
  and 16 offline release tests pass. Native DuckDB typing corroborates new numeric,
  timezone and DATE_TRUNC helpers. The shipped pin and generated fixtures are still
  unchanged; coherent generation and exact ledger reconciliation are next.
- Coherent target integration: 104 oracle fixtures and all generated AST/tokenizer/
  typing/function tables now use `v30.22.0-5-ga00dc2e5c`. ABI-preserving sidecar
  policies are generated; overlays, sparse Array false defaults and temporary
  WeightString/Alter validation contracts are retired. Newly surfaced dependencies
  are ported with hand-owned/native regressions (extension #50). Forty-two temporary
  pin-drift rows are removed. Six existing merged-key reasons/signatures remain
  unchanged with new descriptor IDs; twelve qualification and seventeen PostgreSQL
  type rows are intentional native safeguards, plus optional-schema lineage.
  Six obsolete PG-14 grammar rejects are removed after real PG-18 verification.
  Existing corpus deferrals remain explicit inventories, not parity claims. No
  failures are approved by counts. Native Doris and authorized downstream candidate
  verification followed in the final round below; no release has been published.
- Final validation round (2026-10-11): native Doris shared escapes/comments, IF/CASE,
  DATE_ADD/SUB, generic properties and normalized MySQL INSERT VALUE rows pass on
  `doris-4.1.3-rc02-7126cf65d96`; this is not a 4.1.4.1 execution claim. All 1,283
  Kotlin tests pass with the configured native gates (four optional skips); build,
  38 compatibility/editor methods, two generator, three candidate safety and sixteen
  offline release tests pass. Private candidate validation against unchanged peer
  commit `3cc5b5a` passes 40 runtime, 100 compiler, nine schema-input and fourteen
  tooling tests, CLI assembly, smoke build and ten IDE compiler `2.4.20-ij262-34`
  fixtures. Both relocated plugins contain the frozen target pin. Candidate hashes
  and retained scratch logs are recorded in the linked evidence document. Branch
  CI for `dd1ea09` passed; no publication, main merge or peer-source edit occurred.
  The four isolated task containers were removed after verification; candidate
  scratch inputs, artifacts and logs remain retained. Pausing after this round.
- Doris current-release follow-up (2026-10-11): 4.1.3 was a convenient cached image,
  not a claim that 4.1.4 lacked significant changes. Official pinned FE/BE images
  now execute `doris-4.1.4.1-rc02-5dbec55b399`. All 81 Doris-selected methods pass,
  including six live catch-up methods and the 100,000-row nullable JSON Boolean
  OR/CASE crash regression with short-circuit execution both enabled and disabled.
  All 92 historical behavior vectors reproduce exactly (88 values/four expected
  errors); unrerun historical datasets keep their original engine provenance.
  The six-commit hotfix comparison changes no grammar, so the existing exact 4.1.4
  parser remains current. Core/metadata candidate bytes and peer source are unchanged.
  CI for `e036d1d` passed. Remaining coverage/risk limits are documented explicitly,
  not concealed by passing test counts.
  Build and the 38 contracts pass; the full sweep passes 1,267 methods with 24
  optional skips (only Doris/Python chDB are enabled this time). Earlier broader
  native and 163-test peer evidence applies to unchanged core/metadata bytes.
  Sixteen release, two generator and three candidate safety tests pass.
  The two isolated Doris containers and internal network were removed; logs and
  version/matrix evidence are retained. No publication, main or peer-source changes.
