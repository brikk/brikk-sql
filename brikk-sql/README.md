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

DuckDB CONNECT/DISCONNECT remain session-routing statements, not ATTACH/USE rewrites or query
outputs. CONNECT accepts an attached catalog name or a literal connection string; options are
allowed only for strings, using whitespace separators. PostgreSQL/MySQL strings are preserved
as opaque data. Bare CONNECT and CONNECT LOCAL are parsed native forms but are not implemented
in pinned 2.0, so executable generation refuses them. A quoted catalog named "LOCAL" is different.
Other targets get explicit routing refusals, not an invented portable connection operation.

Authored connection strings/options remain in AST, serde and emitted SQL and must be handled as
sensitive data. DuckDB parser/editor-wrapper/verifier diagnostics redact routing details without
altering those values. Low-level token/AST dumps and native execution exceptions are not a safe
logging channel. Routing verification never prepares/binds options, even in the trusted corpus
oracle; its non-SELECT parse-only limitation remains Unverified. Analysis does not acquire a
backend, evaluate option expressions, change a user's source connection or reconnect anything.

Native loopback Quack tests cover named versus ephemeral routing, local/remote session state,
return to local scope, reconnect persistence, forwarded errors and cleanup after authentication
failure. The fixture uses a random token/port, disables extension auto-install/load and secret
creation, retains signature verification, and stops its own server. It does not certify live
PostgreSQL/MySQL deployments or promise an explicit catalog name escapes an active remote route.
Run that optional fixture with `BRIKK_DUCKDB_QUACK_EXTENSION` set to a signed v2.0.0 file named
`quack.duckdb_extension`, plus matching httpfs already available. Tests never install it into the
user's extension store. Without this explicit dependency the new loopback test reports a skip.

DuckDB 2.0 trigger definitions keep one inline INSERT/UPDATE/DELETE/MERGE action. Grammar
checks do not substitute PostgreSQL EXECUTE FUNCTION, multi-event or WHEN syntax. Qualified
DROP TRIGGER targets retain the required attached-table identity. Native single-statement
trigger bodies have script boundaries; compound BEGIN/END bodies remain refused.

Definition scopes/completion expose target fields for UPDATE OF, NEW for supported AFTER INSERT
row triggers, OLD for AFTER DELETE row triggers, and explicit OLD/NEW TABLE aliases for supported
AFTER statement triggers. Image columns have target types but computed provenance. Those aliases
are local to the action and are not physical input tables. Trigger DDL has no body-result shape
or lineage; SELECT optimizer/type rewrites and unproved cross-target DDL explicitly refuse it.
Known unsupported timing, granularity, transition and temporary forms also refuse executable
generation. Native binding still decides schema-dependent validity, dependencies and catalog state.

Native tests verify trigger order, BEFORE/AFTER state, empty statement input, per-row duplicates,
UPDATE OF, transition audits, outer RETURNING/generated columns, MERGE bodies and rollback.
MERGE can be an action, including against the trigger's own table; top-level MERGE against a
trigger-bearing table is a separate unsupported path. Runtime controls pin row RETURNING,
mixed granularity, row UPDATE/generated/cascading restrictions and dependency/drop behavior.
Analysis and parse-only verification neither register nor run a definition. No application-side
trigger execution, mutation retry, or new JDBC result interpretation is introduced.

DuckDB 2.0 SECURE VIEW definitions retain their marker through copying, serde, qualification
of a non-recursive authored body, catalog definition reads and SQL regeneration. Native tests
cover filtered results, aliases, replacement/existence/temporary forms, recursive views,
checkpoint/reopen and database export/import. Secure recursive view syntax has a dedicated
modifier. The explicit PERSISTENT keyword is rejected for views by native 2.0, even though
its shared CREATE grammar contains it; omitting TEMP already gives a persistent view.
Recursive self-reference scope, dependency inventory and optimizer qualification/type rewriting
are not modeled and explicitly refuse. DDL itself has no query-result shape or lineage. A
non-recursive authored body can be analyzed separately, but row-contract catalogs do not encode access policy or permission
to inline a stored view. Unproved cross-target SECURE VIEW translations refuse execution,
even when another engine uses the same keyword, rather than silently emitting an ordinary view.

SECURE hides internal query plans and statistics for rows hidden by the view. It does not
encrypt the definition: `duckdb_views()` and `information_schema.views` still report SQL,
and the definition reader deliberately preserves that SQL and its SECURE marker. An unfiltered
view may expose statistics about values its caller can already read. Native controls pin
logical/physical/ANALYZE/JSON plan hiding, filtered-statistics suppression, visible-only outer
predicate evaluation and SQL-plan export retaining the view reference. This is not a claim of
general authorization, definition secrecy, timing-side-channel protection or a ban on safe
predicate pushdown. Persistent secure views require native storage format v2.0.0 or newer;
older storage fails instead of downgrading the view. Temporary secure views remain possible.

