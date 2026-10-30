# DataFusion and Spice SQL support

## Steps

- [x] DFS-001: Pin engine profiles and preserve the compatibility audit as reproducible fixtures.
- [x] DFS-002: Build version-pinned parser and execution reference adapters.
- [ ] DFS-003: Capture and generate separate DataFusion and Spice metadata catalogs.
- [ ] DFS-004: Correct DataFusion parsing and SQL generation, starting with silent rewrites.
- [ ] DFS-005: Add the Spice dialect and its product-specific SQL behavior.
- [ ] DFS-006: Complete types, parameters, scope, output shapes, lineage, and semantic limits.
- [ ] DFS-007: Add IDE dialect identities, parsing, highlighting, and source-position support.
- [ ] DFS-008: Connect IDE completion, documentation, resolution, and inspections.
- [ ] DFS-009: Add connection-aware catalog discovery and metadata refresh.
- [ ] DFS-010: Qualify IDE validation, execution, prepared statements, EXPLAIN, and pipes.
- [ ] DFS-011: Implement repeatable upstream monitoring, catalog diffs, and upgrade checks.
- [ ] DFS-012: Run compatibility gates and prepare the supported-dialect consumer handoff.

## Purpose and working agreement

Created on 2026-10-29 in worktree and branch `spice-support`, branched from `main`.
This is a plan for review. Implementation starts after the user accepts it. Tick the steps
as their acceptance criteria pass; preserve the constraints and evidence requirements.
This repository file is the complete implementation record. The Project knowledge entry
links here; read this file before implementation and keep both step checklists aligned.

The goal is two independently selectable, maintained SQL dialects: `datafusion` and `spice`.
Both must be useful for editing, analysis, and faithful SQL generation. The IDE must offer
accurate completion and resolution with bundled metadata offline, and better facts when a
compatible connection is available. Spice requires a specialization of DataFusion, including
its parser configuration, patched engine, registered functions, and search behavior.

DataFusion is an embeddable query engine, not a server with one universal function registry,
transport, or configuration. Describe supported DataFusion profiles explicitly. A Spice
release identifies a product profile, but its build features and runtime configuration still
affect availability. Do not treat a Flight SQL connection, PostgreSQL syntax, or a DuckDB
accelerator as proof of product identity.

Keep the existing Kotlin toolchain and JVM floors: core/metadata/verification API remain
Java 17 compatible; IDE support retains its existing target. Follow `AGENTS.md` for public
JVM ABI compatibility, including old constructors, Kotlin default bridges, and `copy`
signatures. Extend the appropriate binary compatibility tests.

Reuse the existing IntelliJ support APIs. Preserve the project's constraint against adding
internal IntelliJ method calls or increasing experimental API warnings. If a required feature
has no alternative, identify the exact API and obtain review before using it; do not suppress
warnings or weaken API audits. This plan does not authorize publication, stable tags, or
consumer dependency changes. Future release work must respect the existing release hold.

## What counts as supported

Maintain separate, versioned feature matrices for DataFusion and Spice. Each row must say
whether it has structured parsing, faithful generation, analysis, metadata, native evidence,
and IDE coverage. A single acceptance percentage is not a support claim.

The first supported release must cover:

- Query syntax: identifiers, literals, operators, casts, CTEs, subqueries, joins, set operations,
  grouping, windows, QUALIFY, wildcard modifiers, ordering, limits, and supported table functions.
- Functions: every registered name in the pinned inventory is classified, with accurate kinds
  and aliases. Signature/type coverage is explicit; arbitrary UDF calls can remain generic,
  but recognized functions must never lose arguments or acquire another engine's semantics.
- Statements: the documented, native-verified DDL/DML, settings, SHOW, EXPLAIN, and prepared
  statement forms selected in DFS-001. Important advertised forms must have structured ASTs.
  Storage-specific execution restrictions remain visible.
- Analysis: bind occurrences, quoting/casing, scopes, output columns, Arrow types, nullability,
  and lineage for common queries and supported table functions. Incomplete schemas stay open.
- IDE: dialect selection, useful error recovery, highlighting, formatting, completion, function
  help, references/navigation, inspections, catalog discovery, and tested console integration.
