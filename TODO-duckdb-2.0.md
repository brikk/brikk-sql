# DuckDB 2.0 support checklist

Reconciled on 2026-10-28 against the final 2.0 announcement, migration guide,
documentation index, follow-up Gems post, the user's linked examples and existing
source/tests. The user selected DDB20-001 and DDB20-002 after the planning checkpoint.
On 2026-10-28 the user deferred DDB20-002 until after the remaining syntax work, to keep the
recursive IDE model/native-introspection effort isolated. The user subsequently authorized
continued DuckDB work on `duckdb-2-0-syntax`; focused batches cover DDB20-003 through DDB20-027.
Keep deferred integration/model work separate from syntax and core semantic fixes.

## Baseline and how to use this list

- Engine 2.0.0 LTS, source `d54cbbe3df`; JDBC `org.duckdb:duckdb_jdbc:2.0.0.0`.
- Published SQL libraries `0.20.1`, SQL IntelliJ support `0.2.4`.
- SQLGlot reference recorded by the earlier audit: `v30.22.0-5-ga00dc2e5c`.
- Captured catalog: 1,093 definitions / 2,963 overloads; both 1.5.6 and 2.0.0 dumps remain bundled.
- DuckDB plugin 0.5.1 was prepared for the new EAP ABI requirement. The user reports
  warnings-only verification and is awaiting approval; do not call it Marketplace-approved.

IDs are stable. Do not renumber or reuse them when reordering or completing work.
Each ID has exactly one checkbox definition. References elsewhere are not new tasks.

| State | Meaning |
|---|---|
| DONE | The narrowly stated deliverable exists, with evidence below. Not a claim of complete feature semantics. |
| AUDIT | Existing generic/partial support needs focused proof; change code only when a real gap is found. |
| ADD | Structured support is missing or intentionally opaque; classify/model it before transforming it. |
| MONITOR | Engine/client behavior to document or probe, not automatically a parser change. |
| DEFER | A separate integration/executor workstream, not a prerequisite for core DuckSQL syntax. |

Unchecked items are not all release blockers. In particular, syntax acceptance,
AST preservation, namespace resolution, result semantics, native editor validation
and IDE introspection/completion are separate contracts.

## SQL grammar, names and statement behavior

