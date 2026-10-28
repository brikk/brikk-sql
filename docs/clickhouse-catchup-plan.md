# ClickHouse 2026 catch-up

## Steps

- [x] CH26-001: Pin reference engines and commit a reproducible coverage audit.
- [x] CH26-002: Fix incorrect STREAM and aggregate-combinator rewrites.
- [ ] CH26-003: Capture versioned function, keyword, type and settings metadata.
- [ ] CH26-004: Add missing query expressions and type syntax.
- [ ] CH26-005: Correct star filtering, built-in names and higher-order expression analysis.
- [ ] CH26-006: Replace opaque fallback for the identified DDL and EXPLAIN forms.
- [ ] CH26-007: Update version-sensitive semantics and executable-generation guards.
- [ ] CH26-008: Validate existing coverage and run native, compatibility and regression gates.

## Scope and constraints

Worktree and branch: `clickhouse-catchup`. This plan requires review before implementation.

Target the latest stable release verified during the audit, ClickHouse **26.10.2.103**, and an exact pinned **26.8 LTS** build. Retain the existing **26.5.1.1** embedded-engine metadata snapshot. Pin the precise LTS patch and image digest or binary checksum in CH26-001 rather than using a moving tag. Use earlier releases as needed to establish introduction/removal boundaries.

The task covers SQL parsing, AST structure, generation, scope/shape analysis, metadata and evidence-backed semantic checks. It does not include server operations, web UI, client features, a new KQL/LogsQL/Trino-on-ClickHouse dialect implementation, or integration changes in sibling repositories. New engines and formats need only the SQL and metadata handling supported by this library, not connector implementations.

Keep these distinctions explicit:

- Text acceptance is not structured AST support.
- A generic function call that round-trips is not evidence of typing, aggregate recognition or cross-dialect equivalence.
- Native `formatQuery` acceptance is parser evidence, not execution or semantic evidence.
- Engine registries differ by build and enabled features. A name missing from the embedded 26.5 capture is not automatically a newly introduced function.
- Experimental features remain marked experimental and setting-dependent. Missing evidence remains unknown.

Do not weaken corpus ledgers, silently drop clauses, or widen semantic certification to make tests pass. Unsupported lowering must refuse clearly rather than produce plausible but incorrect SQL. Preserve source ranges and authored syntax where existing editor/executable-lowering contracts require them.

Preserve JVM binary compatibility for changed public classes, including hidden old constructors and defaulted `copy` methods where applicable, and add binary compatibility tests per `AGENTS.md`. Keep the repository's current toolchain/runtime requirements; do not introduce a runtime uplift. No commits, publishing or consumer dependency updates are authorized by this plan.

## CH26-001: Reference engines and audit fixtures

Sources:

- https://clickhouse.com/docs/resources/changelogs/oss/2026
- https://github.com/ClickHouse/ClickHouse/releases/tag/v26.10.2.103-stable
- https://presentations.clickhouse.com/2026-release-26.10/

The docs page fetched on 2026-10-28 stopped at 26.9 despite the published 26.10 stable release. Record the source retrieval date and release identifiers. Review SQL-relevant New Feature, Experimental Feature, Improvement and incompatible-change entries across 26.1 through 26.10. Supplement the presentation with official reference documentation and upstream tests/source for exact grammar.

Commit minimal fixtures and a repeatable probe runner. The initial exploratory script was `/tmp/opencode/clickhouse-2026-audit.kts` in the original session; do not depend on that temporary file surviving. Recreate its useful vectors in the repository and correct any tentative examples against native grammar before treating them as regressions.

For each finding record a stable case ID, release boundary, input SQL, current AST/output/error, expected native behavior and evidence source. Categorize it as incorrect rewrite, rejection, wrong AST, opaque Command, metadata omission, semantic gap, or already supported. Save raw native output, exact version and checksum/digest. The available local 26.7.1.1315 binary is useful supplemental evidence, not a substitute for the targets.

Acceptance: every correction below has a verified fixture; uncertain claims are corrected or marked unverified rather than copied from the initial audit.

## CH26-002: Incorrect rewrites first

1. `SELECT * FROM t STREAM` currently renders as `SELECT * FROM t AS STREAM`. Model streaming reads explicitly, including clause ordering and the documented watermark forms. Reserve STREAM in the appropriate grammar position without breaking legitimate identifiers or aliases. Preserve streaming behavior through generation and refuse unsupported cross-dialect lowering.
2. `SELECT quantileTuple(0.5)(t) FROM src` currently renders as `SELECT quantileTuple(0.5) AS (t) FROM src`. Add the `-Tuple` aggregate combinator to resolution and generation. Cover parameterized, non-parameterized, nested combinators, windowed calls, argument tuples and named tuple results. Keep existing aggregate combinators unchanged.

