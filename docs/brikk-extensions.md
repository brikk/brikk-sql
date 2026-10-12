# brikk-sql extensions registry — deliberate divergences from sqlglot

brikk-sql is a SQLGlot-derived Kotlin engine (oracle pin: `v30.22.0-5-ga00dc2e5c`), checked by
differential and semantic gates. This file registers where brikk **deliberately diverges**
from or **extends beyond** sqlglot, so that upstream syncs know exactly where conflicts
can arise: when a future sqlglot version adds its own handling for one of these, the
sync MUST reconcile here (adopt upstream, keep ours, or merge) and update this registry.

All divergence sites are marked in code with the greppable phrase **`brikk extension`**
(`rg "brikk extension" brikk-sql/src`).

**Protected policy.** Verifier-backed intentional divergences registered here must
**never** be copied into actionable TODO/problem inventories (e.g. `TODO-BUGS-*.md`)
or "fixed" toward SQLGlot unless this policy is explicitly reversed in this registry.
Their parity ledger entries are **expected and protected**. Generation-ledger
exceptions today:

- **4 Doris** in `brikk-sql/testResources/generator-corpus/doris-generator-known-failures.json`
  — §9 (3× `PARTITION BY` clause completion) and §10 (1× MV column list). §19 (Doris DDL
  parsing) adds no ledger entries.
- **1 Trino** in `brikk-sql/testResources/generator-corpus/trino-generator-known-failures.json`
  — §8 (`JSON_QUERY` wrapper clause).
- **1 StarRocks** in `brikk-sql/testResources/generator-corpus/starrocks-generator-known-failures.json`
  for the ASTRA-001 final ordering correction in section 6.

## 1. First-class pipe syntax (Phase 4)

- **What:** `|>` queries parse into `PipeQuery` + per-operator stage nodes instead of
  sqlglot's parse-time desugaring into `__tmpN` CTE chains. Desugaring is an explicit
  transform (`desugarPipes`), with the stage-boundary corrections below; pipe *generation*
  (AST → `|>` text) exists — sqlglot has no equivalent.
- **Where:** `ast/PipeNodes.kt`, `ast/PipeDesugar.kt`, parser `parsePipeSyntax*` handlers,
  generator `pipe*Sql` methods.
- **ASTRA-005:** requested lowering at `transpileTo`/`toExecutable` and analysis
  preparation is recursive even when the root is not a pipe. Subqueries and CTEs
  therefore reach shape inference, lineage, and certification in standard form.
  Explicit pipe-rendering mode and the author AST remain unchanged.
  `NestedPipeEntryPointsTest` covers those APIs and source-map consistency;
  `SqlVerifierTest.nestedPipesLowerBeforeNativeVerification` adds native parser
  acceptance and executed DuckDB results for self-contained nested queries.
- `PipeSelect` preserves the SELECT's optional `Distinct` node, including `ON` keys,
  through parsing, copying, serialization, pipe rendering, and desugaring. Earlier
  versions silently dropped `|> SELECT DISTINCT`. Projection, filtering and deduplication must
  consume the preceding stage's result. Prior LIMIT/OFFSET, DISTINCT (including ON),
  grouping/QUALIFY, and computed or renamed projections stay inside an input CTE
  before the next SELECT/DISTINCT/WHERE. Simple direct projections and chained WHERE
  stages still fuse when safe. A WHERE after DISTINCT ON must not choose a different
  representative row; a WHERE after a limited input must not restore excluded rows.
  ORDER BY remains with the restriction; DISTINCT ON also retains a copy for ranking.
  Copied ranking keys bind to existing input output columns, including renamed or
  computed sort keys. Unexported expressions are refused rather than re-evaluated
  after the boundary, which would change volatile values or aggregate semantics.
  Whole-row DISTINCT followed by plain projection can return duplicate projected
  values. The older test expecting these to collapse was incorrect and is replaced
  by row-result assertions.
- The pipe corpus has two explicit, checked native expectations: sequential slices
  that exhaust the earlier limit, and WHERE's boundary after DISTINCT. The extracted
  SQLGlot fixtures are unchanged; overrides must match current inputs and become stale
  if upstream adopts their output. `DorisPipeStageOrderTest` and
  `PipePaginationSemanticsTest` use independent staged references and actual row results.
- Input boundaries preserve an alias only for a complete, unmodified projection of
  that rowset. Scope references and selected sources must agree; a missing source
  registration is not evidence of single-source ownership. Lateral views, joins,
  TVFs and derived rowsets are distinguished, and pipe-AS references are adapted on
  analysis copies. `LIMIT |> SELECT e.item` and `LIMIT |> SELECT t.*` over a base
  table plus lateral view refuse rather than lose `e` or widen `t.*`. Explicit
  unique output names and a user-assigned rowset alias (`|> AS s`) remain supported.
  Generated CTE names avoid both user relation names and aliases.
  Unresolved multi-source stars, duplicate/unnamed input columns, database-qualified or
  source references requiring rebinding, and ambiguous qualified stars raise
  `UnsupportedError` rather than guessing. Correlated stage references are checked
  across child queries, including CTEs and qualified stars, without rewriting local
  bindings. `SqlFragment` supplies the input dialect's identifier rules for these
  proofs. The existing two-argument `desugarPipes` binary entry point remains;
  the dialect-aware overload is available for direct AST callers.
- `PipeBoundaryContractTest` exercises fixed success/refusal outcomes across source
  topology, stage ordering and projection forms. Successful outputs must pass native
  grammar, strict qualification with a complete fixture schema, and independently
  specified column names/order. It checks exact source positions and parent/metadata
  preservation, with unchanged DuckDB SQL executions for portable cases. See
  [consumer verification](consumer-verification.md) for testing candidate JARs against
  the real downstream adapter and result-dispatch code before publication.
- **Conflict risk on upstream sync:** HIGH for the desugar semantics (sqlglot's pipe
  handler table grows most releases — e.g. DISTINCT was added in 30.x; new upstream
  operators must be mirrored in both our parser and `desugarPipes`, with their tests
  landing in the pipe gates). The stage-node design itself cannot conflict (native).

## 2. Extended GoogleSQL pipe operators