DuckDB 2.0 extension repositories are structured statements, including CREATE/OR REPLACE/
IF NOT EXISTS, literal PREFIX and PUBLIC KEY(S), and DROP/IF EXISTS. INSTALL AND LOAD,
INSTALL VERSION, explicit LOAD FROM and LOAD AS retain their operands. Simple INSTALL keeps
its earlier AST/serde shape. Prefixes, URLs and multiline key bytes remain authored data;
analysis does not normalize keys, resolve repository names, fetch metadata, install/load code
or change grammar activation. Parser/editor/verifier diagnostics redact repository details;
AST/serde/emitted SQL and raw native execution exceptions are still sensitive logging channels.

The spelling of FROM is significant. A repository identifier and a string are not interchangeable:
for a custom repository, INSTALL AND LOAD FROM 'name' can install successfully and then fail
to load because the load step loses the repository selection. That partial installation is
not rolled back, and the library never retries it or rewrites the string into an identifier.
Repository selection is separate from a grammar profile, and registration does not load a module.

CREATE returns repository_name VARCHAR, prefix VARCHAR and key_fingerprints VARCHAR[]. Those
are native runtime management results, with computed provenance, not table-column lineage.
Other extension-management statements have no query output shape. SELECT qualification and
type rewrites of new management nodes refuse; simple INSTALL keeps its earlier literal-annotation
contract. Unproved cross-target execution refuses. All verifier channels stay
parse-only for management, including the trusted corpus oracle; unsupported non-SELECT
serialization remains Unverified instead of preparing/binding or manufacturing acceptance.

Native tests cover pinned fingerprints, PEM/key rotation, metadata fetched only at explicit
execution, stored trust across instances, runtime policy tightening and configuration locks.
The optional signed-binary fixture also tests private repository installs, bare-load isolation,
string-versus-identifier partial success, wrong-key/origin failures and fresh-session loading
after key changes or forbidden trust. It does not promise to unload already-resident code.
Run it with BRIKK_DUCKDB_REPOSITORY_EXTENSION set to a trusted matching v2.0.0 file named
inet.duckdb_extension. The fixture re-signs a scratch copy with ephemeral RSA keys, registers
only a private trust directory and installs only into a private extension directory. Signature
checks remain enabled, auto-install/autoload are disabled, and the source binary is untouched.
No network repository or user's trust/extension store is used. Without the explicit dependency,
the signed-binary tests report skips rather than substituting unsigned or unrelated evidence.

DuckDB 2.0 external-resource syntax is structured: CREATE, REGISTER ... FROM <handle>, DESTROY,
SHOW [ALL], ATTACH TO and CONNECT TO, including NEW TEMPORARY sources. Provider/type strings,
resource identifiers, handle expressions and option values remain authored data. Creation options
belong to the resource source; attachment/connection options are a different bag. ATTACH requires
an AS alias between its bags. CONNECT to a newly provisioned temporary source cannot take a
second option bag. CREATE/REGISTER without a name can be parsed but executable generation
refuses the known native binder error instead of inventing a name or changing ownership.

Ordinary SHOW lists the instance's registrations; SHOW ALL invokes native discovery callbacks.
Both have a fixed eight-column runtime contract with computed provenance, nullable discovery
fields and an opaque MAP(VARCHAR, VARCHAR) handle, not a guessed provider-specific schema.
The other management statements expose no query output shape. SELECT qualification/type
rewrites, lifecycle lineage, handle-subquery dependency inference and unproved cross-target
execution refuse rather than treating resources as physical tables or inlining their callbacks.

All verifier channels stay parse-only for this syntax and recognizable resource API calls,
including the trusted corpus oracle. Neither SHOW ALL nor EXPLAIN/prepare is a validation
fallback: binding options/handles can evaluate expressions, and discovery can call providers.
Pinned native controls show bind-time sequence effects; autocommit JDBC prepare can also hit
DuckDB 2.0's internal read-only-transaction error. That error is isolated in a test, with no
production retry, transaction workaround, lifecycle emulation or change to a user's session.
Parser/editor/verifier diagnostics redact resource details. AST/serde/generated SQL, native
execution errors and native listing rows can still contain sensitive handles/URIs and tokens.

Native execution tests use only SQL mock recipes, sequences and owned scratch DuckDB files.
They cover CREATE versus REGISTER callback counts, quoted names, local versus discovery lists,
borrowed versus owned attachment cleanup, post-provision failure cleanup, option collisions,
failed DESTROY retaining registration and explicit repair. CONNECT cleanup is tested against
the local backend's unsupported-routing error, not a live provider connection. No cloud resource,
provider extension, remote service, installation, or user's database/resource is touched. These
tests do not certify provider APIs, billing, polling deadlines or general teardown reliability.
No dedicated public DuckDB 2.0 documentation page was found for this syntax; the grammar and
runtime contracts are pinned to native source/tests, and metadata deliberately invents no URL.