Primary files: `brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseParser.kt`, `ClickhouseGenerator.kt`, relevant AST/tokenizer/typing files and focused ClickHouse tests.

Acceptance: neither input becomes an alias; parse-generate-parse preserves structure; native result tests prove behavior on finite fixtures, with bounded/cancelled streaming tests that cannot hang the suite.

## CH26-003: Versioned metadata

Capture official target builds' `system.functions`, `system.table_functions`, `system.keywords`, available data types, relevant settings and documentation. Record feature/build availability. Use `system.documentation` and `system.statements` where available to support documentation refreshes without inventing signatures.

Refactor `tools/generate_clickhouse_functions.py` and keyword generation to accept explicit versioned captures. Extend `ClickhouseMetadata.kt` from a single snapshot to version selection using the existing metadata API. Preserve the 26.5.1.1 snapshot; add pinned 26.8 and 26.10 snapshots and test selection. Use the latest pinned stable snapshot as the unknown-server default, and document that policy. Keep exported catalog symbols compatible and ensure `ClickhouseDialect.functionCatalog` and default metadata agree.

Candidate missing names to verify include:

- 26.6: `arrayTopK`, `arrayBottomK`, `MVTEncode`, `MVTEncodeGeom`, bounding-box functions, quantization functions, `aiEmbed` and new compatibility aliases.
- 26.7: `groupFormat`, `randomHadamardTransform`, `xxHash64Spark`, `dictGetRoot`, transposed/quantized distances, coordinate conversions and geometry intersection functions.
- 26.8: `gini`, `mergedJSONPatch`, `notHas`, `parseQueryToJSON`, `formatQueryFromJSON`, `bigquery`, `mergeTreeCodecBlockCounts` and new AI functions.
- 26.9: all nine `regr_*` aggregates, `parseISO8601Duration`, `arrayFlattenedLength`, `hierarchicalKMeans` and `assignCentroid`.
- 26.10: array min/max index and removal functions, map functions, bit-packing functions, IPCrypt, quoted-printable functions, `normalizedQueryHashUnordered`, `obfuscate`, `predictXGBoost` and `elasticsearch`.

Verify introduction boundaries from engine evidence. Capture aliases, case sensitivity and aggregate kinds accurately. Do not fabricate overload signatures or fixed output schemas for argument-dependent table functions. Check generator behavior for newly recognized aggregates and combinators, not just catalog lookup.

Acceptance: deterministic regeneration, version-selection tests, representative lookup/completion tests and removals absent from new snapshots but retained in historical ones.

## CH26-004: Query expressions and types

Add native grammar, AST representation, generation and negative tests for:

- Boundary-based LIMIT: `LIMIT n AFTER condition`, `LIMIT UNTIL condition`, `LIMIT AFTER condition UNTIL condition`, and `LIMIT n AFTER condition ALL`. Check interactions with ORDER BY, offsets, LIMIT BY, negative LIMIT and WITH TIES against the engine.
- Zero-argument `year()` without weakening ordinary year argument validation.
- `NATURAL JOIN`, including side/kind combinations, deduplicated columns and alias resolution. Existing generic support is not correctly wired into the ClickHouse parser.
- `AT LOCAL` and SQL-standard compound intervals such as `INTERVAL '1:30' HOUR TO MINUTE`.
- Trailing `SETTINGS` arguments in `remote`, `remoteSecure`, `cluster` and `clusterAllReplicas` calls.
- `QBit`, including the documented element types, dimension and optional stride; `MultiPoint` and its relationship to `Geometry`.

Acceptance: native acceptance and result checks where meaningful, correct source spans, structural round trips, clear rejection of malformed forms and explicit cross-dialect refusal where no safe equivalent exists.

## CH26-005: AST and analysis corrections

- `SELECT * LIKE 'a%'` and qualified LIKE/ILIKE forms select columns by name. They must not remain ordinary boolean Like/ILike expressions. Test pattern escaping, qualification, schema-aware expansion, output shape, lineage and completion.
- Bare `LOCALTIME`, `LOCALTIMESTAMP` and `SESSION_USER` currently parse as columns. Add built-in handling and test quoted/qualified identifiers to avoid stealing real column names. Verify no-parenthesis NOW and related SQL-standard names introduced earlier in 2026.
- Audit `date_part`, EXTRACT additions and compatibility aliases. Generic call passthrough is acceptable only where it preserves correct analysis; add dedicated mappings when needed.
- Model 26.10 higher-order function composition and implicit lambda arguments. `lower | reverse` must not be analyzed as integer bitwise OR, and `_`, `_1`, `_2` must bind lambda arguments in the documented context rather than become unresolved table columns. Preserve ordinary bitwise expressions and real underscore-named columns outside that context.

