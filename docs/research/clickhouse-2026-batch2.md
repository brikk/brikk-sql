# ClickHouse catch-up batch 2

CH26-003 and CH26-004, on `clickhouse-catchup`. References were checked on 2026-10-28.
This follows [batch 1](clickhouse-2026-catchup.md); it does not replace that batch's
historical captures or the older semantic-hazard evidence.

## Versioned metadata

The official engine images remain pinned by digest in
`vendor/data/clickhouse-2026-audit/engines.json`. `tools/capture_clickhouse_metadata.py`
captures their read-only registries through isolated `clickhouse local` containers.
Each `vendor/data/clickhouse-capture-<version>.json` records the exact queries,
version result, image digest, filenames and SHA-256 hashes.

| Snapshot | Function definitions, including table functions | Table functions | Keywords | Type families |
| --- | ---: | ---: | ---: | ---: |
| 26.5.1.1 embedded historical capture | 1,648 | 87 | 630 | Not captured |
| 26.8.34.13 LTS official build | 1,735 | 94 | 658 | 141 |
| 26.10.2.103 stable official build | 1,808 | 99 | 666 | 141 |

These are registered names/kinds, not a count of newly introduced features. The
embedded and official builds have different build options. Registry rows, aliases
and case flags are captured independently for each version. The new captures also
retain function `introduced_in`, `deterministic` and `higher_order` fields as raw
evidence, relevant query settings and their tiers/defaults, build options, selected
embedded documentation, and statements where available. 26.8 has no
`system.statements`; its manifest records the native unavailable response. 26.10's
statement documentation is captured.

`CLICKHOUSE_METADATA` now serves all three snapshots. Use
`CLICKHOUSE_METADATA.forServerVersion(version).snapshot` for a known server. The
unknown-server default and the existing `CLICKHOUSE_FUNCTION_CATALOG` getter both
use 26.10.2.103. The getter stays in its original JVM file class. Old snapshots still
include `catboostEvaluate` and `windowID`; the current one correctly does not.

The catalog's `getAll` and completion projections now honor registered per-name
case-insensitive overrides without treating every ClickHouse name as insensitive.
For example, `SuM` resolves but `ARRAYTOPK` does not. Case-only registered aliases
such as `sparkBar` are retained. Legacy `get`, `contains` and `isKnown` remain
case-insensitive by their existing API contract; they are not native name validators.
Captured type-family case flags independently control type lookup.

No prose documentation signature becomes a `FunctionOverload`. All three
ClickHouse catalogs keep overload lists empty. No `sinceVersion` facts or semantic
equivalence claims are inferred from documentation fields. Argument-dependent table
function output schemas remain unknown. The small existing fixed-schema table
function list is unchanged. Registered experimental functions are not a claim that
their execution gates are enabled; the captures retain the relevant setting evidence.

`FunctionCatalog` adds an optional per-name case policy and `TypeDef` an optional
captured type-case flag. Hidden old constructors/defaults bridges and the old
`TypeDef.copy` shape are retained. Reflection tests exercise those old entry points.
There is no runtime-version uplift.

## Query and type corrections

- LIMIT AFTER/UNTIL ranges have dedicated AST nodes. Counts, boundary predicates,
  ALL, offsets, WITH TIES and a preceding LIMIT BY survive serialization and
  generation. AFTER and UNTIL remain contextual count identifiers. A boundary is
  not treated as an ordinary integer slice by pipe lowering.
- NATURAL INNER/LEFT/RIGHT/FULL joins retain their method, aliases and sources.
  Shared keys are deduplicated in scope output. GLOBAL remains separate from
  NATURAL, and invalid strictness/CROSS/ON/USING combinations are rejected.
- AT LOCAL and SQL-standard compound intervals are inspectable native expressions,
  not opaque text or a guessed cross-dialect conversion. TO in ORDER BY WITH FILL
  and TTL destinations remains an outer clause, not a compound interval qualifier.
- remote, remoteSecure, cluster and clusterAllReplicas can carry trailing SETTINGS
  inside the call while retaining a separate query-level SETTINGS clause. Parameters
  keep their source ranges. A bare boolean setting remains bare. A positional
  identifier named SETTINGS is not mistaken for an empty settings clause. Existing
  function-name anchors are retained.
- QBit stores the element type, dimension and optional stride as AST children.
  Int8, BFloat16, Float32 and Float64 forms are covered, including typed parameters
  and nested declarations. MultiPoint remains a distinct native type; native
  execution checks its relationship with Geometry. Invalid parameter shapes are
  rejected instead of flattened into generic arrays or unstructured text.
- Newly captured aggregate names, including parameterized groupFormat and their
  combinators, enter the aggregate resolver. The registry also labels window-only
  implementations as aggregate, so the existing lagInFrame/leadInFrame ASTs are not
  reclassified merely because the catalog was refreshed.

Unsupported cross-dialect rendering of the new native expressions refuses rather
than dropping clauses or inventing a translation. This is not new cross-engine
semantic certification. Existing pipe capability restrictions remain unchanged.