- Maintenance: pinned reproducible captures, native regression fixtures, a working upgrade-diff
  process, and a named compatibility baseline for each dialect.

Do not count opaque `Command` fallback as structured support, parser acceptance as execution
evidence, or catalog presence as cross-dialect equivalence. Parsed but unsupported features
need explicit limits. Executable lowering must refuse unsupported transformations instead of
dropping information. Preserve source text/ranges where the existing source-preserving API
requires them. Do not weaken existing ledgers or manufacture type/nullability certainty.

## Current evidence

The initial audit ran all existing DataFusion tests successfully: 275 identity fixtures,
2 transpile cases, 1,208 upstream parse cases, and all 15 selected test methods. These are
not live Spice execution results.

Across 352 extracted SQL examples from 12 Spice documentation pages, 337 produced structured
parses, 6 passed as opaque commands, and 9 failed parsing. There were also 24 focused probes.
Several accepted examples changed meaning during generation. The documentation corpus is
an exploratory sample, not an exhaustive or release-pinned conformance suite.

Temporary research is under `/tmp/opencode/spice-sql-check/`: `check.py`, `Probe.java`,
`cases.json`, `results.json`, downloaded documentation, and selected upstream source under
`upstream/`. Promote useful evidence into repository fixtures in DFS-001; do not require
these temporary files or their original checkout paths to survive.

Source inspection of Spice `v2.4.0` found:

- `Cargo.lock` resolves DataFusion 55.2.0 from Spice's fork at
  `cf084aa85dfdc27b4736208f5c132828020774ea`, sqlparser 0.62.0, and
  datafusion-functions-json 0.55.6. Use the resolved graph, not just workspace dependency ranges.
- The query session selects the PostgreSQL parser dialect. Its defaults disable identifier
  normalization, contrary to the SQL docs' lowercase description. Resolve this with live
  tests before choosing Spice name-resolution rules.
- Spice's statement parser adds Cayenne `CREATE TABLE ... CLUSTER BY` handling around the
  configured DataFusion parser. Tracking only sqlparser misses this behavior.
- The pinned DataFusion fork implements SHOW FUNCTIONS and routine/parameter metadata.
  Table routines appear with a TABLE return marker, but their parameters are deliberately
  absent. Scalar signatures use example types and may have unknown return types.
- `list_udfs()` enumerates scalar and table function registries, not aggregate/window
  registries. Built-ins have no signature metadata there. The HTTP functions endpoint lists
  declared user functions only. These interfaces are complementary, not interchangeable.

## DFS-001: Reference profiles and durable audit

Start with Spice v2.4.0 as the researched candidate baseline, verify the release and exact
runtime digest/checksum, and select an explicit upstream DataFusion baseline separately.
Use the corresponding upstream release for comparison where available. Record which
Spice fork changes are absent upstream. Trunk changes are upgrade candidates, not evidence
for the pinned release. Add an older supported baseline when defining a version range.

Create provenance manifests under proposed `vendor/data/datafusion/<version>/` and
`vendor/data/spice/<version>/`. Record release/tag and commit, resolved dependency revisions,
Arrow/sqlparser/JSON/Spark package versions, binary/image hash, build features, parser/session
settings, capture commands, source licenses, documentation revision/retrieval date, and
fixture-generator version. No credentials or environment-specific secrets belong in captures.

Give findings stable IDs and record input SQL, actual AST/output/error, expected behavior,
engine profile, and evidence source. Separate syntax errors, wrong ASTs, silent rewrites,
opaque commands, metadata gaps, analysis gaps, and IDE gaps. Include positive and negative
controls. Native-check documentation examples, especially placeholder syntax and the
extra-comma `ARRAY_AGG(c, ORDER BY d)` example, before declaring engine regressions.

Audit the full statement inventory, including supported CREATE/ALTER/DROP, COPY/external
tables, views, settings, prepared statements, and connector-specific DML. Record unsupported
forms and the reason. Verify mixed-case names, quoted dotted dataset names, namespace depth,
default catalog/schema, search-path behavior, and setting-dependent grammar.