Acceptance: scope, shape, typing and lineage assertions accompany SQL round trips. Unproved cross-dialect conversion is rejected rather than interpreted as a different expression.

## CH26-006: Structured DDL and EXPLAIN

Replace the identified opaque Command fallbacks with structured nodes and generator support, reusing existing models where suitable:

- Refreshable views with `APPEND INCREMENTAL`, preserving existing schedule, dependencies, settings and target clauses.
- `CREATE HYPOTHETICAL INDEX` and `EXPLAIN WHATIF`.
- `EXPLAIN ANALYZE`, with statement classification that acknowledges it executes the enclosed query.
- `ALTER TABLE ... MODIFY COLUMN ... ADD ENUM VALUES ('name' [= number], ...)`.
- `CREATE TOKEN` and authentication-method `GRANTS (...)`, with the documented validity forms and credential redaction.
- `CREATE`, `ALTER` and `DROP HANDLER`, using upstream-verified examples, not the tentative handler example from the initial probe.
- Audit the new projection-index grammar, projection-level settings/ALTER forms, constraint modification and unified SYSTEM commands. Add the missing forms discovered by CH26-001 and enumerate each remaining limitation explicitly.

Acceptance: expected AST nodes, not Command, for each supported fixture; clauses survive regeneration; statement safety classification and sensitive-text handling remain correct. Test the parse-only verifier with dangerous statement text as data, never by executing it. Existing generic Command behavior for unrelated unsupported SQL must remain unchanged.

## CH26-007: Version and setting-sensitive semantics

- Retire `catboostEvaluate`, `windowID`, WINDOW VIEW/WATCH and removed model-management forms from current-version advertising where represented. Retain historical knowledge; do not remove still-supported tumble/hop functions.
- Treat 26.9 `year()` and `year(date)` determinism accurately; avoid reusing the old `toYear` alias metadata indiscriminately.
- Test 26.10 `sql_compatible_division_for_integers` both off and on, including negative numbers, overflow and division by zero. Preserve settings-dependent warnings when the session setting is unknown.
- Review Nullable tuple extraction, Date/Time arithmetic, new regression aggregates, changed higher-order arguments and aggregate Map forms. Do not infer full type support from parser acceptance.
- Check PostgreSQL multi-dimensional `cardinality` mappings against new `arrayFlattenedLength`; retain Trino's outer-array cardinality behavior. Do not globally replace LENGTH/cardinality mappings.
- Revisit stale executable-generation guards for NATURAL JOIN, 26.10 LATERAL JOIN, native pipes, TRY_CAST and Trino-compatible forms. Widen only after pinned native result tests prove the exact form. Experimental LATERAL remains setting-dependent.
- Do not relabel historical semantic-hazard evidence as tested on newer versions. Refresh affected vectors and provenance explicitly; keep unrelated evidence unchanged.

Acceptance: paired result/error evidence for semantic changes, version/setting boundaries tested, and no unmeasured widening of pushdown or equivalence claims.

## CH26-008: Regression gates and handoff

Keep explicit passing coverage for materialized CTEs, projection WHERE, LIKE ESCAPE, JSON brackets, Nullable tuples, GROUPS frames, FROM-only queries, pipes, ARRAY literals and new map aggregate forms. The exploratory checks showed representative parsing success, not complete semantic support. Verify execution separately where necessary, especially TRY_CAST and newer native compatibility forms.

Run focused tests after each batch, then the full repository gates using the pinned wrapper. At minimum build/test core and metadata, affected verifier tests, AST serde, typing, scope/shape/lineage, source-preserving lowering and binary compatibility. Record the actual commands and results; the original audit only ran a core build and exploratory probes, not the full suite.

Add native parse and execution gates for pinned 26.8 and 26.10 engines. Keep ordinary tests self-contained with committed fixtures. State which optional integration tests skipped and why. Remove only task-owned containers/processes. Never execute source SQL through the editor verifier.

Update version/provenance comments, `vendor/README.md`, relevant attribution records, `docs/brikk-extensions.md` and the README target summary. Finish with an itemized coverage/evidence summary and any explicit remaining limitations. Tick this plan's steps as they complete without dropping constraints. Publishing, merging and consumer adoption require separate authorization.
