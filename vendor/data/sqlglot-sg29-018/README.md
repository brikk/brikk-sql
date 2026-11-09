# SG29-018 ArraySort compatibility decision and DuckDB overlap

SG29-017 was verified and pushed as `8382f309` before this round. The owner
authorized SG29-018 and its tested commit/push. SG29-019 onward, publication,
consumer adoption and PR creation remain outside the request.

## Decision for this branch

Do not remove or replace SortArray. Do not change the frozen ArraySort schema
or make old serialized payloads load as a different class. Both published
classes, Map/default constructors, registry names and arg-type shapes remain.
This explicitly retains the DDB20-054 disposition of `094509aa`/#8643 rather
than importing its breaking class deletion under a behavior-fix label.

The bounded native fix preserves the explicit NULL-order argument of DuckDB
LIST_REVERSE_SORT/ARRAY_REVERSE_SORT in the existing SortArray node. Previously
the parser discarded it, and the generator could discard a raw string NULL
order paired with a boolean direction. Canonical ARRAY_REVERSE_SORT(array, order)
now preserves the argument and native session defaults. Both NULL positions,
lowercase spelling, NULL/empty/all-NULL
arrays, copy=false, copy and serde are controlled. No AST-node migration occurs.

Detached ArraySort comparators are explicitly refused by DuckDB even without a
source-dialect hint. Programmatic post-pin direction/NULL fields on ArraySort
also refuse instead of silently dropping them. The staged adapter below is not
implemented or implicitly enabled by accepting an arbitrary Args map.

## Proposed future compatible transition, not applied here

1. In a separately approved schema release, append exact optional asc/nulls_first
   fields to ArraySort and gate the old full schema plus the new suffix. Keep
   SortArray and its registry entry. Keep all old constructors and dispatch
   callables, including DuckdbGenerator.sortarraySql. No JVM signature deletion.
2. Add an explicit, non-mutating normalization adapter that returns a new node.
   Translate legacy SortArray literal direction/NULL flags and DuckDB raw strings
   to ArraySort flags; leave comparator nodes distinct. Refuse dynamic/unresolved
   orders where a target requires literals. Never pick an engine's default NULL
   order as a universal default. Do not infer an executable mapping from a name.
3. Keep the default serde loader faithful to its recorded class/module and keep
   writing old-node payloads for old nodes. Offer migration as an opt-in versioned
   operation. Test old payloads, new payloads, copies and separately compiled
   consumers before any parser starts producing the new shape by default.
4. Reconcile parser builders, all dialect generators/typing tables and the frozen
   schema/corpus only during SG29-020 or a separately approved release. Review
   native null/ordering/empty/error semantics per target. Keep compatibility
   dispatch for SortArray indefinitely unless an explicit major-version removal
   is approved. Retain published data-class defaults/hidden old shapes if any
   public helper data class gains a field.

Those are prerequisites, not unchecked implementation work hidden in this
round. The decision here is compatibility freeze plus the qualified native bug
fix. A future adapter/release needs separate authorization; no automatic global
pin, generated manifest or downstream migration is performed.

## Reuse of DDB20-054/055

The authoritative prior review is
`vendor/data/duckdb-sqlglot-2.0-audit.json`, with 37 commit dispositions and frozen
source/probe hashes. Its DDB20-054 `--check` remains a gate. Its 43-family,
559-cell DDB20-055 executable matrix remains a gate. Neither is rewritten.