Acceptance: reproducible fixtures and manifests exist for both profiles; every initial
finding is confirmed, corrected, or explicitly unverified. Remaining scope is enumerated.

## DFS-002: Native reference adapters

Add a small Rust tool pinned to the resolved engine/parser dependencies, using DataFusion's
DFParser and the selected dialect/settings, plus Spice's statement extensions where needed.
Do not substitute sqlparser's generic dialect for the Spice profile. Define a stable machine-
readable request/result format for scripts, AST/statement kind, errors, and source offsets.
Exercise multi-statement input, comments, Unicode, incomplete text, and malformed trailing SQL.

Keep the Rust/native dependency outside the portable core. Start as a development/CI oracle;
decide separately whether an optional `brikk-sql-verify-datafusion` or Spice verifier adapter
is worth shipping. If exposed through `SqlVerifier`, preserve all four verification states,
authority scope, cancellation, and positioned errors. A bare upstream parser cannot certify
Spice-only syntax or runtime function binding.

Add pinned execution fixtures for scalar/aggregate behavior, output schemas, and errors.
Use an actual Spice runtime for product-specific semantics and deterministic local test
datasets. Search/AI tests should use local deterministic fixtures or controlled test doubles;
do not make paid model calls a normal test prerequisite. Mark unexercised runtime behavior.

Acceptance: original and regenerated SQL can be compared for native acceptance and measured
results/types. Parser-only evidence remains distinguishable from binding and execution.

## DFS-003: Versioned metadata and introspection

Build capture/generation tools following the existing `tools/metadata-capture`,
`tools/generate_*_functions.py`, `FunctionCatalog`, `DialectMetadata`, and `MetadataSnapshot`
patterns. Add separate `DatafusionMetadata` and `SpiceMetadata` registrations, generated
catalogs, documentation sources, and `bundledMetadata` entries. Wire each core dialect's
`functionCatalog` to the matching default snapshot.

Capture, when supported by the selected runtime:

```sql
SHOW FUNCTIONS;
SELECT * FROM information_schema.routines;
SELECT * FROM information_schema.parameters;
SELECT * FROM list_udfs();
SELECT * FROM information_schema.df_settings;
```

Capability-detect queries rather than assuming every DataFusion embedding implements Spice
UDTFs. Capture names, aliases, scalar/aggregate/window/table kinds, representative overloads,
parameter modes/order/names, variadic information, return types, determinism/volatility,
descriptions, SQL examples, types, keywords, and relevant settings. Preserve same-named scalar
and table entries; search functions may register scalar stubs for nested syntax.

Supplement incomplete SQL metadata with a version-pinned Rust registry exporter and targeted
source-derived definitions. Record true signature constraints separately from representative
examples. Missing types must not mean zero arguments or no return value. Table-return markers
must not become invented fixed column lists. Custom coercion and value-dependent types need
explicit rules or unknown results. User function declarations can provide additional evidence.

Separate upstream built-ins, optional packages, Spice additions, build features, and runtime
user functions. Version selection should use existing metadata APIs; an approximate/offline
snapshot is labeled as such. Exact engine version alone does not certify the same function set.

Acceptance: deterministic regeneration, provenance and kind/alias tests, explicit incomplete
signature handling, snapshot-selection tests, and reviewed changes for every registry delta.

## DFS-004: Correct the shared DataFusion dialect

Primary files are `DatafusionDialect.kt`, `DatafusionParser.kt`, and `DatafusionGenerator.kt`
under `brikk-sql/src/dev.brikk.house.sql/dialects/`. Prefer dialect-local fixes; change shared
parser/generator behavior only when its wider contract is correct and protected by tests.

Reproduce these audit findings and assign each to upstream DataFusion or the Spice child
using native evidence. Fix silent changes before expanding advertised coverage:

| Input | Current problem |
| --- | --- |
| `regexp_like('ABC', 'abc', 'i')` | Generates case-sensitive `~`, dropping flags. |
| `like('spice.ai', 'spice%')` | Reverses string and pattern. |
| `map('a', 1, 'b', 2)` | Drops the second key/value pair. |
| `to_char('2023-03-01'::date, '%d-%m-%Y')` | Becomes a plain text cast, dropping formatting. |
| `array_sort([3,1,2], 'DESC', 'NULLS LAST')` | Drops null placement. |
| Three-argument `date_bin` | Timestamp origin loses its quotes. |
| Two-argument Spark-style `date_add` | Gains an extra `'DAY'` argument. |
| Ordered `string_agg` | Moves the delimiter into ORDER BY. |
| JSON `->` and `->>` | Generate another function family without proving equivalence. |
| `truncate(10, 101)` | Spice's partition transform becomes numeric `trunc`. |

Audit all recognized function collisions, not just these examples: arity, variadic calls,
argument ordering, named arguments, aliases, aggregate FILTER/ORDER BY/DISTINCT, window/null
treatment, date format languages, and array indexing. Generic lossless calls are preferable
to incorrect dedicated mappings, but they do not supply aggregate semantics or typing alone.

Cover lexical gaps such as `#`, `@>`, `<@`, E-strings, and hex literals under the profiles that
accept them. Preserve `?` JSON containment versus parameter syntax, dollar parameters versus
identifiers, dollar-quoted strings, nested comments, and escaping. Audit all query families
listed in the support definition, including explicit null ordering and wildcard expansion.

Acceptance: native-backed round trips retain meaning, argument count, and required spelling;
negative tests reject malformed forms. Existing DataFusion and other-dialect gates stay green.

## DFS-005: Spice specialization

Introduce `SpiceDialect : DatafusionDialect` with local parser/generator/tokenizer differences
and canonical registry name `spice`. Document aliases deliberately rather than routing generic
DataFusion or PostgreSQL identities to Spice. Keep its metadata and engine-version selection
separate. Preserve existing `datafusion` and `arrow-datafusion` consumers.

Implement correct syntax and function handling for:

- `vector_search`, `text_search`, `rrf`, and `rerank`, including two-argument vector search,
  positional/named forms, nested calls, array queries, limits, score options, and rank weights.
  Replace the inherited VectorSearch signature collision. The current node requires a
  `query_table` argument that is not Spice's two-argument API.
- `ai`, `embed`, decision functions, JSON extraction and shredding functions, vector helpers,
  partition transforms, and Spice's registered Spark-compatible functions. Respect optional
  build/runtime availability and scalar-stub versus executable scalar distinctions.
- Product-specific DDL and Cayenne CLUSTER BY, documented EXPLAIN options, SHOW forms, session
  settings, PREPARE/EXECUTE, and the connector/accelerator-aware DML inventory from DFS-001.

Do not copy all PostgreSQL grammar or function semantics. Spice's fork and extensions define
the target. Implement source-verified differences in the child; share only measured common
behavior with DataFusion. Never push Spice AI/search functions into another engine merely
because it has a function with the same name.

Acceptance: dedicated Spice fixtures pass both structural and native checks; base DataFusion
does not acquire Spice-only built-ins, capabilities, or altered default semantics.

## DFS-006: Analysis and type behavior

Add dialect-aware rules to the existing typing, qualification, scope, relation-facts, shape,
and lineage APIs. Cover Arrow integers including unsigned types, decimal precision/scale,
timestamps and time zones, intervals, binary/string types, list/fixed-size-list, struct, map,
and JSON union results. Preserve native type names while exposing appropriate logical types.

Validate aliases and ambiguity through CTEs, correlated queries, joins, USING/NATURAL output,
grouping/windows, wildcard modifiers, UNNEST, nested fields, and table-function column aliases.
Track `$n` and `?` as parameters with correct UTF-16 ranges and occurrence identity; neither
should appear as an unresolved column. Distinguish JSON `?` and quoted lookalikes.

Search function schemas depend on the input dataset, options, and nesting. Resolve table
arguments as relations and column arguments as columns, not ordinary unqualified expressions.
Derive `_score`, `fused_score`, rerank output, and collision rules from pinned engine behavior.
Carry lineage through passthrough columns and mark computed scores as derived. Unknown inputs
remain open. Fixed JSON-shredder schemas can use existing `OutputSchema.Fixed`; dynamic
schemas need argument-aware facts, not one global function-name entry.

