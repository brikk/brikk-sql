# brikk-sql

SQL tokenizer, parser, AST, and generator for Kotlin Multiplatform, derived from
[sqlglot](https://github.com/tobymao/sqlglot) (Python, MIT, © Toby Mao).
BigQuery/GoogleSQL pipe syntax (`|>`) stays first-class in the AST instead of being
desugared away at parse time.
Target-specific fixes and other deliberate differences are listed in the
[extensions registry](../docs/brikk-extensions.md).

Differential gates compare token streams, serialized AST structure, and generated SQL
against pinned sqlglot (`v30.18.0-43-g3ca82489`) across thousands of corpus cases,
with explicit expectations for deliberate divergences. Separate semantic tests check
row results and output schemas. See [parsing research and plan](../docs/parsing-research-and-plan.md)
for architecture and status. Oracle parity alone does not prove correct row results
or preservation of the author's original SQL text.

This module lives at `brikk-sql/` in the standalone SQL repository, alongside
[metadata](../brikk-sql-metadata/), [verification](../brikk-sql-verify/), and
[real-engine oracles](../brikk-sql-oracle/). Its Toolchain name remains `brikk-sql`
and its Maven coordinate remains `dev.brikk.house:brikk-sql-jvm`. The Kotlin
runtime/compiler consumer lives in [Brikk Engine](https://github.com/brikk/brikk-house/tree/main/brikk-engine);
Kotlin packages and annotations have not changed.

Publications currently target JVM, with Java 21-compatible bytecode. The core is
predominantly common Kotlin; the schema-cache file API lives in `src@jvm/`.

## Quick start

```kotlin
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.dialects.transpileAll
import dev.brikk.house.sql.dialects.sql

// Parse (dialect-aware) → AST
val ast = parseOne("SELECT a, COUNT(*) FROM t GROUP BY a", dialect = "mysql")

// Generate SQL from an AST, in any dialect
ast.sql(dialect = "duckdb")                 // -> SELECT a, COUNT(*) FROM t GROUP BY a
ast.sql(dialect = "postgres", pretty = true)

// One-shot transpile between dialects
transpile("SELECT `col` FROM t LIMIT 5, 10", read = "mysql", write = "postgres")

// Scripts are explicit; transpile() rejects multiple statements instead of dropping them
transpileAll("SELECT 1; SELECT 2", read = "mysql", write = "postgres")
```

See the [dialect list](../README.md#dialects) for supported names and aliases.
Unknown names throw in `Dialects.forName`; `Dialects.forNameOrNull` probes availability.

## SQL preservation requirement

Parsing for validation, shapes, or lineage does not imply regenerating executable
SQL. Brikk Engine must leave same-dialect native SQL as written when no composition
or transpilation is requested. Binding may change only parameter representation;
native relation inputs permit only necessary slot/CTE changes. Pipe lowering may
change required structure, but must keep unaffected native SQL as close to source
as possible, including important hints, comments, and statement semantics.
Cross-dialect translation is explicit. Unchanged SQL must not go through automatic
optimization or canonicalization.

This is a requirement, not a source-preserving parse/generate API claim. The AST
generator regenerates SQL. Engine's `Rel.render()` now bypasses generation for a
standalone same-dialect native query unless its binding names need rewriting;
other composition/lowering paths still use generation. The [SQL preservation policy](https://github.com/brikk/brikk-house/tree/main/brikk-engine#sql-preservation)
requires output-diff inspection as well as semantic tests. Pipe lowering is the
largest migration risk: fix failures here with synthetic regressions rather than
keeping permanent copied handwritten SQL in the consumer as a workaround.

## Working with the AST

Every node is an `Expression` (package `dev.brikk.house.sql.ast`): a class per SQL concept
(`Select`, `Column`, `Literal`, `Join`, ... ~1,000 classes mirroring sqlglot's catalog),
holding children in an `args` map with declared `argTypes`, plus parent links, comments,
and positions.

```kotlin
import dev.brikk.house.sql.ast.*

val ast = parseOne("SELECT a, b + 1 AS c FROM d JOIN e ON d.id = e.id WHERE x > 2")

// Search
val where = ast.find(Where::class)                     // first Where node
val columns = ast.findAll(Column::class).toList()      // all column references

// Traverse (walk = DFS by default; bfs flag available on find/findAll)
ast.walk().forEach { node -> println(node.key) }       // key = lowercased class name

// Transform (copies by default; returns the new tree)
val rewritten = ast.transform { node ->
    if (node is Column && node.name == "a") parseOne("FUN(a)") else node
}

// Copy / equality are structural (parents, comments, positions excluded)
val clone = ast.copy()
check(clone == ast)
```

### Serialization

`Serde` (in `dev.brikk.house.sql.ast`) dumps/loads ASTs in the **same JSON format as
Python sqlglot's `serde`** — trees are interchangeable between the two implementations:

```kotlin
val payloads = Serde.dump(ast)          // kotlinx JsonArray
val restored = Serde.loadExpression(payloads)
check(restored == ast)
```

`Serde.stripMetaAndComments(payloads)` normalizes dumps for structural comparison
(drops positions/comments).

## Pipe syntax — the brikk extension

sqlglot parses `|>` queries but immediately desugars them into `WITH __tmpN AS (...)` CTE
chains — the author's stage boundaries are unrecoverable. brikk-sql keeps them:

```kotlin
val ast = parseOne(
    """
    FROM Produce
    |> WHERE item != :varthing
    |> AGGREGATE COUNT(*) AS num_items, SUM(sales) AS total_sales GROUP BY item
    |> ORDER BY item DESC
    """.trimIndent()
)
```

This yields a **`PipeQuery`** node (package `dev.brikk.house.sql.ast`, module
`brikk.pipes` in serde dumps — these nodes are brikk-native, they do not exist in Python
sqlglot):

- `PipeQuery.thisArg` — the head (the `FROM Produce` source; a `Select`/`Subquery`)
- `PipeQuery.expressionsArg` — the ordered stage list, one node per `|>` operator:

| Stage node | Carries |
|---|---|
| `PipeSelect` / `PipeExtend` | projections |
| `PipeWhere` | condition (`Where`) |
| `PipeAggregate` | aggregate projections + `group` / `group_and_order` (incl. `GROUP BY x AS y`) |
| `PipeOrderBy` | `Order` (last one wins on desugar) |
| `PipeLimit` / `PipeOffset` | `Limit` / `Offset` (desugar composes ordered row slices) |
| `PipeAs` | stage alias (`TableAlias`) |
| `PipeDistinct` | — |
| `PipeJoin` | `Join` |
| `PipeSetOperation` | UNION/INTERSECT/EXCEPT + ALL/DISTINCT/BY NAME/side modifiers |
| `PipeTableSample` / `PipePivot` / `PipeUnpivot` | respective clauses |

Stage nodes are ordinary `Expression`s: `walk`/`find`/`transform`/`copy`/serde all work.

```kotlin
import dev.brikk.house.sql.ast.*

val pipe = ast.find(PipeQuery::class) as PipeQuery
val stages = pipe.expressionsArg              // List<Expression>, in pipeline order

// 1. Desugar to standard SQL (byte-identical to what Python sqlglot produces)
val standard = desugarPipes(ast)              // copies by default
standard.sql()                                 // WITH __tmp1 AS (SELECT ...) SELECT * FROM ...
standard.sql(dialect = "doris")                // ...in any dialect

// 2. Or render BACK to pipe syntax (sqlglot cannot do this)
ast.sql()                                      // FROM Produce |> WHERE item != :varthing |> ...
```

Round-trip guarantee: generated pipe SQL re-parses to the identical tree. This is
structural equality, not preservation of the original text, formatting, comments,
or hints. It does not establish the SQL preservation requirement above.

### Lightweight stage splitting (no parser)

For tooling that only needs raw stage text (offsets, counts, JSON), the tokenizer-level
splitter avoids full parsing — `|>` inside strings, comments, quoted identifiers, and
parenthesized subpipelines never splits:

```kotlin
import dev.brikk.house.sql.parser.PipeStageSplitter

val doc = PipeStageSplitter.split(sql, dialect = "bigquery").toDocument()
doc.toJson()
// {"dialect":"bigquery","token_count":36,"pipe_operator_count":3,
//  "raw_stages":["FROM Produce","WHERE item != :varthing",...]}
```

## Placeholders and parameters

All three placeholder forms tokenize/parse/generate across dialects: positional `?`
(`Placeholder`), named `:name` (`Placeholder(this="name")`), and `@name` (`Parameter`).
These are the anchor points for brikk's binding and (future) table-valued fragment slots.

## Semantic layer

The full sqlglot semantic pipeline is ported and oracle-gated:

- **Type inference** — `annotateTypes(ast, schema, dialect)` (`optimizer/AnnotateTypes.kt`,
  per-dialect metadata generated from sqlglot's typing tables); gated against
  Python-annotated serde dumps for every dialect corpus.
- **Qualification** — `qualify(ast, schema, dialect)` (`optimizer/Qualify.kt`:
  normalize/qualify tables + columns, schema-aware star expansion, validation); gated
  against sqlglot's own optimizer fixture files.
- **Column-level lineage** — `lineage(column, sql, schema)` (`optimizer/Lineage.kt`):
  provenance DAG through CTEs, subqueries, set ops, pivots, star expansion; 54-case
  structural parity with Python.
- **Shape contracts** — `shape/SqlFragment`: `outputShape(inputs)`, three-way shape
  comparison (SATISFIES / HAS_ADDITIONAL / HAS_LESS), TVF-style slot detection,
  `describe()`/`contract()` serializable payloads.
- **Source mapping** — AST nodes carry original source positions; `transpileTo(target,
  trackSourceMap = true)` maps errors in *generated* SQL back to the original text.
- **Native-grammar verification** — the [brikk-sql-verify](../brikk-sql-verify/) modules (JVM, one artifact per
  engine plus an aggregate) check emitted SQL against the target engine's real parser (Doris FE, Trino,
  DuckDB embedded).
- **Function catalogs + semantics** — the [brikk-sql-metadata](../brikk-sql-metadata/) module (featherweight,
  ~100KB) ships each engine's registered functions with signatures where extractable,
  per-function `SemanticProfile`s (null propagation from Doris's own source; DuckDB
  parameter names on 96% of overloads), grammar-builtin allowlists for names the
  engine parses at the grammar level instead of registering (Trino `COALESCE`/`CAST`/
  `EXTRACT`, DuckDB `COALESCE`/`GROUPING` — `FunctionCatalog.isKnown`), and the
  probe-verified `HazardRegistry` (trino↔duckdb semantic verdicts with provenance).
  Powers engine-exact `unmappableFunctions(target)` capability checks and
  certification below.

`RelationScope.analyze` and `RelationScope.complete` also handle DuckDB UPDATE and MERGE expressions.
SET targets see only the physical target table; values and WHERE see the target plus FROM
sources, with that frame available to correlated scalar subqueries. FROM joins cannot read
the target, and RETURNING sees only the target. This is symbol resolution and completion,
not UPDATE result-shape or assignment-lineage support. MERGE branches expose both sides when
matched, only the target for NOT MATCHED BY SOURCE, and only the source for NOT MATCHED BY TARGET.
Its RETURNING also exposes the VARCHAR `merge_action` pseudo-column, unless a real target column
shadows that name. An incomplete target inventory does not prove the pseudo-column's type.
Without an explicitly supplied query scope, query-lineage entry points refuse root
UPDATE/MERGE statements instead of mistaking their last nested query for the statement result.
MERGE INSERT BY NAME stays structured through
serde and generation; executable generation refuses it for unproved non-DuckDB targets.

DuckDB NEAREST joins retain their count, mode and ranking expression through parsing, serde,
qualification and generation. Native 2.0 permits INNER and LEFT joins with a positive int64
literal count, or an omitted count meaning one. Ranking expressions resolve against candidate
rows before LEFT join NULL extension. Quoted mode-word aliases remain identifiers. Native
result tests cover per-row top-k, metric direction, NULL ranks and tie cardinality; they do
not promise a particular tied winner or future approximate-search recall. Other targets get
flagged previews and executable refusal, not an invented portable nearest-join translation.

DuckDB 2.0 side-effecting CTEs support INSERT, UPDATE, DELETE and COPY TO bodies. Their
RETURNING fields feed later CTE/query scopes and completion. Without RETURNING, the pinned
engine exposes a BIGINT `Count`, not the target table's columns. COPY TO exposes `Count`,
`Count`/`Files`, or the seven RETURN_STATS fields according to its options; dynamic result
flags leave the contract unknown. These contracts come from syntax and copied schema facts.
Analysis never prepares or executes a statement, evaluates a function, or opens an output path.

INSERT/UPDATE RETURNING describe new row images, so their CTE outputs do not inherit old-value
physical provenance. DELETE can retain direct pre-deletion provenance. Query-root shape inference
uses these contracts without SELECT-optimizer rewrites. Automatic qualification/star expansion
and assignment-lineage inference across side-effecting CTEs explicitly refuse where unmodeled;
ordinary read-only CTEs keep their existing analysis. Cross-target executable generation also
refuses unverified DuckDB side-effecting CTE translations. Native DuckDB generation preserves
the producer, references, dependencies and authored materialization hints.

Native result tests verify one execution per statement even for unreferenced/empty consumers
and NOT MATERIALIZED, plus fresh execution of prepared plans, table rollback and actual COPY
files. This is the pinned engine's behavior, not an app-side materialization algorithm or a
promise that exported files roll back. JDBC 2.0.0.0 may classify an outer SELECT as an update
count when a producer lacks RETURNING. An executeQuery error in that case does not mean the
write did not run; do not retry the SQL to recover a ResultSet.

DuckDB 2.0 grouping UNNEST stays a scalar grouping expression. Qualification preserves it,
including projection aliases, ordinals and scalar wrappers, rather than moving it into FROM.
Native result tests cover repeated values, NULL elements, NULL/empty lists, aggregate inputs,
zip/padding of grouped UNNESTs and post-aggregate expansion of ungrouped projection UNNESTs.
List element types feed scopes and shapes; constant `recursive`/`max_depth` options also remove
the appropriate list levels. Unknown options and recursive struct expansion do not prove a
single-column type. A non-null list does not prove non-null elements or prevent NULL padding.
Native binding, not static name resolution, enforces the restrictions on nested UNNEST calls,
struct expansion, GROUP BY ALL, aggregates and windows inside grouping expressions.
Non-DuckDB executable generation refuses unverified grouping-UNNEST translations before target
projection rewrites; previews carry a finding. Ordinary scalar/table UNNEST paths are unchanged.

DuckDB FETCH accepts FIRST/NEXT, ROW/ROWS, an omitted count of one and full count expressions.
Generation normally canonicalizes it to LIMIT/OFFSET. The parser requires FIRST/NEXT and
ROW/ROWS ONLY, rejects duplicate pagination, and does not accept WITH TIES or PERCENT. The
engine still decides count/offset binding, rounding, NULL and negative-value behavior.
Anonymous pagination parameters retain their authored order through copy, serde and generation.
For `OFFSET ? FETCH NEXT ?`, DuckDB emits `OFFSET ? LIMIT ?` so existing JDBC bindings do not
swap meanings. Cross-target execution refuses a pagination rewrite that would reorder these
parameters; numbered parameters can retain their identity across clause reordering. Tests run
both authored and generated queries with the same prepared bindings and check CTE/set boundaries.

DuckDB 2.0 JSON mutation calls retain their authored values and paths, including JSON Pointer
escapes, quoted JSONPath keys, reverse indexes and named arguments. SET/INSERT/REPLACE accept
one scalar path and one JSON value; REMOVE accepts one scalar path. Path lists, repeated
path/value pairs and BigQuery strip-null options are not DuckDB overloads. Values such as
`'"hello"'` are JSON strings; `'hello'` is invalid JSON, and numeric SQL literals need an
explicit JSON conversion. The library does not quote values or reinterpret paths to make
these calls resemble another dialect.

Scope/shape contracts return JSON for mutation, deep merge, merge-patch diff and null stripping;
`json_normalize` returns VARCHAR. Static typing recognizes the pinned positional and fixed named
signatures, not arbitrary schema-owned UDFs. Native binding still decides argument types and
JSON/path validity. Nullability follows argument roles: REMOVE may return SQL NULL when removing
the root; deep merge and patch diff can ignore a SQL NULL base, and a later deep-merge patch can
replace a SQL NULL intermediate. SQL NULL is not JSON `null`.

Deep merge skips JSON-null object patch members where the original is also an object, while
merge patch deletes those keys. Arrays replace rather than concatenate. Normalization sorts
object keys recursively but preserves array order and duplicate keys; it can change numeric
serialization and is not a cross-engine canonical-byte guarantee. Null stripping removes
JSON-null object members but retains null array elements and empty containers. Native diff tests
cover representable round trips, not every JSON document: applying the diff from `{}` to
`{"a":null}` returns `{}`, because merge-patch null deletes a key. Native tests pin that limit.

The existing JSON nodes and raw function calls preserve these operations without app-side
JSON evaluation or new AST classes. Cross-target execution refuses unverified same-name
translations in either direction; previews carry a finding instead of claiming equivalence.

DuckDB VARIANT retains its own binary-value semantics, not JSON text semantics. Static contracts
cover the documented variant functions and scalar/list-path return dimensions. Extracted fields
remain nullable, computed VARIANT values; no object-field inventory or embedded SQL type is
invented. Qualification preserves case-sensitive VARIANT fields and one-based bracket indexes,
including when the input's type becomes known only from schema facts. Existing native casts,
DDL, COPY/shredding options, copy and serde remain structured. Cross-target execution refuses
unproved translations of VARIANT types and functions.

Native tests distinguish VARCHAR-to-VARIANT from JSON-to-VARIANT, missing fields from VARIANT
nulls, numeric equality from string equality, and literal extraction fields from JSONPath.
They also verify checkpoint/reopen with shredding enabled and default/explicitly shredded
Parquet round trips. `variant_group_array` is a macro with aggregate behavior, but native 2.0
rejects ORDER BY/DISTINCT/FILTER inside that call; ordered input is a separate operation.
These result tests do not add a JDBC nested-VARIANT reader or promise another engine's semantics.

DuckDB `$name` is a named prepared parameter with an optional session-variable default. It
must not be rewritten to `getvariable('name')`: an unset `$name` errors, while `getvariable`
returns SQL NULL. Explicit EXECUTE arguments take precedence; omitted names use the current
session values at execution time. Numeric `$1`, quoted numeric `$"1"` and anonymous `?` do not
use that fallback. SET VARIABLE is session state, not SET GLOBAL configuration.

PREPARE bodies, EXECUTE arguments, DEALLOCATE and RESET VARIABLE are structured ASTs. Parameter
discovery sees the prepared body without preparing it. PREPARE/EXECUTE statement result shapes
and lineage are not inferred from a nested query body. The legacy unquoted-dollar Placeholder
shape stays serde-compatible, while quoted dollar names retain their quotes. Exact authored
occurrences and `SqlFragment.scalarParams` classify dollar parameters as engine sigils, not IDE
colon substitutions. `duckdbSessionDefaultCandidates(sql)` reports eligible names only; it does
not prove a variable exists, read its value/type, resolve it, or require a bind prompt. Existing
editor colon discovery/masking leaves dollar names, dollar-quoted strings and numeric binds alone.
Cross-target execution refuses unproved variable/default and session-statement translations;
ordinary authored `:client` substitutions keep their existing translation path.

SQL EXECUTE fallback and JDBC PreparedStatement binding are separate contracts. With the pinned
2.0.0.0 driver, a direct unbound JDBC named statement reports a parameter-count mismatch, and
index-based binding is not a named-argument override. Use the client's supported binding API;
the library adds no retry or driver workaround. JDBC duplicate copies current session variables
at creation, but later changes remain local. Native tests also prove PREPARE/editor analysis do
not run writes and that each deliberate EXECUTE runs once with its current defaults.

DuckDB recursive `USING KEY` accepts aggregate payload expressions and explicit destination
aliases. Plain keys keep their existing Identifier serde shape. The documented `TABLE cte`
query shorthand renders as `SELECT * FROM cte`. Analysis uses separate contracts for raw
candidate rows, finalized `recurring.cte` state and the final result. `UNION ALL` feeds raw
candidates to the next iteration; `UNION` feeds only new/changed finalized rows, with aggregate
result types. The aggregate arguments themselves read the raw anchor-column contract.

Scopes/completion expose the CTE's declared aliases in each stage. Known aggregate families
provide final AVG, promoted SUM, COUNT, MIN/MAX, LIST and string-result types; unmodeled
overloads stay unknown instead of borrowing seed types. The final row is computed, not direct
seed-value provenance. `recurring.cte` is recognized only in its recursive member and is not
reported as a physical input. Shape inference reads these contracts without evaluating a
fixed point. Generic SELECT qualification/type rewrites, lineage and unproved cross-target
execution refuse keyed aggregate-state transformations; ordinary recursive CTE paths remain.

Native tests cover aggregate output types and aliases, frozen recurring snapshots, UNION versus
ALL work, a convergent cyclic shortest-path graph and final-state draining without another
recursive build. Error controls retain native restrictions on aggregate modifiers and key/
payload targets. The probes are bounded or use native keyed convergence and have query deadlines;
the library does not prove termination, emulate recursion, or add an app-side materialization rule.

### Certified transpilation

`transpileTo()` is best-effort by design. `SqlFragment.certify(target)` rolls every
diagnostic channel into one `TranspileReport`: unmappable functions (Class-3 capability
holes), generator `unsupported` flags, raw-passthrough statements (Command/Pragma), and
probe-verified semantic hazards (`HazardRegistry` in brikk-sql-metadata; trino↔duckdb,
trino↔doris, duckdb↔doris). Hazard detection is **verdict-driven and multi-key**: each
source function is looked up under every name it can be known by for the pair (its parsed
`sqlName`/aliases plus the names it renders to under the source and target dialects, so a
translated cross-name function still matches), and the **verdict decides** — divergent/
unclear are refusals, conditionally-equivalent are warnings, identical/no-equivalent clear.
A dedicated renderer no longer blanket-trusts a mapping: trust lives in the probe data (an
`identical` entry is verified-safe; a `divergent` one refuses whether or not a renderer
exists). Machine mode
("must work or error"): `transpileStrict(target)` / `report.orThrow()`. Human mode
("warn me, I'll hand-edit"): read `report.findings`, ship `report.result.sql` anyway.
Pipe-syntax fragments targeting a real engine should pass `desugarPipes = true`
(engines don't speak `|>`; available on `transpileTo`/`certify`/`transpileStrict`).
Native grammar checking verifies syntax, not bindings, output columns or row
semantics. Schema-backed tests should also strictly qualify the generated SQL and
assert its exact output columns; executable fixtures should compare rows.

Verdicts stay faithful to the research, but a consumer can accept a refusal its own data
makes irrelevant via `report.okAccepting { predicate }` — `ok` where every refusal is
accepted. Each `SEMANTIC_HAZARD` finding carries the hazard's `areas` tags, so an
ASCII-only corpus can waive the unicode case-folding hazard with
`report.okAccepting { "unicode" in it.areas }` while any other refusal still blocks.

## Errors

- `ParseError` — structured (message, line, col, context highlight)
- `TokenError` — tokenizer-level failures
- `UnsupportedError` — lowering or generation cannot safely preserve the requested query

`toExecutable()`, `transpileTo()` and certification can throw `UnsupportedError`
before returning a result. `unsupportedMessages` is a separate channel for
best-effort SQL that was returned; it is not an exception container. Callers must
handle expected refusals as translation failures. Do not execute the original pipe
or delegate to a previous execution handler after a refusal, and do not catch
cancellation or arbitrary failures as though they were supported translations.
The [candidate consumer gate](../docs/consumer-verification.md) tests this
contract against the Doris plugin's actual adapter and dispatch helper.

Pipe LIMIT/OFFSET lowering accepts only non-negative signed-64-bit integer
numeric literals. Unsupported values, expressions, placeholders, row-count
modifiers and overflowing slice arithmetic raise `UnsupportedError`; they are
not coerced or silently dropped. Syntax errors still come from the parser.

Doris PIPE RENAME requires a complete input shape. The no-schema executable path
refuses with `UnsupportedError`; use `toStandardSql("doris", inputs, expandStars = true)`
for schema-aware projection expansion. Invalid known mappings or duplicate schema
columns raise `ShapeError`. Qualified rename arguments and unrepresentable target
bindings remain typed refusals. This does not change DDL RENAME support.

Anything the parser does not yet support fails loudly with a `ParseError` raise-gate —
there is no silent misparsing.

## Status / known gaps

- Optimizer rules beyond the semantic pipeline are not ported (pushdown_projections/
  predicates, merge/eliminate subqueries, canonicalize, full normalize/simplify) —
  nothing currently depends on them.
- UDF typing is stubbed (`Schema.getUdfType` → UNKNOWN); function `sinceVersion`
  metadata awaits a doris-website docs source; Trino semantic profiles (null handling)
  are not exposed by `SHOW FUNCTIONS`.
- Hazard coverage is trino↔duckdb only today; other pairs gain entries as they get
  probe-verified evidence.
- Platforms are temporarily narrowed to JVM while Amper KMP publishing matures (the
  code is pure common Kotlin; restore note in `module.yaml`).
- Per-dialect gate status lives in the `testResources/**/known-failures*.json` ledgers,
  enforced two-directionally (a stale ledger entry fails the build). Remaining entries
  are few and documented (e.g. UPDATE ... FROM rewrites, `explode_projection_to_unnest`).
- Deliberate divergences from upstream sqlglot are registered in
  [brikk extensions](../docs/brikk-extensions.md).
  Verifier-backed entries there are intentional and must not be copied into
  actionable TODO inventories or "fixed" toward SQLGlot unless that policy is
  reversed.

## Attribution

This module is a Kotlin port of **[sqlglot](https://github.com/tobymao/sqlglot)** by Toby
Mao, licensed MIT. The architecture, AST node catalog, dialect semantics, and much of the
test corpus derive from sqlglot; ported functions carry `// sqlglot: <symbol>` provenance
comments referencing their Python counterparts, and generated tables are pinned to a
specific upstream version. Pipe-syntax semantics follow the
[GoogleSQL pipe query syntax](https://cloud.google.com/bigquery/docs/reference/standard-sql/pipe-syntax)
specification. See [ATTRIBUTIONS.md](../ATTRIBUTIONS.md) for license texts.