- **What:** SET / DROP / RENAME / CALL / WINDOW / standalone OFFSET — parsed, desugared
  (star REPLACE/EXCEPT/RENAME, TVF-arg nesting, EXTEND-like CTE), rendered. sqlglot
  raises "Unsupported pipe syntax operator" for all of these (SET/DROP existed upstream
  briefly and were removed in their #5248 refactor).
- **Where:** `ast/PipeNodes.kt` (5 nodes), parser handlers (spec citations to
  `googlesql/docs/pipe-syntax.md`), `ast/PipeDesugar.kt`, `shape/SqlFragment.kt`
  (`expandStarModifiers`).
- Doris requires a LIMIT for standalone OFFSET. Generation uses
  `Long.MAX_VALUE - offset` for non-negative signed-64-bit literal offsets so the
  planner's two-phase `limit + offset` does not overflow. Nonliteral, negative or
  out-of-range standalone offsets refuse explicitly. Existing LIMITs are unchanged.
- Pipe LIMIT/OFFSET accepts only non-negative integer numeric literals through
  `Long.MAX_VALUE`. Strings, fractional/scientific notation, expressions, placeholders
  and unsupported row-count modifiers raise `UnsupportedError`; no evaluation,
  coercion, parameter dropping or generic number-format failure occurs. Offsets
  consume the remaining limited slice before a new limit applies. Accumulated offsets
  and limit-plus-offset arithmetic are checked before addition, even for empty slices.
- PIPE RENAME has an output CTE boundary so later filters, projections and joins see
  the renamed columns. Schema-aware star expansion validates each star's own inputs
  and the complete simultaneous mapping, preserving column order and target quoting.
  Unknown/repeated sources and duplicate/colliding targets raise `ShapeError`.
  Doris column collisions are case-insensitive, including backtick-quoted names;
  table-name policy is unchanged. Qualified rename arguments are refused rather than
  losing their qualifier. Known qualified input tables remain supported.
- Doris refuses a surviving star RENAME with `UnsupportedError`. Use the existing
  `toStandardSql(target, inputs, expandStars = true)` path for complete catalogs,
  or the tracked AST-based expansion/generation APIs when source maps are needed.
  Cross-dialect inputs with case-distinct intermediate columns that Doris cannot
  distinguish are refused. DDL RENAME remains a separate, unaffected operation.
- **Conflict risk:** HIGH if sqlglot re-adds SET/DROP (their earlier implementation used
  the same star-modifier desugar — semantics should converge, but CTE naming/shape may
  differ; our pipe gates will catch it).

## 3. Doris: FILTER (WHERE) → CASE rewrite

- **What:** `AGG(x) FILTER (WHERE c)` → `AGG(CASE WHEN c THEN x END)` (and
  `COUNT(*)` → `COUNT(CASE WHEN c THEN 1 END)`) when generating for Doris. sqlglot
  passes FILTER through unchanged, which Doris rejects (no FILTER clause). Conservative
  allowlist (COUNT/SUM/MIN/MAX/AVG/ANY_VALUE/simple ARRAY_AGG); everything else raises
  UnsupportedError — never silently-wrong SQL.
- **Where:** `dialects/DorisGenerator.kt` `filterSql` override.
- **Conflict risk:** MEDIUM — if upstream sqlglot adds Doris FILTER elimination (they
  have precedent: similar rewrites exist for other dialects), prefer upstream's version
  if result-identical, port it, and retire ours; the DorisDialectTest assertions define
  the required behavior either way.

## 4. Shape layer (pure addition)

- **What:** `shape/` package — `SqlFragment`, `Shape`, contracts, slot detection. No
  sqlglot counterpart; consumes only parity-verified primitives.
- **Conflict risk:** none directly; it inherits behavior changes from everything above.
- **ASTRA-004:** output contracts reconcile set-operation columns recursively rather
  than reading types/nullability from the left SELECT. Ordered summaries retain
  duplicate positional names and unknown verdicts. UNION combines nullable inputs,
  INTERSECT is non-null when either input excludes nulls, and EXCEPT retains the left
  verdict. BY NAME aligns columns, null-pads missing inputs, and honors INNER/LEFT/ON
  output selection. This also corrects the core type annotator's nested alias handling
  and BY NAME scope names, with no changes to generated SQL or corpus ledgers.
  `SqlFragmentTest`, `NullabilityTest`, and the compiler's generated getter checks
  cover these contracts. These are analysis/compiler regressions, not engine-result
  equivalence claims; existing type-coercion rules still bound numeric inference.

## 5. Native pipe nodes in serde

- **What:** serde registry has a NATIVE section (module `"brikk.pipes"`) for nodes with
  no Python counterpart; `ArgTypesManifestTest` allowlists them explicitly.
- **Conflict risk:** LOW — only if upstream ever introduces same-named classes.

## 6. Window-filter lowering: output shape and clause order

- **What:** In sqlglot's `eliminate_qualify` (QUALIFY → subquery rewrite), an original
  projection containing a star produces `SELECT *, rn FROM (subquery)` — the outer star
  already re-exports the inner `rn`, so the output gains a duplicate column and the
  result shape diverges from the original query. brikk collapses the outer projection to
  the bare star. Verified as result-shape-breaking on DuckDB→Doris/Trino by customer
  agents. **Upstream bug candidate — worth reporting to sqlglot.**
- **Where:** `generator/Transforms.kt` `eliminateQualify` (outer projection branch).
- **ASTRA-001 correctness divergence:** QUALIFY lowering now moves DISTINCT, final
  ORDER BY, OFFSET, and LIMIT/FETCH to the filtering query. WITH moves with them so
  pagination subqueries retain CTE visibility. DISTINCT ON lowering keeps final
  ordering and pagination outside its row-number filter, and runs after QUALIFY.
  Its ranking window resolves ORDER BY ordinals and standalone projection aliases.
- **Counterexample:** `SELECT x FROM (VALUES (1), (2)) AS t(x) QUALIFY
  ROW_NUMBER() OVER (ORDER BY x DESC) = 1 ORDER BY x LIMIT 1` must return `2`.
  The pinned upstream and the previous Kotlin lowering instead return no rows.
- **Hidden expressions:** sorting expressions stay intact in the inner scope and
  use collision-free helper names outside it. Explicit outer projections exclude
  helpers from both output and DISTINCT. Plain DISTINCT with a hidden sort key is
  refused rather than emitting SQL rejected by PostgreSQL/Trino or changing the
  deduplication key. Unresolved compound output-alias references and star orderings
  requiring new hidden columns are also refused pending input-schema expansion.
- **Regressions:** `QualifyLoweringResultTest` executes the source and actual generated
   SQL in DuckDB JDBC 1.5.6.0, comparing ordered rows and column names. It covers the
  counterexample, pagination, DISTINCT, DISTINCT ON composition, qualified names,
  helper-name collisions, aggregate/subquery ordering, and CTE visibility. These are
  executed-result checks for the tested DuckDB-compatible target SQL, not live
  Presto/PostgreSQL/MySQL execution. The Trino counterexample also passes its native
  parser. `TransformsTest` checks FETCH placement and explicit unsupported cases.
- **Corpus impact:** one intentional StarRocks generator mismatch for `SELECT
  DISTINCT ON (a) a, b FROM x ORDER BY c DESC`. The upstream fixture remains intact;
  only its exact divergence is ledgered. No oracle-gate behavior changes.
- **Upstream tracking:** ASTRA-001 is inherited from the pinned
  `v30.18.0-43-g3ca82489`. Owner: Brikk maintainers. Reporting status: pending;
  no upstream issue/PR has been filed by this change. Adoption revision: none.
  On each upstream sync, check `eliminate_qualify` and `eliminate_distinct_on`
  against these result regressions before adopting them, then reconcile this entry
  and the exact StarRocks ledger entry. String parity alone is insufficient.
- **Doris native path:** ordinary window-dependent QUALIFY stays native, including
  bare/qualified stars and caller-selected window columns. No `_w` helper is projected.
  The pinned FE's `FillUpQualifyMissingSlot.checkWindow` requires a window expression
  in the predicate or a genuinely referenced window-output slot. A selected window
  elsewhere is not enough. Scalar-only predicates use the explicit-projection
  fallback when their aggregate/column bindings can be proved; stars, nested queries,
  unresolved alias collisions and unprojected aggregates refuse safely.
- Cross-dialect QUALIFY window-alias references require known input columns or an
  inline window predicate: an input column may shadow the same-named output alias.
  Same-dialect native clauses retain their binding semantics. `DorisQualifyTest` and
  `DorisQualifySemanticsTest` check row sets, exact schemas, caller `_w`/`_row_number`
  columns, alias/input precedence, and source maps. Generic non-Doris star lowering
  still has the documented helper-leak limitation; this is not a universal fix.
- Pretty source-map matching permits indentation changes between a CTE/subquery and
  its recorded child fragment. It retains the parent window instead of assigning a
  repeated ORDER BY column to the wrong clause. Literal newlines remain protected by
  generator sentinels; other within-line whitespace is not relaxed.
- **Conflict risk:** MEDIUM. Adopt an upstream fix only when both output shape and
  clause-order result regressions pass; retire the matching local branches then.

## 7. Doris: first-class arrays

- **What:** sqlglot's Doris dialect inherits MySQL's array rejection wholesale, but Doris
  supports arrays natively. Divergences from the Python oracle (v30.18.0-43-g3ca82489),
  each rendering pinned against the real Doris FE parser
  (`SqlVerifierTest.dorisAcceptsBrikkArrayRenderings`):
  - **Array literals** render as the canonical constructor `ARRAY(1, 2, 3)` — the same
    string sqlglot's fallback emits, but *without* the "Arrays are not supported by
    MySQL" flag. (The FE parser also accepts `[1, 2, 3]`; Doris docs use `array()`.)
  - **Subscripts are 1-based:** Doris `arr[i]` / `ELEMENT_AT(arr, i)` start at 1, but
    sqlglot inherits MySQL's `INDEX_OFFSET = 0` and mis-renders duckdb `arr[1]` as
    doris `arr[0]` (and doris `arr[1]` as duckdb `arr[2]`). We set parser/generator
    index offset to 1 (`DorisParser.indexOffset` / `DorisGenerator.dialectIndexOffset`).
  - **`ARRAY<T>` type mapping and casts** (`CAST(x AS ARRAY<INT>)`, nested
    `ARRAY<ARRAY<STRING>>`, DDL columns) already worked at parity — covered by tests and
    the verifier pin, no divergence.
  - **Table-position UNNEST** (`SELECT * FROM UNNEST(ARRAY(1, 2, 3))`) is base-generator
    behavior the FE parser accepts; it only failed upstream because of the array-literal
    flag. Now clean.
  - **Scalar-position UNNEST/EXPLODE** (duckdb `SELECT unnest([1, 2, 3])` parses as
    `Explode`) still has no Doris equivalent (EXPLODE is only valid in LATERAL VIEW), so
    it stays flagged — with an accurate message instead of the MySQL one. The
    `EXPLODE(...)` fallback is still emitted (= Python oracle output). Explode inside
    `LATERAL VIEW` is not flagged.
  - **Array set-containment ops** (duckdb `@>` / `<@`) stay flagged with an accurate
    message: Doris's `ARRAY_CONTAINS_ALL` is order-sensitive (subsequence match), so
    there is no result-identical mapping.
- **Where:** `dialects/DorisGenerator.kt` (`dialectIndexOffset`, `arraySql`,
  `arrayOpUnsupportedSql`, `explodeSql` + `Explode` transform), `dialects/DorisParser.kt`
  (`indexOffset`). Tests: `DorisArraysTest.kt` (common),
  `SqlVerifierTest.dorisAcceptsBrikkArrayRenderings` (JVM, real FE parser),
  `TranspileGateApiTest.unsupportedTranslationsAreFlaggedNotSilent` (updated message).
- **Corpus/ledger impact:** none — no dialect-corpus/verify case changes outcome (the
  `doris.json` `ARRAY_SUM(..., ARRAY(2, 3))` output string is unchanged; it just loses
  the bogus warning), all ledgers stay empty/as-were.
- **Upstream-PR candidate:** yes — MySQL's `array_sql`/`INDEX_OFFSET` should be
  overridden in `sqlglot/generators/doris.py` + `Doris.INDEX_OFFSET = 1`; verifier-proven
  against Doris FE grammar `g7027772afcb`.
- **Conflict risk:** MEDIUM — if upstream adds its own Doris array handling, adopt it if
  result-identical (watch the index offset: an upstream fix that keeps offset 0 is
  *wrong* per Doris semantics and should be reported, not adopted). DorisArraysTest
  defines the required behavior.

## 8. Trino: grammar-legality fixes (JSON_QUERY wrapper, SET PROPERTIES)

- **What:** two places where sqlglot's Trino output is rejected by Trino's own grammar
  (`reference/trino/core/trino-grammar/.../SqlBase.g4`), found by the brikk-sql-verify
  gate and fixed as divergences from the Python oracle:
  - **JSON_QUERY wrapper clause:** `jsonQueryWrapperBehavior : WITHOUT ARRAY? | WITH
    (CONDITIONAL | UNCONDITIONAL)? ARRAY?` — the modifier is legal only after WITH, but
    sqlglot's `JSON_QUERY_OPTIONS` cross-products WITH/WITHOUT with every modifier and
    re-emits e.g. `WITHOUT CONDITIONAL WRAPPER`. Under WITHOUT no wrapping happens at
    all, so the modifier is vacuous: we drop it (`WITHOUT WRAPPER` /
    `WITHOUT ARRAY WRAPPER`). The upstream option-table `("CONDITIONAL", "ARRAY",
    "WRAPPED")` typo is also repaired on output (`WRAPPED` → `WRAPPER`). Parsing stays
    lenient (upstream tables untouched).
  - **ALTER TABLE ... SET PROPERTIES:** sqlglot leaves the whole statement as a raw
    `Command` passthrough (warning), re-emitting string-literal property names
    (`'foo bar' = 456`) that the grammar rejects (`property : identifier EQ
    propertyValue`). We parse it into `AlterSet` (option=PROPERTIES) and render
    string-literal keys as quoted identifiers (`"foo bar" = 456`); identifier keys and
    `= DEFAULT` values round-trip byte-identically to the oracle.
- **Where:** `dialects/TrinoGenerator.kt` (`normalizeJsonQueryWrapperOption`,
  `altersetSql`), `dialects/TrinoParser.kt` (`parseAlterTableSet`,
  `parseSetPropertyAssignment`), `parser/Parser.kt` (`parseAlterTableSet` opened).
  Tests: `TrinoDialectTest.kt` (common),
  `SqlVerifierTest.trinoAcceptsBrikkGrammarLegalityRenderings` (JVM, real trino-parser
  483, which also pins that the replaced forms are indeed rejected).
- **Corpus/ledger impact:** trino-verify-known-failures.json is now empty (both rejects
  resolved). Divergences from the oracle are ledgered in
  `generator-corpus/trino-generator-known-failures.json` (1: JSON_QUERY case),
  `parser-corpus/trino-parser-known-failures.json` (3: the SET PROPERTIES statements now
  parse into `AlterSet` instead of `Command`) and
  `annotate-corpus/known-failures-trino-annotated-serde.json` (same 3).
- **Upstream-PR candidate:** yes, both — the JSON_QUERY options table should not offer
  CONDITIONAL/UNCONDITIONAL under WITHOUT (and has a WRAPPED typo), and SET PROPERTIES
  could be parsed properly; upstream's own test inputs
  (`tests/dialects/test_trino.py:15,123`) encode the grammar-illegal forms.
- **Conflict risk:** MEDIUM — if upstream adds real SET PROPERTIES parsing or fixes the
  wrapper table, adopt theirs if the emitted SQL stays grammar-legal (the verifier tests
  define the required behavior).

## 9. Doris: CREATE TABLE `PARTITION BY` clause completion

- **What:** sqlglot emits bare `PARTITION BY (cols)` for Doris CREATE TABLE, but the FE
  grammar (`reference/doris/fe/fe-sql-parser/.../DorisParser.g4` `partitionTable`)
  requires a parenthesized partition-definition list: `PARTITION BY (RANGE | LIST)?
  identityOrFunctionList '(' partitionsDef? ')'`. We complete the clause with Doris's own
  defaults, both FE-analyzer-valid (`LogicalPlanBuilder.visitPartitionTable`,
  `PartitionTableInfo.validatePartitionInfo`):
  - column keys → `PARTITION BY (cols) ()` — the kind-less form is LIST per the FE,
    with an empty initial partition list (partitions added later);
  - a function key (e.g. `DATE_TRUNC(c2, 'MONTH')`) → `PARTITION BY RANGE (func) ()` —
    the FE auto-infers AUTO partitioning from the function expression, and the internal
    catalog rejects functions in LIST partitions, so RANGE is the only analyzer-valid
    completion.
  `CREATE MATERIALIZED VIEW ... PARTITION BY (col)` keeps the bare form (its
  `mvPartition` rule takes no definition list), and explicit
  `PARTITION BY RANGE/LIST (...) (...)` (`PartitionByRangeProperty`) is untouched.
- **Where:** `dialects/DorisGenerator.kt` `partitionedbypropertySql`. Tests:
  `DorisDialectTest` (common), `SqlVerifierTest.dorisAcceptsBrikkPartitionByRenderings`
  (JVM, real FE parser, also pins that the bare form is rejected).
- **Corpus/ledger impact:** the 3 bare-PARTITION BY rejects left
  `doris-verify-known-failures.json`; the output divergences are ledgered in
  `generator-corpus/doris-generator-known-failures.json`.
- **Upstream-PR candidate:** yes — `partitionedbyproperty_sql` in
  `sqlglot/generators/doris.py` emits grammar-invalid DDL; verifier-proven against Doris
  FE grammar `g7027772afcb`.
- **Conflict risk:** MEDIUM — if upstream fixes Doris partition rendering, adopt theirs
  if FE-parser-accepted and analyzer-valid; DorisDialectTest defines required behavior.

## 10. Doris: materialized-view column lists use bare names

- **What:** Doris MV column lists take bare column names, optionally with COMMENT
  (`DorisParser.g4` `simpleColumnDef : colName=identifier (COMMENT ...)?`) — types derive
  from the query and cannot be declared. sqlglot re-emits full typed defs (`c1 INT`),
  which the FE parser rejects. We strip the type from ColumnDefs that sit directly in a
  `CREATE MATERIALIZED VIEW` schema (dropping only what Doris cannot express; the engine
  derives the same columns from the query either way). Non-MV CREATEs are untouched.
- **Where:** `dialects/DorisGenerator.kt` `columndefSql`. Tests: `DorisDialectTest`
  (common), `SqlVerifierTest.dorisAcceptsBrikkMaterializedViewColumnRendering` (JVM,
  real FE parser, also pins that typed MV defs are rejected).
- **Corpus/ledger impact:** the divergence is ledgered in
  `generator-corpus/doris-generator-known-failures.json`. The
  `doris-verify-known-failures.json` entry for the corpus case **stays** with an updated
  reason: the upstream test input `CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT)
  KEY (c1)` has no `AS <query>`, which the `createMTMV` rule makes mandatory — a query
  cannot be derived from the AST, so the statement is inherently unverifiable; the FE
  parser now advances past the column list and rejects only at `<EOF>`.
- **Upstream-PR candidate:** yes — same `generators/doris.py` area as #9; the upstream
  test input itself is incomplete Doris DDL.
- **Conflict risk:** MEDIUM — same protocol as #9.

## 11. Presto/Trino: GREATEST/LEAST NULL-algebra flag

- **What:** Presto/Trino `GREATEST`/`LEAST` return NULL if ANY argument is NULL; calls
  parsed under NULL-skipping dialects (DuckDB, Postgres) carry `ignore_nulls=true`
  (probe-verified divergent: `trino-duckdb-hazards.json` "greatest / least" — "never
  translate by name when args may be NULL"). sqlglot renders the bare name silently. No
  result-identical Presto/Trino rewrite is verified (candidates like
  `ARRAY_MAX(FILTER(...))` have unprobed NaN/type edges), so brikk keeps the oracle's
  output but flags it via `unsupported()` when `ignore_nulls=true` and ≥2 arguments.
  The forward direction (NULL-propagating sources → DuckDB) is *upstream parity*, not an
  extension: sqlglot's `_greatest_least_sql` CASE wrap is now ported
  (`DuckdbGenerator.greatestLeastSql`), together with the missing
  `MySQL.LEAST_GREATEST_IGNORES_NULLS=False` parser recording (MySQL/Doris).
- **Where:** `dialects/PrestoGenerator.kt` `greatestLeastSql` (+ TRANSFORMS Greatest/
  Least); parity ports in `dialects/DuckdbGenerator.kt`, `dialects/MysqlParser.kt`.
  Tests: `FunctionSemanticsTest` (common), `SqlVerifierTest.duckdbAccepts...` /
  `trinoAcceptsFunctionSemanticsRenderings` (JVM).
- **Corpus/ledger impact:** none (output unchanged; only a warning is added).
- **Upstream-PR candidate:** yes — sqlglot fixed only the duckdb-target direction; the
  presto generator should at least warn for `ignore_nulls=true`.
- **Conflict risk:** MEDIUM — if upstream adds a Presto-side rewrite, adopt it only if
  probe-verified result-identical (incl. NaN ordering); FunctionSemanticsTest defines
  required behavior.

## 12. Presto/Trino: REGEXP_REPLACE modifiers / replace-first handling

- **What:** Presto/Trino `REGEXP_REPLACE` always replaces ALL matches and has no
  modifiers argument (`trino-duckdb-hazards.json` "regexp_replace", verdict=divergent);
  the only 4-ary form takes a lambda. sqlglot re-emits DuckDB's modifiers as a 4th
  string argument (analyzer-invalid). brikk renders the grammar-legal 3-arg form and
  flags: DuckDB replace-first sources (`single_replace=true` without `'g'`) — Trino has
  no replace-first form; non-`'g'` modifiers — no equivalent, dropped; Snowflake-style
  position/occurrence arguments — dropped. A lone `'g'` is dropped silently (it *is*
  Trino's default). Forward direction (Trino → DuckDB `'g'`-forcing) is upstream parity:
  sqlglot's `regexpreplace_sql` is now ported (`DuckdbGenerator.regexpreplaceSql`).
- **Where:** `dialects/PrestoGenerator.kt` `regexpreplaceSql`; parity port in
  `dialects/DuckdbGenerator.kt`. Tests: `FunctionSemanticsTest`,
  `SqlVerifierTest.trinoAcceptsFunctionSemanticsRenderings`.
- **Corpus/ledger impact:** none — no corpus case renders a modifier-carrying
  RegexpReplace under presto/trino (the presto corpus 3-arg and lambda forms are
  byte-identical through the new path).
- **Upstream-PR candidate:** yes — the 4th-argument re-emission is analyzer-invalid
  Trino; upstream should drop/translate modifiers.
- **Conflict risk:** MEDIUM — same protocol as 11.

## 13. Presto/Trino: scalar-position UNNEST/EXPLODE flag

- **What:** sqlglot eliminates projection-level `Explode` via the
  `explode_projection_to_unnest` Select preprocess, which is not ported (existing
  ledger entries in `duckdb-transpile-known-failures.json` /
  `postgres-transpile-known-failures.json`). Any `Explode` that reaches the Presto
  generator renders as `EXPLODE(...)` — not a Trino function. brikk keeps the fallback
  output (= what the non-ported path already produced) but flags it accurately,
  mirroring the Doris treatment from entry 7.
- **Where:** `dialects/PrestoGenerator.kt` `explodeSql`. Tests: `FunctionSemanticsTest.
  duckdbScalarUnnestToTrinoIsFlaggedNotSilent`.
- **Corpus/ledger impact:** none (warning only; the ledgered cases stay ledgered until
  the preprocess is ported).
- **Upstream-PR candidate:** no (upstream has the transform; this papers over the
  missing port — retire the flag when `explode_projection_to_unnest` lands).
- **Conflict risk:** LOW.

## 14. Catalog-backed fixes for absent-name renders (gap-report bucket B)

- **What:** the Python oracle renders several nodes under function names the target
  engine does not have (`gap-report.json` "renderedNameNotInTargetCatalog"). Where the
  target catalog + engine sources pin an exact equivalent, brikk renders it instead
  (each rendering grammar-pinned in `SqlVerifierTest.*FunctionSemanticsRenderings`):
  - **Presto/Trino targets** (`dialects/PrestoGenerator.kt`): `IsInf` → `IS_INFINITE`
    (was `IS_INF`); `CurrentSchema` → parenthesis-less `CURRENT_SCHEMA` (SqlBase.g4
    special form; `CURRENT_SCHEMA()` is grammar-rejected, verifier-pinned);
    `AddMonths` → `DATE_ADD('MONTH', n, d)` (was `ADD_MONTHS`; `preserve_end_of_month`
    flagged); `StringToArray` → `SPLIT(s, sep)` (was `STRING_TO_ARRAY`; Postgres 3-arg
    null-replacement flagged).
  - **Doris targets** (`dialects/DorisGenerator.kt`): `BitwiseAndAgg/OrAgg/XorAgg` →
    `GROUP_BIT_AND/OR/XOR` (was MySQL's `BIT_AND/...`); `DayOfWeekIso` →
    `(WEEKDAY(x) + 1)` (was `DAYOFWEEK_ISO`; Doris `DAYOFWEEK` is Sun=1 so a bare
    rename would rotate); `ArrayFilter`/`Transform` → `ARRAY_FILTER`/`ARRAY_MAP` with
    the lambda FIRST (FE `ArrayFilter.java`/`ArrayMap.java` signatures); `ArrayPrepend`
    → `ARRAY_PUSHFRONT(arr, e)`; `SortArray` → `ARRAY_SORT`/`ARRAY_REVERSE_SORT`
    (explicit NULL placement flagged); `MD5Digest`/`SHA2Digest` → `UNHEX(MD5(x))` /
    `UNHEX(SHA2(x, n))` (Doris digests return hex VARCHAR vs Trino's VARBINARY — the
    same return-shape hazard the trino↔duckdb research pinned; mirrors the upstream
    duckdb `UNHEX(SHA256(x))` treatment; was `MD5_DIGEST`/`S_H_A2_DIGEST`);
    `ApproxQuantile` → `PERCENTILE_APPROX(x, p)` (approximate↔approximate;
    accuracy/weight flagged — Trino's accuracy fraction is not Doris's compression);
    `GenerateSeries` → `ARRAY_RANGE` (end-exclusive exactly matches duckdb `range`;
    inclusive sources shift the stop bound one step, only when the step direction is
    statically known, else flagged fallback).
- **Where:** as listed. Tests: `FunctionSemanticsTest` (common), the three
  `*FunctionSemanticsRenderings` verifier tests (JVM, real engine parsers).
- **Corpus/ledger impact:** none — no dialect-corpus case exercises these paths for the
  affected targets (checked before landing).
- **Upstream-PR candidate:** yes, all of them (the emitted names simply do not exist in
  the target engines; several are broken default `sql_name`s, e.g. `S_H_A2_DIGEST`).
- **Conflict risk:** MEDIUM — if upstream adds its own mappings, adopt if
  verifier-accepted and argument order matches the FE sources (watch lambda-first for
  Doris).

## 15. DuckDB: zone-less TIME(x) renders on the wall-clock tier

- **What:** sqlglot's `no_time_sql` transpiles `TIME(x, zone)` to
  `CAST(CAST(x AS TIMESTAMPTZ) AT TIME ZONE zone AS TIME)`; with no zone (Doris/MySQL
  `TIME(x)`, which extracts wall-clock time) it emits an empty `AT TIME ZONE` operand —
  grammar-invalid DuckDB (verifier-pinned reject). brikk renders the zone-less form as
  `CAST(CAST(x AS TIMESTAMP) AS TIME)`: valid, and session-zone-invariant per the
  probe-verified wall-clock tier (hazards doc "Datetime / timezone" — TIMESTAMPTZ reads
  are reinterpreted through the session zone, plain TIMESTAMP is not). The zone-carrying
  branch is the faithful `no_time_sql` port (previously missing entirely).
- **Where:** `dialects/DuckdbGenerator.kt` `noTimeSql` (+ TRANSFORMS Time). Tests:
  `FunctionSemanticsTest.dorisTimeToDuckdbStaysOnWallClockTier`,
  `SqlVerifierTest.duckdbAcceptsFunctionSemanticsRenderings` (accept + reject pins).
- **Corpus/ledger impact:** none (no corpus case hits exp.Time under duckdb).
- **Upstream-PR candidate:** yes — `no_time_sql` emits invalid SQL for a missing zone
  in every dialect that uses it.
- **Conflict risk:** LOW-MEDIUM — if upstream fixes the empty-zone case, adopt theirs
  if it stays on the wall-clock tier (a TIMESTAMPTZ detour is semantically wrong for
  zone-less sources).

## 16. DataFusion: an entire brikk-native dialect (no sqlglot counterpart)

- **What:** the `datafusion` dialect (alias `arrow-datafusion`) is brikk-native — sqlglot
  ships NO DataFusion dialect, so this is not a port and carries no `// sqlglot:`
  provenance (its comments use `// brikk:`). It is a thin, flag-tuned child of the BASE
  dialect modeled on polyglot's standalone DataFusion dialect
  (`reference/polyglot/.../dialects/datafusion.rs`) cross-checked against the DataFusion
  SQL docs. Generator deltas over BASE: `normalize_functions=lower`,
  `copyHasIntoKeyword=false`, `supportsLikeQuantifiers=false`, `multiArgDistinct=false`,
  `supportsWindowExclude=false`; transpile renames SQUARE→power, REGEXP_MATCHES→regexp_match,
  DATE_FORMAT/TIME_TO_STR→to_char, GROUP_CONCAT/LISTAGG→string_agg (IFNULL→coalesce comes
  for free from BASE parsing). Tokenizer = `TokenizerConfig.BASE` unchanged (double-quote
  identifiers, nested comments and the `|>` token are all already BASE-true — no
  `DatafusionTokenizerTables`). Parser is a near-empty passthrough.
- **Where:** `dialects/DatafusionDialect.kt`, `DatafusionParser.kt`, `DatafusionGenerator.kt`;
  registered in `dialects/Dialect.kt` (`Dialects.DATAFUSION`).
- **Gates (oracle-less — stated in each test's KDoc):**
  `DatafusionFixtureTest` (275/275 polyglot-derived identity fixtures plus transpile cases,
  `dialect-corpus/datafusion-fixtures.json` via `tools/import_polyglot_datafusion_fixtures.py`),
  `DatafusionSltParseTest` (real-engine parse-acceptance from DataFusion's own
  sqllogictest suite, `tools/extract_datafusion_slt_corpus.py`, 1208/1208 parsed),
  `DatafusionPipeSmokeTest` and `DatafusionDialectTest`. An engine verifier is
  **phase 2** (not built).
- **Upstream-conflict note:** if a future sqlglot version ADDS its own `datafusion`
  dialect, the sync MUST reconcile: adopt sqlglot's dialect (retiring this one, moving
  its fixtures/SLT gates onto the ported implementation) or keep ours if upstream's is
  thinner — and switch the provenance comments from `// brikk:` to `// sqlglot:` for any
  behavior that becomes a port. Until then this dialect owns the `datafusion` /
  `arrow-datafusion` registry names outright.
- **Conflict risk:** NONE today (native); becomes MEDIUM the moment upstream adds a
  DataFusion dialect.

## Not-a-bug findings (for the record)

- **Trino `date_trunc` unit casing:** reported as "lowercase-only, case-sensitive";
  verified FALSE against reference/trino source — `DateTimeFieldProvider.match()` folds
  case via `| 0x20` (DateTimeFunctions.java:352), so `'MONTH'` matches. sqlglot's
  uppercase-unit output is valid Trino; no divergence implemented.

## 17. StarRocks dialect + version-pinned semantics (StarRocks 4.1.4)

The `starrocks` dialect is a faithful port of sqlglot's StarRocks dialect (StarRocks
extends MySQL, mirroring upstream — NOT Doris inheritance). The port itself is upstream
parity; the entries below are the brikk-side additions/base changes it required:

- **Base parser made overridable for StarRocks**: `Parser.parseCreate` / `parseUnnest`
  are now `open` (StarRocks overrides `_parse_create` to hoist the out-of-schema PRIMARY
  KEY and `_parse_unnest` to default the `unnest` alias/column).
- **`parseTable` TableFromRows hoist** (base parity, previously unexercised): `TABLE(<tvf>)`
  parses to a `Table` wrapping `TableFromRows`, then hoists alias/joins/pivots/sample onto
  the latter — sqlglot base behavior, first reached via StarRocks' `TABLE(GENERATE_SERIES(...))`.
- **`parseRefresh` EXTERNAL TABLE** (sqlglot `3c6d84248`, previously excluded as a
  StarRocks-only fix): the `REFRESH EXTERNAL TABLE` branch is now ported.
- **BigQuery `DATE_DIFF` unit normalization** (bug fix, not a divergence): the BigQuery
  builder now normalizes the date-part unit via `TimeUnit.__init__` semantics (Column→Var),
  matching sqlglot; this also fixed the BigQuery `WEEK(SUNDAY)`→`WEEK` generator gap. Both
  cleared 19 previously-ledgered BigQuery transpile/generator cases.
- **Version pin**: the StarRocks function catalog + live semantic probe + engine
  verification are pinned to **StarRocks 4.1.4** (tag `4.1.4` → commit `4a9848e`; Docker
  `starrocks/allin1-ubuntu:4.1.4`). No accommodation for older StarRocks versions.

## 18. Semantic coverage gate (conservative refusal for unresearched pairs)

**NOT sqlglot parity.** `SemanticCoverage` (brikk-sql-metadata) records explicit
per-dialect `EngineClass` plus directional, versioned semantic scopes. A hazard file is
`PARTIAL` by default; its existence never means the pair is complete. Under a partial
scope, every encountered function must resolve (through canonical, alias, source-rendered,
or target-rendered keys) to an evidence-backed concept or `certify()` emits a
`SEMANTIC_COVERAGE_UNKNOWN` refusal. `COMPLETE` requires an explicit closed-world
declaration proving every applicable catalog relationship was classified and tested.
Entirely unresearched real-engine pairs also refuse. base/sqlglot (translation-only) and
datafusion (brikk-native) remain explicit non-engine classifications. Regenerated by
`tools/generate_semantic_coverage.py`; pinned by `SemanticCoverageSyncTest`.

### DuckDB ↔ Doris CAST coverage (0.18.1 fix)

`CAST` now resolves to researched **conditionally-equivalent** evidence, including
DuckDB's `::` spelling. The SQL generation is unchanged. Certification replaces the
unknown-coverage refusal with a visible conditional warning; the pair stays PARTIAL.
This is not blanket CAST acceptance: numeric rounding versus truncation, integer-text
parsing, strict-mode errors versus NULL, boolean formatting and DATE-to-integer casts
have pinned divergences. A bare column supplies no type/domain proof. Existing
`ok`/`orThrow()` policy permits warnings; consumers requiring unconditional equivalence
must review or escalate them. TRY_CAST is not registered as an alias.
See [the research report](research/REPORT-duckdb-doris-cast-coverage-2026-10-10.md)
for the 22 live DuckDB 1.5.6 / Doris 4.1.4.1 cases and scope limits.

## 19. Doris: DDL parsing for `SHOW CREATE TABLE` output (brikk-native)

**NOT sqlglot parity — brikk exceeds upstream.** sqlglot's Doris dialect is used for
transpiling queries *into* Doris and never parses Doris DDL; upstream HEAD
(`v30.17.0-97`, checked Sep 2026) fails every case below identically. Needed so Doris
can be the schema-cache dialect (`docs/virtual-pipelines-wiring.md`, "Schema cache
format"). Parser fidelity is the goal; the generator renders each node back so the DDL
round-trips, and every rendering is accepted by the real Doris FE parser.

- **What / Where:**
  - *Type keywords* — `DorisDialect.TOKENIZER_CONFIG` layers `LARGEINT → INT128`
    (StarRocks' mapping, never propagated to Doris upstream), `IPV4` / `IPV6` (existing
    kinds; ClickHouse tokenizer has them), `DECIMALV2` / `DECIMALV3 → DECIMAL`,
    `DATETIMEV2 → DATETIME`, `DATEV2 → DATE`, and the brikk-native `BITMAP` / `HLL` /
    `QUANTILE_STATE` / `AGG_STATE` TokenType + DType members over the
    GENERATED `DorisTokenizerTables.CONFIG` (`TokenizerConfig.withKeywords`). The
    generated tables stay oracle-parity for `TokenCorpusDifferentialTest`. The native
    enum members are appended by `tools/gen_tokenizer_tables.py`
    (`BRIKK_NATIVE_TOKEN_TYPES`) and `tools/gen_ast_nodes.py` (`BRIKK_NATIVE_DTYPES`),
    each with an assert that fires if upstream ever adds a same-named member.
    `DorisGenerator.TYPE_MAPPING` adds `INT128 → LARGEINT` (sqlglot emits `INT128`,
    which the FE rejects).
  - *Aggregate-key tables* — `AGGREGATE KEY (cols)` → `AggregateKeyProperty`; column
    aggregator suffix (`v BIGINT SUM`, `b BITMAP BITMAP_UNION`, `SUM|MAX|MIN|REPLACE|
    REPLACE_IF_NOT_NULL|HLL_UNION|BITMAP_UNION|QUANTILE_UNION|GENERIC`) →
    `ColumnConstraint(kind = AggregateTypeColumnConstraint)` via
    `DorisParserTables.CONSTRAINT_PARSERS`.
  - *Parameterized storage types* — `DorisParser.parseTypes` intercepts
    `VARIANT<'name':type, ..., properties("k" = "v")>` (→ `DataType(VARIANT, nested,
    expressions = DorisVariantField* + Properties?)`) and
    `AGG_STATE<fn(type [NULL | NOT NULL], ...)>` (→ `DataType(AGG_STATE, nested,
    expressions = [Anonymous(fn, DataType*)])`, argument nullability on each
    `DataType.nullable`). `DorisGenerator.datatypeSql` renders both. Variant fields are
    `DorisVariantField` nodes (`this` = quoted name, `kind`, optional `match` =
    `MATCH_NAME` / `MATCH_NAME_GLOB`).
  - *Partition definition lists* — `DorisParser.parsePartitionProperty` parses the whole
    list itself (`parsePartitionDefinition`) instead of going through the MySQL port:
    `()` (what dynamic / auto partitioning emits) yields `PartitionByRange/ListProperty`
    with an **empty** `create_expressions` (built directly; sqlglot's required-arg check
    rejects it); `PARTITION BY LIST (...)` yields `PartitionByListProperty` even without
    definitions (sqlglot always builds RANGE); entries may mix `VALUES LESS THAN (..)`,
    bare `VALUES LESS THAN MAXVALUE`, `VALUES [(..), (..))`, `VALUES IN (..)` and
    `FROM (..) TO (..) INTERVAL n [unit]` (numeric bounds, optional unit) in one list;
    a trailing per-partition `("k" = "v")` list is kept as a `Properties` node appended
    to the `Partition`'s expressions and rendered bare by `DorisGenerator.partitionSql`.
    `MAXVALUE` is normalized to a `Var` per column (MySQL only does so for a lone value).
    `DorisGenerator.partitionrangeSql` treats only a list-of-lists as the bracket
    range; sqlglot rendered every multi-value `PartitionRange` as one, turning a
    multi-column `LESS THAN ('2020-01-01', 100)` into `[('2020-01-01'), (100))`.
    The kind-less `PARTITION BY (cols)` path of #9 is unchanged.
  - *Temporary-partition DML targets* — `INSERT INTO t TEMPORARY PARTITION (p, ...)
    [(columns)] SELECT ...` keeps the ordinary `Partition` selection inside a
    `DorisTemporaryPartition` wrapper and round-trips through `DorisGenerator`.
  - *`AUTO PARTITION BY RANGE|LIST (...) (...)`* → `AutoPartitionProperty(this =
    inner partition property)`; a bare `AUTO` not followed by `PARTITION BY` is given
    back to the property loop. Previously an opaque `Command`.
  - *`INDEX name (cols) USING INVERTED PROPERTIES (...) COMMENT '...'`* →
    `IndexPropertiesOption` in `IndexColumnConstraint.options`.
    `MysqlParser.parseIndexConstraintOption` was extracted from the option loop (same
    behavior) so Doris can override it.
  - *`ROLLUP (name (cols) [DUPLICATE KEY (cols)] [PROPERTIES (...)], ...)`* →
    `RollupProperty` of `DorisRollupIndex` nodes. Shaped after StarRocks'
    `_parse_rollup_property` (upstream #4509), but the Doris entry grammar
    (`DorisParser.g4` `rollupDef`) has `DUPLICATE KEY` where StarRocks has `FROM base`
    — the FE parser rejects the StarRocks form — hence a separate node rather than
    sqlglot's `RollupIndex`. `RollupProperty` is lifted from UNSUPPORTED to
    POST_SCHEMA for Doris (as StarRocks does).
  - *Statement-level DDL a pipeline runtime issues* (each a brikk-native node in
    `ast/DorisNodes.kt`, rendered by `DorisGenerator`, FE-parser verified):
    `REFRESH MATERIALIZED VIEW [db.]mv [AUTO | COMPLETE] [PARTITION[S] (..)]`,
    `REFRESH CATALOG c [PROPERTIES (..)]`, `REFRESH DATABASE db` → `DorisRefresh`
    (`REFRESH TABLE` stays on sqlglot's `Refresh`; the `REFRESH` statement token had to
    be added to the Doris tokenizer — StarRocks' table has it, Doris' never did, so every
    REFRESH statement hard-failed). `CREATE INDEX .. ON t (cols) [USING ..] [PROPERTIES (..)]
    [COMMENT ..]` → `Index.params = DorisIndexParameters` (sqlglot's `IndexParameters`
    wants USING before the columns; `parseIndexParams` made `open`). `ALTER TABLE` actions
    `ADD [TEMPORARY] PARTITION` → `DorisAddPartition`, `DROP [TEMPORARY] PARTITION [IF
    EXISTS] p [FORCE] [FROM INDEX r]` → `DorisDropPartition`, `REPLACE PARTITION (..) WITH
    TEMPORARY PARTITION (..) [FORCE] [PROPERTIES (..)]` → `DorisReplacePartition`, `MODIFY
    PARTITION p | (..) | (*) SET (..)` → `DorisModifyPartition`, `RENAME PARTITION | ROLLUP`
    → `DorisRename`, `RENAME COLUMN a b` (no TO) → sqlglot `RenameColumn` with a Doris
    renderer, `ADD ROLLUP r (cols) [DUPLICATE KEY] [FROM base] [PROPERTIES], ..` →
    `DorisAddRollup` of `DorisRollupIndex`, `REPLACE WITH TABLE t2 [PROPERTIES]` →
    `DorisReplaceWith`; all via `DorisParser.alterParsers` overrides for ADD / DROP /
    MODIFY / REPLACE that fall back to the MySQL parser. `ALTER MATERIALIZED VIEW mv
    REFRESH .. | RENAME .. | SET (..) | REPLACE WITH MATERIALIZED VIEW ..` → `Alter(kind =
    "MATERIALIZED VIEW")` via a `parseAlter` override (made `open`); the MV `REFRESH`
    method is now optional (`REFRESH ON COMMIT`; sqlglot's port took ON as the method).
    `ALTER .. SET (..)` keeps sqlglot's `AlterSet` with `alterSetWrapped = true` (the FE
    rejects the bare form). `DESC t ALL` → `Describe.style = "ALL"` rendered trailing.
    `CREATE TABLE t2 LIKE t WITH ROLLUP [(r1, ..)]` → a `WITH ROLLUP` `Property` on
    `LikeProperty` whose value is the name `Tuple`. `DROP INDEX idx ON db.t`: `OnProperty`
    takes a `Table` (was a lone identifier). `BUILD` / `CANCEL` / `PAUSE` / `RECOVER` /
    `RESUME` are `COMMAND` tokens so their statements degrade to `Command` instead of a
    parse error.
  - *Remaining ALTER TABLE actions* — `ADD COLUMN col | (cols) [TO rollup] [PROPERTIES]` →
    `DorisAddColumn` (only when a target / properties / the parenthesized form is present;
    a plain `ADD COLUMN c INT` stays sqlglot's bare `ColumnDef` action; the `TO rollup`
    form used to mis-parse into a bogus second action), `ORDER BY (cols) [FROM rollup]` →
    `DorisAlterOrderBy`, `ENABLE FEATURE '..' [WITH PROPERTIES]` → `DorisEnableFeature`,
    `MODIFY DISTRIBUTION ..` / `MODIFY ENGINE TO ..` / `MODIFY COMMENT ..` →
    `DorisModifyDistribution` / `DorisModifyEngine` / `DorisModifyComment`.
  - *COMMAND-word statements* — `BUILD INDEX idx ON t [PARTITIONS (..)]` →
    `DorisBuildIndex`, `PAUSE | RESUME MATERIALIZED VIEW JOB ON mv` →
    `DorisMaterializedViewJob`, `CANCEL MATERIALIZED VIEW TASK n ON mv` →
    `DorisCancelMaterializedViewTask`, `RECOVER TABLE | DATABASE | PARTITION .. [id] [AS new]
    [FROM t]` → `DorisRecover`. These words are `COMMAND` tokens, and sqlglot's tokenizer
    folds the rest of a command statement into a single STRING token, so
    `DorisParser.parseCommand` re-tokenizes that body and parses it with a fresh parser
    (`Parser.parseIntoWith`, brikk-native); shapes it does not model stay an opaque
    `Command`, and the words remain usable as identifiers.
  - *SHOW* — `SHOW CREATE MATERIALIZED VIEW [db.]mv [ON t]` (`for_table` holds the ON
    target), `SHOW [TEMPORARY] PARTITIONS FROM [db.]t [WHERE] [LIMIT]`, `SHOW DATA [FROM
    [db.]t]` via `DorisParserTables.SHOW_PARSERS`; `DorisGenerator.showSql` renders these
    and keeps `SHOW CREATE <kind> db.t` db-qualified (MySQL's `t FROM db` is rejected).
  - *Column-definition renderings the FE rejects when inherited from MySQL*
    (generator-only): `k INT KEY` (was `PRIMARY KEY`), `STRUCT<x:INT>` (was `x INT`),
    `DEFAULT CURRENT_TIMESTAMP[(n)]` / `ON UPDATE CURRENT_TIMESTAMP` / `DEFAULT CURRENT_DATE`
    (were `NOW()` / back-quoted; the `CurrentTimestamp → NOW()` mapping still applies outside
    DEFAULT), `b INT AS (a + 1)` (was `GENERATED ALWAYS AS (..) VIRTUAL`),
    `AUTO_INCREMENT(start)` (start value was dropped; `parseAutoIncrementDoris` keeps it as a
    `GeneratedAsIdentityColumnConstraint(this = false, start)`).
  - Nodes: `ast/DorisNodes.kt` (module `brikk.doris`, allow-listed in
    `NATIVE_EXPRESSION_CLASSES`, scraped as HANDWRITTEN by `gen_ast_nodes.py`).
    Parser: `dialects/DorisParser.kt` (`parsePartitionProperty`,
    `parseAutoPartitionProperty`, `parseAggregateTypeConstraint`,
    `parseIndexConstraintOption`, `parseRollupProperty`, `DorisParserTables`).
    Generator: `dialects/DorisGenerator.kt` TRANSFORMS / PROPERTIES_LOCATION /
    `dorisrollupindexSql`.
  - Doris 4.1.4: `DEFAULT(qualifiedName)` uses `DorisDefault`, whose identifier path
    denotes a write-schema default rather than input-row lineage. Single-column
    ALTER ADD/MODIFY retains nested field paths, quoting, nullability, comments, and
    position. Nested defaults and ON UPDATE remain grammar-invalid. Tablet compaction
    uses `DorisCompactTablet`; SHOW COMPUTE GROUPS uses Show. TIMESTAMPTZ now stays
    TIMESTAMPTZ during generation, including the microsecond ISO timestamp conversion.
    The two Variant parsing functions infer VARIANT and use their catalog nullability.
    Tests: `Doris414Test`, `Doris414GrammarTest`, and `FunctionCatalogTest`.
    Native verification uses the exact release grammar; see `vendor/README.md`.
- **Tests:** `DorisDialectTest` (DDL section: each clause + realistic `SHOW CREATE TABLE`
  statements incl. a MoW unique-key table with `ORDER BY`, typed VARIANTs and a function
  RANGE partition, all asserted as `Create` with a stable re-parse; statement section:
  each REFRESH / CREATE INDEX / ALTER form asserted as its node class with a stable
  re-parse, incl. the COMMAND-word statements and the SHOW forms; group-B column renderings),
  `DorisTokenizerTest.dialectConfigAddsDorisStorageTypeKeywords`,
  `SqlVerifierTest.dorisAcceptsBrikkDdlRenderings` (JVM, real FE parser).
- **Known lossy:** `BUCKETS AUTO` is dropped by the base `parseDistributedProperty`
  (absent = AUTO in Doris ≥ 1.2, so the round-trip is semantically equivalent).
  `DECIMALV3(p, s)` / `DATETIMEV2(n)` / `DATEV2` render as `DECIMAL` / `DATETIME` / `DATE`
  (the same types in current Doris). `PARTITION IF NOT EXISTS p ...` inside a CREATE TABLE
  definition list is accepted and the flag dropped (a no-op for a new table; the ALTER
  TABLE ADD PARTITION form keeps it).
- **Backlog:** `TODO-doris-ddl.md` tracks the remaining Doris DDL gaps (statement-level
  DDL, generator renderings the FE rejects).
- **Corpus/ledger impact:** none — no Python-oracle corpus case exercises these inputs,
  and all ledgers are unchanged.
- **Upstream-PR candidate:** the tokenizer / type-mapping bits (`LARGEINT`, `IPV4`/
  `IPV6`, `DECIMALV3`, `INT128 → LARGEINT`) and the empty-partition-list tolerance are
  small and general; the aggregator / AUTO PARTITION / INDEX PROPERTIES / rollup nodes
  are probably too Doris-specific for sqlglot.
- **Conflict risk:** MEDIUM — if upstream adds a `BITMAP` / `HLL` / `QUANTILE_STATE`
  TokenType or DType, the generator asserts fire: drop ours and re-point the Doris
  keywords at the upstream member. If upstream adds Doris DDL parsing, compare node
  shapes and prefer theirs when equivalent; `DorisDialectTest` defines required behavior.

## 20. Outer explosion cardinality and positions

- **ASTRA-002:** `explodeProjectionToUnnest` now includes the pinned upstream
  empty/null array normalization for EXPLODE_OUTER and POSEXPLODE_OUTER. A safe
  first-element lookup creates a correctly typed null singleton for missing input.
- **Local corrections:** outer maps use LEFT JOIN UNNEST ON TRUE, since maps cannot
  contain synthetic null keys. Outer positional output puts position before value,
  uses zero-based positions, and returns null position for the synthetic row.
  Private UNNEST columns avoid capturing source predicates; default public names
  remain `col`, `pos`, `key`, and `value`. Map dispatch requires type evidence,
  as with the existing non-outer map path.
- **Verification:** `OuterExplodeResultTest` checks target grammar and output names
  on every run. With `BRIKK_TRINO_CONTAINER` set to a running Trino 483 Docker
  container it executes the actual Presto/Trino-generated SQL and compares row
  multisets, including nulls and duplicate counts. This mode was run for this fix.
  It is Trino execution, not a live Presto or Spark check. The normal full suite
  does not start Docker. The original outer correction needed no ledger changes.
- **ASTRA-002-ZIP:** the inherited empty zipped-input defect is fixed separately.
  Multiple array inputs use LEFT JOIN UNNEST ON TRUE with null-ordinal padding.
  Null cardinalities become zero; when every input is empty, an empty position
  array avoids Presto/Trino's descending `SEQUENCE(1, 0)`. The focused regression
  compares DuckDB source results with the actual Trino output for empty, null,
  unequal-length, duplicate, and per-row inputs. Three nonempty corpus cases
  change SQL spelling under this general correction: two DuckDB and one Spark
  `write|presto` cases. Those exact transpile-ledger entries are intentional and
  protected, while the upstream fixtures and gates remain unchanged.
- **Upstream sync:** retain these result tests when adopting changes to
  `explode_projection_to_unnest`. The array branch follows the pinned upstream;
  map/position/name corrections are local. Upstream reporting/adoption is pending.

## 21. Temporal difference precision and elapsed days

- **ASTRA-010:** the missing BigQuery TIMESTAMP_DIFF unit and Presto/Trino
  TimestampDiff/DatetimeDiff mappings are restored, including the upstream
  week-start alignment. Eleven exact BigQuery transpile failures now pass.
- **Local corrections:** timestamp DAY differences use complete elapsed hours
  divided by 24, avoiding calendar-day differences across DST. Newly coerced
  timestamp literals retain six-digit precision; fractional typed literals also
  avoid millisecond rounding across a boundary. BigQuery's unzoned timestamp
  strings get UTC explicitly, independently of the target session timezone.
  Unsupported units and unknown week starts report generator diagnostics.
- **Where:** `PrestoGenerator.dateDiffSql`. `TemporalDiffResultTest` runs actual
  Presto/Trino output on Trino 483 when `BRIKK_TRINO_CONTAINER` is set, including
  UTC and America/New_York sessions. No live BigQuery or Presto execution is claimed.
  The native BigQuery and cross-dialect corpus gates retain their upstream fixtures.
- **Upstream sync:** keep elapsed-day and precision regressions when adopting
  `_date_diff_sql` changes. Local correctness corrections have no new corpus
  mismatches at the current pin; reporting/adoption status is pending.

## 22. BigQuery UNNEST alias and offset contracts

- **ASTRA-008:** explicit and implicit BigQuery UNNEST aliases use the upstream
  column-only AST representation. Implicit paths resolve against preceding sources
  using BigQuery's identifier rules, including quoted physical-table names.
  Generator cleanup removes internal relation qualifiers by scope, without stripping
  struct-value prefixes or nested aliases that shadow an outer UNNEST.
- **Cross-dialect corrections:** Presto/Trino keep named struct elements intact with
  a single-field ROW wrapper. Named BigQuery offsets use a lateral projection that
  exposes zero-based positions. In the reverse source-aware route, named relation
  columns become fields of an ARRAY subquery's STRUCT, preserving qualifications
  and renamed fields; explicit ordinality becomes offset plus one.
- **Limits:** unknown element types, unresolved bare/star/nested struct references,
  offsets without the required aliases, and filtered correlated outer offset joins
  report unsupported diagnostics. Supplied schema annotations are retained. This
  does not implement all array operations or every relational UNNEST form.
- **Verification:** `BigqueryUnnestResultTest` executes forward output on Trino 483
  when `BRIKK_TRINO_CONTAINER` is set. Native BigQuery and reverse output have
  parser/SQL-shape checks, not live BigQuery execution. All ten remaining exposed
  qualification cases and eighteen existing transpile-ledger cases now pass.
- **Upstream sync:** the parser/normalization representation follows the pin; row
  preservation, offset conversion, and scope-aware cleanup are local corrections.
  Retain these regressions rather than reverting toward silent upstream mistakes.

## 23. Doris: preserve native FULL OUTER JOIN

Doris supports `FULL OUTER JOIN`, so its SELECT preprocessing must not inherit
MySQL's `eliminateFullOuterJoin`. That rewrite aggregates and deduplicates each
branch separately before combining them with `UNION ALL`. It also changes an OR
filter's grouping when adding the right-only predicate. The generated SQL passes
the Doris grammar with no unsupported diagnostics but returns incorrect rows.

`dialects/DorisGenerator.kt` overrides the SELECT dispatch entry, retaining
`eliminateDistinctOn`, `eliminateSemiAndAntiJoins`, and `eliminateQualify` while
leaving the native full join intact. MySQL's rewrite is unchanged and needs a
separate semantic correction.

Regression coverage is in `DorisDialectTest`, `PipeDorisDesugarTest`, and
`brikk-sql-verify`'s `DorisFullOuterJoinTest`. The latter checks Doris grammar
acceptance and executes the generated portable SQL unchanged in embedded DuckDB.
It covers counts, grouped counts, DISTINCT, OR/filter/LIMIT, matching and unmatched
rows, duplicates, NULL keys, and empty inputs. It does not claim live Doris or
plugin JDBC coverage. No dependency was added.

Upstream syncs must retain this override unless upstream also preserves Doris's
native full join. Existing corpus ledgers are unchanged.

## 24. BigQuery relation-lowering guards (ASTRA-009)

`BigqueryGenerator.cteSql` pushes CTE column names into explicit projections,
including the left side of set operations. Partial lists leave trailing columns
intact. Unexpanded stars and overlong lists produce unsupported diagnostics.
Unlike the pin, alias shadowing also produces a diagnostic when query modifiers
refer to the old alias, rather than silently emitting an invalid reference.

Derived VALUES become UNNEST arrays of named structs. The rewrite walks only
direct rows, not nested tuples, and checks row/alias widths before pairing cells
with names. VALUES-local ordering/limits are diagnosed, not dropped. INSERT
VALUES stays unchanged. These checks are local correctness guards around the
pinned SQLGlot rewrites; retain them on upstream sync.

`BigqueryRelationLoweringTest` checks exact SQL, quoted names, duplicates, NULLs,
partial CTE lists, alias shadowing, set operations, diagnostics, and source-AST
immutability. `BigqueryUnnestResultTest` checks the BigQuery output after conversion
to Presto/Trino and optionally executes it with `BRIKK_TRINO_CONTAINER`. There is
no live BigQuery execution claim.

## 25. Doris: DISTINCT ON output columns and row restrictions

For DISTINCT ON with projection-level stars or final LIMIT/OFFSET, Doris uses native
`QUALIFY ROW_NUMBER() OVER (...) = 1`. No ranking helper is projected. User columns
named `_row_number` remain untouched, as do qualified stars and mixed projections.
Original ordering is copied into the ranking window and retained for final output;
LIMIT/OFFSET remain after ranking. Unrestricted explicit projections keep the
existing subquery rewrite.

The pinned Doris source `7027772afcb` supports direct window expressions in QUALIFY
(`DorisParser.g4`, `FillUpQualifyMissingSlot`). The synthesized clause deliberately
bypasses `eliminateQualify`, which would reintroduce the helper-column leak. This
path refuses existing QUALIFY, window/subquery ranking keys, positional keys, and
unresolved projection-alias references with `UnsupportedError` rather than emit a
silently different query. Ordinary QUALIFY uses the separate native/fallback
binding rules described in entry 6.

The shared `eliminateDistinctOn` also distinguishes a projection star from a star
inside a scalar subquery. The latter needs an explicit outer scalar projection,
not `SELECT *` exporting its ranking helper.

Tests: `DorisDistinctOnTest`, `PipeQueryTest`, `SourceMapTest`, and verifier module
`DorisDistinctSemanticsTest`. Result tests execute generated SQL unchanged in
embedded DuckDB, asserting row multiplicities and actual column names/order, and
check the native Doris grammar. They include prior restrictions, head DISTINCT ON,
qualified references, alias/CTE collisions, correlated and shadowed namespaces,
NULL groups, empty inputs, mixed stars, and real helper-like column names. No live
Doris execution is claimed; other dialects' unresolved star rewrites are not fixed
by this Doris-specific path. Existing corpus expectations and ledgers are unchanged.

## 26. Presto/Trino struct-array NULL fields (BQ-1)

Literal NULL fields in an array of structs must inherit a type from non-NULL
sibling fields before rendering named ROW casts. BigQuery's standalone BIGINT
default otherwise creates incompatible row types beside strings or nested values.
`PrestoGenerator` reconciles those literal fields positionally, including nested
structs and arrays. Explicit casts remain authoritative; all-NULL fields keep
their default. This changes generator-local annotations, not the caller's AST or
the shared SQLGlot annotation corpus.

`BigqueryStructArrayTypesTest` covers NULL-first/last, duplicates, nested values,
typed NULLs, all-NULL defaults, and source-AST preservation. The optional Trino
execution checks in `BigqueryUnnestResultTest` no longer need a string cast around
the NULL in lowered VALUES. This is a local correction to the pin's per-row typing;
retain it until upstream reconciles these fields too.

## 27. BigQuery scalar result and mode guards (BQ-26, BQ-30)

BigQuery UUID parsing records its STRING result contract. The base UUID generator
honors that flag with a target string cast. PostgreSQL's GEN_RANDOM_UUID override
also honors it, unlike the pin, which otherwise returns PostgreSQL's UUID type.
Native UUID calls without the flag keep their original return type. Tests cover
the five pinned BigQuery routes plus PostgreSQL, AST preservation, and DuckDB's
actual text type and string operations.

DuckDB ROUND lowering supports the two BigQuery literal modes. An unknown or
dynamic mode is retained, as in the pin, but additionally produces an unsupported
diagnostic. It must not silently become ordinary rounding. Executed DuckDB tests
cover signed ties/non-ties, negative and positive scales, NULLs, and native defaults.

## 28. BigQuery epoch floors and UTC date extraction (BQ-4, BQ-5)

DuckDB epoch generation truncates the timestamp to seconds/milliseconds before
conversion. The pin's BIGINT cast rounds fractional seconds; EPOCH_MS alone
truncates negative fractions toward zero. Both violate BigQuery whole-unit floor
semantics. EPOCH_US remains integer-valued and preserves microseconds. UNIX_DATE
and TIME_DIFF use DATE_DIFF with the correct unit and argument order.

DATE extraction converts a TIMESTAMPTZ instant directly to the requested zone,
defaulting to UTC, rather than casting it to a session-local TIMESTAMP and then
reinterpreting that clock time as UTC. Unzoned timestamp literals get explicit
UTC, with midnight added to date-only literals. Explicit zones survive. With an
explicit BigQuery source, CURRENT_DATE without a zone also uses UTC; native DuckDB
CURRENT_DATE remains session-local. Unknown DATE argument types are diagnosed.

Five existing assertion IDs remain protected with reviewed new signatures:
three BigQuery-ledger UNIX_SECONDS/UNIX_MILLIS writes to DuckDB, and two DuckDB-ledger
BigQuery DATE reads using Los Angeles/Berlin. The Berlin fixture is equivalent to
the pin but uses the same UTC-normalized lowering as explicitly zoned inputs.
They carry `status: intentional-divergence` and are excluded from actionable BQ
counts. Do not remove them or restore the previous SQL to improve parity.

`BigqueryEpochResultTest` executes negative/fractional boundaries, years 0001/9999,
INT64 result types, and TIME_DIFF directions in DuckDB under UTC/New York.
`BigqueryDateResultTest` checks offsets, dates near midnight, typed NULLs, and
CURRENT_DATE across UTC/New York/Auckland sessions. These are DuckDB execution
checks, not live BigQuery verification. Upstream issue/PR status: not reported.

## 29. Array-to-string replacement bindings (BQ-14)

DuckDB's three-argument ARRAY_TO_STRING lowering uses LIST_TRANSFORM/COALESCE,
as in the pin, but allocates a lambda name that cannot shadow identifiers in the
source expression. A runtime replacement column named `x` must not become a
reference to the lambda's element. The body uses a Column node so forced identifier
quoting remains consistent. `BigqueryArrayToStringResultTest` executes runtime
replacement columns, mixed-case/qualified collisions, escaping, and NULL inputs.
An explicitly NULL BigQuery delimiter yields NULL, not DuckDB's default comma.
The two-argument SQL remains unchanged. Column-dependent delimiters are diagnosed:
DuckDB's underlying STRING_AGG requires a constant separator. Their separate
lowering is tracked as BQ-36. No existing ledger divergence is needed.

## 30. BigQuery temporal constructors and parsers (BQ-6, BQ-7)

BigQuery DATETIME parsing stores its second argument in the AST's `expression`
slot, preserving date/time and timestamp/zone overloads through native generation.
DuckDB lowering keeps absolute TIMESTAMP values separate from civil DATETIME/TIME:
TIMESTAMP uses TIMESTAMPTZ, date plus time produces TIMESTAMP, and timestamp plus
zone converts a UTC instant to local civil time. Presto retains WITH TIME ZONE.

Unzoned strings in DATETIME(timestamp, zone) are normalized to UTC before zone
conversion. This intentionally differs from the pin's session-dependent direct
TIMESTAMPTZ cast. STRING(timestamp, zone) uses the same instant conversion but
currently emits a diagnostic because DuckDB TEXT omits BigQuery's UTC offset;
offset-preserving formatting is BQ-37. Zone-less TIMESTAMP with an unknown input
type and DATETIME with an unknown second-argument type are also diagnosed instead
of guessed; schema-driven overload resolution is BQ-38. Native BigQuery keeps both
arguments.

DuckDB PARSE_DATETIME applies BigQuery's 1970 default year and PARSE_TIME casts
STRPTIME to TIME. Hive DATE_FORMAT targeting BigQuery uses FORMAT_DATETIME rather
than the pin's invalid FORMAT_DATE-with-DATETIME call. Three signed rows carry
reviewed protected signatures: DATETIME zone conversion, diagnosed STRING offset
loss, and Hive FORMAT_DATETIME. TIMESTAMP(x) matches the pin, while direct tests
require its ambiguity diagnostic. They are excluded from actionable BQ counts.

`BigqueryTemporalConstructorsResultTest` and `BigqueryParseTemporalResultTest`
execute NULLs, missing/full years, microseconds, named zones and civil/instant
boundaries in DuckDB under UTC, New York and Auckland. These are not live BigQuery
checks. Upstream issue/PR status: not reported.

## 31. BigQuery interval and LAST_DAY guards (BQ-8 to BQ-10)

Temporal ADD/SUB nodes render target-native interval operators without quoting the
unit twice. BigQuery generation casts Hive/Spark date strings through DATETIME and
back to DATE before DATE_ADD. Spark renders DATETIME and TIMESTAMP subtraction as
binary interval arithmetic. PostgreSQL uses real interval units rather than string
units, so negative amounts remain one valid interval literal.

LAST_DAY drops the redundant MONTH argument for targets that do not support a date
part. DuckDB lowers WEEK/WEEK(day)/ISOWEEK to a DATE plus weekday offset; PostgreSQL
uses DATE_TRUNC plus month/day intervals. Presto/Trino diagnose unsupported non-month
forms instead of silently returning month-end. MAKE_INTERVAL uses the compact literal
form for integral constants and runtime interval components for dynamic or NULL
arguments. Source ASTs remain unchanged.

`BigqueryTemporalArithmeticResultTest` executes signed millisecond/second arithmetic.
`BigqueryLastDayIntervalResultTest` executes month edges, every week start, NULLs,
literal/dynamic/empty MAKE_INTERVAL, and result types in DuckDB. All eighteen signed
parity assertions now pass; no protected divergence was added.

## 32. BigQuery temporal truncation types and zones (BQ-11, BQ-12)

DuckDB truncation distinguishes DATE, civil DATETIME, and instant TIMESTAMP.
DATE week truncation casts back to DATE. DATETIME uses unzoned TIMESTAMP. TIMESTAMP
converts an instant to civil time in the requested zone, defaults that zone to UTC,
applies the week-start shift, and converts the boundary back to an instant. Unzoned
BigQuery timestamp literals are normalized to UTC before truncation.

Four BQ-11 rows retain reviewed signatures: the unknown TIMESTAMP Sunday row, two
equivalent unzoned timestamp-literal rows, and an ISOWEEK DATE row whose pin output
has the wrong DuckDB result type. They are executed under UTC, New York, and Auckland
sessions and excluded from actionable counts. Six other BQ-11 rows match the pin.

WeekStart rendering now reports the pin's unsupported diagnostic for Spark,
ClickHouse, MySQL, and Hive targets that cannot represent BigQuery custom week starts.
Fallback SQL uses plain WEEK rather than malformed WEEK_START/WEEK(day)/weekday
extract units. All six BQ-12 negative assertions pass. Native BigQuery week syntax
and the all-weekday LAST_DAY behavior from section 31 remain unchanged.

`BigqueryTemporalTruncationResultTest` executes every week start, ISOWEEK, NULLs,
year boundaries, explicit zones, and result types. These are DuckDB checks, not live
BigQuery verification. Upstream issue/PR status: not reported.

## 33. DuckDB prefix-alias lookahead and portable template markers (DDB-001)

Engine's portable interpolation spelling is `:name`, including a bare projection
such as `SELECT :n AS n`. The pinned SQLGlot DuckDB parser checks only whether the
next token is a colon, then parses the current token as an unrestricted identifier.
It therefore mistakes `SELECT` in `SELECT :n`, or `(` in `(:n)`, for a prefix-alias
name and fails before the actual placeholder can be parsed.

Brikk's `DuckdbParser.parseExpression` requires the current token to be in the
dialect's identifier/alias-token set before entering the prefix-alias branch.
`SELECT`, opening delimiters, numeric literals and string literals do not become
alias declarations. Actual native prefix aliases such as `answer: :n`,
`"odd alias": :n`, and `current_date: :n` remain supported.

`SELECT :n AS n`, `SELECT :value AS result`, `SELECT :n::INTEGER AS n`, and
`SELECT (:n) AS n` now produce the intended Placeholder/Alias/Cast/Paren shapes.
Scalar parameter discovery and parser-proved UTF-16 occurrence/name intervals
remain accurate, including repeated markers, nested queries, CTEs and pipe stages.
Source-preserving rendering keeps the portable marker and authored text unchanged;
ordinary DuckDB generation still emits its native `$name` marker spelling.

This does **not** claim that DuckDB JDBC natively accepts `:name`. Consumers must
continue their parameter-only adaptation/binding. `DuckdbTemplateParameterTest`
pins the AST/source/interval contract and native prefix-alias controls.
`DuckdbTemplateBindingResultTest` adapts only proved marker intervals to `?` and
executes every bare/cast/parenthesized/prefix-alias case in DuckDB 1.5.6 with both
integer and NULL bindings, checking column labels and returned rows. No copied
sample SQL, regex rewriting, or SQL-regeneration fallback is used.

The finding is recorded by Engine's `samples/duckdb-pipelines/FINDINGS.md` as
DDB-001. This SQL-side fix ships in `0.18.0`, not `0.17.0`; Engine's compiler/sample
blocker reproducers need updating after `0.18.0` is integrated.
The `0.18.0-SNAPSHOT` testing candidate is installed in Maven Local
on the development machine (all four SQL artifacts, with DuckDB JDBC 1.5.6.0).
Its installed core JAR was exercised directly on Java 21 against the reported
bare/cast/parenthesized projection reproducers and a native prefix-alias control.
Upstream issue/PR status: not reported. Existing corpus fixtures and known-failure
signatures are unchanged.

## 34. Qualification inference compatibility alongside upstream no-inference APIs

The catch-up to SQLGlot `a00dc2e` retains Brikk's published parameterized
`qualify` / `qualifyColumns` signatures and Kotlin default-argument bridges after
upstream #8552 removes `infer_schema`. Explicit `inferSchema=true` keeps legacy
ownership inference; null keeps the historical empty-schema automatic policy.
`inferSchema=false` uses evidence-only ownership and the upstream alias-expansion
ordering. New overloads without that parameter select the same disabled policy.
Kotlin prefers the shorter overload for new source calls that omit the flag;
already-compiled callers still reach the unchanged old default bridge. Resolver
likewise has a new two-argument no-inference constructor and retains its explicit
three-argument legacy policy and historical constructor bridge.

Brikk's shape discovery, stage shapes, star-expansion/standard-SQL preparation and
schema-less lineage explicitly select the legacy nullable policy. They must not
silently become schema-required optimizer operations. This includes Engine's
two-step `toStandardSql` then strict qualification path, not just outputShape.
Optional schema and legacy attribution remain part of Brikk's lineage facade;
upstream's new mandatory schema signature is not imposed on our consumers.

See [Engine analysis contract](engine-analysis-contract.md) for hand-owned
regressions, enabled/disabled/new-signature tests, and the ABI/default-bridge checks.
The upstream-shaped path follows alias-expansion-after-qualification, while legacy
true/null paths retain the old ordering to protect shipped shape behavior. Exact
parity-fixture refresh at the new target is a separate integration gate.

## 35. Spark array-slice counts and CONCAT_WS target boundaries during catch-up

Spark SLICE stores a length, not an inclusive end index. The parser's canonical
`end`/`is_length` representation is preserved through serde and native Spark
generation; older serialized `length` nodes remain readable. DuckDB normalizes
negative starts before computing the inclusive end. Unlike a bare upstream index
calculation, the local lowering propagates NULL inputs first and raises a target
ERROR for invalid non-NULL zero starts/negative lengths. Those Spark runtime
errors must not become successful DuckDB slices.

Hive/Spark CONCAT_WS carries its array-flattening policy explicitly. DuckDB casts
array/scalar elements to text and joins a combined list, preserving skipped NULLs
and empty arrays; an outer CONCAT_WS retains NULL-separator and empty-result
semantics. Native DuckDB CONCAT_WS remains native, including its array stringification.
The companion Trino route coalesces NULL arrays and flattens mixed arrays/scalars.
Unknown mixed argument types produce unsupported diagnostics. DuckDB row-dependent
ARRAY_TO_STRING delimiters retain the existing diagnosed execution limitation
rather than bypassing it through this new rewrite.

`SparkDuckdbArrayCatchupTest` covers parser/serde/native round-trips, legacy nodes,
wide slice ends and target diagnostics. `SparkDuckdbArrayResultTest` executes
actual DuckDB rows and invalid-count errors, typed columns and NULL behavior;
the Trino direction also passes its native grammar. No live Spark or Trino
execution is claimed. These catch-up ports are not in published `0.18.0`.

## 36. Source-aware StarRocks TIME_SLICE bucket origin

StarRocks TIME_SLICE parses numeric INTERVAL amounts and FLOOR/CEIL into the
dedicated TimeSlice node; CEIL corresponds to the next boundary even when the
input is already on a boundary. Unknown boundary identifiers refuse rather than
silently selecting FLOOR. The generic four-argument form remains understood for
AST/cross-dialect compatibility.

For explicit StarRocks-to-DuckDB requests, TIME_BUCKET uses a timestamp origin of
`0001-01-01 00:00:00` and a timestamp input. DuckDB's default origins (2000) are
not interchangeable: 2019-07-10 floors to July 8 in StarRocks for three-day slices,
but July 10 with default DuckDB TIME_BUCKET; four-year slices floor to 2017 in
StarRocks versus 2016 with DuckDB's default. CEIL adds the period after the floor.
Source-unknown/other-source generation retains the ordinary target/default-origin
path instead of globally assigning StarRocks semantics.

`StarrocksTimeSliceCatchupTest` checks AST, serde and source-aware/native rendering.
The opt-in `StarrocksWindowEngineTest` executes actual generated SQL in the pinned
StarRocks 4.1.4 engine and compares emitted DuckDB results across minute/hour/day/
month/year periods, exact boundaries and NULLs. It separately verifies FIRST_VALUE,
LAST_VALUE, LAG and LEAD IGNORE NULLS and native-default RESPECT NULLS row behavior.
These are native engine result proofs, not advisory MySQL parser acceptance.

Run the native gate with an isolated pinned container:

```sh
BRIKK_STARROCKS_TEST_CONTAINER=<4.1.4-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*StarrocksWindowEngineTest'
```

The gate skips when no container is configured. It requires the cached pinned
image/source version documented in `vendor/README.md`; no ports or host volumes
are needed for the container. No implementation of a live Spark/BigQuery engine
oracle is implied by these tests.

## 37. Set-operation scope and full-join replacement pagination

Set-operation generation now keeps each branch's LIMIT/OFFSET/ORDER inside that
branch where the target requires parentheses. A nested set's own modifiers and
WITH scope prevent flattening it into the enclosing set chain. ClickHouse's
native branch-local limits remain native; global set modifiers instead use an
outer SELECT wrapper, with WITH retained above it so pagination subqueries can
still see the same CTEs. The shared TOP parser hook retains branch TOP and defers
trailing global LIMIT/FETCH until a set operation exists; this adds no unsupported
T-SQL dialect to the registry.

The existing MySQL full-outer-join replacement had stored global ORDER/LIMIT on
its final UNION arm, depending on unparenthesized rendering to make them look
global. Once branch scoping is explicit, that produces wrong rows (e.g. LIMIT 0
would retain the left-join arm). The rewrite now attaches ORDER/LIMIT/OFFSET/WITH
to the replacement UNION itself. This restores both row restriction and CTE
ownership rather than approving the exposed parity difference as a new failure.

`SetOperationPaginationCatchupTest` checks AST-level branch/root placement and the
shared TOP hook. `SetOperationPaginationResultTest` executes branch restrictions,
nested set restrictions, outer wrappers, CTE-dependent limits, and the portable
MySQL full-join replacement in DuckDB. DuckDB, Doris and Trino native grammars
accept the branch-scoped outputs. No live MySQL or ClickHouse execution is claimed.
Existing exact known-failure ledgers remain unchanged.

## 38. Integer-division truncation, type evidence and temporary oracle drift

The SQLGlot #8532 port lowers non-native integer division to a BIGINT quotient,
with TRUNC/TRUNCATE before casts on targets whose integer casts round (BigQuery,
DuckDB, MySQL/Doris and Presto/Trino). Native operators/functions remain native.
The follow-up MySQL DIV rendering preserves binary operands, nested right-hand
divisions and unary negation/bitwise NOT; TRUNCATE supplies its required default
scale of zero. StarRocks explicitly retains its non-rounding-cast capability.

Typed source division now uses proven operand types as well as syntactic casts;
unknown types are not fabricated. A fresh untyped quotient prevents recursive
typed-division lowering. Source integer annotations alone do not establish that a
target's function returns integers (e.g. FLOOR in DuckDB), so that path uses the
truncated quotient rather than assuming target-native integer operand types.
DuckDB's NULL-on-zero integer operator is not substituted for erroring typed
source division. NULLIF guards retain the divisor's proven type so safe typed
integer division cannot accidentally become fractional division after guarding.

`IntegerDivisionCatchupTest` fixes the target SQL, AST immutability/serde, typing
policy, precedence, native spelling and target capability choices.
`IntegerDivisionResultTest` executes DuckDB signs, NULLs, zero-divisor failures,
safe division and BIGINT results beyond 32-bit range; native integer operators
also retain exact Long.MAX_VALUE inputs. Generic real-quotient fallbacks still
have the target's floating precision/overflow behavior; native exactness is not
a claim that every floating fallback has full 64-bit precision. Trino and Doris
grammar checks run without a live engine.

The opt-in `IntegerDivisionEngineTest` runs actual generated SQL in Trino 483 and
MySQL 8.4. Trino proves the shipped old rounding cast returns 4/-4, while the new
fallback returns BIGINT 3/-3. It checks NULLs, zero-divisor failure and safe NULL
behavior. MySQL verifies native DIV precedence, signs, NULL/zero policy, wide
integers and TRUNCATE scale, plus the typed-column fallback.

```sh
BRIKK_TRINO_TEST_CONTAINER=<483-container> \
BRIKK_MYSQL_TEST_CONTAINER=<8.4-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*IntegerDivisionEngineTest'
```

Each native gate skips if its container is not configured. The catch-up proof
used cached images `db58cc93e593...` (Trino 483) and `c831a0f11348...` (MySQL 8.4.10),
in isolated containers with no host ports/volumes, removed after verification.

The shipped oracle still expects `CAST(CAST(x AS DOUBLE) / y AS INTEGER)` for one
Hive-to-Presto assertion. Its exact reviewed ID/signature is temporarily recorded
as `oracle-pin-drift` in `hive-transpile-known-failures.json`; the corrected output
matches the frozen a00dc2e reference and has independent native result proof.
Remove this row during the coherent target fixture refresh. No other existing
ledger entries, generated fixture stamps or shipped pin were changed by this batch.

## 39. Native string escape profiles, source ranges and DuckDB NUL text

SQLGlot #8470/#8484 require shared numeric escape decoding, not just replacements
in a generator string. The tokenizer now reads bounded numeric escapes, handles
octal byte wrapping, combines byte-valued escapes into valid UTF-8 scalars, and
combines Unicode surrogate pairs. Unknown, truncated and unterminated escapes
follow each configured dialect's rules; fixed escapes may decode to the empty
string (ClickHouse `\N`). Invalid UTF-8 runs retain the frozen target's single-byte
code-point fallback. That tokenizer behavior is not a claim of native equivalence
for invalid source bytes: PostgreSQL rejects such input, explicitly proved by the
native test rather than treating its normalized Unicode result as an engine proof.

DuckDB and PostgreSQL use byte-valued hex/octal escapes only in escape strings;
PostgreSQL also has Unicode escapes. ClickHouse hex escapes require two digits.
DuckDB `\a`/`\v` and PostgreSQL `\a` drop their backslash, rather than inventing
control characters. Generation inverts only real escape mappings, keeps printable
letters literal and emits numeric control escapes where supported. It does not
invert a rule that merely drops a backslash before a literal newline. Parsed
backslashes in escape-string text are escaped again during generation.

`StringEscapeProfiles` supplies hand-owned overlays atop the shipped generated
tables. High-level dialects, direct parser/generator defaults and TokenizerConfigs
all use those overlays. Raw generated table constants remain at the shipped pin
until the coherent refresh. Tokenizer tooling must emit the new numeric policy
before the overlays can be retired. `TokenizerConfig.withStringEscapes` adds the
policy without changing the old constructor or Kotlin default bridge; keyword
overlays copy it without mutating the original config.

Source offsets remain UTF-16 positions in original SQL, never offsets in decoded
literal text. LF/CRLF/lone CR, supplementary characters, templates and emitted
source mappings are covered. Both fast and escaped-string scans count actual
source line breaks; a decoded `\n` does not create a fictitious source line.

DuckDB 1.5.6 rejects NUL in SQL source and in `e'\x00'`, and rejects byte-valued
UTF-8 escape runs, with `pg_verifymbstr NOT IMPLEMENTED`. Decoded Unicode literals
execute normally. NUL text is therefore generated as CHR(0) and concatenated text
segments for ordinary, escape and raw string nodes. The source AST/mapping remains
unchanged. These are text-string lowerings, not a new binary/BLOB escape policy.

`StringEscapeCatchupTest` covers semantic values, serde, native spelling, raw/
dollar-quoted strings, failure/reset behavior, control characters and source ranges.
`StringEscapeAbiContractTest` checks both published TokenizerConfig constructors.
`StringEscapeResultTest` executes DuckDB native/generated results, translated
PostgreSQL/Spark/ClickHouse text, and NUL lowering; known native source rejections
are asserted separately. `StringEscapeEngineTest` runs PostgreSQL 18.4 source and
generated UTF-8/octal/Unicode/backslash/bytea results and invalid-source failures.
No live ClickHouse result proof is claimed.

```sh
BRIKK_POSTGRES_TEST_CONTAINER=<18-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*StringEscapeEngineTest'
```

The native gate skips without a configured container. Verification used cached
PostgreSQL 18.4 image `4efc78b0491e...` in an isolated container with no host ports
or volumes, removed afterward.

The old pin has two PostgreSQL cases whose raw numeric syntax was stored as text:
`e'\xDEADBEEF'` (invalid native UTF-8) and `CAST(e'\176' AS BYTEA)`. Fresh parsing
now matches a00dc2e's decoded payloads. Generating from the *old dumped ASTs* instead
escapes their stored backslashes, also matching a00dc2e when it loads those exact
old dumps. Six exact assertion/signature rows across parser, annotated-AST and
generator ledgers record this temporary `oracle-pin-drift`; annotated-AST payload
mismatches do not imply changed type evidence. Remove the rows during the coherent
fixture refresh. No pre-existing signatures/reasons or shipped fixture stamps/pin
were changed by this batch.

## 40. REGEXP_COUNT/REGEXP_INSTR empty-pattern scope and source-aware lowering

SQLGlot #8474 fixes empty checks in REGEXP_COUNT/REGEXP_INSTR lowerings that were
not yet present locally. Both lowerings are now implemented. The canonical zero-
result policy compares the **original** pattern with the empty string, before
embedding literal i/m/s flags; testing the flags-prefixed pattern would turn an
empty pattern into a nonempty expression and produce unintended matches. Runtime
NULL checks cover all provided arguments, including empty-pattern calls with NULL
subjects, positions, occurrences, options or flags. No compile-time substitution
of a NULL result is used.

The canonical zero rule is not universal native SQL behavior. PostgreSQL and
Trino count empty matches between characters (four for `abc`); PostgreSQL INSTR
returns boundary positions, including the final boundary. MySQL INSTR rejects an
empty pattern, even with a NULL subject, and rejects out-of-bounds start positions.
Explicit source context retains these differences. PostgreSQL starts beyond the
last boundary return zero; its default newline mode and opposing m/s flags map to
the appropriate RE2 flags. Spark's native empty-match policy is carried through
for its two-argument calls, but no live Spark result proof is claimed. Extra native
Trino/Spark COUNT arguments produce diagnostics rather than inventing overloads.

Whole-match INSTR uses lengths of split prefixes and prior matches to compute
1-based positions. Occurrence selection, starting offsets and runtime start/end
options are retained. An explicit BIGINT cast prevents LIST_SUM's wider DuckDB
accumulator type from becoming the exposed position result. Positive-position/
occurrence and valid-option checks refuse invalid non-NULL calls. Original ASTs,
parents, serde and type evidence remain unchanged; missing source type metadata
is not fabricated merely because the target lowering has a known type.

This is a portable **RE2-compatible whole-match subset**, not a promise of arbitrary
POSIX/ICU/Java/Snowflake regex equivalence. Unsupported flags and nonzero capture-
group position arguments are diagnosed. PostgreSQL starting-position lowering
restarts a substring's boundary: known start anchors or dynamic patterns combined
with a nontrivial start position therefore produce a portability diagnostic.
Nonempty zero-width position patterns are diagnosed when recognizable and refused
at execution time otherwise: STRING_SPLIT_REGEX drops zero-width separators, so
using the length-summing result would silently report wrong positions. Empty
patterns instead use the appropriate explicit source/canonical branch.

`EmptyRegexpCatchupTest` fixes original-pattern/flag ordering, source policies,
diagnostics, AST immutability, conservative typing and native DuckDB spellings.
`EmptyRegexpResultTest` executes flags, NULLs, row-dependent patterns/options,
occurrences, Unicode positions and refusal cases. Native DuckDB MATCHES, REPLACE
and EXTRACT_ALL still retain their own empty-pattern behavior; no zero rule is
added to them. `EmptyRegexpEngineTest` compares actual generated DuckDB rows with
PostgreSQL 18.4, MySQL 8.4.10 and Trino 483 results/errors.

```sh
BRIKK_POSTGRES_TEST_CONTAINER=<18-container> \
BRIKK_MYSQL_TEST_CONTAINER=<8.4-container> \
BRIKK_TRINO_TEST_CONTAINER=<483-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*EmptyRegexpEngineTest'
```

Each live gate skips without its configured container. Verification reused the
cached pinned PostgreSQL/MySQL/Trino images documented in extensions #38/#39,
with isolated containers, no host ports/volumes, removed afterward. No Snowflake
dialect/engine is added. No ledger rows or shipped generated fixture stamps/pin
were changed by this batch; earlier reviewed pin-drift rows remain pending refresh.

## 41. DuckDB comprehension binding, native casing and schema-less whole rows

SQLGlot #8557 parses comprehension item/index declarations as Identifiers, not
source Columns. Qualification converts local body/filter references to identifier/
dot expressions before alias expansion and ordinary column resolution. Iterators
remain outside their own declaration scope, so `[x FOR x IN x]` can still resolve
the iterator from a source column or preceding alias. Nested bindings are retained;
query boundaries prevent an inner SELECT's columns from becoming loop variables.
Scope.comprehensions follows the existing collection/cache lifecycle.

Proven local table-qualified columns take precedence over lambda struct fields:
if `x.field` names a real field in the selected table alias x, its Column kind and
ownership remain intact. Without that evidence, struct lookup stays a local Dot.
Older serialized Column declarations are normalized when qualified, preserving
positions/comments. Column type annotations do not prove the local element type
and are not transferred into the new local symbol. Missing local element/whole-row
typing and nullability stay unknown rather than being fabricated from similarly
named schema columns.

Native DuckDB 1.5.6 can distinguish variable spelling during binding before its
case-insensitive column lookup: with a table column x, `[X FOR x IN items]` can
produce the table column's value while `[x FOR x IN items]` uses the loop value.
Nested frames and qualified method calls also require the original spelling.
The local port protects declarations and candidate body/filter roots with the
existing case_sensitive normalization hint, including method-call Identifier
heads and nested lambda declarations that shadow those names. It preserves native
spelling rather than assigning invented source ownership to ambiguous symbols.
No precise element-type proof is implied by this protection.

DuckDB also supports table aliases as whole-row values. With no column evidence,
legacy inference must not turn `SELECT t FROM t` into an invented `t.t` reference.
The local whole-row fallback retains t; a real schema column named t still wins
and qualifies to t.t. This preserves execution under all true/false/null policies
without changing their general inference rules or published JVM signatures.

`ComprehensionQualificationCatchupTest` checks parser/serde, policies, aliases,
struct/table distinction, query boundaries, caches, source mapping and legacy ASTs.
`ComprehensionQualificationResultTest` executes actual original/qualified DuckDB
rows for filters, indices, nested shadowing, casing, methods, whole rows, quoted
variables and empty/NULL inputs. DuckDB still rejects subqueries inside lambdas;
the native refusal is checked separately, not claimed newly supported. The added
`EngineAnalysisContractTest` method protects output names, known external types,
unknown comprehension nullability and the Engine's two-step standard-SQL/strict
qualification path under full, empty and unrelated catalogs.

Two old-pin comprehension cases still contain Column declarations. Four exact
reviewed parser/annotated-AST rows record temporary oracle-pin-drift. The Identifier
declarations and their unchanged UNKNOWN types match a00dc2e. Remove the rows at
coherent fixture refresh; the existing UTF-16 divergence reason/signature is untouched.

## 42. Scalar array values versus row-query constructors

SQLGlot #8575 and 51864e76b4 distinguish a sole direct query supplying rows from
scalar/value array elements. Shared DuckDB/BigQuery generation no longer searches
arbitrarily inside the first element for a query, which could discard later
elements or promote a function/struct/comprehension into invalid ARRAY(query) SQL.

Bracket and LIST_VALUE scalar-query constructors retain value_constructor=true
in ordinary AST args/serde. ARRAY(SELECT ...) and ARRAY((SELECT ...)) remain row
constructors. Thus a scalar subquery returning no row produces `[NULL]`, while a
row query returning no rows produces `[]`. Other elements, nested arrays/functions
and struct fields survive unchanged. Legacy unmarked single-query ASTs retain the
row-constructor default; absent source intent is not reconstructed by guessing.

The optional intent is currently sparse (only true is stored). Existing generated
arg-type metadata and false defaults remain at the shipped oracle until coherent
target regeneration. That refresh must add Array.value_constructor to generated
argTypes and emit corresponding false defaults with the refreshed fixtures, not
rewrite current stamps while those fixture changes are pending.

`ArraySubqueryCatchupTest` fixes parser/serde and both-direction output distinctions.
`ArraySubqueryResultTest` executes DuckDB rows for every retained element, scalar
NULL versus empty row arrays, LIST_VALUE, nested functions/structs and ordered
multirow query arrays. Portable BigQuery-shaped output is also executed in DuckDB;
no live BigQuery engine or general BigQuery grammar proof is claimed. Source ASTs
remain unchanged during generation. No new array ledger entries were needed.

## 43. Source-order stars, native USING policies and conservative expansion

SQLGlot #8529 keeps table/derived-table/UDTF references in source walk order rather
than grouping ordinary tables first. #8530 expands every level of parenthesized
join constructs before alias assignment, rebuilding scopes until stable; sources
starting with queries and aliased inner join groups remain present. Source order
now governs star fields and positional consumers. Aliasing callbacks still run
once per source, not once per expansion pass.

The #8536 port exposes UsingColumnOrder: default USING_LIST puts each join's keys
first in USING order; MySQL/Doris LEFT_TABLE uses the left output order; DuckDB
IN_PLACE keeps keys at their left-side position. Native MySQL RIGHT USING instead
uses the right side's key and residual-source order, preserved explicitly. Earlier
keys move behind later USING keys where the policy requires it. Natural common
keys follow the current left-side layout when they can be determined.

Native qualified-star behavior is also dialect-specific. PostgreSQL, DuckDB and
MySQL expose raw source fields, including duplicate keys and source-side NULLs.
Trino hides joined keys from qualified stars. StarRocks exposes merged keys first,
sharing them across distinct qualified sources but retaining them for repeated
source-star occurrences. QualifiedStarUsing captures these policies; other
unprobed dialects retain their existing merged-key default rather than claiming
new native proof. Every unqualified star expands independently, so `*, *` does
not lose the second set of keys. Literals, EXCEPT/REPLACE/RENAME and other selections
remain in their own positions; existing rename refusal/atomicity gates still pass.

Merged-key expressions retain logical join stages instead of assuming that a key
always comes from the first physical source. Later FULL joins compare the previous
merged key, preserving right-only rows. RIGHT prefers the right value (including
MySQL case-insensitive matches). DuckDB INNER/LEFT keys retain the left field/type,
RIGHT uses the right field/type, and FULL coalesces; blindly coalescing mixed INT/
BIGINT keys would widen DuckDB's native INNER/LEFT result incorrectly. Other
merged expressions retain their nested coercion/merge stages rather than flattening
them merely for old-oracle text parity.

**Conservative boundaries:** converting USING to ON while leaving an unresolved
bare star changes native output into duplicate keys. If visible columns are
unknown, wildcard-shaped or duplicated, native USING/NATURAL and the bare star
remain intact. Merged-name references retain unqualified native spelling and
unknown type evidence, not invented single-table ownership. Flat scopes cannot
model separate comma-join key families safely, so those groups likewise retain
native syntax. Known qualified/raw fields can still carry their evidence.
StarRocks 4.1.4 FULL USING has native star-slot behavior unlike the generic merged
projection; its stars/USING are retained rather than silently changing those rows.
These cases may keep UNKNOWN star shapes even with some available schema evidence.

`StarUsingOrderingCatchupTest` checks source order, caches, policies, repeated/raw
stars, wrapper retention, callbacks and safe unknown-star behavior.
`StarUsingOrderingResultTest` compares actual DuckDB column labels, types and rows,
including all join sides, mixed INT/BIGINT keys, duplicate names, chained FULL
joins, UDTFs, comma groups, NULL supply and partial/no schema.
`StarUsingOrderingEngineTest` compares original/qualified headers and rows in
PostgreSQL 18.4, MySQL 8.4.10, Trino 483 and StarRocks 4.1.4, including MySQL RIGHT
ordering/collation and StarRocks qualified-star cases. NATURAL is not implemented
by native Trino; StarRocks parses that token as an implicit alias in this form.
Neither is claimed as a native NATURAL-join equivalence proof. PostgreSQL/MySQL/
DuckDB natural joins are covered. No new live Doris/Presto/Spark/ClickHouse/BigQuery
execution proof is claimed here. The added Engine contract protects DuckDB order,
key type/nullability and two-step preparation/validation for unresolved USING stars.

```sh
BRIKK_POSTGRES_TEST_CONTAINER=<18-container> \
BRIKK_MYSQL_TEST_CONTAINER=<8.4-container> \
BRIKK_TRINO_TEST_CONTAINER=<483-container> \
BRIKK_STARROCKS_TEST_CONTAINER=<4.1.4-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*StarUsingOrderingEngineTest'
```

Verification reused the pinned cached images from extensions #36/#38/#39 in
isolated containers, no host ports/volumes, removed afterward. Public qualification
signatures/default bridges and the general true/false/null inference policy are
unchanged. Scope and dialect additions do not alter existing constructor descriptors.

The full texts of fourteen qualification discrepancies and one lineage discrepancy
were reviewed individually. Eight are old-pin source/key ordering changes matching
the frozen target and are temporary oracle-pin-drift. Six intentionally retain
logical merged-expression stages; one retains unresolved native USING in lineage's
source SQL while preserving both downstream source nodes. Their exact signatures
and curated reasons are recorded separately as intentional-divergence, not approved
by counts. Remove only the eight stale ordering rows during coherent fixture refresh;
retain intentional safeguards unless the new oracle genuinely adopts them.

## 44. Correlated UNNEST projection evidence and lexical source precedence

SQLGlot #8571 (`9f2f4cd5a2`, corrected tests `6683ad28a4`) resolves correlated
UNNEST struct fields from annotated source projections before searching physical
schemas. Computed arrays/nested structs can supply child field types without a
catalog, and a computed projection shadows a same-named physical column. CTE
column aliases remain visible. #8472 caches the outer resolver chain; this port
keeps those caches and propagates our existing explicit inference policy rather
than reconstructing every resolver with a different default.

**Evidence boundaries:** the upstream fallback walks arbitrary nested sources by
name. We instead trace an UNKNOWN projection only when a direct qualified column
proves its source binding. An unknown function result, duplicate projected name,
or renamed scalar cannot borrow a same-named ARRAY/STRUCT from another projection
or physical input. Known and UNKNOWN trace results are memoized; recursive visits
have an in-progress guard. Set-operation UNNEST field discovery remains deferred
(as explicitly reverted in #8571), not guessed from the first physical arm. This
does not remove the existing reconciled type summaries for ordinary set outputs.

Type annotation also respects lexical visibility. Inherited CTE declarations are
not themselves FROM bindings: selected sources and visible lateral inputs win,
and outer lookup crosses only correlatable scopes. This fixes the frozen target's
same-name CTE/inner-source shadowing bug, independently proved with native DuckDB
and Trino results. Multi-level UNNEST input discovery follows the permitted outer
resolver chain and stops at a nearer UNKNOWN binding or a potentially owning
unknown local source, rather than stealing farther-out type evidence.

`CorrelatedUnnestCatchupTest` covers the upstream computed/nested cases, physical
name collisions, CTE aliases, local/UNKNOWN shadowing, multi-level correlations,
unaliased bare stars and element-qualified struct stars, missing-field refusal,
TVF conservatism, Spark lateral schema fields and CTE PIVOT outputs. A twelve-level
shared-source DAG checks memoization of both known and UNKNOWN schema types. The
new-signature APIs still disable inference; explicit true/null keeps legacy TVF
qualification with UNKNOWN types. No public constructor/default bridge changed.

`CorrelatedUnnestResultTest` compares native DuckDB field labels, types and rows
before/after qualification, including NULL/empty arrays, nested structs, computed
schema collisions, same-name CTE shadowing, real parquet TVFs and CTE PIVOTs. Typed
portable BigQuery correlated output also executes in DuckDB with asserted values;
that is not a live BigQuery proof or a general BigQuery-to-DuckDB UNNEST lowering.
No live Spark/Doris proof is claimed by the schema-resolution regressions.

`CorrelatedUnnestTrinoTest` compares native Trino 483 headers and rows across all
three inference policies, including nearer-source shadowing and NULL/empty arrays.
The shadowed scalar output is also asserted BIGINT in the annotated tree.

```sh
BRIKK_TRINO_TEST_CONTAINER=<483-container> \
  ./kotlin test -m brikk-sql-verify --include-classes='*CorrelatedUnnestTrinoTest'
```

Verification reused image `db58cc93e593...` (extension #38) in an isolated
container without host ports/volumes, removed afterward. Two added Engine
contracts protect computed correlated shape types (including bound partial slots),
UNKNOWN computations despite same-named catalog fields, conservative nullability,
strict two-step validation and nonmutation of the original SqlFragment AST.
All existing exact corpus gates pass: no ledger waivers or fixture/pin changes
were needed for this batch.

## 45. Trino temporal precision versus Presto limitations

SQLGlot #8538/#8564 preserve the exact fractional precision of Trino TIME/TIMESTAMP
literals, including zero and trailing zeros, with or without zones. Zone promotion
runs before type-literal hooks; explicit cast precision remains explicit. We also
retain a declared typed-literal precision when promoting its zone rather than
dropping it. Parse/serde/type/render tests cover 0..12; native Trino 483 TYPEOF,
VARCHAR values, NULL casts and precision-sensitive DATE_ADD match the original.
The shared Presto generator removes temporal parameters and diagnoses anything
other than its implicit/explicit millisecond precision. This can expose earlier
precision-loss assumptions: temporal-difference tests now assert exact diagnostics
and do not execute lossy Presto output in Trino as if Trino were a native Presto
result oracle. No live Presto proof is claimed. Twelve reviewed exact old-pin
parser/type/transpile discrepancies match a00dc2e and await coherent refresh.

## 46. ClickHouse ISO calendar mappings without option loss

SQLGlot #8494 maps one-argument toDayOfWeek/toISOWeek/toISOYear to their canonical
ISO nodes and back. Extra mode/timezone arguments remain native Anonymous calls,
since canonical nodes cannot represent them. ISO-year generation now uses toISOYear;
the companion DuckDB rendering uses EXTRACT(ISOYEAR). Existing toDayOfMonth/toDayOfYear
mappings already implemented #8473 and now have focused equivalence regressions.
Live chDB 26.5.1.1 results agree with DuckDB at ISO week/year, leap-day and NULL
boundaries; timezone/mode calls retain their native results. Canonical typing metadata
is unchanged (it is not a claim of ClickHouse's exact unsigned native widths).
Enable live regressions with BRIKK_CHDB_TEST_PYTHON pointing to a Python containing
chdb. No ledger or shipped fixture changes were needed.

## 47. Shared ALTER dependencies for MySQL catch-up

The #8419/#8539/#8565 prerequisites model mixed operations, options-only ALTERs
and alternating actions/options; MySQL bare DROP and PostgreSQL TYPE requirements
remain dialect-controlled. Options that require whitespace before partition clauses
are separated from comma-separated options. Unknown trailing syntax retains the
whole Command rather than silently truncating actions. Native MySQL 8.4.10 SHOW
CREATE and PostgreSQL 18.4 column/type comparisons match original SQL. Six exact
reviewed old-pin AST/type discrepancies move options from actions to options as
at a00dc2e. Alter.actions optionality temporarily has a narrow parser validation
overlay until its generated metadata/fixtures refresh together. Public parseDrop
and its default bridge remain intact; new explicit-kind parsing is additive.

## 48. MySQL native lexical, DDL and expression catch-up

MYSQL-01 ports #8465/#8471/#8437/#8503/#8515/#8553/#8561/#8544/#8559/#8409/#8417.
MySQL -- comments require whitespace/control/EOF and line comments stop at LF only;
ClickHouse also keeps CR inside comments. Doris/StarRocks retain their different
dash/CR policies. TokenizerConfig adds copy-preserving sidecar policies without
changing published constructors/default bridges. Native MySQL escape decoding and
generation preserve NUL, SUB, unknown escapes and pattern backslashes under default
SQL mode; NO_BACKSLASH_ESCAPES/ANSI_QUOTES session modes are not newly claimed.

INSERT VALUE is accepted only in INSERT sources, not by renaming VALUE identifiers.
Charset aliases/ASCII(latin1)/UNICODE(ucs2), SIGNED defaults, DROP KEY/CHECK and
comma-optional table properties retain their scopes. Key options stay on PRIMARY
KEY, while MySQL generic table options are emitted without WITH; Doris/StarRocks
generic properties retain PROPERTIES wrappers. WEIGHT_STRING has canonical this/to
arguments for its AS form; undocumented extra arguments retain their native name.
CHAR charset casts preserve their length and type metadata. Native IF retains its
required three arguments; source CASE stays CASE. DATE_ADD/SUB keep explicit DATE
conversions so time-of-day is not silently restored.

Focused tests cover parser/serde, context/range checks and derivative rendering.
Live MySQL 8.4.10 compares original/generated HEX values, conditions, date results,
weight bytes, cast truncation and SHOW CREATE/inserted rows. Three exact additional
old-pin discrepancies (preserved charset metadata and escaped NUL rendering) match
a00dc2e and await refresh. WeightString's exact temporary metadata contract fails
as stale when the target manifest includes it. Generated metadata/fixtures remain
coherent at the old shipped pin until integration. No new live derivative SQL-mode
or WEIGHT_STRING cross-engine equivalence is claimed.

## 49. PostgreSQL native JSON_VALUE paths and return evidence

The frozen target's #8579 parser fix retains PostgreSQL JSONPATH as native literal
or dynamic expressions, rather than interpreting it through our structured path
subset. Strict/lax filters, quoted keys, concatenations and CASE paths survive.
RETURNING parses complete DataTypes, including decimal parameters and timestamp
zones; companion MySQL/Trino parsing uses the same type entry point. Defaults and
error clauses remain native SQL, not approximate scalar-extraction rewrites.

Live PostgreSQL 18.4 tests compare original/generated values and PG_TYPEOF, including
NULL/empty paths, scalar casts, explicit defaults and dynamic JSONPATH columns.
ERROR ON EMPTY/ERROR paths retain native SQLSTATE 22035/22034/22P02; tests require
the original and generated expressions to raise the same specific state. Return
type evidence is hand-owned: PostgreSQL's explicit RETURNING type, or documented
TEXT default, supplies analysis types without claiming the path's value type or
nonnullness. An Engine contract protects two-step preparation and original ASTs.
This typing is intentional native evidence, not Python's generic UNKNOWN default.
Two exact companion Trino parser/type old-pin discrepancies replace a bogus
RETURNING column node with VARCHAR DataType and await coherent refresh.

## 50. Coherent a00dc2e integration and surfaced dependencies

The branch now coherently uses `v30.22.0-5-ga00dc2e5c` / full frozen target
`a00dc2e5c489214bea53bacbbeba8713f21ebce1`: generated node/arg-type/tokenizer/typing/
function tables and 104 oracle fixtures were regenerated together. Generator stamps
use a deterministic nine-character git description. Numeric, byte-literal, unknown-
escape, surrogate and comment policies are emitted through ABI-preserving config
methods. StringEscapeProfiles retains stable aliases/helpers but no longer overlays
policies. Array value-constructor false defaults, With.cycle and Alter.actions
optionality are coherent; temporary WeightString and Alter validation allowances
are retired. Existing published inference/parser/tokenizer entry points remain.

The refreshed corpus exposed dependencies that older fixtures did not exercise:
dialect-scoped function names (#8512, including legacy native dump compatibility),
singular/countless FETCH/OFFSET ROW (#8429), boolean update assignments (#8428),
atomic EXISTS keyword matching, keyword-named/dotted ALTER fields, SEARCH+CYCLE
and its defaults (#8574), duplicate-alias ordinals (#8436), SEMI/ANTI condition
ownership (#8383/#8430), qualified index drops (#8526), and lateral ordinality
(#8463). NULL-order alias substitution now stops at the query's own ORDER boundary
(#8514), avoiding aggregate recursion and outer/set-operation alias capture.
Native DuckDB rows and PostgreSQL/Trino rows/types corroborate these fixes.

Typed JSON intent (#8413), Hive/Spark FROM-first insert parsing/rendering and CTE/
lateral retention (#8527/#8535), numeric typing (#8547) and decimal literal evidence
are reconciled. FROM-first inserts with implicit shared sources refuse unsafe
QUALIFY lowering, and unsupported targets report that feature explicitly. This is
parser/generator support, not a new multi-table query-plan/executor feature or live
Spark/Hive execution claim. Native source-aware ClickHouse mappings remain protected.

Byte literals (#8479) store actual bytes as Latin-1 characters; source Unicode is
encoded as UTF-8 without moving UTF-16 source ranges. BigQuery rendering escapes
non-ASCII bytes, DuckDB uses BLOB hex escapes, and PostgreSQL uses DECODE(hex).
Portable outputs preserve arbitrary/non-UTF8, NUL and backslash bytes in actual
DuckDB/PostgreSQL tests. This is not live BigQuery grammar proof; non-byte AST
payloads refuse rather than invent encoding. TokenizerConfig constructors remain
unchanged; keyword/escape/comment copies retain byte policy.

The embedded PostgreSQL fidelity oracle now uses 18.4.0 on its three supported
platforms, so it can verify JSON_VALUE instead of mislabeling PG-14 rejection as a
generator bug. Specific post-parse SQL/JSON states 22034/22035 are semantic errors;
the verifier still distinguishes syntax rejection and host unavailability. Six
obsolete PG-14 grammar ledger rows now genuinely pass and are removed.

Forty-two temporary pin-drift rows are removed. Six existing merged-key curated
reasons and discrepancy signatures are preserved verbatim; only their descriptor
IDs changed with target fixture policy/schema data. Five DuckDB left-key/type and
one comma-group qualification signatures retain native safeguards. Sixteen
PostgreSQL JSON_VALUE and one ordinality annotation signatures change only type
payloads: native explicit/default return evidence and BIGINT ordinality replace
Python UNKNOWN, without inventing field types/nullability. Optional-schema lineage
keeps its legacy best-effort dependency graph, not strict column proof. The old
unresolved-USING assertion now matches its refreshed fixture; native safeguards
and hand-owned regressions remain. Full expected/actual mismatch artifacts are
written for review; terminal controls are escaped only in reporting, not signatures.

Coverage inventory: 3,152 named transpile assertions retain 3,133 display labels and
19 collision labels, with stable IDs. The pre-existing deferral policy remains
explicit: 2,478 named identity assertions, 43 non-pipe base identities and 1,482
base transpile directions have no direct assertion consumer; parser/generator
overlap is not claimed as full parity. Excluded dialects and dynamic extraction
limitations remain independently inventoried. None of these counts approves a
failure signature. New hand-owned TargetBehavior/TargetByte/TargetTyping tests,
native result tests and all existing compatibility/corpus gates pass.

The catch-up through `09d5539` was subsequently merged into main on user request,
retaining `0.20.0-SNAPSHOT` and the existing test-gated snapshot publisher. This is
not a new stable release. The original port rounds above describe their then-current
pin/overlay state; this coherent refresh supersedes their temporary drift rows and
policies. Final native and downstream candidate evidence follows below.

### Final native and downstream candidate validation

`DorisCatchupResultTest` now runs on official pinned **Doris 4.1.4.1** FE/BE images,
both reporting `doris-4.1.4.1-rc02-5dbec55b399`. The earlier 4.1.3 all-in-one run
was a historical shared-behavior baseline, not the current native validation target.
VERSION() gives only MySQL wire compatibility; actual FE/BE versions are checked.
Native/generated HEX values, comment boundaries, IF/CASE, DATE_ADD/SUB, properties,
SHOW CREATE and inserted rows agree. MySQL's singular INSERT VALUE normalizes to
VALUES before Doris execution; Doris itself rejects singular VALUE.

Current native checks also cover all nine nullable Boolean OR combinations and
the hotfix's 100,000-row JSON Boolean null-payload/OR/CASE regression under both
short-circuit execution modes, with independent exact counts. VARIANT/NULL inputs
and timezone-aware COALESCE compare native/generated values. All 81 Doris-selected
methods pass (six execute the live catch-up engine); all 92 historical behavior
vectors reproduce exactly. The hotfix changes no grammar, so the vendored 4.1.4
parser is retained. Enable the live gate with `BRIKK_DORIS_TEST_CONTAINER`; see
[native evidence](doris-native-catchup-verification.md) for digests and runtime
limits. This is not a production-database, performance or broader SQL-mode proof.

The exact private core/metadata JARs from `dd1ea09` pass 163 peer compiler/runtime/
schema/tooling tests, CLI plugin assembly, a real compiler-plugin smoke build and
ten exact IDE compiler fixtures. Both relocated plugins contain the frozen target
pin. The peer checkout stays clean on its original main commit; candidate files,
dependency overrides, generated inputs and logs are confined to a retained scratch
snapshot. No Maven Local or public release was published. See
[candidate verification](engine-candidate-verification.md) for artifact hashes,
reproduction and the limits of that gate.

## 51. Presto/Trino refusal for unexpanded pipe RENAME

Presto and Trino do not support `SELECT * RENAME (...)`. Previously a valid pipe
such as `FROM t |> RENAME id AS x` could reach executable generation with that
syntax and an empty unsupported-message list. The shared Presto generator now
throws `UnsupportedError` for surviving star RENAME modifiers, including Trino's
inherited path, matching the existing Doris refusal policy. No SQL result is
returned for that unsupported translation; this is not a warning callers can miss.

A minimum trait proving `id` exists does not prove the complete row layout. The
Brikk-house runtime erases its `Rel<T>` shape and uses schema-less source-preserving
executable rendering, so it must refuse this translation rather than guess the
unlisted fields. Analysis may still infer the known renamed field. SQL-side Shape
does not encode Engine's Partial/closed distinction: callers supplying schemas
for executable star expansion must supply complete layouts, not trait minima.

An explicit projection established from a complete schema remains supported and
preserves column positions, simultaneous swaps, types, NULLs and duplicate rows.
`SELECT * EXCEPT (id), id AS x` is neither Trino syntax nor generally an equivalent
rename: it appends x, changing column order. Native DuckDB star RENAME and ordinary
Presto/Trino stars remain supported. Explicit pipe-formatting mode still preserves
authored `|>` text; it is not executable engine SQL and its public defaults are
unchanged.

`PipeRenameGenerationTest` protects the schema-less/trait runtime path, source-map
and pretty modes, nested pipes, qualified/direct stars, unresolved joined layouts,
complete projections and AST nonmutation. `TrinoPipeRenameResultTest` independently
rejects the old output with Trino 483's native grammar, checks complete-projection
grammar and portable row/header/type semantics in DuckDB, and has an opt-in live
Trino execution check. The malformed trailing `c` in `RENAME id AS x c` remains a
separate parse error, not the valid generator input used to reproduce this defect.

Verification: the new regression class initially failed three refusal methods;
after the guard, build, 1,277 Kotlin methods (24 optional native skips), 38 existing
consumer/editor contracts, sixteen offline release and three candidate safety
tests pass. Trino 483 ran the complete-projection row checks; all existing ledgers
are unchanged. The retained precompiled brikk-house runtime also refuses a bound
`Rel<HasId>` rename against the new core JAR while explicit SELECT still renders;
the original peer checkout stays clean. Logs are retained under
`/tmp/opencode/trino-pipe-rename-*`; only the isolated task container is removed.

## 52. DROP/SET capabilities, native Doris replacement and executable pivot refusal

The Presto/Trino star guard from #51 now also refuses surviving EXCEPT and REPLACE
modifiers. Schema-less `|> DROP` and `|> SET` previously returned invalid Trino SQL
with no diagnostics. A complete caller-supplied layout can still expand them to
ordered explicit projections; trait minima are not a complete field inventory.
Ordinary stars, set-operation EXCEPT and explicit author pipe-formatting are unchanged.

Doris is different: official 4.1.4.1 FE/BE images reporting
`doris-4.1.4.1-rc02-5dbec55b399` execute both star EXCEPT and star REPLACE. Live
schema-less pipeline checks compare row/header results and wire column metadata
against independent explicit projections: untouched fields retain their positions,
simultaneous replacements read the original values, chained SET reads the previous
stage, and NULL/duplicate rows and wide/type-changing results survive. Native
replacement rejects unknown, duplicate and ambiguous target names instead of
appending missing fields or replacing both joined copies. Star RENAME is still
unsupported. Do not apply Trino's EXCEPT/REPLACE refusal to Doris.

One unrelated native limitation surfaced while probing metadata: creating a view
from the generated WITH plus star REPLACE failed in this Doris build with a
`start ... end ... length ...` SQL-text rewrite error. The SELECT itself executes
correctly. Tests inspect its wire metadata directly and do not claim that this
CREATE VIEW form works; this is not evidence that star REPLACE is unsupported.

Unsupported PIVOT/UNPIVOT formerly dropped the operator and returned flagged SQL.
IDE execution adapters may honor those blockers, but a plain consumer such as
Brikk-house `Rel.render()` can use the SQL without reading the messages. Executable
generation, standard rendering and source-preserving lowering now refuse the
unsupported query transformation with `UnsupportedError`. `transpileTo` remains
the diagnostic-preview API and retains its existing flagged best-effort output;
native DuckDB pivot/unpivot still works. Internal purpose state is additive and
does not change published generator constructors or fragment method/default bridges.

The grand-unification `lowerForExecution` adapter maps this refusal to `Refused`;
its earlier test expecting `Ok` plus PIVOT blockers needs the new expectation when
integrating this change. Other diagnostic previews and blocker checks remain valid.
Parser-derived join keyword enumeration is untouched: executable-engine completion
filtering belongs in the IDE/profile consumer, not the core parser's keyword tables.

`PipeExecutionCapabilitiesTest`, `PipeExecutionCapabilitiesResultTest` and
`DorisPipeSetResultTest` cover the guards, complete-layout execution, preserved
previews and native behavior. Live Trino 483 and Doris 4.1.4.1 run the relevant
row checks. Scratch evidence is retained under `/tmp/opencode/pipe-capabilities-*`
and `/tmp/opencode/doris-set-*`; no peer source or grand-unification branch is edited.

Verification on 2026-10-12: build, 1,295 Kotlin methods (18 optional native skips),
38 existing consumer/editor contracts, sixteen offline release and three candidate
safety tests pass. All nine selected pipe-result methods run with live Trino/Doris
configured; 111 core pipe methods pass. No generated metadata, fixture stamps or
curated ledger entries change. The retained precompiled Brikk-house runtime also
refuses unknown-layout DROP/SET and unsupported pivots while native Doris SET still
renders; its original checkout remains clean. Only the task's isolated containers
and internal network are removed after verification.

## 53. Doris native lexical rules

**NOT sqlglot parity.** `DorisDialect.LEXICAL_CONFIG` layers native DorisLexer.g4 rules
(apache/doris `3226e0c`) over the generated tables via `TokenizerConfig.withLexicalRules`;
`DorisTokenizerTables.kt` and its generator are unchanged.

- Untagged `$$...$$` is one raw HEREDOC_STRING; a longer `$`-spelled identifier wins
  (`$$a$$b`); tagged forms and an unclosed `$$abc` stay identifiers.
- Block comments nest, also in `/*+` hints, and end where the native lazy recursive rule
  does, `/*/` overlaps and unclosable nesting included (checked against the native lexer for
  every `/*`-prefixed string over `/`, `*`, `a` up to 13 characters).
- Backslash + LF continues a `--` comment; backslash + CRLF does not.
- `#` and `{# #}` are not comments.
- Editor tokenization (so `SqlScriptSnapshot`) refuses an unclosed `$$` and a comment that no
  depth count closes at the native end: both usually mean unfinished input.
- `Dialect.semicolonsInBrackets` is false for Doris, so a snapshot statement with `;` inside
  brackets is a boundary error (PostgreSQL rule actions keep the default).
- `Dialect.halfOpenPartitionRanges` is true for Doris: after VALUES, `[` closes with `)`
  (`fixedPartitionDef`, `VALUES [(lower), (upper))`, the grammar's only mismatched pair).

Evidence: Doris 4.1.4.1 FE (`doris-4.1.4.1-rc02-5dbec55b399`) over raw JDBC rejects `#` and
`{# #}` comments (`mismatched input '#'`) and accepts nested comments, nested hints and
backslash-LF continuation. `TokenCorpusDifferentialTest` accepts sqlglot mismatches only under
named `DorisNativeLexing` exceptions; `DorisScannerCorpusTest` replays the Doris plugin's
scanner corpus against native results, naming its conventions and remaining disagreements.

## 54. ClickHouse typed query parameters stay as written

ClickHouse `{name:Type}` is a server query parameter, unlike an IDE `:name` bind. Parameter
discovery (`parseParameterOccurrences`, `SqlScriptParameters`, `SqlFragment.scalarParams`)
reports it as `ParamStyle.NAMED_TYPED`; `maskNamedParameters` and IDE prompting, which take
only `NAMED_COLON`, leave it alone. `Dialect.typedQueryParameters` marks ClickHouse, the only
core dialect whose parser reads the form. SQLGlot renders `{name: Type}`; the ClickHouse
generator instead reuses the authored text while the parsed placeholder is unchanged, so
lowering keeps `{a:UInt32}` byte for byte. A changed placeholder renders canonically.

## 55. Executable refusal for pipe stages the target rejects

Executable lowering (`toExecutable`, `toStandardSql`, source-preserving lowering and so
`lowerForExecution`) refuses with `UnsupportedError`, using #52's executable-purpose state,
where it used to return SQL the target engine rejects:

| Target | Refused | Engine evidence |
|---|---|---|
| Trino | pipe CALL, STRAIGHT_JOIN, ASOF JOIN, NATURAL JOIN | Trino 483: syntax errors; `t ASOF` reads as an alias; "Natural join not supported" |
| DuckDB | STRAIGHT_JOIN | DuckDB 1.5.6: syntax error |
| Doris | pipe CALL, STRAIGHT_JOIN, NATURAL JOIN | Doris 4.1.4 FE parser: mismatched input |
| ClickHouse | pipe CALL, star RENAME, STRAIGHT_JOIN | ClickHouse 26.7: `f((SELECT …))`, `* RENAME` and STRAIGHT_JOIN syntax errors |
| Presto | STRAIGHT_JOIN | Presto `SqlBase.g4` `relation` has only CROSS, typed and NATURAL joins |

Pipe CALL becomes a table function taking `(query)`, which these targets lack. ClickHouse
star RENAME follows #51: a complete caller-supplied layout (`toStandardSql` with
`expandStars`) still expands it to explicit ordered projections, which ClickHouse runs.
`transpileTo` previews keep their earlier output and carry the same message as an
unsupported finding. DuckDB CALL, ASOF, NATURAL and RENAME,
Doris ASOF and ClickHouse ASOF still lower. `PipeEngineRejectionTest` covers each case.

## Upstream sync protocol

1. Re-pin `reference/sqlglot`, regenerate all generated tables/corpora (`tools/*.py`),
   run the full gate suite.
2. `rg "brikk extension" brikk-sql/src` and revisit each site against the upstream diff
   (especially `parser.py` pipe handlers and `generators/doris.py`).
3. For each conflict: adopt upstream / keep ours / merge — then update this registry and
   the affected gate expectations in the same commit.
4. Do **not** treat remaining ledger entries for registered intentional divergences as
   bugs to close toward SQLGlot, and do **not** copy them into `TODO-BUGS-*.md`, unless
   step 3 explicitly reverses the policy here.