Use literal JSON questions to refine `ai_decide` structs only when their shape is known;
otherwise retain unknown fields. Add accurate scalar versus aggregate/window recognition,
nullability, coercion, and constant-folding limits. Do not evaluate volatile/model-backed
functions in analysis. Version and setting-sensitive decimal/float behavior needs measured
tests. Cross-dialect mappings stay unknown/conditional until paired engine evidence exists.

Acceptance: output names/order/types/nullability, qualification, lineage, parameter spans,
and partial-catalog behavior have contract tests. Existing Engine analysis/ABI gates pass.

## DFS-007: IDE dialect identity and parsing

The existing `SqlDialectRegistry` has a DataFusion label but no language or DBMS IDs; Spice
has no entry. Add independent identities, markers, and registry-version updates after agreeing
the actual consumer IDs. Do not map `GenericSQL`, all PostgreSQL consoles, or all Flight SQL
data sources to either dialect. Product identity and explicit user selection take precedence.

Evaluate `PgFamilyDialect`, `PgFamilyResolveExtension`, `PgFamilyElementFactory`, existing
lexer masks, statement-boundary gates, and lenient parsing as the IDE implementation base.
An inherited PostgreSQL PSI parser must not define the advertised engine grammar. Extend only
the differing forms; preserve host offsets, comments, malformed/incomplete statement recovery,
and special forms such as CAST. Keep core semantics independent of IntelliJ classes.

Cover highlighting and formatting for engine keywords, functions, parameters, named arguments,
JSON/array operators, casts, E/hex/dollar strings, and pipes. Test injected SQL, CRLF, Unicode,
large files, incomplete code, and multiple statements. Parameter/diagnostic spans must map to
authored text. No broad error suppression to hide grammar mismatches.

Acceptance: shared platform fixtures exercise both distinct languages and prove they do not
claim another plugin's files/consoles. Formatting retains behavior and source boundaries.

## DFS-008: IDE completion, help, and references

Use `SqlCompletionProfile`, `EngineBuiltins`, `SqlBuiltinPrototypeFilter`,
`SqlEngineDocumentationProvider`, relation analysis, and `SqlColumnCompletion`.

- Provide version-aware function/type/keyword completion, overload signatures, return types,
  docs URLs/examples, aliases, and availability labels. Filter inherited PostgreSQL prototypes
  that do not belong to the selected engine without breaking SQL special forms or user UDFs.
- Offer scalar/aggregate/window/table functions in the appropriate contexts. Nested search
  calls require their own argument context; a scalar registration stub is not an ordinary
  scalar function suggestion. Complete `=>` labels without resolving them as columns.
- Resolve dataset and column arguments in search calls, then offer output columns and nested
  fields from DFS-006. Retain ambiguity and unknown nullability. Avoid duplicate completions
  from the platform and Brikk contributors.
- Support aliases, CTEs, schema-qualified relations, quoted/case-sensitive names, reference
  navigation, and local-symbol rename through existing PSI mechanisms. Do not rename string
  literals or named options merely because their text matches a column.
- Reconcile native/local diagnostics with existing profiles. Known arity/type errors are
  useful inspections; missing optional metadata is not proof that a function is invalid.

Acceptance: completion, documentation, resolution, rename, and inspection fixtures run both
offline and with connection facts, including partial input and search-output schemas.

## DFS-009: Connection-aware catalogs

Implement harvesters over `CatalogRowSource` and `LiveCatalogHarvester`, reusing
`LiveCatalogService`, storage/merge rules, `CatalogProfile`, and `DialectDataSourceResolver`.
Support versioned bundled fallback, cached facts, and live facts with visible provenance.
Query metadata off the EDT and outside PSI read actions. Failed/partial harvests must not
replace complete inventories; disconnects must not erase useful offline metadata.

Choose and test the actual transport/driver for the consumer, including Flight SQL JDBC where
appropriate. PostgreSQL syntax does not imply PostgreSQL wire protocol. Use driver metadata
and information_schema to preserve catalog/schema/table/column identity, quoting, types,
views, and available constraints/comments. Use generic JDBC introspection only where it
retains the required facts; document driver omissions and use existing extension points.

