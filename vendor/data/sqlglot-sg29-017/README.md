# SG29-017 shared typing and qualification audit

SG29-016 was verified and pushed as `6b06fbef` before this round. The owner
authorized SG29-017 followed by SG29-018, with tests and commit/push between
items. The global oracle stays pinned. No new native ClickHouse proof is claimed.

## Upstream dispositions

- `f0b4347b`/#8775: port all-row VALUES column coercion. The source alias map
  folds corresponding cells across every row rather than trusting the first row.
  Missing cells remain UNKNOWN rather than providing a fabricated type.
  Exact homogeneous types bypass generic coercion, preserving native extension
  UDT precision metadata such as TIMESTAMPTZ_NS. The pre-existing native LTTB
  copy/serde/value/type matrix caught this requirement and remains mandatory.
- `a32ef636`/#8818: port conservative unknown MAP and BigQuery approximate-top
  guards. DataType(UNKNOWN) must not compare as if it were a known type merely
  because it is not the enum value UNKNOWN. Existing array-by-args already exits
  on unknown elements. Retain the pinned empty-array representation and first
  known nested-type selection; the ARRAY<NULL> representation change is deferred
  to the global oracle migration rather than silently changing corpus/API shape.
- `fb36fb71`/#8694: explicit SELECT AS STRUCT subqueries use complete query struct
  evidence. Row references derive reconciled source types, including set-operation
  output types; empty/missing/UNKNOWN source schemas do not become complete rows.
  Query struct metadata is cleared before recomputation and uses ordered set
  output types rather than only the leftmost SELECT. No new public fields.
- `5e0f7169`/#8677 and `4678f6cb`/#8723: intentionally retain disabled inference
  in upstream-shaped entry points and explicit legacy inference in existing
  nullable-policy overloads. Do not back-project an arbitrary missing column
  onto a TVF by default. Existing InferencePolicyContractTest and EngineAnalysis-
  ContractTest remain required. Resolver.getTable(Any), including String callers,
  stays available; no upstream signature removal is adopted.
- `e54ad9aa`/#8720: retain the existing ordered recursive set-operation reconciler.
  It preserves positional duplicates, nested/parenthesized operands and recursive
  anchors. New tests cover nested operations, duplicate RHS names and repeated
  CTE references. The upstream cache rewrite is not imported over that code.
- `2bf94d96`/#8695 and `9d2966ed`/#8696: keep established qualification ordering
  and scoped invalidation while testing nested struct stars and positional
  GROUP/ORDER outputs. Keep TypeAnnotator.uncache and all old callable shapes.
  No wholesale deferred-annotation refactor or cache/API deletion is authorized
  by a behavior catch-up. A target failure must get a bounded fix, not a global
  annotator replacement.

## Safeguards and evidence

SharedAnalysisCatchupTest adds hand-owned shapes, physical lineage, unknown TVF,
SEMI visibility, struct/CTE and Engine-style preparation controls. Existing
StarRocks/DuckDB PIVOT aliases and scope tests remain gates, as do native
ClickHouse inference/provenance, public binary compatibility and strict unknown
schema policies. SharedAnalysisCatchupResultTest compares synthetic native
DuckDB values/types with normalized SQL for VALUES and nested/shared set sources.
It performs no DDL or user database access.

Build root: `build/sg29-017-build`. Logs:
`/var/tmp/jayson/opencode/sg29-017-*.log`. The pre-round native audit is retained
at `build/sg29-017/native-before.json`. No global corpus/schema refresh or broad
parity/native-audit allowance is permitted.

`ledger-review.json` admits exactly one annotation-only row for unknown-input
APPROX_TOP_COUNT. Its arguments, SQL and source metadata are unchanged; the
result is UNKNOWN instead of an ARRAY/STRUCT wrapper containing UNKNOWN.
`native-coverage-review.json` verifies every native outcome, local classification
and engine/setting is unchanged. Only that reviewed ledger's inventory hash
changes. Historical baselines are retained; the current exact summary is gated.

Final all-module build, 1,832 core tests, 762 other JVM tests and all 827 IntelliJ
tests pass. The native LTTB precision/copy/serde matrix passes unchanged. The
existing 72 optional JVM assumptions and one optional Python skip are retained;
228 Python tests pass. Tested source hashes match
`build/sg29-017/source-manifest.json`. SG29-017 is complete and commit/push is
authorized before SG29-018. No public constructor/copy/API is removed or retyped.