| Area | Disposition in SG29-018 | Existing evidence/owner |
| --- | --- | --- |
| Global SortArray deletion/unification | Deferred by the compatibility decision above | 094509aa; DDB20-054, future approved schema release/SG29-020 |
| Native reverse-sort explicit NULL order | Separately qualified bounded port on legacy node | New core and JDBC controls; no node replacement |
| Incoming sort/comparator mappings | Intentionally refused where unproved | DDB20-055 boundary matrix; detached comparator guard added |
| Array slice endpoints/step | Intentionally refused incoming mappings; native controls retained | a97d0357, 1f8d15bb; DDB20-055/073 |
| Exclusive series endpoints | Retain native behavior; unproved target mapping not inferred | e0ac5e4e; DDB20-055/063 |
| Array insertion and null replacement | Intentionally refused incoming mappings | df0a3006, e92c2228; DDB20-055 |
| Unicode escape strings | Intentionally refused incoming conversion | 4aa189a4; DDB20-055 |
| Fractional interval splitting | Intentionally refused even same-target when it would lose meaning | dd71d360; measured 129600 vs 86400 counterexample, DDB20-055/077 |
| Custom Base64 alphabets | Intentionally refused incoming conversion | 549d64b9; DDB20-055/063 |
| JSON path precedence and multi-path extraction | Existing native model retained; lossy multi-path mappings refused | d335f898, 5859d714; DDB20-051/055 |
| SQL/JSON options | Intentionally refused programmatic option loss | ebd5fb4d; DDB20-055 |
| Native regex/session operators | Already covered by richer native model; foreign option loss refused | 5b7af1e7, eb03a972; DDB20-037/055 |
| Trino REGEXP_COUNT | Deferred to its existing separately queued work; no ClickHouse countMatches equivalence | Existing Trino handoff, not SG29-018 |
| Triggers | Already covered structured native model; foreign execution refused | c514be04, 8cf42771; DDB20-020/055 |
| NEAREST | Already covered exact/approximate/native omitted-count model; foreign refusal retained | a220ffb9; DDB20-008/055 |
| Routing/session ownership | Already covered richer native model; foreign/raw-command refusal retained | 46af6e46; DDB20-019/055 |
| Reserved words/SORT BY/backticks | Already covered native lexical/catalog policies; frozen parity tables retained | f31f109d, 840347fd, 68cd4a2d, 83479d06; DDB20-054/068 |
| Shared nested typing/row references | SG29-017 bounded ports/retained reconciler; no wholesale import | a32ef636, fb36fb71; prior commit 8382f309 |
| BigQuery array/percentile folds | SG29-016 preview/refusal, not newly qualified execution | f536a823; prior commit 6b06fbef |

No completed native feature family is reopened wholesale. Historical native
baselines and proof provenance remain distinct from syntax acceptance. In
particular, a finite SQLGlot/Brikk parser result is not runtime equivalence, and
no ClickHouse regex count mapping is added to satisfy a Trino request.

## Validation

ArraySortCompatibilityCatchupTest freezes both class/serde/schema identities,
reverse-sort argument preservation, detached comparator refusal and existing
incoming-sort refusals. ArraySortCompatibilityCatchupResultTest checks 32 native
original/result-type controls through generation, copy and serde, including
empty, all-NULL and NULL arrays under both ASC/DESC task-local default_order
settings. Synthetic SELECTs and local settings only; no DDL or external DB.
The standard all-module/JVM/IntelliJ and native coverage gates remain required.
Build root `build/sg29-018-build`; logs
`/var/tmp/jayson/opencode/sg29-018-*.log`. Global pin and schema are unchanged.

`ddb054-replay-review.json` verifies the historical check again: pinned/upstream
source hashes, probe outcomes, history hash and all 37 dispositions match. The
shared local upstream object store was missing blobs at the old review commit;
an independent task-private public-source fetch supplied the exact historical
checkout without changing the shared checkout or the historical review file.
Failed replay setup logs are retained separately.

`native-coverage-review.json` admits exactly two new comparator warnings on
pre-existing Presto/Spark write origins. All 24,606 native outcome tuples,
engine/settings, corpus file hashes and existing local dispositions are identical.
Only the two corresponding local-warning inventory counters change. The audit
uses a new exact baseline and retains its previous baseline; no count allowance
or parity-ledger refresh is introduced.

The first IntelliJ attempt logged an RMI EOF from StreamingRequest.cancel after
a streaming-cancel timeout disconnected its test console. It was not a SQL
assertion or a compiler-daemon failure. The full gate is retried on the identical
source with no exclusions; the failed stdout/test logs are retained in the
task's approved log directory. No unrelated streaming/console code is changed.

The complete retry also ran 827 tests and reported the same one failure in
StreamingCancelTest.testTheConstructorBeforeTheDeadlineRemains. Both runs are
retained and neither is reported as passing. The user explicitly directed that
this unrelated issue be investigated/fixed/tested/committed/pushed in another
thread without pausing the main task. The separate session is
`ses_e4e293404ffe0l6FxMEduKqXsP`, "Streaming cancellation RMI EOF". It will own
the streaming fix and full IDE rerun, not an exclusion or an ArraySort change.

Final all-module build, 1,835 core tests and 763 other JVM tests pass, including
32 native sort controls across default_order settings, copy and serde. The
existing 72 optional JVM assumptions remain; Python discovery passes 228 tests
with one optional skip. Final source hashes match
`build/sg29-018/source-manifest.json`. No parity ledger, global pin, AST schema
or public constructor/copy shape changes. SG29-018 is complete with the above
explicit IDE-gate exception authorized by the user; commit/push is authorized.
SG29-019 onward is not started.
