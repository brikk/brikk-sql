# ClickHouse 2026 catch-up evidence

Audit date: 2026-10-28. Batch 1 covers CH26-001 and CH26-002 in `docs/clickhouse-catchup-plan.md`.

## References and limits

- [2026 OSS changelog](https://clickhouse.com/docs/resources/changelogs/oss/2026), retrieved 2026-10-28. At retrieval it listed 26.1 through 26.9, not 26.10.
- [26.10 release presentation](https://presentations.clickhouse.com/2026-release-26.10/) and [26.10.2.103 stable release](https://github.com/ClickHouse/ClickHouse/releases/tag/v26.10.2.103-stable).
- Pinned parser sources in `vendor/data/clickhouse-2026-audit/cases.json`, including stream option ordering, cursor objects and watermark grammar. The tentative CREATE HANDLER example from the initial audit was confirmed by upstream grammar and both pinned parsers.
- Exact targets and immutable image digests are in `vendor/data/clickhouse-2026-audit/engines.json`. The LTS reference is **26.8.34.13**; stable is **26.10.2.103**. Both report the exact expected version when executed as `clickhouse local` on linux/amd64.

This is a targeted audit, not full engine compatibility certification. Native parser acceptance does not imply successful execution, correct AST analysis, or a safe cross-dialect translation. Introduction versions in the case list come from release notes, not a bisection across all earlier builds. Older parser acceptance of a generic call or bare identifier does not establish feature availability. In particular, `year()` and implicit lambda identifiers can parse on an older engine without implementing the newer semantics.

## SQL-relevant release review

Reviewed the new/experimental features, improvements and incompatible-change sections of the 26.1 through 26.9 changelog, plus the 26.10 presentation. Runtime optimizations, service configuration, web UI and protocols are not parser backlog items unless they change SQL or metadata contracts.

| Release | Relevant work or regression coverage |
| --- | --- |
| 26.1 | Projection-index grammar, Nullable tuples, QBit, reverseBySeparator, mergeTreeAnalyzeIndexes and lake table functions. |
| 26.2 | QBit promotion, join column aliases, OPTIMIZE DRY RUN, primes and new scalar functions. Build-specific registries need separate evidence. |
| 26.3 | Materialized CTEs, no-parenthesis built-ins, SOME, joined-table parentheses, EXTRACT/dictionary/Iceberg additions and WASM UDF declarations. |
| 26.4 | NATURAL JOIN, compound intervals, VALUES relations, TIME ZONE syntax, projection index forms, new JSON/array/text/AI functions and temporal arithmetic. |
| 26.5 | Bare function names in higher-order calls, negative LIMIT BY, refreshable-view replacement/dependencies, SQL tooling functions and aggregate aliases. |
| 26.6 | STREAM reads, hypothetical indexes, ADD ENUM VALUES, star LIKE/ILIKE, LIKE ESCAPE, SOME/ALL over arrays, date_part, bare local/session built-ins and MVT/array/vector functions. |
| 26.7 | Tuple combinator, EXPLAIN ANALYZE, AT LOCAL, projection WHERE/settings, QBit stride/Int8, parameterized settings, SYSTEM commands and new geometry/vector aggregates. |
| 26.8 | Watermarks, native pipes/FROM-only queries, GROUPS frames, HANDLER statements, trailing table-function SETTINGS, MultiPoint, AST JSON functions and new aggregate/AI/table functions. |
| 26.9 | LIMIT boundaries, incremental refresh, tokens/auth grants, regression aggregates, JSON brackets, zero-argument year, Nullable tuple defaults and removed functions/statements. |
| 26.10 | Higher-order composition/implicit arguments, integer-division setting, native Trino-compatible forms, experimental LATERAL, Map combinator arguments, array/map/bit/encryption functions, database engines and formats. |

The committed cases cover the concrete findings from the initial audit. Secondary DDL forms and changed function semantics remain a research checklist under CH26-003 through CH26-007, not a claim of verified support. Exact grammar and execution evidence must be added before their implementation or certification.

## Case categories and ownership

Each JSON case has a stable ID, documented release boundary, owning plan step and SQL. Its expected native parse result defaults to acceptance; malformed cases explicitly require rejection. The baseline observations record the actual Brikk AST, generated SQL or parser error at base commit `8ef3506616c03dba2bd2bbf351b2e4289a54cd4d`.

| IDs | Baseline finding | Next owner |
| --- | --- | --- |
| S01 | STREAM silently becomes a table alias. | CH26-002 |
| S02 through S04 | Streaming options and derived streaming reads rejected. | CH26-002 |
| A01 through A03 | Tuple aggregates unclassified; parameterized calls become aliases or fail. | CH26-002 |
| Q01 through Q09, T01 through T02 | Missing LIMIT/time/join/table-function/type syntax. | CH26-004 |
| M01 through M03 | Star name filters become boolean expressions; bare built-ins become columns. | CH26-005 |
| M04 through M06 | Generic date_part, composition and implicit lambda syntax require semantic handling. | CH26-005 |
| D01 through D07 | Opaque Command fallback, not structured AST support. | CH26-006 |
| P01 through P11 | Existing representative acceptance to retain; semantic/setting checks still needed. | CH26-007/008 |
| N01 through N03 | Malformed streaming input must remain rejected. | CH26-002 |

Catalog omissions/removals are a separate CH26-003 inventory. Do not treat the original embedded-build versus official-build name delta as a list of newly introduced functions. No catalog or semantic certification changes are part of batch 1.

## Reproduction

1. Read `engines.json` and explicitly pull the two exact Docker tags. Confirm their resolved digests match the file. The runner uses the digest, never the moving tag, and refuses implicit image pulls.
2. Run `python3 tools/probe_clickhouse_2026.py --output /tmp/opencode/clickhouse-2026-native`.
3. Run `./kotlin test -m brikk-sql --include-classes='*Clickhouse2026AuditTest'`. This checks the corrected fixtures and writes all current Brikk observations to `build/reports/clickhouse-2026/brikk.json`. To choose an absolute output path add `--jvm-args='-Dbrikk.clickhouseAuditOut=/tmp/opencode/clickhouse-2026-after.json'`. The standalone `tools/clickhouse_2026_audit.kts` provides the same observations when run with built core/metadata and kotlinx-serialization on its classpath; it was used for the pre-fix capture.
4. Pass that observation file as `--brikk <file>` to the Python runner. This executes both the original finite fixtures and Brikk's generated SQL against both pinned engines and checks exact expected results.

The Python probe runs `formatQueryOrNull(unhex(...))` for the audit SQL. It never executes the token, handler, DDL or hypothetical-index audit statements. Only the separate execution fixtures run, inside task-owned, network-disabled containers with no host mounts. Native queries have a five-second execution limit and containers have a 30-second host deadline plus unconditional task-owned-container cleanup. Every streaming execution fixture uses BOUNDED and LIMIT, with `enable_streaming_queries = 1`. Production generation never injects that setting.

`native/*.json` stores the raw native parser output, version query and native/generated execution results. `brikk-before.json` records the pre-fix audit; `brikk-after.json` records the batch result. These files are evidence, not a failure ledger. Normal unit tests do not need Docker or the Kotlin script.

The generated native formatting is required to match the original for the corrected
cases, except for S03's explicitly recorded expected text. Existing interval AST
canonicalization changes ordinary `INTERVAL 1 SECOND` into `INTERVAL '1' SECOND`,
which the native formatter renders as `toIntervalSecond('1')` instead of
`toIntervalSecond(1)`. E02 checks the original/generated watermark query results.
The stream-specific idle-timeout grammar does not allow a quoted count; generation
handles that separately. This literal-type difference is not claimed as AST identity
or general expression equivalence.

## Batch 1 design

STREAM is a dedicated Brikk-native relation wrapper with inspectable options, cursor entries and watermark expressions. The table or derived query underneath keeps its identity and alias for scope analysis. STREAM is reserved in the implicit table-alias position only; explicit AS and quoted stream aliases remain usable. Modifier order follows the pinned parser: alias, FINAL, SAMPLE, STREAM, BOUNDED, UNORDERED, CURSOR, WATERMARK. Cursors require string keys and unsigned integer leaves. Watermarks require FOR, AS and an expression; optional IDLE TIMEOUT requires an unsigned interval count.

Tuple joins the existing aggregate-combinator resolver instead of becoming a new unrelated scalar function. Existing aggregate nodes retain the authored function name, parameter lists, arguments and window placement. Native finite checks cover nested combinators and preservation of named-tuple fields. No new inferred tuple return-type formula or general cross-dialect equivalence is claimed.

Other dialects refuse STREAM and Tuple-combinator execution rather than silently stripping streaming behavior or passing through an unproved aggregate name. AST serialization, scope/source spans and source-preserving executable lowering receive regression tests. No public data-class constructor/copy shape changes are needed.

## Batch 1 validation

- `./kotlin test -m brikk-sql --include-classes='*Clickhouse2026CatchupTest'`: seven focused tests passed.
- Final `./kotlin test`: **1,707 passed, 61 assumption-aborted optional tests, zero failures**, across 1,768 discovered tests. This includes the new audit-fixture gate, AST registry/manifest, serde, binary/runtime compatibility, corpus, scope, typing, lineage and source-preserving tests. The initial full run caught the missing native-node allowlist entries; those were registered without changing any SQLGlot manifests or failure ledgers.
- `python3 -m unittest discover -s tools -p test_probe_clickhouse_2026.py`: two probe-isolation/deadline tests passed.
- `python3 tools/probe_clickhouse_2026.py --output /tmp/opencode/clickhouse-2026-native --brikk /tmp/opencode/clickhouse-2026-after.json`: passed on both pinned targets. Per target: 45 native parser observations, seven generated correction-query parser checks, six original finite-result checks and six generated finite-result checks. The standalone and test-generated Brikk observation JSON files match.
- `git diff --check`: clean. Task-owned probe containers were removed.

The 61 optional test aborts are for unconfigured live ClickHouse HTTP, Trino,
Doris, PostgreSQL, MySQL, StarRocks and chDB integrations. The new pinned native
ClickHouse probe above did run independently; it does not turn those unrelated
integration skips into passes. Network documentation checking was not enabled,
and the custom IntelliJ platform test task was not run. No IDE or published API
contract was changed by this batch. Existing build warnings are unchanged.

Remaining steps CH26-003 through CH26-008 require a new user-approved batch.
Metadata is still 26.5.1.1; missing LIMIT/type/built-in/DDL forms and unmeasured
semantic changes are not fixed or certified by these two corrections.
