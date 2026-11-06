# SQLGlot catch-up to 30.29.0

Status: Batches A and B approved, implemented and verified in worktree `sqlglot-30-29-batch-a`,
2026-11-03. SG29-008 and SG29-009 are separately approved, implemented and verified,
SG29-009 on 2026-11-04, including authorized commit/push of the accumulated branch.
SG29-010 is separately approved, implemented and verified on 2026-11-04. The owner
approved SG29-011 through SG29-013 in order, with commit/push between items; all
three are now implemented and verified on 2026-11-05. The owner subsequently
approved only the SG29-014 LIKE/map wrong-result pair, with commit/push at the end;
that bounded round was verified and pushed as `19c29eda`. The owner then approved
the remainder of SG29-014; those assessed sub-rounds are now implemented and
verified, with commit/push at the end.
SG29-015 is separately approved, implemented and verified on 2026-11-06. Its approval
did not cover SG29-019 onward or publication/adoption. The owner
subsequently approved commit/push of SG29-015 followed by SG29-016 through
SG29-018 in order, with separate tested commits/pushes between items.
The owner subsequently authorized the final two items, SG29-019 and SG29-020,
while the separately authorized streaming-cancellation validation runs.
SG29-019's complete frozen-target ledger is now verified; SG29-020 is in progress.
Publication and downstream adoption still require their own authorization.

## Baselines and evidence

- Global oracle remains `v30.22.0-5-ga00dc2e5c`, commit
  `a00dc2e5c489214bea53bacbbeba8713f21ebce1`, dated October 9. The previous
  [full sync](archive/TODO-sqlglot-a00dc2e.md) finished October 11.
- Proposed frozen target is `v30.29.0`, commit
  `2fe25297af5b0f44f5c9e5d03e1b296424b7acbe`. The November 3 review inspected
  upstream main `f7a36727`, which adds the release changelog after that target.
  There are 250 commits from our global pin to the release, 251 to reviewed main.
- DuckDB has a newer, narrower baseline: `DDB20-054` reviewed upstream through
  `6de2df31b0ae8b21155828f575a14dc8903c1b9b` on November 1. Its 37 touching
  commits and 18 probes are recorded in
  `vendor/data/duckdb-sqlglot-2.0-audit.json`. There are 27 commits from that
  baseline to reviewed main. Do not count its already-reviewed changes as new.
- Initial investigation ran 23 isolated `parseOne`/`transpile` probes against
  downloaded published Brikk SQL `0.23.0` JARs and upstream Python sources.
  Scratch evidence is at
  `/var/tmp/jayson/opencode/sqlglot-review-20261103/`, including
  `probe-results.json`, `probe.py` and `BrikkProbe.java`.
  These are parser/rendering observations, not live database or full-suite proof.

## Constraints

- Preserve [intentional divergences](../docs/brikk-extensions.md), first-class
  pipe syntax, source maps, UTF-16 positions, parameter order and caller-owned ASTs.
- Keep generic oracle rendering distinct from native/executable qualification.
  `DDB20-055` already refuses unproved DuckDB mappings. A generic renderer probe
  does not demonstrate a bypass of those safeguards. Do not remove refusals just
  because upstream can render SQL.
- Preserve Java 17 support and published JVM constructors, copy methods and
  default bridges. Public data-class additions need the compatibility shapes
  required by `AGENTS.md` and binary-compatibility tests.
- Do not add IntelliJ internal calls or increase experimental API usage. Bring
  unavoidable new risk back for approval rather than suppressing warnings.
- Do not move the oracle pin, regenerate global metadata/corpora, or migrate AST
  shapes during individual fix batches. Any early parity difference must have an
  exact, reviewed explanation, never a blanket failure allowance.
- Test against recorded engine versions. A catalog version, another engine's
  successful execution or parser acceptance does not prove semantic equivalence.
  Preserve explicit unsupported results when evidence is missing.
- Query-plan optimization, executor implementation, unsupported dialects, plugin
  features, toolchain upgrades and consumer dependency changes are outside scope.

## Batch A: new robustness and escaping fixes, recommended first

- [x] **SG29-001: Establish durable regression evidence.** Reproduce the relevant
  scratch probes on the implementation checkout and retain hand-owned tests or
  checked-in inputs/results with exact source/artifact identities. Preserve the
  original observations and distinguish confirmed gaps from source-review candidates.