DuckDB EXPLAIN has a structured wrapper and ordered option nodes for SQL, FORMAT and ANALYZE,
with its child statement retained through copy/serde/generation. The lexer keeps EXPLAIN tails
tokenized, so parameter intervals and authored bind order remain available. Prefix ANALYSE and
the unquoted ANALYSE option canonicalize to ANALYZE without turning the option into a quoted
identifier. Invalid option combinations remain inspectable but refuse executable generation;
unmodeled renderer names, CLI-only WEB and unresolved bindings in ignored ANALYZE arguments
also refuse. Child statement families still represented by a raw command are preserved for
inspection, not certified for execution, dependency inference or parameter binding.

ANALYZE's presence enables execution. In native 2.0, even ANALYZE false or ANALYZE 0 executes
the child; its argument is not a boolean switch and expressions in it are ignored. Repeated
ANALYZE options do not execute the child repeatedly. SQL occurs once without arguments and
cannot combine with ANALYZE or FORMAT. FORMAT accepts supported constant names, identifiers
and DEFAULT, not an arbitrary evaluated expression. Context-specific renderer lookup is not
emulated by the library, and no browser or renderer UI is launched.

EXPLAIN always returns explain_key VARCHAR and explain_value VARCHAR, including SQL export,
JSON formatting and ANALYZE; it does not expose the child's row shape or a typed JSON value.
Child column scopes can be inspected separately from option names, and source inventory uses
the child. Plan text has computed provenance, not child-result lineage. Automatic wrapper
qualification/type rewrites and unproved cross-target option/plan translation explicitly refuse.

Native tests distinguish SQL export, plan generation, binding effects and child execution.
SQL mode exports native optimized SQL without executing the child, but binding range(nextval())
still advances a sequence. Explicit execution of exported DML is a separate operation; ANALYZE
executes DML/COPY itself. Neither EXPLAIN nor EXPLAIN SQL is an editor-validation fallback.
All verifiers remain parse-only for EXPLAIN, with transformer/renderer limitations left
Unverified rather than binder-based acceptance or a false parser rejection. The existing
DuckDB text-EXPLAIN editor action remains refused. Tests use owned memory tables, sequences
and scratch output files; generated SQL/plan text can be sensitive and is not sanitized output.

DuckDB 2.0 OVERLAY accepts either `OVERLAY(string PLACING replacement FROM start [FOR count])`
or three/four comma-separated positional arguments. Both use the existing Overlay node and
may regenerate in keyword form. Mixing the two forms is rejected. Bare OVERLAY's special
grammar rejects named arguments; the quoted ordinary call `"overlay"(...)` accepts them and
retains its quoted name and authored argument order. Invalid arities stay inspectable without
losing arguments, but their typing stays unknown and executable generation refuses them.

Successful builtin calls return VARCHAR even when the source is SQL NULL. NULL in any present
operand propagates; omitted count is distinct from an explicit NULL count. An omitted or
negative count uses the replacement's Unicode code-point length, zero inserts without removal,
and starts beyond the source append. Zero/negative starts are accepted and affect the removed
span, e.g. `overlay('abcdef','XY',0,2)` returns `XYbcdef`, not `XYcdef`. Native tests pin BIGINT
boundaries, multibyte characters, combining marks and emoji: this is neither byte nor grapheme
indexing, and the library does not normalize Unicode or emulate the operation with substrings.

Scopes/shapes use the fixed string return type and all present operands' NULL contracts, with
computed provenance; column lineage/completion still inspect the arguments. Qualified UDFs
remain ordinary calls with unknown builtin contracts. Unproved DuckDB-to-other-engine and
reverse same-name translations refuse execution. No generated catalog/corpus is refreshed:
tests compare the two captured 2.0 overloads with the real engine and confirm absence from 1.5.6.
The omitted default count is never expanded into a second replacement expression; native
sequence and prepared-binding controls prove no duplicated evaluation or reordered binds.

DuckDB 2.0 COPY layout options use the native `PARTITION_BY` and `ORDER_BY` names.
`PARTITION_BY (region, day)` selects output columns; compute a new partition key in the
source SELECT and refer to its output alias rather than treating `PARTITION_BY` as a
row-expression clause. `ORDER_BY (abs(value) DESC NULLS LAST, id ASC)` has structured
ordering terms. It can appear alone or with partitioning, in either option order. The
parser retains option order, expression order, explicit direction and NULL ordering.
Omitted ordering defaults remain omitted, so DuckDB resolves them in the executing session.
Copy/CopyParameter/Tuple/Ordered are existing AST nodes; no new public AST shape is needed.