- [x] `DDB20-001` Nested schemas: core qualification, scope and lineage [DONE].
  Existing tests round-trip `CREATE SCHEMA finance.reports` and
  `CREATE TABLE finance.reports.q3 (...)`; native verification covers the schema DDL.
  Cover the full parent/schema/table sequence from [nested schemas][schemas], reads,
  INSERT/UPDATE/DELETE/DROP, deeper paths, explicit attached-catalog prefixes and quoted
  components containing dots. Do not assume the first of three components is a catalog;
  prove namespace resolution rather than only unchanged generated text.
  `Table.parts` flattens dotted AST components, but `Table.catalog` / `Table.db` still expose
  fixed slots. Audit their scope/lineage consumers before choosing a recursive name model.
  See [Doris lessons for nested schemas](#doris-lessons-for-nested-schemas).
  Implementation now preserves every CREATE/DROP SCHEMA component, resolves deep table/column
  paths in relation scopes/completion, handles deeper optimizer schema mappings and slot bindings,
  and keeps quoted dots distinct in source/lineage keys. DuckDB shape catalogs retain the existing
  uniform-depth contract; other shape dialects retain their previous three-component limit.
  Evidence: DuckdbNestedSchemaTest and DuckdbNestedSchemaResultTest. Native result checks execute
  generated DDL/DML on the final 2.0 JDBC engine, including attached catalogs and quoted dots.
  Nested schemas cannot be a native current namespace in 2.0: USE/SET schema reject them.
  Core fact suffix matching remains the documented provider lookup contract, not proof that an
  arbitrary shortened path is accepted by the engine. The native tests pin rejected leaf-only paths.
  Final module gates passed: 1,261 core tests and 23 DuckDB verifier/native tests, including
  existing corpus/ledger and binary compatibility checks. No ledgers were weakened or refreshed.

- [ ] `DDB20-002` Nested schemas: IDE catalog tree and editing [DEFER].
  User decision: finish the other syntax work first, then revisit this as an isolated effort.
  It is not a gate for syntax fixes. Keep the true hierarchy requirement; no flat-name fallback
  or custom model replacement was approved or implemented in this pass.
  The plugin profile currently declares database/schema levels. Verify how nested schema
  parents arrive through JDBC/native metadata, then test introspection, refresh, picker
  paths, reference resolution, completion and navigation without flattening or losing parents.
  Keep this distinct from DDB20-001; parser support alone does not prove IDE support.
  Reuse the shared catalog kit and Doris regression patterns, not its fixed-depth Ms model
  or MySQL-specific workarounds. Audit shared depth/scope/search-path assumptions described
  [below](#doris-lessons-for-nested-schemas); raising maxDeepenDepth alone is insufficient.
  Partial shared fixes preserve the complete ancestor chain and quoted-dot boundaries in
  DasRelationFacts, and make CatalogSchemaSearchPath refuse a nested path instead of dropping
  its parents. These fixes do not supply recursive introspection or a recursive IDE tree.
  Native 2.0 exposes schema oid/parent_schema_oid and table schema_oid; JDBC returns only leaf
  schema names and can return identical catalog/schema/table triples for different nested tables.
  The plugin's PgSchema has a PgDatabase parent and no child-schema family. A different model
  and native parent-ID-aware introspector are still required before this item can be checked off.
  Preserve ordinary and literally dotted schemas, cached children on failure, session-local
  attachments and current top-level namespace. Do not manufacture USE support for nested schemas.
  Verification of the retained shared safeguards: CatalogSearchPathTest, DasRelationFactsTest and
  InternalApiContainmentTest, 25 tests each on IDEA 261.25134.95 and 263.5701.42. The broader platform
  sweep hit the command's 120-second timeout and is not reported as passed. No Plugin Verifier run.

- [x] `DDB20-003` UPDATE from the same table [DONE].
  Pin the exact [self-update example][update-same], with distinct target/source aliases,
  correlated subquery references and actual resulting rows. Check qualification and lineage
  do not capture the wrong instance of the table. This documented form is not automatically
  a newly introduced 2.0 grammar rule.
  Focused coverage now pins both the documented scalar form and aliased self-FROM form,
  parse/generate/serde and native rows before/after table qualification. DuckDB relation scopes
  now retain the outer target in scalar subqueries, keep inner aliases separate and offer
  assignment/correlated completion. Query-lineage APIs explicitly refuse a root UPDATE instead
  of inventing statement lineage from its last nested SELECT. Full assignment/result-shape
  lineage remains unsupported, not silently inferred.

- [x] `DDB20-004` UPDATE using joins [DONE].
  Basic `UPDATE ... FROM` already passes the native 2.0 test. Pin the [city/country example][update-joins],
  join predicates in WHERE, aliases, unqualified target assignments and unchanged nonmatching rows.
  Separately classify explicit joined FROM sources that the final grammar permits, including
  ambiguity/error cases; do not infer their behavior from the simple example.
  Added exact city/country rows, explicit joined and derived FROM sources, target-only assignment
  lookup, FROM/target expression lookup and scope tests for joins attached to derived sources.
  Native errors pin duplicate aliases, ambiguous RHS columns and qualified SET targets.
  FROM join predicates cannot see the target; RETURNING sees only the updated target, not FROM
  aliases. No UPDATE-to-MERGE or scalar-to-FROM semantic rewrite was introduced.

- [x] `DDB20-005` UPDATE FROM with multiple source matches [DONE].
  [Documented semantics][update-multiple]: each target row is updated once using an unspecified
  matching source row. Assert allowed outcomes, not a particular accidental winner. Compare
  the scalar-subquery cardinality error and `scalar_subquery_error_on_multiple_rows` behavior.
  Native result tests assert one returned/updated target, one increment of its counter, an allowed
  winner among both source values and unchanged nonmatches/source rows. The scalar form errors
  atomically by default; with the compatibility setting false its winner is unspecified, and
  no-match scalars yield NULL rather than UPDATE FROM's unchanged target. MERGE's separate
  multiple-match contract remains DDB20-007.
  Evidence for DDB20-003 through DDB20-005: DuckdbUpdateTest and DuckdbUpdateResultTest.
  Full core and DuckDB verifier/native module gates passed: 1,268 core tests and 28 verifier/native
  tests. Existing corpus, ledger and binary-compatibility tests remain green without ledger changes.

- [x] `DDB20-006` MERGE INTO statement forms [DONE].
  Basic native acceptance exists. Pin the [documented variants][merge]: whole-row UPDATE/INSERT,
  UPDATE SET, ON versus USING(key), conditional/multiple clauses, DELETE, INSERT BY NAME,
  NOT MATCHED BY SOURCE/TARGET and `RETURNING merge_action, *`. Preserve AST, qualification,
  typing and executable output; do not claim all variants from one parser-acceptance test.
  Structured INSERT BY NAME now uses the existing Insert.by_name argument rather than a
  value-shaped expression, with serde/generation preservation and executable refusal for
  unproved non-DuckDB targets. Core relation scopes distinguish matched, source-only and
  target-only branches, SET/INSERT target columns and source expressions. RETURNING exposes
  target fields and the VARCHAR merge_action pseudo-column, not source aliases.
  A real target column named merge_action shadows the pseudo-column, retaining its own type;
  incomplete target facts do not prove an unshadowed VARCHAR. Whole-statement
  result shape and assignment lineage are not claimed; query-lineage APIs refuse the wrong root.
  DuckdbMergeTest and DuckdbMergeResultTest pin the documented people sequence, ON/USING keys,
  whole-row actions, explicit assignments/inserts, reordered BY NAME input, conditional/multiple
  clauses, both unmatched directions, returned actions and native invalid-alias controls.

- [x] `DDB20-007` MERGE multiple-match semantics [DONE].
  Assert the 2.0 error when matched UPDATE/DELETE would affect one target more than once,
  and successful deduplicated controls. Keep this separate from UPDATE FROM's unspecified
  source selection; never transpile one into the other without a semantic warning.
  Native probes distinguish duplicate keys from duplicate actual target changes: a guard that
  leaves only one applicable matched action can succeed. Deduplicated or unique-ON controls
  succeed; matched duplicates with no applicable action or only an INSERT branch need not fail.
  Multiple inserts from unmatched source rows are allowed without a conflicting target constraint.
  Tests require atomic UPDATE/DELETE failures when the same target would change twice, unchanged
  target rows after failure, and exact successful control rows. No global duplicate-source ban or
  UPDATE/MERGE rewrite was added.
  Evidence for DDB20-006 and DDB20-007: DuckdbMergeTest and DuckdbMergeResultTest. Full module
  gates passed with 1,273 core tests and 35 DuckDB verifier/native tests, including the UPDATE
  regression batch and existing corpus/ledger/ABI gates. No ledger changes or IDE model work.

- [x] `DDB20-008` NEAREST joins [DONE].
  Implemented `Nearest` AST, optional k, APPROX/EXACT, distance/similarity, generation and
  serde; malformed forms and non-DuckDB executable refusal have tests. Extend [nearest joins][nearest]
  coverage to aliases/join types, expression precedence, ties, NULL vectors, top-k per left
  row and metric direction. Only assert determinism promised by the engine.
  Native v2.0.0 supports only INNER and LEFT [OUTER] NEAREST joins. The parser now rejects
  unsupported join kinds/methods, mixed ON/USING qualifiers, and non-positive, fractional,
  overflowing or bound counts; default k remains one and positive int64 literal spellings work.
  Quoted exact/approx aliases stay identifiers rather than mode hints, with all NEAREST words
  still usable as ordinary names. Ranking expressions now resolve/complete against both candidate
  sides before LEFT join NULL extension; shape, qualification, serde and physical lineage retain
  the join. Executable generation continues to refuse non-DuckDB targets, with flagged previews.
  Native result coverage checks both vector metrics and scalar rankings, modes on the pinned
  engine, default k, per-left-row top-k and duplicate-row multiplicity, target-only scores,
  expression precedence, target prefilters versus post-ranking WHERE, empty/all-NULL candidates,
  whole NULL vectors and NULL vector-element errors. Ties assert k and allowed candidates, never
  a particular winner. APPROX is informational in this pinned engine, not a future recall guarantee.
  DuckdbNearestJoinTest and DuckdbNearestJoinResultTest compare raw/generated/qualified SQL and
  a separately written native lateral top-k oracle. Source audit: v2.0.0 nearest basic/parser/error
  tests, transform_select.cpp BuildNearestJoin and bind_joinref.cpp BindNearestJoin. No library
  approximation algorithm, portable top-k rewrite, global keyword reservation or IDE model work.
  Final gates passed: 1,280 core tests and 43 DuckDB verifier/native tests, with existing
  corpus, ledger and binary-compatibility checks green. No ledger changes or Plugin Verifier run.

- [x] `DDB20-009` Data-modifying CTEs [DONE].
  The UPDATE RETURNING case already round-trips and passes native acceptance. Cover all
  [documented INSERT/UPDATE/DELETE forms][ctes], DELETE-to-INSERT pipelines, MATERIALIZED,
  RETURNING-dependent scopes/lineage and modification-once semantics. Verify materialization
  dependencies rather than assuming a parsed CTE executes only once.
  Added structured INSERT/UPDATE/DELETE CTE contracts, RETURNING aliases/star fields and types,
  later-CTE/query scope/completion, physical source inventory and schema-driven outer query shapes.
  New INSERT/UPDATE images are computed outputs, not pre-write physical value provenance; direct
  DELETE outputs may retain old-row provenance. Whole assignment lineage and SELECT optimizer/star
  expansion across these producers explicitly refuse rather than tracing an input SELECT as output.
  The actual v2.0.0 engine exposes BIGINT Count without RETURNING; a returning outer INSERT verifies
  that result independently of the JDBC update-count classification. Native tests pin documented
  shipped-row and move-to-archive examples, producer dependencies, duplicate references, unreferenced
  and empty consumers, same-statement visibility, prepared executions and atomic table rollback.
  Default/MATERIALIZED/NOT MATERIALIZED all execute each side-effecting producer once in this engine;
  preserve the user's hint and do not inline, duplicate or eliminate the write. Parser/binder controls
  cover unsupported body types, recursive writes, nesting and views. MERGE remains illegal as a CTE
  body even though it is legal as a standalone statement. Cross-target execution is unproved/refused.

- [x] `DDB20-010` COPY inside CTEs [DONE].
  Explicitly pin COPY bodies and the result exposed to subsequent CTE/query stages.
  This was previously grouped with DML CTEs without a dedicated focused test. Include
  statement boundaries and side effects only in deliberate scratch-engine result tests.
  Fixed COPY parsing at the enclosing CTE parenthesis instead of opaque-command fallback. COPY TO
  table/query bodies and nested COPY-over-statistics stay structured through serde and generation.
  Static output contracts match native Count BIGINT, RETURN_FILES Count/Files VARCHAR[], and all
  seven RETURN_STATS names/types, including UBIGINT count and MAP(VARCHAR, VARIANT) extra_info.
  Explicit false flags and unknown/dynamic flags are distinguished; no guessed full schema.
  Tests write only uniquely created opencode test-temp directories, read the resulting CSV/Parquet
  data and statistics, prove single source evaluation across multiple references with a private
  sequence, retain unreferenced/empty-consumer exports, and rerun prepared parameterized paths.
  COPY FROM, recursive/nested producers and view placement have native error/no-output-file controls.
  Pure analysis and connection-backed parse-only verification leave tables, a sequence and an output
  path untouched. No prepare/execute fallback is introduced into editor validation. JDBC may expose
  COPY's default Count through its update-count channel; RETURN_FILES/RETURN_STATS and returning
  consumers verify actual SQL schemas. File rollback/atomicity is not inferred from table rollback.
  Evidence for DDB20-009/010: DuckdbSideEffectCteTest, DuckdbDmlCteResultTest and
  DuckdbCopyCteResultTest. Full gates passed: 1,287 core tests and 58 DuckDB verifier/native tests,
  including existing corpus/ledger/ABI gates. No ledgers, driver/IDE APIs or connection settings
  changed. Source review: v2.0.0 bind_cte_node.cpp, cte_inlining.cpp, copy_function.cpp and native
  DML/COPY CTE tests; measured JDBC results take precedence over stale no-RETURNING test comments.

- [x] `DDB20-011` GROUP BY UNNEST [DONE].
  Parse/generate and native acceptance exist. Add result and shape tests for the
  [documented grouping form][groupby], repeated/nested expressions, aliases, NULL/empty
  lists and interaction with aggregate outputs. Do not rewrite scalar grouping UNNEST
  into a table source unless that rewrite is independently proved equivalent.
  Focused tests preserve scalar ASTs through copy/serde/qualification, cover alias/ordinal grouping,
  repeated expressions and nested scalar wrappers, and retain computed element provenance rather
  than the list column's direct provenance. Shapes/scopes use element types without assuming
  list nullability proves element nullability. Constant recursive/max_depth options now type
  list-only flattening at the correct depth; unknown options/recursive structs remain conservative.
  Native results pin the documented tag counts, duplicate/NULL elements, NULL/empty lists,
  aggregate input duplication, HAVING, zip/NULL padding of grouped UNNESTs and post-aggregate
  expansion of ungrouped projection UNNESTs. Invalid nested calls, struct expansion, GROUP BY ALL,
  and aggregates/windows in group expressions have native binder-error controls, not a claim of
  complete static grouping validation. Cross-target previews flag, and execution refuses,
  unproved grouping rewrites before projection-to-FROM transforms. Ordinary UNNEST generation is unchanged.

- [x] `DDB20-012` FETCH FIRST / NEXT [DONE].
  Existing generation canonicalizes to LIMIT/OFFSET. Pin [FIRST/NEXT][fetch], omitted count,
  ROW/ROWS, offsets and invalid combinations against the actual engine, plus result controls.
  Round-trip need not preserve FETCH spelling, but must preserve pagination semantics.
  DuckDB-specific parsing now requires FIRST/NEXT and ROW/ROWS ONLY, accepts full count expressions,
  and rejects incomplete clauses, WITH TIES/PERCENT and duplicate pagination instead of repairing
  them. Native tests compare authored SQL and generated LIMIT SQL for both offset orders, omitted
  counts, arithmetic/CASE/subquery counts, NULL/zero/decimal counts, bounds and negative/binding errors.
  CTE/set-operation result tests keep pagination on its own query. Prepared executions use unchanged
  bindings, including numbered parameters. Reordering anonymous OFFSET/FETCH binds is not safe:
  parser metadata preserves their relative order through copy/serde/reparse and DuckDB can emit
  OFFSET ? LIMIT ? instead. Cross-target execution refuses a rewrite that would change bind order.
  FETCH-to-LIMIT conversion also refuses unpreservable WITH TIES/PERCENT from foreign ASTs instead
  of silently dropping the options. Existing other-dialect FETCH parsing remains supported.
  Evidence for DDB20-011/012: DuckdbGroupUnnestTest, DuckdbFetchTest and DuckdbGroupFetchResultTest.
  Full gates passed: 1,297 core tests and 69 DuckDB verifier/native tests, including corpus/serde,
  annotation ledgers and ABI controls. No ledgers or IDE/connection configuration were changed.
  Native reference: v2.0.0 unnest_group_by.test, fetch_first.test and final GROUP BY/LIMIT docs.

- [x] `DDB20-013` JSON mutation functions [DONE].
  All four names are captured; basic calls parse/generate and pass native acceptance.
  Pin [json_set/json_insert/json_replace/json_remove][json], overloads, path/value lists,
  JSONPath/JSON Pointer, missing paths, arrays, duplicate keys, invalid JSON and SQL NULL
  versus JSON null. Audit generic JSON AST nodes before assuming another dialect's semantics.
  Existing JSONSet/JSONRemove/JSONStripNulls and raw call nodes preserve the actual argument/path
  text; no new AST classes or JSONPath reinterpretation are needed. Added pinned builtin signature,
  JSON/VARCHAR return-type and nullability contracts for all eight functions in DDB20-013/014,
  including fixed named-argument roles. Schema-owned UDFs and unmodeled signatures stay unknown.
  Native mutations have one scalar path, not path lists or variadic path/value pairs; values are
  JSON documents, not automatically quoted SQL strings/numbers. Native error controls pin those
  overload limits and malformed JSON/paths, wildcards and invalid options. Positive result tests
  cover missing/existing/nested paths, JSON Pointer escapes, quoted keys, duplicate keys, root
  replacement/removal, zero-based/reverse array indexes, append/end bounds, removal shifts, SQL NULL
  versus JSON null, column paths, named parameters and repeated prepared execution. Same-name
  cross-target previews flag and executable generation refuses unproved translations in either
  direction. Native generation does not silently repair invalid overloads or alter JSON values.

- [x] `DDB20-014` JSON patching and normalization [DONE].
  Audit `json_merge_patch_diff`, `json_deep_merge`, `json_normalize`, `json_strip_nulls`
  and their final overloads. Prove RFC 7396 delete-on-null versus deep-merge skip-on-null,
  recursive object-key sorting with preserved array order, and object-null stripping without
  removing null array elements. Add dedicated nodes only where semantics/typing require them.
  Native tests distinguish deep merge's JSON-null member skipping from merge patch deletion,
  whole-array/scalar replacement, left-to-right variadic patches and SQL NULL intermediate reset
  followed by recovery. Diff tests cover nested changes/removals, unchanged members, arrays/scalars,
  SQL NULL and representable round trips. The docs' unrestricted diff round-trip claim is too broad:
  diff({}, {"a":null}) is {"a":null}, whose merge patch produces {}, not the requested null member.
  Keep that measured counterexample; do not invent an app-side workaround or promise universal
  RFC 7396 representability. Normalization returns VARCHAR, sorts nested objects but not arrays,
  preserves duplicate members and can reserialize numbers. Stripping retains null array elements,
  scalar roots and empty containers while removing null-valued object members recursively.
  Bundled catalog signatures already match the engine and now have exact overload/return tests;
  no generated catalogs or global hazard/null-propagation ledgers were rewritten.
  Evidence for DDB20-013/014: DuckdbJsonMutationTest, DuckdbJsonMutationResultTest and exact
  FunctionCatalogTest overload assertions. Full gates passed: 1,302 core, 115 metadata and 81
  DuckDB verifier/native tests. Existing corpus/annotation/serde/ABI gates remain intact; no
  driver, IDE, connection-setting, publication or consumer-pin changes. Native sources reviewed:
  json_modify.cpp, json_deep_merge.cpp, json_merge_patch_diff.cpp, json_normalize.cpp,
  json_strip_nulls.cpp and their v2.0.0 SQL tests; measured results bound the semantic claims.

- [x] `DDB20-015` VARIANT end-to-end behavior [DONE].
  Type/cast syntax and new function metadata exist. Cover storage DDL, JSON conversion,
  nested extraction, field case, containment/comparison, numeric and missing/null distinctions,
  inferred types, shredding and Parquet round trips. Do not equate VARIANT with textual JSON.
  Added builtin-specific return typing, including scalar/list-path result dimensions, without
  guessing embedded field types. Qualified UDFs and unknown path shapes remain unknown. Dot/bracket
  field outputs retain VARIANT and nullable computed provenance, not the root's physical value.
  Fixed case folding of proven VARIANT fields during qualification, including explicit casts/CTEs,
  and canonical index handling when schema facts reveal a previously unknown input is VARIANT.
  Both native VARIANT brackets and variant_extract integer indexes are one-based; raw field strings
  are not JSONPath. Cross-target VARIANT types/functions explicitly refuse unproved equivalence.
  Native results distinguish VARCHAR-as-value from JSON-as-document, case-sensitive fields,
  missing from VARIANT null via exists/type, numeric buckets and large-integer comparisons,
  containment, list paths, duplicate/null aggregate keys and array-macro modifier restrictions.
  A private database is checkpointed with shredding enabled, closed and reopened; generated COPY
  tests read back default and explicitly shredded Parquet, preserving untyped overlay fields,
  case and null/missing behavior. Tests use only their own unique, cleaned scratch directories.
  Evidence: DuckdbVariantTest and DuckdbVariantResultTest; full gates passed 1,307 core and 87
  DuckDB verifier/native tests with existing corpus/annotation/serde/ABI ledgers unchanged.
  No new AST classes, driver/result rendering, IDE connections or publication changes. JDBC display
  of raw nested VARIANT values is not claimed as a new reader API; result probes use explicit casts.

- [x] `DDB20-016` `$name` variable shorthand [DONE].
  Distinguish session-variable lookup from named/positional prepared parameters and JDBC/IDE
  bind prompting. Cover `$threshold`, `$1`, `?`, dollar-quoted strings, SET/RESET VARIABLE
  and variable scopes. Do not automatically mask or prompt for a session variable as a bind.
  The final engine implements $name as a named parameter with a session default, not a distinct
  getvariable call: unset $name errors while getvariable returns SQL NULL. Preserve that dual
  role and explicit-argument precedence. Existing unquoted Placeholder AST dumps remain compatible;
  quoted dollar names now preserve Identifier quoting. Fixed invalid dollar-tag detection that
  mistook adjacent comma-separated $names for a dollar-quoted string. Exact source occurrences and
  scalar inventories distinguish engine dollar sigils from IDE colon substitutions. The additive
  duckdbSessionDefaultCandidates API reports eligibility only, never variable presence/value/type.
  Default lexical bind discovery/masking does not turn dollar names into prompts or substitutions.
  Numeric names, including quoted numeric $"1", never fall back to variables. Cross-target execution
  refuses unproved session-default semantics, without blocking proven authored :client placeholders.

- [x] `DDB20-017` Session variables as prepared-parameter defaults [DONE].
  Cover PREPARE/EXECUTE with omitted named arguments, explicit-argument precedence,
  execution-time variable changes and missing-variable errors. Keep the engine's fallback
  semantics distinct from the IDE's own parameter substitution.
  PREPARE/EXECUTE/DEALLOCATE and RESET VARIABLE now retain structured bodies, names and arguments,
  allowing exact parameter discovery inside a prepared SELECT/DML body. Existing RESET-setting
  token/AST contracts remain opaque and unchanged; the new PREPARE token is appended to preserve
  old token ordinals and identifier/alias uses. Native tests pin omitted/partial/all explicit
  arguments, execution-time changes/reset, quoted/case-insensitive names, excess argument errors,
  missing/default errors and one deliberate write per EXECUTE. Editor parse/generation/analysis
  and PREPARE itself do not execute the prepared INSERT. Session isolation tests account for JDBC
  duplicate copying the initial variables while keeping subsequent changes local.
  Direct JDBC PreparedStatement is a separate client limit: unbound named statements report a
  parameter-count mismatch; positional setInt is not a named override. SQL EXECUTE fallback works.
  Keep the driver boundary explicit; no bind-time evaluation, automatic prompting, connection
  refresh, retry or native-prepare fallback was introduced into validation.
  Body references are available for analysis, but PREPARE/EXECUTE statement result shapes and
  lineage explicitly refuse rather than being inferred from a nested SELECT. Evidence for
  DDB20-016/017: DuckdbVariablesTest, DuckdbVariablesResultTest and the corrected mask regression.
  Full gates passed 1,312 core, 115 metadata and 93 DuckDB verifier/native tests. The final
  variables/lineage subset also passed after the statement-lineage refusal. Existing generated
  corpora/annotation/serde/ABI gates remain intact; four exact native AST registrations were added,
  not exempted from the Python manifest's existing checks. No IDE connection or publication changes.

- [x] `DDB20-018` Recursive USING KEY aggregate payloads [DONE].
  Generic key-expression AST/generation slots exist. Pin `USING KEY (a, avg(b))`, recurring-table
  references, UNION versus UNION ALL, output types and terminating result probes after
  the recursive execution rewrite. Include ordinary recursive CTE regression controls.
  The actual parser was identifier-only despite the earlier triage. DuckDB now parses full key/
  aggregate expressions and aliased destinations while retaining the old Identifier AST shape of
  plain keys. The documented TABLE cte shorthand is normalized to SELECT * so WITH can attach.
  Scope analysis separates raw UNION ALL candidates from finalized recurring/final CTE rows;
  UNION's working table uses finalized types too. Aggregate arguments bind against CTE aliases and
  raw anchor types, not outer tables or the aggregate result. Known AVG, SUM, MIN/MAX, COUNT, LIST
  and string-aggregate contracts preserve promoted/result types; unknown aggregates/overloads stay
  unknown, never silently inherit the seed type. Recursive row values have computed provenance.
  Only active recursive-member recurring.name references are virtual CTE inputs, not physical
  source tables or ordinary outer/sibling references. VALUES seed rows are typed in this context.
  Shapes use these declarative contracts without executing recursion. SELECT optimizer qualification,
  generic aggregate-state type annotation, lineage and unproved cross-target execution explicitly
  refuse; raw/native generation preserves hints, UNION spelling and aggregate destinations.
  Native tests pin the documented average/maximum, aggregate aliases, list snapshots, SQL result
  metadata and raw-versus-finalized type visibility. A cyclic shortest-path query converges under
  UNION; stable keyed averages terminate when finalized state stops changing. Private sequences
  prove UNION/ALL can have identical final rows but different recursive work, and final draining
  across 2,049 rows does not rerun the recursive build. Invalid key/aggregate overlaps, destinations,
  missing fields and DISTINCT/FILTER/ORDER BY modifiers have binder-error controls. Ordinary
  recursive CTE and recurring-table execution controls remain green. Native queries have deadlines;
  this is not a claim that arbitrary recursive queries terminate or that binding is statically complete.
  Evidence: DuckdbKeyedRecursiveTest and DuckdbKeyedRecursiveResultTest. Full gates passed 1,318 core,
  115 metadata and 102 DuckDB verifier/native tests, including the unchanged generated corpus,
  annotation, serde and JVM compatibility gates. No new AST classes or published data-class fields,
  connection changes, driver workaround or publication. Reviewed v2.0.0 bind_recursive_cte_node.cpp
  and recursive_cte_key_{aggregation,union,snapshot,final_drain}.test alongside the final WITH docs.

- [x] `DDB20-019` CONNECT / DISCONNECT [DONE].
  SessionConnect/SessionDisconnect nodes and simple URI/attached-name/options cases exist.
  Expand final Quack/Postgres/MySQL forms and routing scope; preserve credentials as authored
  data without leaking them in diagnostics. Cross-target generation must refuse unsupported routing.
  Final grammar now distinguishes literal URIs, attached catalog identifiers, LOCAL and quoted
  "LOCAL". Options belong only to string targets and use whitespace, not assignment syntax;
  alias options, numeric/subquery targets and extra DISCONNECT targets are rejected. Quack,
  PostgreSQL/libpq and MySQL strings remain opaque authored data, not normalized connection URLs.
  Bare CONNECT and CONNECT LOCAL parse but are not implemented in native 2.0; executable generation
  explicitly refuses them. Quoted aliases are not accidentally treated as that keyword form.
  Routing has no query-result shape/scope or lineage and is not a SELECT optimizer input. Native
  and cross-target refusals contain no credentials. Parser errors omit routing SQL contexts,
  lexical/editor diagnostics and verifier reasons are redacted, while AST/serde/executable SQL
  deliberately retain the authored data. Raw low-level dumps/native exceptions remain sensitive.
  All verifier channels stay parse-only for routing: even the trusted corpus oracle does not
  prepare/bind CONNECT options. Unsupported non-SELECT serialization is Unverified, not fabricated
  acceptance; private-sequence controls prove option expressions are not evaluated by validation.
  A signed v2.0.0 Quack extension was loaded explicitly from private test scratch, with automatic
  install/load and secret creation disabled. Random loopback ports/tokens exercise authored and
  generated alias/URI routing, independent local/remote variables, persistent named-backend state,
  forwarded CONNECT failure, ephemeral detach/recreation and authentication-failure cleanup. Only
  DISCONNECT returns to local scope; explicit catalog qualification is not assumed to do so.
  The shared-catalog behavior of the in-process fixture is not proof of remote data availability.
  PostgreSQL/MySQL URI grammar is parse-checked without contacting endpoints; those live backends
  are not certified by the Quack result tests. User connections, URLs and grammar preferences stay.
  Evidence: DuckdbRoutingTest, DuckdbRoutingResultTest and DuckdbQuackRoutingResultTest. Full gates
  passed 1,322 core, 115 metadata and 108 verifier/native tests with the loopback fixture enabled,
  zero skips; 29 final focused controls also passed. Generated ledgers and public data-class JVM
  shapes remain unchanged. Opt-in artifact: signed v2.0.0 linux_amd64 quack.duckdb_extension,
  SHA-256 9c893bd543ec2c5253d0c47d5614f90a1bfa1791c735dc6bbcea4e8bd3811a97, downloaded only to
  /tmp/opencode/duckdb-routing-v2. Set BRIKK_DUCKDB_QUACK_EXTENSION to run that optional result test;
  no extension binary, installation, consumer pin or publication is part of this commit.

- [x] `DDB20-020` Trigger contract [DONE].
  Inline DML bodies and simple DROP TRIGGER round-trip; native acceptance is tested.
  Cover BEFORE/AFTER, supported row/statement modes, OLD/NEW transition tables, UPDATE OF,
  multiple triggers, MERGE bodies, RETURNING and dependency/drop behavior. Pin documented
  unsupported combinations as refusals, not assumed PostgreSQL EXECUTE FUNCTION semantics.
  Existing Create/TriggerProperties/Event/Referencing nodes retain inline INSERT/UPDATE/DELETE/
  MERGE actions through copy/serde/generation. Added DuckDB-specific grammar checks: one I/U/D
  event, TABLE AS transition aliases, complete FOR EACH and required DROP TRIGGER ... ON table.
  Qualified attached-table drops now remain structured rather than failing at a schema dot.
  PostgreSQL OR-event/TRUNCATE/WHEN/constraint/EXECUTE FUNCTION and qualified trigger-name forms
  do not silently become DuckDB actions. Explicit generation refusals cover known unsupported
  timing/granularity/transition combinations and temporary triggers, plus unproved cross-target DDL.
  Script snapshots split native single-DML trigger bodies while retaining compound BEGIN/END
  refusal. Scope/completion uses target-column facts for UPDATE OF, supported OLD/NEW row images
  and statement transition aliases only inside the action. These are computed image contracts,
  not pre-write provenance or persisted trigger metadata. Physical source inventory excludes
  transition aliases and the trigger name, but keeps body write targets and the attached table.
  Trigger DDL does not expose its body's result shape/lineage; generic SELECT qualification/type
  rewriting refuses this definition context rather than binding transitions as physical tables.
  Native results pin BEFORE/AFTER observations, empty statement input, duplicate row images,
  old/new UPDATE audit tables, UPDATE OF assignment semantics, trigger creation order, outer
  RETURNING including generated columns, MERGE actions including a same-table action, atomic
  statement/caller rollback, OR REPLACE/IF NOT EXISTS, qualified DROP, and dependency RESTRICT/
  CASCADE. Runtime controls distinguish outer MERGE, row RETURNING, upsert UPDATE triggers,
  mixed granularity, row UPDATE bodies/generated columns/cascades and cross-catalog dependencies.
  Temporary rejection can occur in the native PEG transformer before binding. Pure definition
  analysis and parse-only verification do not register a trigger or consume a body sequence.
  This does not emulate trigger execution or make schema-dependent limitations statically decidable.
  Evidence: DuckdbTriggerTest and DuckdbTriggerResultTest. Full gates passed 1,328 core, 115
  metadata and 118 DuckDB verifier/native tests with the loopback routing fixture still enabled
  and zero skips. The final 16 trigger controls also passed after the NEW.begin field/boundary
  regression. Existing generated corpora, annotation/serde and JVM compatibility gates are intact;
  no new AST classes or public data-class fields. Reviewed the final CREATE TRIGGER docs, native
  create_trigger grammar, bind_create.cpp and trigger/merge/dependency/RETURNING test families.
  No user connection, driver-result workaround, consumer-pin, merge or publication changes.

- [x] `DDB20-021` SECURE VIEW [DONE].
  SecureProperty generation and round-trip exist. Verify its preservation through copying,
  regeneration and relevant catalog/definition paths; add engine controls for information
  hiding. Never silently emit an ordinary view for an executable cross-target translation.
  Audit distinguishes the security boundary from definition secrecy: duckdb_views() and
  information_schema.views still return CREATE SECURE VIEW SQL. DefinitionQueries reads that
  native SQL unchanged; no new IDE catalog adapter or access-control model is introduced.
  Native 2.0 can expose statistics for an unfiltered secure view, since those values are visible;
  filtered/limited/distinct/grouped/joined view statistics omit hidden min/max/distinct facts.
  Logical, physical, ANALYZE and JSON plans retain a secure node without body scans/joins or
  hidden scan counters. SQL-plan export keeps the view reference; it does not inline private SQL.
  Added structured recursive-view modifier and strict modifier/object validation, copy/serde/
  pretty-regeneration checks and hard executable cross-target refusals; another engine's
  same-spelling feature is not a proved semantic translation. Non-recursive body qualification
  preserves SECURE; recursive-view self-reference scope, dependency inventory and optimizer
  qualification/type rewriting explicitly refuse rather than reporting the self-name as a physical input.
  DDL exposes no query-result shape/lineage; authored-body column analysis is not catalog policy.
  Native positive controls cover aliases, OR REPLACE/IF NOT EXISTS, TEMP and default persistent lifetimes,
  stacked/recursive views, safe visible-row predicate evaluation, checkpoint/reopen, export/import
  and read-back/regenerated definitions. Explicit PERSISTENT is rejected: despite its shared grammar,
  the native transformer only supports that keyword for CREATE SECRET. It is not silently normalized.
  Old-format persistent storage fails with the native v2.0.0+ requirement; temporary secure views
  still work there. Parse-only checking creates no view and consumes no sequence in its body.
  This is not a claim of general authorization, definition secrecy, timing-side-channel protection
  or a ban on safe native predicate pushdown. No application-side security emulation is introduced.
  Reviewed final create_view docs and pinned native create_view grammar/transformer,
  catalog/view/secure_view{,_persistence}.test, optimizer/secure_view_{statistics,explain,pushdown}.test
  and sql_export/secure_view_{sql_export,predicates}.test. Existing native definition reader code
  already preserves SQL verbatim; its path is tested instead of adding an ordinary-view rebuild.
  Evidence: DuckdbSecureViewTest and DuckdbSecureViewResultTest. Full gates passed 1,334 core,
  115 metadata and 126 DuckDB verifier/native tests, zero skips with the signed loopback Quack
  fixture still enabled. The new recursive modifier is explicitly registered under brikk.duckdb
  and listed as a native expression; Python manifests/generated corpora and every existing parity
  comparison stay unchanged. No public data-class JVM shape or IntelliJ API changes.
  No user connection, extension installation, consumer pin, merge or publication changes.
  Final 14 focused controls also passed after adding the paired hidden-value cast-error case:
  with join-filter pushdown disabled, the ordinary query errors on the hidden value while the
  secure view returns only allowed rows. Logs: /tmp/opencode/duckdb-secure-view-verified.log and
  /tmp/opencode/duckdb-secure-view-final-controls.log.

- [x] `DDB20-022` Custom extension repositories [DONE].
  Currently opaque. Model final CREATE [OR REPLACE] EXTENSION REPOSITORY, WITH PREFIX,
  PUBLIC KEY(S), DROP, INSTALL/LOAD FROM and INSTALL AND LOAD as applicable. Keep repository
  selection distinct from grammar activation; do not fetch keys/install modules during editor
  analysis, normalize key material unsafely, or expose secrets in logs.
  Added native AST nodes for repository CREATE/DROP, extended INSTALL and LOAD. Simple INSTALL
  retains its earlier manifest-backed AST/serde shape. Final grammar includes optional WITH/USING,
  PUBLIC KEY or KEYS with comma-separated literal strings, IF NOT EXISTS/OR REPLACE exclusivity,
  FROM identifier versus string, INSTALL VERSION, FORCE INSTALL AND LOAD and LOAD AS. Prefix/key
  strings are preserved, including PEM whitespace; static analysis does not validate or normalize
  cryptographic data, fetch a key, read trust files, install/load code or activate a grammar.
  CREATE exposes the native management result (repository_name VARCHAR, prefix VARCHAR,
  key_fingerprints VARCHAR[]), not query/table lineage. Other actions have no query output shape;
  SELECT qualification, type rewrites of new nodes and unproved cross-target management refuse;
  simple INSTALL's existing literal annotation is preserved along with its exact serde. Registration
  leaves the native active-grammar list unchanged. Repository selection is not a grammar preference.
  Parser/editor/verifier diagnostics redact repository details. Raw AST/serde/emitted SQL and native
  execution exceptions remain sensitive. Even the trusted corpus verifier never prepares/binds
  management statements; serializer limitations remain Unverified, and a prepare spy plus private
  filesystem controls show validation writes no trust/extension files or registration state.
  Native results pin fingerprint arrays/types, RSA/PEM normalization by the engine only, multiple
  pinned keys, replacement/existence/drop, local metadata fetching and pin-once persistence across
  instances. Runtime undecided/allowed/forbidden policy and configuration lock checks remain native.
  Signed private repository tests pin real install/load with signatures enabled, per-repository
  layout/bare-load isolation, existing-key loads under undecided, wrong-key/origin rejection and
  fresh-instance key rotation/forbidden trust. Quoted FROM in INSTALL AND LOAD really can install
  then fail to load; no rollback, identifier rewrite, retry or application-side trust emulation.
  Opt-in fixture re-signs only a private inet.duckdb_extension copy with ephemeral RSA keys and
  writes only its own trust/extension directories. It does not contact a network repository,
  change user trust, disable signatures or certify that revocation unloads already-resident code.
  Final documentation: extensions/extension_repositories and sql/statements/load_and_install.
  Reviewed pinned load.gram/transform_load.cpp/bind_load.cpp/physical_load.cpp, repository manager
  and native extension_repositories{,_lock}, repository_not_autoloaded and install_and_load tests.
  Evidence: DuckdbExtensionRepositoryTest, DuckdbExtensionRepositoryResultTest and
  DuckdbSignedRepositoryResultTest. Full gates passed 1,340 core, 115 metadata and 135 DuckDB
  verifier/native tests with both signed-repository and loopback Quack fixtures enabled, zero
  skips. Existing Python corpora, annotated-serde manifests/ledgers and public data-class JVM
  signatures remain unchanged; four native nodes are explicitly registered under brikk.duckdb.
  Opt-in source fixture was the existing v2.0.0 linux_amd64 inet.duckdb_extension, SHA-256
  b1e8776d27d351eceec714c958304eb9011b27a181a4f182e9370a12b939d2c6. Set
  BRIKK_DUCKDB_REPOSITORY_EXTENSION to a trusted matching file to run those signed-binary controls.
  No source binary, user connection/store, grammar preference, IntelliJ API, driver-result
  workaround, consumer pin, merge or publication was changed.
  Final 15 focused controls also passed, including direct LOAD with a string repository and
  parse-only native controls for LOAD aliases and versioned installs. Evidence logs:
  /tmp/opencode/duckdb-repositories-full-final.log and /tmp/opencode/duckdb-repositories-final-controls.log.

- [x] `DDB20-023` External-resource statements [DONE].
  Currently opaque. Classify/model final CREATE/REGISTER/SHOW/CONNECT TO/DESTROY EXTERNAL
  RESOURCE forms, resource types, handles and options; check their statement boundaries,
  generation and cross-dialect refusal. This is not an authorization to create real resources.
  Added structured CREATE/REGISTER/DESTROY/SHOW [ALL] and ATTACH/CONNECT TO statements, with a
  distinct NEW TEMPORARY versus borrowed source node. Types remain literal strings, names quoted
  identifiers, handles arbitrary authored expressions and option bags separate. Native mandatory
  attachment aliases and the CONNECT temporary second-bag prohibition are enforced. CREATE and
  REGISTER without a name remain parseable but executable generation refuses the native binder
  error; no alias, ownership rule, handle conversion or provider-specific schema is invented.
  SHOW has the fixed native eight-column contract (name/type/managed/state/reference/uri/
  attached_db_type/handle), computed provenance and an opaque MAP(VARCHAR, VARCHAR) handle.
  SHOW ALL permits unnamed discovered rows and invokes discovery callbacks; it is not a local-only
  catalog read. Other statements have no query output shape. SELECT optimizer rewrites, lifecycle
  lineage, handle-subquery dependencies and unproved cross-target execution explicitly refuse.
  Parser/editor/verifier diagnostics redact resource details, not the AST/serde/emitted SQL.
  Raw native execution errors and listing values can expose handles/URIs/tokens. All verifier
  channels stay parse-only for resource statements and recognized APIs, including quoted API calls;
  unsupported non-SELECT serialization stays Unverified, never a prepare/EXPLAIN execution fallback.
  Native prepare spies, registry/type-list snapshots and unused sequences show validation invokes
  no create/status/destroy/list callbacks and never evaluates option/handle expressions. Native
  control prepares in an explicit test transaction do evaluate nextval at binding; in autocommit,
  pinned JDBC 2.0 can instead raise its internal read-only-but-changed transaction error. The
  invalidated control database is discarded; there is no production retry or transaction workaround.
  Native execution fixtures are SQL-only mock recipes and owned scratch DuckDB files, not real
  external resources. They pin callback counts, REGISTER skipping create, duplicate-name checks,
  discovery union/deduplication, quoted names, borrowing versus temporary ownership, post-create
  failure cleanup, supplied-option collisions and failed teardown retaining registration until
  explicit repair. CONNECT cleanup uses the local backend's unsupported-routing error; no live
  provider/remote CONNECT, cloud API, billing, polling deadline or general teardown is certified.
  Reviewed pinned external_resource{,_source}.gram and transformers, bind_external_resource.cpp,
  bind_attach.cpp, physical_external_resource.cpp, external_resources.cpp and the lifecycle,
  discovery, attach/failure, option-collision and round-trip native tests. No dedicated final
  public documentation page was found; metadata uses null links instead of inventing a URL.
  Evidence: DuckdbExternalResourceTest and DuckdbExternalResourceResultTest. Full gates passed
  1,346 core, 115 metadata and 145 DuckDB verifier/native tests with both earlier signed fixtures
  enabled, zero skips. Seven native nodes are explicitly registered under brikk.duckdb; existing
  Python corpora, annotation/serde ledgers, public data-class JVM signatures and IntelliJ APIs
  remain unchanged. No user connection, real provider/resource, installation, billing action,
  consumer pin, merge or publication changes. Full log: /tmp/opencode/duckdb-external-resources-full.log.
  Final 16 focused controls also passed, including parameter order across the two option bags and
  quoted API-call no-bind coverage. Log: /tmp/opencode/duckdb-external-resources-final-controls.log.

- [x] `DDB20-024` EXPLAIN (SQL) and explain options [DONE].
  Native grammar accepts it, while brikk preserves an opaque command. Introduce structured
  options before inspecting/transforming them; include FORMAT/ANALYZE option interactions.
  Do not introduce EXPLAIN as an editor-validation fallback because binding can have side effects.
  Added native EXPLAIN/option nodes with ordered options and a retained child statement. A local
  lexer overlay keeps EXPLAIN tails tokenized for exact parameter intervals rather than treating
  every operand as an opaque command string. Copy/serde/regeneration preserves options and bind
  order. Prefix/unquoted-option ANALYSE canonicalizes to ANALYZE, not quoted "ANALYSE", whose
  native meaning is different. Existing raw child families stay inspectable but execution,
  dependency inference and uncertain parameter binding explicitly refuse.
  Native ANALYZE option presence always enables execution, even with false/0; its argument is
  ignored rather than evaluated. Repetition does not run the child twice. SQL is an argument-less,
  single option and cannot coexist with ANALYZE or FORMAT. FORMAT constants/identifiers/DEFAULT
  stay structured; duplicate/missing/dynamic/unknown values and unknown options are not silently
  dropped or normalized into another mode. WEB requires a client renderer and is not statically
  certified; no browser/UI is opened. Unsupported SQL-export child families explicitly refuse.
  The result contract is two computed VARCHAR columns, explain_key/explain_value, even for JSON
  or SQL output. Child scopes/dependencies are distinct from format-name expressions and from
  plan-text lineage. Generic wrapper qualification/type rewrites and cross-target plans refuse.
  Native controls cover renderer strings, explain_output row counts, SQL query/DML export,
  explicit execution of exported SQL, ignored ANALYZE expressions, exactly-once DML analysis,
  and COPY writing only an owned scratch file under ANALYZE. Standard/SQL EXPLAIN may still
  evaluate table-function arguments at binding; volatile scalar output alone is not executed.
  All verifier channels stay parse-only for EXPLAIN, including the trusted corpus oracle;
  transformer option errors are not mislabeled parser errors. Prepare spies, untouched sequences
  and unchanged tables prove validation never executes a wrapper or binds its child. Existing
  prepareTextExplain DuckDB refusal stays intact; no EXPLAIN-based validation fallback was added.
  Reviewed final guides/meta/explain, guides/meta/explain_analyze and dev/profiling documentation,
  pinned explain.gram/transform_explain.cpp/bind_explain.cpp and explain/SQL-export native tests.
  Evidence: DuckdbExplainTest and DuckdbExplainResultTest. Full gates passed 1,352 core,
  115 metadata and 153 DuckDB verifier/native tests with both prior signed fixtures enabled,
  zero skips. Two native nodes are explicitly registered under brikk.duckdb; generated tokenizer
  tables/corpora, annotation/serde ledgers, public data-class JVM shapes and IntelliJ APIs remain
  unchanged. No user session, driver-result workaround, consumer pin, merge or publication changes.
  Full log: /tmp/opencode/duckdb-explain-full-final.log.
  Final 14 focused controls also passed, including ignored option-subquery scope fencing under
  PREPARE, exact parameter discovery and unquoted ANALYSE canonicalization. Focused log:
  /tmp/opencode/duckdb-explain-final-controls.log.

- [x] `DDB20-025` OVERLAY [DONE].
  Generic parser/AST support exists. Pin DuckDB spelling, generation, signature, NULLs,
  offsets/lengths and actual string results before claiming a verified cross-dialect mapping.
  Final documentation and pinned overlay.test/overlay.cpp confirm keyword and comma forms,
  3/4-argument VARCHAR/VARCHAR/BIGINT[/BIGINT] signatures and VARCHAR output. The generic Overlay
  AST/serde shape is retained; the DuckDB parser now rejects mixed forms, accepts full operand
  expressions and preserves wrong-arity comma calls without truncation so binding errors remain
  distinguishable from syntax errors. Bare named arguments are natively rejected, but quoting
  the ordinary function name admits them. Those calls retain quoting and authored order; argument
  roles are matched only for static return/NULL facts, never reordered in emitted SQL.
  Fixed DuckDB typing that formerly inherited the first operand's type: successful OVERLAY is
  VARCHAR even for a NULL source. Nullability considers every present operand; explicit NULL
  count is not omitted count. Negative/omitted count uses replacement code-point length, zero
  inserts, past-end starts append and zero/negative starts affect the replacement end independently
  from the clamped prefix. UTF-8 code points, not bytes or graphemes, determine positions/lengths.
  Native controls cover combining marks/emoji, BIGINT extremes, empty strings, all NULL positions,
  vectorized nullable data, parser versus binder failures, prepared binds and volatile operands.
  No substring/concat or default-count expansion duplicates replacement evaluation. Symbolic
  scopes/shapes, lineage and completion retain ordinary column argument behavior and computed
  provenance. Qualified UDFs do not inherit the builtin contract. Unverified same-name cross-target
  transformations refuse in both directions; no non-DuckDB backend equivalence is certified.
  Exact bundled/native overload comparison and older-capture absence are tested without refreshing
  generated metadata/corpora or changing the public Overlay shape. Final docs: sql/functions/text#overlay;
  native evidence: test/sql/function/string/overlay.test and src/function/scalar/string/overlay.cpp.
  Evidence: DuckdbOverlayTest, DuckdbOverlayResultTest and DuckdbOverlayMetadataTest. Full gates
  passed 1,358 core, 116 metadata and 159 DuckDB verifier/native tests with both signed fixtures
  enabled, zero skips. No new AST nodes or public data-class fields; existing generated catalogs,
  corpus/annotation/serde ledgers, JVM signatures and IntelliJ APIs remain unchanged. No user
  connections, installations, consumer pins, merge or publication changes.
  Full log: /tmp/opencode/duckdb-overlay-full.log.
  Final 13 focused controls passed. Log: /tmp/opencode/duckdb-overlay-final-controls.log.

- [x] `DDB20-026` COPY partitioning and ORDER_BY [DONE].
  Use the actual option names `PARTITION_BY` and `ORDER_BY`, not only generic ORDER BY text.
  Pin multiple partition columns, ORDER_BY alone and together with partitioning, expressions,
  ASC/DESC/NULLS ordering, option placement and the rows written within each partition.
  DuckDB-specific COPY options now retain structured parenthesized ORDER_BY expression
  terms using the existing Copy/CopyParameter/Tuple/Ordered nodes. Underscore option names
  and the native spaced/partitioned aliases canonicalize to PARTITION_BY/ORDER_BY without
  moving options or operands. Explicit ASC/DESC/NULLS flags survive copy/serde/generation;
  omitted direction and NULL order remain session-resolved, not frozen to library defaults.
  PARTITION_BY selects COPY output columns, including projected aliases and multiple keys;
  arbitrary row partition expressions must be projected first, not invented as native support.
  Symbolic scopes/completion bind ordinary columns and ordering expressions against that
  output row, not the SELECT source namespace. Textual order SQL/dynamic or qualified partition lists
  explicitly refuse symbolic scope; automatic qualification and cross-target execution
  refuse unmodeled layout transformations. COPY CTE result shapes remain Count/Files/stats,
  with computed provenance and no fabricated exported-row lineage.
  Native controls inspect physical Parquet/CSV rows, expressions, collations, NULL keys,
  omitted versus explicitly written partition columns, session defaults, parallel writes
  and file rotation. ORDER_BY alone writes a directory even for a .parquet/.csv target name;
  sorting is within each file, not a global file order or stable-tie guarantee. Prepared
  source/destination/order parameters and volatile source/order operands pin bind order,
  repeated execution and no library-added duplicated evaluation. Native source expressions
  run once per row in the fixture, but the native partitioned sorting plan evaluates a
  volatile order key twice per row; no exactly-once ORDER_BY guarantee is claimed.
  Invalid duplicates/modifier placement
  remain parser errors; missing columns, source qualifiers, ordinals, partition expressions
  and ORDER_BY subqueries are native binding failures before any destination is opened.
  Empty ORDER_BY is statically refused although native COPY initially parses it and its
  order-list parser fails later at binding. Native json_serialize_sql cannot serialize COPY;
  this remains parse-only verifier evidence, never EXPLAIN/prepare fallback or execution.
  Final docs: sql/statements/copy#copy--to-options and
  data/partitioning/partitioned_writes#sorting-rows-within-partitions. Pinned implementation: generic
  COPY-option transformer, bind_copy.cpp and bind_helpers.cpp; tests include
  copy_column_identifier_conversion.test, copy_order_by_collation.test,
  copy_order_by_subquery.test and partitioned/partitioned_order_by.test.
  Focused evidence: DuckdbCopyLayoutTest and DuckdbCopyLayoutResultTest, all 16 controls
  passed with fresh build outputs in /tmp/opencode/duckdb-copy-layout-build. This avoids
  the original build directory's stale DuckdbGenerator class observed during incremental
  testing. Log: /tmp/opencode/duckdb-copy-layout-clean-focused.log. No new AST node or
  public data-class field, generated catalog/corpus refresh, ledger weakening, IntelliJ
  API use, user connection/installation changes, consumer pins, merge or publication.
  Final full gates passed 1,365 core, 116 metadata and 168 DuckDB native/verifier tests,
  zero skips with both signed fixtures enabled. Final source compiled from scratch under
  /tmp/opencode/duckdb-copy-layout-verified-build. Ordinary COPY option AST shapes and all
  original serde/annotation/corpus ledgers pass unchanged. Full evidence log:
  /tmp/opencode/duckdb-copy-layout-verified-full.log. PARTITION_PATH was left to DDB20-027.
  Final 16 focused controls passed again. Log: /tmp/opencode/duckdb-copy-layout-final-controls.log.

- [x] `DDB20-027` Custom PARTITION_PATH [DONE].
  Cover CASE/function expressions, requirement for PARTITION_BY, incompatibility with
  HIVE_FILE_PATTERN, relative/nonempty paths, rejection of `..`, and distinct paths per partition.
  Include `hive_partition_component` and `path_join` metadata/result controls and NULL encoding.
  Targeted COPY parsing retains full CASE/function/concatenation/scalar path expressions in
  the existing CopyParameter value, without changing ordinary option ASTs. Missing expressions,
  parenthesized expression lists/order modifiers and duplicate options reject statically.
  PARTITION_PATH joins the existing qualification/cross-target file-layout refusal guard.
  Symbolic scopes and completion use only ordinary unqualified PARTITION_BY output columns;
  other exported columns are absent, and known aggregates/windows/subqueries/lambdas or qualified
  column references refuse rather than pretend to be ordinary row inputs. COPY CTE results
  retain computed Count/Files/statistics contracts, not exported-row lineage.
  Existing metadata contains both exact helper signatures. DuckDB-only typing/NULL evidence
  adds VARCHAR for modeled unqualified helper calls: hive_partition_component encodes NULL
  values to its default marker, escapes literal marker collisions and stringifies even a NULL
  name, while path_join propagates SQL NULL across all supplied components. Qualified UDFs and
  unmodeled signatures remain unknown. No same-name cross-target helper equivalence is claimed.
  Final docs: data/partitioning/partitioned_writes#custom-partition-paths. Pinned source/tests:
  generic COPY option transformer, PartitionPathBinder, physical_copy_to_file.cpp,
  partitioned/partition_path.test and partition_path_json.test, path_join.cpp/.test and
  hive_partition_component.cpp/.test. Native controls exercise case/nested/numeric/struct
  expressions; partition-only binding; HIVE_FILE_PATTERN true/false conflicts; NULL/empty,
  absolute/remote and parent-segment path errors; normalized collisions, nested/prefix paths;
  and native helper/Hive escaping including Unicode and NULL-marker value collisions.
  Paths are not statically evaluated, normalized or converted with host-language helpers.
  Runtime failure may leave earlier partition output; no retry or rollback emulation is added.
  Empty sources evaluate no path expression, including volatile/NULL/parent-segment cases.
  Pinned JDBC classifies the tested empty COPY CTE plus outer SELECT as update count zero,
  not a ResultSet. Controls execute it once without retry; no production workaround is added.
  Evidence: DuckdbPartitionPathTest, DuckdbPartitionPathResultTest and
  DuckdbPathFunctionsMetadataTest. Full gates passed 1,371 core, 117 metadata and 180
  DuckDB native/verifier tests with both signed fixtures enabled, zero skips. Production
  sources compiled fresh in /tmp/opencode/duckdb-partition-path-verified-build. Existing
  generated catalogs/corpora and all serde/annotation ledgers remain unchanged. No new
  AST classes or public data fields/JVM shape changes, IntelliJ API calls, user connection
  or installation changes, consumer pins, merge or publication. Full log:
  /tmp/opencode/duckdb-partition-path-final-full.log.
  Final 19 focused controls passed again. Log: /tmp/opencode/duckdb-partition-path-final-controls.log.

- [ ] `DDB20-028` SHOW configuration settings [AUDIT].
  Pin `SHOW threads`, table-name collisions and `show_behavior` auto/table/setting.
  Preserve the setting operation rather than blindly treating SHOW as DESCRIBE table.
  Record the 2.0 deprecation of SHOW for describing tables/views/queries.

- [ ] `DDB20-029` setval [AUDIT].
  Function metadata already explicitly asserts setval exists. Pin overloads, default and
  false `is_called`, nextval/currval effects, increments and scope. Execute sequence probes
  deliberately in isolated tests; never invoke them to supply editor metadata or validation.

- [ ] `DDB20-030` Regular-expression function additions [AUDIT].
  Pin the third-argument options overload `regexp_extract('ABC', 'abc', 'i')`, ordinary
  group/options forms, named captures and list/struct results of regexp_extract_all where
  documented. Check overload selection, return types and invalid replacement handling.

- [ ] `DDB20-031` Window functions as catalog entries [AUDIT].
  DuckdbDump already maps native `window` to FunctionKind.WINDOW. Verify captured built-ins,
  live extension-defined windows, OVER-context completion and scalar/aggregate/window name
  distinctions. Do not lose window rows or assume every window function is an aggregate.

- [ ] `DDB20-032` Parquet schema parameter and virtual columns [AUDIT].
  Pin the documented `schema` argument, field-ID/name/default/type shape, named parameters,
  and `file_row_group_number` metadata/typing. Generic function-call parsing is not proof that
  catalog completion or inferred output columns are correct.

- [ ] `DDB20-033` Parquet writing options [AUDIT].
  Classify the 2.0 documented writing/encoding/compression options and COPY value shapes,
  including VARIANT output and the release-note `data_page_size_limit` option. Verify metadata
  and syntax only for engine options that exist;
  filesystem/result probes belong in isolated scratch directories, not editor analysis.

- [ ] `DDB20-034` GeoJSON import/export SQL [AUDIT].
  Pin COPY FORMAT GEOJSON/GEOJSONL, ID_COLUMN/GEOMETRY_COLUMN and documented read options.
  Check GEOMETRY typing and extension availability; do not execute files/network reads to bind
  an editor fragment. Generic COPY/function support may already cover syntax.

- [ ] `DDB20-035` Other core-extension SQL additions [MONITOR].
  Triage the final DuckLake 1.1, Iceberg REST ATTACH options and Delta CREATE TABLE,
  partition/property/time-travel/VARIANT syntax for effects on generic ATTACH/DDL/type models.
  Register concrete gaps rather than porting those execution engines or reserving their keywords globally.

## Migration and changed SQL semantics

- [x] `DDB20-036` Python-style lambda generation [DONE].
  DuckDB output always uses LAMBDA ... : ...; legacy arrows remain migration input.
  Evidence: Duckdb20SyntaxTest, corpus expectations and native tests. Keep JSON `->` separate.
  The 2.1 removal of single-arrow compatibility is future-version work, not a reason to reject
  useful old input prematurely while targeting 2.0.

- [ ] `DDB20-037` Regex operators `~` and `!~` [AUDIT].
  The migration guide changes them to partial matching: `'abc' ~ 'b'` is true.
  Verify AST/function lowering, generated SQL and cross-dialect hazards, including the deprecated
  `regex_match_operator_semantics = 'full'` compatibility setting. Do not preserve old full-match semantics accidentally.

- [ ] `DDB20-038` Factorial precedence [AUDIT].
  Pin AST, generation and engine results for `2 * 3!` (12 in 2.0) versus `(2 * 3)!` (720),
  with surrounding unary/binary operators. The old parser's precedence is not the 2.0 contract.

- [ ] `DDB20-039` X/B literal types [AUDIT].
  Verify `X'...'` is BLOB and `B'...'` is BITSTRING, not old VARCHAR text with an x/b prefix.
  Cover tokenizer, AST/serde, value/type preservation, casts, escapes and cross-target output.

- [ ] `DDB20-040` Untyped NULL columns [AUDIT].
  Pin `CREATE TABLE ... AS SELECT NULL` retaining NULL type, subsequent non-NULL insert errors,
  explicit cast controls and `legacy_disable_null_type`. Check inferred schemas and nullability;
  do not automatically substitute INTEGER based on pre-2.0 engine behavior.

- [ ] `DDB20-041` Identifier case setting [AUDIT].
  `preserve_identifier_case` now reports preserve_case/lowercase/uppercase, not BOOLEAN;
  true/false remain compatibility inputs. Test all modes through catalog harvesting,
  normalization, quoting, alias/reference resolution and relevant local-binding behavior.

- [ ] `DDB20-042` Division/modulo by zero [AUDIT].
  Existing native tests pin integer // and % errors, TRY-to-NULL and NULL operands.
  Extend to `error_on_division_by_zero=false`, ordinary division returning infinity and typed
  cross-dialect/safe-division lowering. Do not mechanically change expected rows to hide a semantic mismatch.

- [ ] `DDB20-043` Native ICU replacement [AUDIT].
  Probe timezone-aware timestamps, DST gaps/overlaps, historical zones, calendar behavior,
  locale collations and invalid names. Recheck precision/type effects including TIMESTAMPTZ_NS
  and affected cross-engine hazards rather than assuming all old ICU findings still apply.
  Include collations propagated into lists and list_contains/list_position, not just scalar text.

- [x] `DDB20-044` Other observed 2.0 result changes [DONE].
  Existing result tests pin CONCAT_WS list flattening, valid UTF-8 byte escapes versus rejected
  NUL escapes, and changed comprehension/nested-lambda binding behavior. TargetTypingResultTest
  also pins the observed timezone attachment result type. This is limited evidence, not full DDB20-043 coverage.

- [ ] `DDB20-045` Removed/deprecated configuration and profiling SQL [AUDIT].
  Reconcile settings metadata and SQL forms for removed custom_profiling_settings,
  enable_http_logging/http_logging_output, replacement tracked_metrics/enable_logging('HTTP'),
  and profiling_mode having no effect. Check new async/read-ahead settings and relevant
  PRAGMA/EXPLAIN rendering options without implementing the I/O/profiler engines.

- [ ] `DDB20-046` S3 URL-parameter change [AUDIT].
  2.0 treats `?` and following text as part of the object key unless the deprecated
  `s3_enable_deprecated_url_parameters` behavior is explicitly enabled. Review path literals,
  documentation/examples and credential handling; use secrets and never log credential-bearing URLs.

- [ ] `DDB20-047` Storage compatibility and database fixtures [MONITOR].
  New databases default to storage version 69; old engines cannot read them. Existing files
  keep their version. Pin relevant ATTACH STORAGE_VERSION / storage_compatibility_version
  syntax if exposed. Never migrate a checked-in or user database implicitly; compare engine
  versions with separate scratch files/processes and document compatibility expectations.

- [x] `DDB20-048` Calculator/preview expression-statement scope [DONE].
  Correct the former blanket "not shipped" conclusion: the 2.0 Gems post documents a CLI
  calculator, implemented through shell grammar customization. This does not establish a core
  JDBC DuckSQL expression-statement entry point. Keep CLI-only syntax and dot commands out
  of the static core dialect unless an explicit future scope decision changes that boundary.

## Parser/oracle and cross-dialect acceptance work

- [ ] `DDB20-049` Full 1.5.6-versus-2.0 acceptance comparison [AUDIT].
  Compare parser, generator, tokenizer and AST corpora in isolated engine processes, recording
  actual engine/JDBC/source IDs and settings. Investigate acceptance changes; never automatically
  add ledger entries or mix two incompatible native engine versions in one test environment.

- [ ] `DDB20-050` Every applicable corpus through native 2.0 [AUDIT].
  The normal suites and focused release examples ran on 2.0, but that does not prove every
  identity/transpile/annotated-serde case has a native oracle gate. Inventory actual coverage,
  run missing native checks and retain explicit unsupported/opaque classifications.

- [ ] `DDB20-051` PEG ambiguity/precedence differential cases [AUDIT].
  Cover prefix aliases, FROM-first, simplified PIVOT, GROUP/ORDER BY ALL, star EXCLUDE/REPLACE,
  JSON arrows versus lambdas, parameter markers, explicit/internal join types and nested parentheses.
  Include backtick identifier and star EXCEPT-to-EXCLUDE regression controls already implemented.

- [ ] `DDB20-052` Malformed-input robustness [AUDIT].
  Pin invalid/backtracking-heavy queries, long unmatched parentheses, depth/size/time limits,
  Unicode and prompt cancellation. Confirm the native process cannot hang/crash the IDE;
  a limit/transport failure is unavailable/unverified, not a syntax acceptance.

- [ ] `DDB20-053` Grammar built-ins and special forms [AUDIT].
  DuckdbGrammarBuiltins already claims 2.0 probing, but retains a stale Postgres-derived-parser
  comment. Reconcile that provenance and add repeatable proof of registered versus grammar-only
  calls, bare-only spellings and binder-restricted GROUPING forms. Do not claim no work was done.

- [ ] `DDB20-054` Upstream SQLGlot reconciliation [AUDIT].
  Compare the current pinned reference with upstream 2.0 support before implementing local
  equivalents. Port upstream where available, retain provenance and record only still-needed
  Brikk-specific behavior. Do not refresh unrelated dialects opportunistically.

- [ ] `DDB20-055` Cross-dialect executable behavior [AUDIT].
  For new semantic nodes/options, verify a faithful mapping or an explicit unsupported result
  for each supported target. NEAREST already has a refusal regression; extend the matrix where
  unproved. Anonymous function-name preservation is not proof of equivalent JSON/VARIANT semantics.

- [ ] `DDB20-056` Final release-note and acceptance closure [AUDIT].
  Triage the full v2.0.0 release-note PR list for additional user-visible SQL/type changes not
  covered by the docs index. Engine bugfix/optimizer PRs are not automatically new parser tasks.
  Record exact covered/deferred items, run final applicable gates, review actual ledger diffs
  and preserve reasons. Never make a blanket parity claim from a green subset or preview post.

## Metadata, reproducibility and published evidence

- [x] `DDB20-057` Final engine pin [DONE].
  JDBC 2.0.0.0, v2.0.0 source d54cbbe3df and ordinary native parser tests are pinned.
  The current capture path is JDBC-based; installing a matching Python package is not required.
  Any optional Python oracle introduced later must record and match its engine version.

- [x] `DDB20-058` Base 2.0 catalog generation [DONE].
  Vendored functions, keywords, settings, types, extensions and extension-function rows feed
  GeneratedDuckdb_2_0_0.kt / GeneratedDuckdbDumps.kt. Earlier captures remain available.
  This replaces the obsolete unchecked instruction to generate from an installed Python engine.

- [ ] `DDB20-059` Hermetic future captures and extension policy [AUDIT].
  DuckdbCaptureTest now isolates its extension directory so user-installed psql does not alter
  the baseline. The capture command still opens ordinary engines and explicitly INSTALL/LOADs
  CAPTURED_EXTENSIONS. Make future refreshes hermetic too, record exact extension versions and
  review whether any old extension-function rows were reused instead of freshly captured.

- [ ] `DDB20-060` Richer function registry metadata [AUDIT].
  Inspect final schema/defaults/named arguments/null handling/stability/side effects/table results.
  Extend FunctionDef only where engine evidence supports it, preserving binary compatibility.
  Do not infer null semantics or positional/named splits from misleading parameter names.
  Also classify the new duckdb_settings() `typed_value` column: current connection values
  must not be baked into the static settings catalog as universal defaults.

- [x] `DDB20-061` Basic catalog shape assertions [DONE].
  FunctionCatalogTest pins lambda signatures, new JSON/VARIANT names/kinds, setval and native
  collations; DuckdbDump maps window rows. DuckdbCaptureTest compares bundled/vendor/native
  rows. These assertions do not substitute for the feature result probes above.

- [ ] `DDB20-062` Precise new function typing [AUDIT].
  Review JSON/VARIANT family additions and actual overload/default/variadic/result shapes,
  including BOOLEAN results, key lists and extracted values. Keep function catalogs, type
  inference, docs and live completion consistent; do not claim full end-to-end typing from names alone.
  The full-release title audit also names decimal_average, binom, lttb, invoke, assert_true,
  vertex_extract and aggregate-state export additions. Check their actual captured signatures,
  kinds and required semantic models; ordinary function calls do not automatically need new AST nodes.

- [ ] `DDB20-063` Function gap reports and semantic hazard refresh [AUDIT].
  Update affected DuckDB/Trino/Doris/ClickHouse findings using live evidence at recorded versions.
  Cover changed JSON/VARIANT, temporal, regex and numeric behavior. Regenerate registries only
  after reviewing evidence; do not relabel historical probes as 2.0 or overwrite old hazard versions.

- [x] `DDB20-064` Formal library publication and dependency pin [DONE].
  SQL 0.20.1 and SQL IntelliJ support 0.2.4 are on Central. Formal candidate/public POM/module
  checks and the plugin's fresh Central-only resolution passed; no SNAPSHOT/local-only dependency
  is needed. Keep README, POMs, driver defaults, attribution and actual tested engine versions aligned.

## Native grammar/editor boundary and deferred client work

- [x] `DDB20-065` Parse-only JSON channel and PEG error locations [DONE].
  Both restricted standalone and connection-backed verifiers use json_serialize_sql without
  binding/executing editor SQL. SELECT serialization and parser rejections are handled; unsupported
  serialization stays unverified. UTF-8 start/length and legacy code-point error locations are tested.
  Prepare fallback belongs only to the trusted corpus oracle, never the editor path.

- [x] `DDB20-066` Real runtime-extension smoke test [DONE].
  Signed psql on DuckDB 2.0 was exercised through the plugin with Brikk PIPE off: active acceptance,
  inactive/malformed rejection, actual keyword lookup/insertion, unchanged source activation and
  an untouched sequence. Native autocomplete is separate from grammar activation and currently
  requires explicit installation/loading. No implication that static Brikk AST understands all extension SQL.

- [ ] `DDB20-067` Matching-helper parity beyond embedded files [DEFER].
  Exact follow-console, unnamed-memory and remote helpers remain unavailable rather than guessed.
  Establish an approved same-instance/routing mechanism before claiming native validation/completion
  there. Do not replace them with a fresh memory database, replay arbitrary scripts, mutate user
  transactions or add new internal IntelliJ calls without permission. Keep activation order and
  connection/module/profile changes in cache identity and diagnostic invalidation.
  DDB20-016/017 measured that JDBC duplicate copies current session variables at creation but
  later changes are independent. That is not proof of follow-console parity or permission to
  replay variables/scripts into helpers; retain the deferred matching-session requirement.

- [ ] `DDB20-068` Static-versus-extension grammar isolation guards [AUDIT].
  Policy is established: core DuckSQL plus documented Brikk extensions is not the union of loaded
  native grammars. Add focused identifier/keyword-collision and unknown-statement controls where
  coverage is missing. Do not reserve extension-only keywords globally or assign invented AST semantics.

- [ ] `DDB20-069` Java UDF syntax and live catalog overlay [AUDIT].
  Pin unknown table functions with positional/named arguments, same-name scalar/table functions,
  registration ownership and connection-local visibility. Reuse the existing live catalog/session
  overlay where appropriate; do not put application UDFs into static metadata. Parsing a call must
  not require installing/initializing its implementation or binding its arguments.

- [ ] `DDB20-070` Chunked results and native/client APIs [DEFER].
  The verifier already correctly uses ordinary JDBC ResultSet for a few metadata/JSON cells.
  Do not add chunking there. Evaluate chunked results, vector indexing/composite readers and
  bind/init ownership only for a future executor. Monitor C API v2 instability, C++17/build and
  macOS x86_64 phase-out as packaging concerns. CLI agent/manual/history and Python appender/API
  migration are client features, not missing core SQL grammar implementations.
  The CTE audits also measured JDBC 2.0.0.0 classifying an outer SELECT as CHANGED_ROWS when a
  DML/COPY producer lacks RETURNING/result options. Investigate result-channel handling separately
  for a console/executor or driver fix; an executeQuery failure can follow a completed write, so
  never recover by executing that SQL again. This is not a syntax gate or an editor-parse fallback.
  DDB20-017 also pins the JDBC client binding boundary: direct unbound named PreparedStatement
  calls report parameter-count mismatch and index binding does not override a named parameter.
  Native SQL EXECUTE session-default fallback works. Any driver/console adoption needs its own
  binding contract; do not mask defaults or retry SQL to compensate.

## Additional SQL-facing changes found in full-release titles

These are candidates from the final release notes, beyond the user's links and the docs
index. They need final-grammar/runtime classification before assuming a new implementation
is needed. Their IDs follow the existing sequence and must remain stable.

- [ ] `DDB20-071` SET SCHEMA and namespace switching [AUDIT].
  The release notes explicitly add SET SCHEMA grammar support. Pin accepted forms against
  USE/current-schema behavior, multipart/nested schema paths, quoting and search-path resolution.
  Check IDE namespace tracking without inferring a catalog from a nested schema prefix.

- [ ] `DDB20-072` ANALYSE synonym [AUDIT].
  The release notes add ANALYSE wherever ANALYZE is accepted. Verify tokenizer, statement
  boundaries, AST/generation and native acceptance without treating it as an unknown command.

- [ ] `DDB20-073` Compound and numeric type edge cases [AUDIT].
  Classify SQL-visible empty STRUCT support versus internal LogicalType::TUPLE implementation
  details. Cover final type-literal syntax, integer-only DECIMAL parameters, typed macro defaults
  and numeric promotions that affect inferred types. Do not expose an internal type as new SQL syntax.
  The grouping audit also observed the pre-existing generic SUM(INT) inference as BIGINT while
  native DuckDB returns HUGEINT. Reconcile DuckDB-specific aggregate promotions here; the UNNEST
  batch proves SUM row values, not exact static SUM return-type parity. COUNT/MAX shape controls
  and native results remain separate from that broader aggregate-typing gap.
  DDB20-018 has a separate proved USING KEY SUM(INT) output contract, canonical INT128/native
  HUGEINT. Ordinary SELECT aggregate promotion remains open here; do not infer it was fixed globally.

- [ ] `DDB20-074` Table-function identifier-to-string deprecation [AUDIT].
  Review the release's deprecated implicit identifier-to-string conversion for table functions.
  Pin quoted identifiers versus path string literals and positional/named reader arguments;
  do not quietly rewrite identifiers into filenames or bind readers during editor checking.

- [ ] `DDB20-075` Connection-scoped secret storage [AUDIT].
  Classify the final SQL surface for the new `connection` secret storage, ordinary/persistent
  secret creation, scope/visibility and session cleanup. Preserve options without exposing
  credentials in capture files, diagnostics, completions or test logs.

- [ ] `DDB20-076` Colon aliases for subqueries [AUDIT].
  The release notes add TableAliasColon for subqueries. Verify exact final spelling, FROM-first
  and JOIN placement, nested scopes, reference resolution and generation; existing expression
  prefix-alias tests do not prove subquery-table alias behavior.

- [ ] `DDB20-077` Temporal precision and TRY_CAST changes [AUDIT].
  Pin TIMESTAMP_NS to TIMESTAMPTZ_NS conversion and new time-related TRY_CAST support,
  including accepted type spellings, precision, zone effects, invalid input and inferred types.
  Reuse existing temporal result probes rather than claiming these types are entirely unmodeled.

- [ ] `DDB20-078` Dialect compatibility mode [AUDIT].
  The release notes add `SET dialect_compatibility_mode = 'spark'`. Classify its final parser,
  binding and result effects; document which connection modes the static DuckDB dialect models.
  Do not silently assume copying active grammar extensions reproduces every SQL-semantic setting.

## Doris lessons for nested schemas

Research for DDB20-001 and DDB20-002. Core implementation and conservative shared IDE fixes
are recorded above; recursive IDE model/introspection work remains open.

Doris's catalog support is useful precedent, but it models a fixed hierarchy:
`MsRoot -> MsDatabase` for a Doris catalog, then `MsSchema` for a Doris database,
then tables. Its plugin owns the metadata SQL, introspector/model adaptation, default
scopes and console wiring. Shared support already owns path resolution, lazy discovery,
scope widening, unloaded-reference handling and reusable search-path strategies.
DuckDB uses `PgFamilyModelFacade`, not Doris's Ms model. Neither existing facade establishes
that the platform can represent recursive SCHEMA children. Prove the metadata and model
shape first; do not copy the Ms substitution or assume more configured levels fixes it.

Shared assumptions to audit under DDB20-002:

- `CatalogProfile.kt`: `levels` is a fixed list, and `maxDeepenDepth` defaults to its size.
  `CatalogScopes.select` truncates paths to that size; its selection predicates also map each
  path position to one fixed kind. A nested-schema selection must not silently select its parent.
- `NamespacePathResolver.kt`: child traversal is reusable, but resolution rejects paths over
  maxDepth, including absolute paths formed from current-namespace prefixes. Doris raises its
  limit to three to reach tables; DuckDB currently keeps the two-level default.
- `AutoIntrospect.kt`: current-namespace fallback, discovery steps, relative-prefix search and
  custom-listing decisions depend on `levels.size`. Discovery runs at most `levels.size + 1`
  iterations. Recursive schemas need progress/cancellation bounds independent of a fixed number
  of namespace levels, plus correct handling of schemas containing both tables and child schemas.
- `CatalogSearchPath.kt`: `CatalogSchemaSearchPath.switchSql` uses the DATABASE parent and only
  the leaf schema name. `readBack` constructs exactly DATABASE -> SCHEMA. Preserve the entire
  schema ancestry in both directions, based on verified native metadata. Do not split an opaque
  current-schema string on dots and confuse a quoted dotted name with multiple components.

Practical lessons to carry forward:

- Quote each identifier component separately. Doris inherited SQL that quoted `catalog.schema`
  as one name. The switch SQL, read-back, picker and introspected model must agree on path shape;
  otherwise the dropdown resets after execution and completion loses the current namespace.
  Test actual picker behavior too; Doris's SCHEMA/DATABASE popup branch was easy to get wrong.
- Seed useful defaults when the scope is initially empty, without overwriting later user choices.
  The platform's `@` current-namespace default can select nothing if no node is marked current.
  Distinguish cheap name enumeration from explicitly selecting a parent for deep introspection;
  a checked parent with no child group did not automatically load Doris's descendants.
- Make qualifier heads visible without importing every descendant into unqualified scope.
  `CatalogImports.allCatalogs` handles Doris's inherited MySQL import restriction. Shared docs/tests
  say PostgreSQL-family catalog heads already work, so do not add that workaround to DuckDB blindly.
- Prefer stateless, qualified metadata reads and one-level-at-a-time discovery. Doris's cheap
  `DorisNamespaceListing` adds names without deleting cached descendants; its full introspector
  replaces inventory only after a valid enumeration. Exceptions, null/malformed results and
  cancellation must preserve cached hierarchy; a complete successful empty inventory can justify
  clearing it. Do not copy Doris session-switch fallbacks or assume a fresh DuckDB helper sees the
  console's in-memory, temporary or attached objects. Keep the user's session and transactions intact.
- Reuse the meaningful regression scenarios: completion loads one level at a time; same-named
  tables resolve according to the console namespace; unloaded objects do not generate false
  errors; genuinely missing columns become errors once loaded; failed refreshes preserve children.
  Extend those scenarios to multiple nested levels, relative/attached-catalog paths and quoted dots.

Source pointers for the eventual implementation:

- Shared files above live in
  `brikk-sql-intellij-support/src/dev.brikk.house.sql.intellij.catalog/`.
  Also read `CatalogImports.kt`, `PgFamilyModelFacade.kt` and the catalog-kit README.
- Doris's `DorisModelFacade.kt` lives in
  `~/DEV/sortdev/doris-intellij-plugin/src/main/kotlin/dev/sort/doris/`.
  Its `catalog/` directory contains `DorisIntrospector.kt`, `DorisCatalogQueries.kt`,
  `DorisNamespaceListing.kt`, `DorisCatalogScopes.kt`, `DorisDatabaseDialect.kt` and
  `DorisAutoIntrospectService.kt`.
- Doris tests under `src/test/kotlin/dev/sort/doris/catalog/` include
  `DorisMultiCatalogFlowTest.kt`, `DorisCatalogFailurePreservationTest.kt` and scope/wiring tests.
  The flow and failure tests exercise offline models/platform machinery, not live-server proof.
- DuckDB's `DuckdbModelFacade.kt` and `catalog/DuckdbCatalogProfile.kt` live under
  `~/DEV/sortdev/duckdb-intellij-plugin/src/main/kotlin/dev/sort/duckdb/`.

## Evidence, coverage and work rules

Primary existing evidence, not a substitute for reading the assertions:

- `brikk-sql/test/dev.brikk.house.sql/Duckdb20SyntaxTest.kt`:
  nested schema/table spelling, UPDATE CTE, FETCH, grouping UNNEST, JSON calls, lambda output,
  backticks, star EXCEPT, SECURE VIEW, CONNECT/DISCONNECT, trigger bodies and NEAREST/serde/refusal.
- `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVerifierTest.kt`:
  final native acceptance examples including MERGE, UPDATE FROM and the cases above.
- `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/ConnectionDuckdbVerifierTest.kt`:
  helper ownership, parser ranges, missing grammar, cancellation, timeouts and no execution/binding.
- `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/FunctionCatalogTest.kt`,
  `DuckdbDumpTest.kt`, `tools/metadata-capture/test/DuckdbCaptureTest.kt`:
  registry/kind/signature capture and mapping, not exhaustive function result semantics.
- Result probes in `brikk-sql-verify/test/dev.brikk.house.sql.verify/`:
  IntegerDivisionResultTest, StringEscapeResultTest, TargetTypingResultTest,
  SparkDuckdbArrayResultTest and ComprehensionQualificationResultTest.
- Plugin integration evidence is in `~/DEV/sortdev/duckdb-intellij-plugin`, notably
  DuckdbLiveCatalogConsoleTest and grammar/completion tests. Do not report those as core AST tests.

For every selected implementation item: inspect upstream first; add valuable focused
parse/generate/serde/token tests as applicable; add deliberate native result probes for semantic
claims; check invalid inputs and cross-target refusal; run the affected gate before wider tests.
Do not weaken ledgers or add useless count-only tests. Preserve published constructor/copy JVM
shapes per AGENTS.md. New defaulted public data-class fields require hidden compatibility bridges.

Keep SQL-only work here. Plugin changes require Maven Local/shared-release coordination as needed,
but no repeated slow Plugin Verifier pass for unrelated SQL edits. No new marked internal IntelliJ
calls without permission, no experimental annotations on our methods, no connection resets or
source-URL changes for grammar preferences. Dogfood and release authorization are separate steps.

Completeness here means every feature in the reviewed final SQL documentation/index, migration
guide and Gems post has an item or explicit client/engine scope classification, with additional
SQL-facing candidates from the final release titles recorded separately. It does **not**
claim that every PR in the 96 KB full release body has been exhaustively audited; DDB20-056 owns
that remaining triage. In particular, basic round-trip tests do not close nested-name or DML semantics.

## Source coverage for the user's examples

| Documentation | Items |
|---|---|
| Nested schemas | DDB20-001, DDB20-002 |
| UPDATE from same table | DDB20-003 |
| UPDATE using joins / multiple matches | DDB20-004, DDB20-005 |
| Nearest joins | DDB20-008 |
| MERGE INTO | DDB20-006, DDB20-007 |
| Grouping by UNNEST | DDB20-011 |
| FETCH FIRST | DDB20-012 |
| JSON mutation | DDB20-013, DDB20-014 |
| Data-modifying / COPY CTEs | DDB20-009, DDB20-010 |

## Sources

- [Final 2.0 announcement](https://duckdb.org/2026/10/21/announcing-duckdb-200)
- [Final GitHub release notes](https://github.com/duckdb/duckdb/releases/tag/v2.0.0)
- [What's new in the 2.0 docs](https://duckdb.org/docs/2.0/whats_new_in_the_docs)
- [Gems of 2.0](https://duckdb.org/2026/10/26/gems-of-duckdb-200)
- [Migration guide](https://duckdb.org/docs/2.0/operations_manual/migration)
- [Variable shorthand](https://duckdb.org/docs/2.0/sql/statements/set_variable#the-name-shorthand)
- [Prepared variable defaults](https://duckdb.org/docs/2.0/sql/query_syntax/prepared_statements#session-variables-as-default-values)
- [Partition paths and sorting](https://duckdb.org/docs/2.0/data/partitioning/partitioned_writes)
- [SHOW](https://duckdb.org/docs/2.0/sql/statements/show)
- [Sequences](https://duckdb.org/docs/2.0/sql/statements/create_sequence#setting-the-sequence-value)
- [Regex functions](https://duckdb.org/docs/2.0/sql/functions/regular_expressions#using-regexp_extract)
- [Parquet](https://duckdb.org/docs/2.0/data/parquet/overview)
- [GeoJSON loading](https://duckdb.org/docs/2.0/data/json/loading_json#geojson)
- [GeoJSON writing](https://duckdb.org/docs/2.0/data/json/writing_json#geojson)
- [PEG parser](https://duckdb.org/docs/2.0/sql/peg_parser)
- [Window extensions](https://duckdb.org/docs/2.0/extensions/window_function_extensions)
- Preview context only: [2.0 highlights](https://duckdb.org/2026/08/17/duckdb-20-highlights),
  [PEG parser article](https://duckdb.org/2026/08/20/duckdb-20-peg-parser),
  [JSON reconciliation](https://duckdb.org/2026/08/18/reconciling-json),
  [chunked Java results](https://duckdb.org/2026/08/21/chunked-query-results-java-driver),
  [Java table functions](https://duckdb.org/2026/08/25/table-functions-in-java).

[schemas]: https://duckdb.org/docs/2.0/sql/statements/create_schema#nested-schemas
[update-same]: https://duckdb.org/docs/2.0/sql/statements/update#update-from-same-table
[update-joins]: https://duckdb.org/docs/2.0/sql/statements/update#update-using-joins
[update-multiple]: https://duckdb.org/docs/2.0/sql/statements/update#multiple-matching-source-rows
[nearest]: https://duckdb.org/docs/2.0/sql/query_syntax/from#nearest-joins
[merge]: https://duckdb.org/docs/2.0/sql/statements/merge_into
[groupby]: https://duckdb.org/docs/2.0/sql/query_syntax/groupby#grouping-by-unnest
[fetch]: https://duckdb.org/docs/2.0/sql/query_syntax/limit#fetch-first
[json]: https://duckdb.org/docs/2.0/data/json/json_functions#json-mutation-functions
[ctes]: https://duckdb.org/docs/2.0/sql/query_syntax/with#data-modifying-ctes