Cache by product, connection identity, engine/build profile, relevant settings, and metadata
revision. Respect user-function hot reload and session-specific changes. Shared helper sessions
cannot prove the console's temporary functions, prepared statements, or current namespace.
Determine refresh triggers and provide a manual refresh path; avoid per-keystroke queries.

The existing `LiveInventory.signatures` flag must not advertise introspected representative
examples as exhaustive signatures. Establish an explicit partial-signature policy, extending
metadata with ABI bridges only if needed. Keep scalar/table same-name entries distinct.

Acceptance: driver/row-source contract tests cover complete/partial harvests, permissions,
version changes, stale cache, reconnect, session separation, and both product identities.

## DFS-010: Validation and console execution

Use `NativeErrorProfile`, `NativeSqlValidator`, `DataSourceVerification`, editor snapshots,
cooperative actions, and `TranslatingScriptModel` where applicable. Local parse diagnostics
remain available without a server. An unavailable or mismatched verifier gives no authoritative
clean verdict. Preserve cancellation, bounded work, document revisions, and source-map offsets.

Do not use EXPLAIN as a presumed parse-only verifier: planning can invoke table functions,
and EXPLAIN ANALYZE executes the query. Choose an actual parser-only adapter for background
diagnostics or report native verification unavailable. Any optional planning/type-check action
must be a separately defined user action with its real behavior documented.

For execution, preserve stock parameter prompting and statement selection. Verify Flight SQL
parameter binding and named PREPARE/EXECUTE session continuity; HTTP requests and helper
connections must not be treated as the same session. Preserve destination, namespace, and
document identity across prompting. Qualify cancellation and error mapping with the chosen
driver. EXPLAIN is explicit; ANALYZE remains visibly an execution action.

Distinguish native engine pipe syntax from Brikk `|>` lowering. Populate runnable-stage
evidence for each profile rather than advertising every parsed stage. Use existing PipeDialect,
preview/run-to-stage, source-preserving lowering, and input-column completion hooks. Unknown
or unproved lowering must not be submitted as if it were verified SQL.

Acceptance: offline/native diagnostic contracts and consumer console tests cover parameters,
session lifecycle, cancellation, stale text, EXPLAIN, and pipes without background execution
of editor SQL or model/search calls.

## DFS-011: Tracking upstream changes over time

Provide one documented refresh command per product and a deterministic compare command. Add
on-demand CI first; propose a weekly upstream-check CI job for approval when implementing this
step. No scheduler is created by this plan. Scheduled checks should produce reviewable changes,
not silently promote a new runtime or rewrite historical captures.

Monitor these sources as a dependency graph:

1. Spice release tags, Cargo.lock, patched DataFusion revision, parser wrapper/session defaults,
   function registration, search/AI/JSON implementations, build features, and release tests.
2. Upstream DataFusion releases, DFParser, sqlparser version/dialect changes, optimizer and type
   semantics, function registries, Arrow changes, and sqllogictests.
3. Optional JSON/Spark packages and the actual driver/protocol versions used by the IDE.

Each proposed upgrade must:

- Save a new immutable manifest and raw catalog captures; retain earlier supported snapshots.
- Diff added/removed names, aliases, kinds, overload constraints, return types, documentation,
  keywords, settings/defaults, table-function schemas, and parser acceptance/rejection.
- Re-run the stable native corpus on old and new profiles, preserving both results. Review
  semantic changes even when SQL parses identically. Include negative tests to detect newly
  permissive parsing and same-name function behavior changes.
- Assign stable follow-up IDs with introduction/removal boundaries and label the impact:
  metadata-only, parser, generator, type/scope, IDE, transport, or breaking behavior.
- Regenerate metadata deterministically and run core/native/IDE gates before changing defaults.
  Distinguish a disabled feature or incomplete capture from an actual removal.
- Update the support matrix, compatibility notes, default-version policy, and consumer handoff.
  Never relabel old evidence as proof for a newer runtime.