- [x] **SG29-002: Stop DESCRIBE recursion.** Port the guarded dot backtracking in
  [#8871](https://github.com/tobymao/sqlglot/pull/8871), `d6e78751`.
  `DESC.` currently produces `StackOverflowError` in the MySQL probe. Test DESC
  and DESCRIBE with whitespace/semicolon variants across applicable dialects,
  valid style/kind-like database names, and Brikk's described-call extensions.
  Malformed input must terminate with an ordinary positioned diagnostic.
- [x] **SG29-003: Handle malformed JSONPath indexes safely.** Reconcile
  [#8869](https://github.com/tobymao/sqlglot/pull/8869), `8a524dfa`.
  `$[1e2]` currently leaks `NumberFormatException`. Test direct JSONPath and SQL
  entry points, invalid/non-decimal and overflowing indexes, valid indexes and
  existing fallback policy. A raw-path fallback is not proof of native acceptance.
- [x] **SG29-004: Correct BigQuery identifier escaping.** Port
  [#8863](https://github.com/tobymao/sqlglot/pull/8863), `c39005c3`, together
  with its target-state follow-ups. Test embedded backticks/backslashes, repeated
  escapes, quoted qualified names and source-aware round trips. Keep string and
  identifier escape rules separate; retain other dialects' escaping contracts.

Batch A evidence and validation, including the retained historical observations,
are in [the evidence guide](../vendor/data/sqlglot-30.29-batch-a-README.md).
Batch A itself left the oracle pin, generated tables/corpora, ledgers,
executable safeguards and IntelliJ production sources unchanged. Batch B's
bounded audit/ledger changes are recorded below. No commits or publication yet.

## Batch B: precedence and grouping

- [x] **SG29-005: Reconcile IS predicate precedence.** Review
  [#8676](https://github.com/tobymao/sqlglot/pull/8676), `479c4cae`, across
  every supported profile, including DataFusion/Spice inherited behavior.
  Our PostgreSQL `a = b IS NULL` AST currently means `a = (b IS NULL)` rather
  than `(a = b) IS NULL`. Cover DISTINCT FROM, comparisons, range predicates,
  chained IS, generated columns, types/nullability and grouped generation.
- [x] **SG29-006: Reconcile INTERSECT precedence and set modifiers.** Review
  [#8704](https://github.com/tobymao/sqlglot/pull/8704), `86abcde3`, plus the
  parenthesized VALUES operand fix `a4e08982`. Test each dialect's precedence,
  explicit parentheses, per-arm versus outer pagination, CTEs, ClickHouse
  SETTINGS/FORMAT, scopes and deep chains. Assert AST meaning and native row sets;
  unchanged SQL text alone does not prove a correct AST.
- [x] **SG29-007: Fix MySQL operator precedence without assuming derivative rules.**
  Review `fdd0e9a4`, `8955d752` and `b1fd4e9e` for logical XOR/AND/OR,
  exclamation-mark NOT and bitwise operators. Our probes misgroup `!1 + 1`,
  `1 XOR 1 AND 0` and `1 | 2 & 0`. Qualify MySQL, Doris and StarRocks rules
  independently, including NULLs, explicit grouping and cross-target generation.

Batch B's [evidence and qualification limits](../vendor/data/sqlglot-30.29-batch-b/README.md)
include native PostgreSQL/DuckDB/MySQL/StarRocks/ClickHouse controls, separately
labeled Doris FE-only plans/folds, and source-backed DataFusion/Spice policy.
The host's Doris backend startup guard was not bypassed. Unproved Boolean-IS,
StarRocks logical-XOR and symbolic-shift mappings refuse executable generation.
One exact frozen-pin scope ledger row and a fully reviewed native-syntax audit
baseline explain the bounded drift. No global oracle refresh or release follows.

## Batch C: wrong-result mappings and temporal expressions

- [x] **SG29-008: Preserve population/sample aggregates.** Port
  [#8632](https://github.com/tobymao/sqlglot/pull/8632), `f814e4cb`.
  MySQL STDDEV/VARIANCE currently translate to sample aggregates in our PostgreSQL
  probe. Cover aliases, both directions, derivative dialects, windows, NULLs and
  empty/single/multiple-row groups with result-type and native-value evidence.
  [Evidence and limits](../vendor/data/sqlglot-sg29-008/README.md) include 36
  generated native MySQL/StarRocks controls, DOUBLE metadata, PostgreSQL/DuckDB
  result tests and independent Doris source registration. Doris backend execution
  remains unqualified. Unsupported MySQL/StarRocks statistical DISTINCT refuses
  executable generation. All modules build, 2,517 JVM tests and 827 IntelliJ
  tests pass. Native coverage, existing baseline/ledgers and oracle pin are unchanged.
- [x] **SG29-009: Preserve temporal arithmetic grouping and TIMESTAMPADD.** Review
  [#8815](https://github.com/tobymao/sqlglot/pull/8815), `3cca3ea4`, and
  `2d4b5a5b`. Our PostgreSQL output for `t - DATE_ADD(x, INTERVAL 1 DAY)`
  loses parentheses and becomes `t - x + interval`. Cover nested/unary arithmetic,
  dynamic intervals, SQL_TSI units, temporal types/zones and AST non-mutation.
  [Evidence and limits](../vendor/data/sqlglot-sg29-009/README.md) cover detached
  lowering in PostgreSQL/DuckDB/Spark, 20 native MySQL TIMESTAMPADD/DATE_ADD
  value/type pairs and native PostgreSQL/DuckDB grouping controls. Existing
  executable refusals remain. One exact frozen-pin MySQL-to-PostgreSQL output
  ledger row accounts for an inventory-only audit update; all 24,606 native
  SQL/status/type tuples and local dispositions remain unchanged. Build, 2,528
  JVM tests and 827 IntelliJ tests pass. The oracle pin remains unchanged.
- [x] **SG29-010: Preserve JSON-valued ClickHouse extraction.** Port
  [#8601](https://github.com/tobymao/sqlglot/pull/8601), `4d7d7ca9`.
  Our JSON_EXTRACT probe emits JSONExtractString instead of JSONExtractRaw.
  Keep scalar extraction separate; test arrays/objects/scalars, paths/index bases,
  missing members, JSON null versus SQL NULL and native results/types.
  [Evidence and limits](../vendor/data/sqlglot-sg29-010/README.md) record 26 native
  ClickHouse 26.10.2.103 value/type pairs, four preview-only controls and three
  outbound refusals. Quoted numeric/empty/escaped keys and native index bases
  are preserved. Native raw results have String-compatible typing; cross-engine
  raw execution refuses unqualified missing/null/type conversions. Build, 2,537
  JVM tests and all 827 IntelliJ tests pass. Native coverage, fixtures, ledgers,
  existing baseline and oracle pin remain unchanged. Commit/push is now authorized
  before beginning the next three items.
- [x] **SG29-011: Qualify PostgreSQL interval/date mappings.** Review named
  MAKE_INTERVAL arguments and anonymous-call preservation, `5739cd6c`/`53ccce9f`;
  boundary-counting DATEDIFF, `8709510f`/`991d6ce5`; ISO week/year formats,
  `1e5c649c`; digest versus hex SHA2, `78b24a74`; and regex case flags,
  `eb03a972`. Keep these as separately verified sub-rounds. Do not weaken
   DuckDB executable temporal/regex refusals or infer general engine equivalence.
  [Evidence](../vendor/data/sqlglot-sg29-011/README.md) covers five separate native
  sub-rounds. Strict DuckDB SHA384/512 generation also refuses algorithm loss.
  Five exact parity rows and three unsupported-size preview origins are reviewed;
  all other native origins and local dispositions are unchanged. Build, 2,549
  JVM tests and 827 IntelliJ tests pass. Committed/pushed as `9bc34f04`.

## Batch D: supported syntax and dialect features

- [x] **SG29-012: Support PIVOT after VALUES.** Port
  [#8876](https://github.com/tobymao/sqlglot/pull/8876), `90659e63`.
  Our DuckDB probe rejects this form. Cover alias lists, pivot output columns,
  scope/type/lineage, serialization and targets that lower VALUES to SELECT/UNION.
  Preserve VALUES ORDER/LIMIT/OFFSET when wrapping or lowering.
  [Evidence](../vendor/data/sqlglot-sg29-012/README.md) includes native DuckDB
  and forced SELECT/UNION lowering controls, proper pivot-result qualification,
  homogeneous INTEGER/MAX output typing and lineage. The exact optional Values
  schema suffix is separately gated without refreshing the frozen manifest.
  Build, 2,556 JVM tests and all 827 IntelliJ tests pass. Native audit and existing
  baseline are unchanged. Committed/pushed as `aca9c090` before SG29-013 began.
- [x] **SG29-013: Add Doris full-text operator support.** Review `f3a6eb95` and
  `a0cc28b9`. Our MATCH_ALL with a quoted analyzer rejects. Preserve operands,
  MATCH variants, negation, analyzer strings and implicit aliases. Native grammar
  and semantic evidence must bound support; refuse unsupported target mappings.
  [Evidence](../vendor/data/sqlglot-sg29-013/README.md) includes 21 paired native
  Doris 4.1.5 parsed plans, seven analyzed plans, pinned Boolean/nullability/opcode
  contracts and eight outbound execution refusals. No backend rows, index/tokenizer
  behavior or analyzer-catalog execution is claimed; the host guard remains intact.
  Build, 2,562 JVM tests and 827 IntelliJ tests pass. Native coverage/baselines,
  the global oracle pin and generated manifests remain unchanged.
- [x] **SG29-014: Reconcile StarRocks fixes and features.** First fix LIKE argument
  order, `f321207c`, and map literals, `fc1472a0`, both observed to change meaning
  in our probes. Then assess native TO_DAYS/DATEDIFF, hash-comment rules, PIVOT
  rendering/static outputs, aggregate-key/HLL DDL, materialized-view refresh
  options and administrative command preservation. Split into tested sub-rounds;
  opaque Command preservation must not be called structured semantic support.
  - [x] **SG29-014-A: LIKE argument order.** Native value/pattern order is fixed,
    including NULLs, without changing the base pattern-first builder.
  - [x] **SG29-014-B: Map literals.** Preserve expression/qualified keys, nested
    lookups and map identity rather than struct fields; keep native NULL/duplicate
    key behavior and refuse unqualified cross-engine map execution.
  [Evidence](../vendor/data/sqlglot-sg29-014-wrong-results/README.md): 19 paired
  native StarRocks value/type controls and six map refusals; build, 2,570 JVM
  tests and 827 IntelliJ tests pass. Native audit and global pin are unchanged.
  - [x] **SG29-014-C: Native dates.** Preserve TO_DAYS and date-part DATEDIFF;
    keep elapsed DATE_DIFF separate and refuse unqualified boundary conversions.
  - [x] **SG29-014-D: Hash lexing.** Reject bare hashes/PG hash arrows, preserve
    quoted hashes and normal comments, and leave MySQL comment behavior unchanged.
  - [x] **SG29-014-E: PIVOT.** Render native PIVOT and infer qualified static
    output names; keep unresolved aggregate names unknown and refuse UNPIVOT,
    simplified PIVOT and unqualified cross-engine result-name/type mappings.
  - [x] **SG29-014-F: Aggregate/HLL DDL.** Structured nodes preserve all six
    aggregation suffixes, keys, types and distribution. Native parser field
    snapshots match; no table creation or storage/aggregation execution is claimed.
  - [x] **SG29-014-G: Refresh.** Structured partition/FORCE/mode options,
    invalid-mode rejection and whole unknown-command fallback; exact optional
    Refresh schema suffix is gated without changing the frozen manifest.
  - [x] **SG29-014-H: Administration.** All reviewed admin/blacklist/job-control
    families retain whole text as opaque Command, not structured semantic support.
  - [x] **SG29-014-I: UNNEST prerequisites.** Keep all function arguments and
    avoid fabricating one alias for multi-array duplicate default output names.
  [Remainder evidence](../vendor/data/sqlglot-sg29-014-rest/README.md): 14 native
  read-only value/type pairs, 12 matching native parsed-field pairs and 25 opaque
  records. DDL/refresh/admin statements are never executed. Build, 2,578 JVM
  tests and 827 IntelliJ tests pass. Three exact parity rows and three extra
  opaque-command warnings are reviewed; all 24,606 native SQL/status/type tuples
  are unchanged. The global oracle pin and generated manifests remain unchanged.
- [x] **SG29-015: Fix Spark/Hive string aggregation and ALTER forms.** Review
  `ab043477`/`2748f82e`, `0b001782` and `03744a2a`. Our Spark LISTAGG DISTINCT
  emits an extra separator argument. Test ALL/DISTINCT, ordering, separators,
  DROP COLUMN[S]/IF EXISTS and REPLACE COLUMNS, including scope/catalog effects
  and explicit cross-target refusals where appropriate.
  [Evidence](../vendor/data/sqlglot-sg29-015/README.md): ALL/DISTINCT and ordered
  LISTAGG keep their inputs/separators, including Spark's native empty default;
  generation does not mutate AST children. DROP retains nested/plural targets
  and IF EXISTS, and REPLACE has an exact bounded upstream AST/serde node.
  Unsupported/empty replacement definitions stay whole opaque Commands.
  ALTER has no query output or implicit catalog migration; foreign replacement,
  plural/nested drops and Hive CASCADE mappings are refused where unqualified.
  Native Spark 4.0.1 passes 22 SELECT value/type pairs; eight Spark and two Hive
  2.3.10 parser-plan pairs match without executing ALTER. One source-rejected
  unwrapped replacement is normalization only, not native equivalence. Build,
  2,582 JVM tests and 827 IntelliJ tests pass. The full native audit, parity
  ledgers, global pin and frozen manifest remain unchanged. Commit/push authorized.
- [x] **SG29-016: Reconcile BigQuery/Trino translations.** Review
  `d17c7680`, `f536a823` and `a8a7610d` for date parts, FROM_BASE,
  percentiles and array operations. Our weekday probe emits DAY_OF_WEEK instead
  of EXTRACT(DAYOFWEEK FROM ...). Verify weekday numbering as well as syntax;
  preserve the independent BQ-36 through BQ-38 limitations.
  [Evidence](../vendor/data/sqlglot-sg29-016/README.md): correct EXTRACT rendering,
  ISO/Sunday weekday conversion, source-aware DuckDB weekday lowering, decimal
  widening and non-mutating casts. Trino three-argument percentile weight is
  distinct from Presto accuracy. Approximate/array/hex rewrites retain explicit
  qualification refusals; no BigQuery backend execution is claimed. Seven native
  weekday controls plus NULL pass. Build, 2,586 JVM tests and 827 IntelliJ tests
  pass. Exactly five weighted-percentile warnings reviewed; all native tuples,
  corpus hashes, parity ledgers and the global oracle pin remain unchanged.

## Batch E: shared analysis and compatibility-sensitive changes

- [x] **SG29-017: Audit shared typing and qualification changes.** Review all-row
  VALUES coercion, nested UNKNOWN propagation, struct-valued subqueries/row
  references, TVF inference, pivot aliases, semi/anti visibility and annotation
  ordering. Relevant commits include `f0b4347b`, `a32ef636`, `fb36fb71`,
  `5e0f7169`, `4678f6cb`, `2bf94d96`, `9d2966ed` and `e54ad9aa`.
  Compare existing ordered recursive set-operation typing before porting; retain
  unknown-schema conservatism, Engine inference policies, native ClickHouse
   proof provenance and old APIs. Add hand-owned shape/lineage/Engine regressions.
  [Evidence](../vendor/data/sqlglot-sg29-017/README.md): all-row VALUES with
  exact native-type preservation, conservative MAP/approx-top UNKNOWN guards,
  complete struct subqueries/row references and ordered query-type evidence.
  Retained existing recursive set reconciler, inference-policy overloads and
  uncache API; no broad upstream cache/refactor or unknown-TVF inference port.
  New shape/lineage/Engine and native DuckDB controls pass. Build, 2,594 JVM tests
  and 827 IntelliJ tests pass. One annotation-only ledger row reviewed; every
  native tuple, local classification and engine/setting remains unchanged.
- [x] **SG29-018: Decide ArraySort migration and reconcile DuckDB overlap.**
  Upstream `094509aa` merges SortArray into ArraySort with explicit NULL ordering.
  Propose a compatible AST/serde/API transition before replacing nodes. Reuse
  DDB20-054/055 dispositions for array endpoints/insertion/null replacement,
  Unicode strings, fractional intervals, Base64 alphabets, JSON paths, regex,
  triggers, NEAREST, routing and reserved words. Classify each as already covered,
  intentionally refused, a separately qualified port or deferred; do not reopen
  completed native work wholesale. Keep the queued bounded Trino REGEXP_COUNT
  work separate from any ClickHouse countMatches mapping.
  [Evidence and compatibility proposal](../vendor/data/sqlglot-sg29-018/README.md):
  retain both published node classes/serde/schema names; no breaking replacement.
  Preserve native reverse-sort NULL-order arguments and session defaults, with
  32 native controls across copy/serde. Detached comparators/post-pin flags refuse
  rather than losing options. Replayed DDB20-054's exact 37-commit review and
  retained DDB20-055's refusal matrix. Build and 2,598 JVM tests pass; all native
  tuples/corpus hashes unchanged and exactly two comparator warnings reviewed.
  Two full IntelliJ runs each execute 827 tests with one unrelated streaming-
  cancellation RMI EOF failure. The owner directed a separate fix/commit/push
  thread, without pausing SG29-018; that gate is not represented as passed.
  Subsequent independent fix `17a3d5d4` passes all 829 IntelliJ tests, the original
  827 plus two cancellation regressions, without exclusions. Full build and
  2,598 JVM tests also pass; no SG29-018 sort/native evidence was changed.

## Batch F: oracle integration, only after the preceding decisions

- [x] **SG29-019: Complete the frozen-target disposition ledger.** Account for
  commits omitted from the initial targeted review, including shared escaping,
  UNNEST/explode transforms, parser progress guards and new AST/type definitions.
  Mark each relevant change ported/tested, already equivalent with evidence,
  intentionally divergent or deferred with an owner. The upstream COPY loop fix
  did not reproduce a hang in our initial probe; investigate rather than claim it
  fixed. Python's large-exponent is_int exception does not directly apply to our
  toLongOrNull implementation. No replace_tables-equivalent API was found in
  the initial search. Confirm these dispositions, not automatic feature additions.
  [Complete ledger](../vendor/data/sqlglot-sg29-019/README.md): all 250 exact
  commits have full IDs, subjects, paths, patch hashes and explicit owned
  dispositions. Three offline checker/safety tests and a complete upstream
  history replay pass. New quoting/EXPLODE/REF integration prerequisites are
  assigned to SG29-020, not labeled ports; excluded optimizer/external-oracle
  work retains named owners. No pin, generated corpus or native baseline moved.
  The separately committed cancellation fix is `17a3d5d4`; its full build/JVM/IDE
  gates are green before this documentation/tooling-only round is committed.
- [ ] **SG29-020: Refresh coherently and qualify integration.** Once scope and
  compatibility decisions are approved, regenerate target AST/tokenizer/typing
  metadata and corpora together, update FixturePinSyncTest and pin declarations,
  and review exact ledger changes. Run build/core/other JVM suites, tooling tests,
  Engine contracts, ABI/Java 17 checks and affected native source/target tests.
  Run shared IDE contracts and API-risk checks where affected. Record exact
  versions, failures, skips and unrun oracles; no pass-by-count ledger changes.
  Keep publication, consumer qualification and adoption separate.

## Review decision

The owner approved **Batch A, SG29-001 through SG29-004**, in a separate worktree,
then **Batch B, SG29-005 through SG29-007**, in that same worktree, followed by
the bounded **SG29-008** population/sample-aggregate fix and **SG29-009** temporal
arithmetic grouping/TIMESTAMPADD. The owner also authorized commit/push of the
accumulated branch after SG29-009 validation, completed as `70a9f1c0` on
`origin/sqlglot-30-29-batch-a`. SG29-010 JSON-valued ClickHouse extraction is
separately approved. The owner subsequently approved SG29-011, SG29-012 and
SG29-013 in order, with commit/push after each. Commit/push SG29-010 first to
keep the new items separate. The SG29-014 LIKE/map pair was verified and pushed
as `19c29eda`, followed by approval of the remaining SG29-014 sub-rounds with
commit/push after validation. SG29-015 was then separately approved and verified.
Commit/push for that round and sequential SG29-016 through SG29-018 are now
authorized, with commit/push between items. That three-item approval did not
authorize SG29-019 onward. The owner has now separately authorized SG29-019
and SG29-020, retaining separate tested commits and no publication/adoption.

SG29-010 through SG29-013 are complete, with the three requested items validated
and committed/pushed separately. All SG29-014 areas are now assessed and verified,
with opaque preservation distinguished from structured support and native
execution. SG29-015 is separately implemented and verified, with commit/push
authorized before starting SG29-016. SG29-016 through SG29-018 are approved in
order. SG29-016 is now implemented and verified, with commit/push authorized.
SG29-017 is also implemented and verified, with commit/push before SG29-018.
SG29-018 is complete with the compatibility decision and native fix. The owner
authorized continuing its commit/push while the unrelated IntelliJ cancellation
blocker is fixed in a separate session. A later request authorizes the last two
items. SG29-019's complete disposition ledger is verified; SG29-020 remains in
progress. The cancellation fix retains its own commit and full validation.

Implementation is isolated on branch/worktree `sqlglot-30-29-batch-a`. The
planning request initially added this TODO and its index links; later approvals
cover the implementation/evidence through SG29-015. Earlier
guides' uncommitted-status statements describe their original checkpoints.
Publication, consumer adoption and PR creation remain unrequested.