## Native boundaries that differ from the release notes

Both pinned engines still register YEAR as an alias of toYear. `formatQuery`
accepts `year()`, but execution returns code 42,
`NUMBER_OF_ARGUMENTS_DOESNT_MATCH`. Brikk now preserves that syntax without
inventing a current-year result or silently lowering it to another dialect.
The ordinary one-argument form stays typed as before. The two-argument timezone
form now retains its second argument; three or more arguments no longer get
silently truncated. A year-boundary fixture proves that the timezone argument
changes the result from 2026 to 2025.

26.10 accepts LIMIT boundaries in the parser but rejects offsets, negative counts
and WITH TIES combined with them at execution with code 48, `NOT_IMPLEMENTED`.
Their native and regenerated error outcomes are pinned. A preceding LIMIT BY
does execute. The unsupported combinations are not converted into successful
ordinary LIMIT queries, and are not advertised here as runnable features.

The bare `SETTINGS` word at the end of a table function is a positional identifier
in the native JSON AST, not an empty settings clause. That distinction is covered
by a positive parser fixture; malformed assignments have separate negative cases.

## Reproduction and evidence

1. Pull and verify the exact images in `engines.json`, then run
   `python3 tools/capture_clickhouse_metadata.py`. No live credentials or host mounts
   are used. Regenerate with `python3 tools/generate_clickhouse_functions.py` and
   `python3 tools/generate_keywords.py`. Both generators also accept explicit input
   selection and output paths for fixture tests. Keyword categories stay UNKNOWN
   because the native table has no reservation evidence.
2. Run the core audit test with JVM properties `brikk.clickhouseAuditCases` pointing
   to the absolute path of `vendor/data/clickhouse-2026-audit/batch2-cases.json`, and
   `brikk.clickhouseAuditOut` pointing to an output JSON file. The test checks the
   corrected AST/serde round trips and captures every input's generated SQL/error.
3. Run `python3 tools/probe_clickhouse_2026.py --cases
   vendor/data/clickhouse-2026-audit/batch2-cases.json --task CH26-003 --task CH26-004
   --brikk <observation-file> --output <output-directory>`.

The runner records all parser observations on both versions. Generated positive
cases are checked against native canonical formatting only for applicable release
lines. Execution fixtures have exact expected results or exact native error
code/category expectations. Later-release execution fixtures are explicitly marked
version-inapplicable on the older engine, not counted as passing executions.

The raw batch-2 native captures and final Brikk observations are stored separately
under `vendor/data/clickhouse-2026-audit/batch2/`. Deadlines and task-owned cleanup
are the same as batch 1. Remote table-function forms are parse-only checks; they
never connect to the hostnames in the SQL. Neither metadata captures nor parser
acceptance rewrites historical hazard provenance or widens certification.

## Validation

- The final full `./kotlin test` run, with the audit-case/output JVM properties
  above, discovered **1,784 tests: 1,723 passed, 61 optional assumption aborts,
  zero failures**. This includes all existing parser, generator, annotation and
  transpilation corpus gates, with no failure-ledger changes, plus the new syntax,
  snapshot, serde, scope and binary-compatibility checks.
- Native batch-2 probes passed on both immutable images. Each recorded 45 parser
  observations. Applicable generated parser checks numbered 24 on 26.8 and 35 on
  26.10. On 26.8, 13 original and 13 generated execution outcomes matched, with
  nine later-release cases explicitly inapplicable. On 26.10, all 22 original and
  22 generated execution outcomes matched. These counts include the pinned native
  errors above, not only successful result sets.
- The standalone Kotlin capture and the audit test produced identical 67-row
  observation JSON files.
- `python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'`: eight tests
  passed, including regeneration determinism, raw-capture hashes, exact keyword
  selection, escaping, process isolation/deadlines and expected-error handling.
  The existing keyword-generator test also passed independently.
- Raw TSV empty final fields retain their trailing tabs and manifest hashes.
  Whitespace attributes exempt only those captured TSV end-fields, not code or
  SQL ledgers. No probe-owned containers remain.

The optional aborts are unconfigured live ClickHouse HTTP, Trino, Doris,
PostgreSQL, MySQL, StarRocks and chDB integrations. The pinned local ClickHouse
probes did run independently. Website documentation checking and custom IntelliJ
platform tests were not run; no IDE API or integration contract was changed.

During development, full corpus gates caught over-eager TO parsing, registry-based
reclassification of old window implementations, and lost remote-function name
anchors. Those defects were corrected rather than added to failure ledgers.
Native parsing also corrected an initially wrong assumption that a positional
SETTINGS identifier or a bare TO alias was malformed syntax.

## Remaining work

CH26-005 through CH26-008 remain open: star name-filter/built-in/higher-order
analysis, structured DDL/EXPLAIN, the remaining version-sensitive semantic work,
and final catch-up validation/handoff. In particular, this batch does not remove
the old WINDOW VIEW documentation pattern, update stale semantic proofs, enable
experimental execution settings, or claim support for every registered function.