Acceptance: demonstrate the workflow across two pinned profiles or a controlled fixture delta;
it detects additions, removals, signature changes, and a semantic/parser regression.

## DFS-012: Qualification and consumer handoff

Run focused dialect/metadata/analysis suites, both native profiles, shared IDE platform
contracts, and the repository's required build/test checks. Preserve existing DataFusion
fixtures and add Spice corpora with exact ledger accountability. No native test skipped for
missing infrastructure can count toward the supported-engine claim.

Use the documented build commands and current custom IntelliJ platform-test task; verify its
task name from the tooling rather than inventing a Gradle command. Run packaged API-reference
and ABI checks when public classes or IDE integration change. Keep deterministic offline
tests in ordinary CI; run native fixtures in the qualification job with explicit pins.

The shared library registers no plugin extensions. Deliver a tested minimal consumer fixture
and registration guide for both dialects: languages/DBMS identity, file/console association,
parser definition, completion/docs/resolution, catalogs, validation, and optional pipes/actions.
Choose whether one consumer plugin exposes both dialects or separate plugins own them before
packaging. Production integration in a consumer repository is a separate handoff, and its IDE
and driver smoke tests are required before calling the user-facing plugin supported.

Publish a final feature matrix and known limits with reproducible evidence paths. State exactly
which DataFusion embedding and Spice versions/builds were qualified, what works offline, what
requires a live connection, which DDL/DML capabilities are provider-specific, and which
cross-dialect translations remain unsupported. Update README and dialect docs only to those
claims. Prepare candidate artifact coordinates and downstream adoption checks; actual release
and dependency updates require separate authorization.

Acceptance: both dialects meet the support definition, all identified correctness blockers are
resolved or excluded explicitly, the upgrade workflow works, and the consumer integration
fixture passes without increasing unreviewed IntelliJ API risk.

## Implementation map

- Core dialects and registry: `brikk-sql/src/dev.brikk.house.sql/dialects/`.
- Existing DataFusion gates: `DatafusionDialectTest`, `DatafusionFixtureTest`,
  `DatafusionSltParseTest`, `DatafusionPipeSmokeTest`, and `TODO-datafusion.md`.
- Metadata: `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/`; generators/captures in
  `tools/` and versioned raw evidence in `vendor/data/`.
- Verification contracts: `brikk-sql-verify-api`; optional native adapters remain separate.
- IDE: `brikk-sql-intellij-support` dialect, parser, lexer, editor, completion, docs, relation,
  catalog, diagnostics, host, and pipe packages; JDBC and platform-test support modules.
- Existing contracts: [engine analysis](engine-analysis-contract.md),
  [source-preserving lowering](source-preserving-lowering.md),
  [IDE support API](../brikk-sql-intellij-support/README.md), and
  [vendor provenance](../vendor/README.md).

## Upstream references

- [Spice SQL reference](https://spiceai.org/docs/reference/sql)
- [Spice function discovery](https://spiceai.org/docs/features/functions#discovering-registered-functions)
- [Spice v2.4.0 lockfile](https://github.com/spiceai/spiceai/blob/v2.4.0/Cargo.lock)
- [Spice session defaults](https://github.com/spiceai/spiceai/blob/v2.4.0/crates/runtime-datafusion/src/session_config.rs)
- [Spice statement parser](https://github.com/spiceai/spiceai/blob/v2.4.0/crates/runtime/src/datafusion/planner/mod.rs)
- [Spice list_udfs](https://github.com/spiceai/spiceai/blob/v2.4.0/crates/runtime/src/udtfs.rs)
- [Spice function registration](https://github.com/spiceai/spiceai/blob/v2.4.0/crates/runtime/src/datafusion/udf.rs)
- [Pinned DataFusion introspection](https://github.com/spiceai/datafusion/blob/cf084aa85dfdc27b4736208f5c132828020774ea/datafusion/catalog/src/information_schema.rs)
- [DataFusion SQL documentation](https://datafusion.apache.org/user-guide/sql/)

The initial sources were read on 2026-10-29. Versioned source findings and unversioned docs
must remain distinguishable. Runtime introspection and execution still need to be performed
in DFS-001 through DFS-003.