Native tests inspect physical file rows for Parquet and CSV, multiple partition columns,
projected aliases, NULL keys, collations and explicit WRITE_PARTITION_COLUMNS. In DuckDB
2.0, ORDER_BY alone creates an output directory even when its name ends in `.parquet` or
`.csv`. Partitioned output is sorted within each physical file, including parallel writes
and file rotation; the library does not promise an order across files or stable ties.
Neither options nor source expressions are evaluated by static analysis or native
parse-only validation. Prepared native controls include source, destination and ordering
parameters and repeated executions. Sequence controls pin once-per-row source evaluation
and the native plan's twice-per-row volatile sort-key evaluation. The library preserves
that behavior; it does not promise exactly-once ORDER_BY evaluation or expand sort keys.

Symbolic scopes and completion bind ordinary layout columns/ordering expressions against
the COPY output row, not the source SELECT's FROM namespace. CTE results remain the COPY
Count/Files/statistics contract rather than the exported row. Textual ordering SQL and
dynamic or qualified partition-column lists can round-trip to native execution, but their symbolic
scope explicitly refuses rather than inventing references. Automatic qualification and
cross-target layout execution also refuse. Native binder controls distinguish missing
columns, qualified source names, ordinal constants, unsupported partition expressions and
ORDER_BY subqueries from syntax errors. Empty ORDER_BY is refused statically; native COPY
first parses it, then its order-list parser rejects it during binding. COPY itself cannot
be serialized by json_serialize_sql, so parse-only verifier success is not a binding check.
DuckDB 2.0 `PARTITION_PATH` retains a scalar expression in the COPY option bag, including
CASE, function calls and parenthesized expressions. It binds only to unqualified PARTITION_BY
output columns. Scope completion offers that subset, not every exported column or the source
SELECT's table namespace. Known aggregates, windows, subqueries, lambdas and qualified column
references explicitly refuse symbolic scope. Automatic qualification, exported-row lineage
and unverified cross-target layout execution remain refused. COPY result contracts are still
Count/Files/statistics. Static analysis and parse-only verification never evaluate the path.

Native controls pin the requirement for PARTITION_BY and the incompatibility with an explicitly
specified HIVE_FILE_PATTERN, even false. Non-string path results cast natively to VARCHAR.
Runtime paths must be non-NULL, nonempty after removing empty/dot components, relative to the
target and free of `..` components. Different partitions cannot share a normalized directory;
one partition's directory may contain another partition's directory. The library does not
normalize authored paths, precompute names, provision resources, retry COPY or emulate rollback.
A runtime failure can follow earlier partition writes. Tests use only isolated local targets.

The bundled metadata already captures `hive_partition_component(VARCHAR, ANY) -> VARCHAR`
and variadic `path_join(VARCHAR, ...) -> VARCHAR`; generated catalogs are unchanged. The first
helper encodes both names and values, maps NULL values to `__HIVE_DEFAULT_PARTITION__`, and
escapes a literal marker value to avoid a collision. Even a NULL name becomes the text `NULL`,
so successful modeled calls are non-null strings. `path_join` propagates any SQL NULL and
normalizes its components, unlike the raw PARTITION_PATH expression's directory validation.
No URL-encoding or host-language path-join substitute is emitted. Qualified same-name UDFs
retain unknown contracts, and unproved helper translations refuse in both directions.
Native file controls cover Hive-equivalent escaping, Parquet/CSV/JSON output, nested/prefix
directories, row sorting, rotation, prepared bindings, repeated executions and per-partition
volatile path evaluation. No global file order or filesystem-independent helper semantics are
certified.
Empty sources have no partitions and do not evaluate even an otherwise invalid path expression.
The pinned JDBC driver reports the tested empty COPY CTE as update count zero despite its
outer SELECT. Tests use execute once, not executeQuery followed by a retry; no production
classification workaround is added.

DuckDB 2.0 `SHOW name` retains its authored SHOW operation and name quoting. It is not a
DESCRIBE rewrite: `show_behavior='auto'` prefers a matching table, then a setting; `table`
and `setting` restrict resolution. Qualified names cannot resolve to settings. SHOW of
tables/views/queries is deprecated in 2.0 in favor of DESCRIBE; a parenthesized query still
describes its columns regardless of show_behavior. Existing SHOW TABLES/ALL TABLES and
other legacy command forms retain their previous representation. Symbolic analysis does
not guess a setting/table result shape or pretend that described-query rows are returned.
Qualification, result lineage, source dependencies and unproved cross-target SHOW execution
refuse. Native controls compare modes, collisions, quoted/case-insensitive names, error
classes, prepared execution and parse-only validation without binding the described query.
Native prepared SHOW captures its setting value at bind time; a newly executed SHOW sees a
later SET, but an existing prepared SHOW retains its earlier value. No auto-reprepare or retry
is added to manufacture a fresh value.

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
