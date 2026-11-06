# SG29-014 remaining StarRocks sub-rounds

This round follows the committed LIKE/map fixes `19c29eda` and assesses every
remaining area of SG29-014. The global oracle stays at
`v30.22.0-5-ga00dc2e5c`. No later TODO item, publication or downstream adoption
is authorized by this work.

## Qualified support and explicit limits

- Native dates, #8636/#8648: TO_DAYS keeps its native ToDays kind rather than an
  inherited MySQL year-zero approximation. DATEDIFF compares dates; DATE_DIFF
  remains elapsed-unit arithmetic. The overnight control returns 1 versus 0.
  Only DAY boundary counting can select DATEDIFF; non-DAY boundary conversion
  refuses strict execution. Native TO_DAYS does not gain a cross-engine mapping.
- Hashes, #8644 and `caa920da`: # is not a StarRocks comment and #>/#>> are not
  PostgreSQL JSON operators here. Quoted hashes and --/block comments remain
  valid; MySQL's own hash comments are unchanged. Bare hashes are conservatively
  rejected instead of reproducing native lexer error recovery.
- PIVOT, #8679/#8689: standard PIVOT renders rather than disappearing. UNPIVOT
  and DuckDB's simplified PIVOT statement remain refused. Static string, tuple,
  IN-alias and aggregate-alias names follow StarRocks rules. Multiple unaliased
  aggregates keep an explicit unknown output instead of invented names; star
  expansion stops and only known passthrough types are exposed. Pivoted table
  groups introduce a scope. Foreign PIVOT execution is not inferred equivalent
  because output names/types can differ between engines.
- Aggregate/HLL DDL, #8591/#8612: structured aggregate-key properties and column
  aggregator nodes reuse the existing native AST classes, with HLL token/type
  handling. SUM/MAX/MIN/REPLACE/REPLACE_IF_NOT_NULL/HLL_UNION are preserved. Native
  shipped-parser acceptance is separate from table creation, catalog effects,
  storage behavior or aggregation execution. No DDL is executed in the probe.
- Refresh, #8613/#8621: native MV partition START/END, FORCE and SYNC/ASYNC mode
  are structured. Invalid/incomplete modes reject; unknown REFRESH forms keep
  the entire opaque statement, not a truncated suffix. Other target generators
  refuse unsupported option loss. The existing Refresh constructor is unchanged;
  its exact optional args suffix is gated without changing the frozen manifest.
- Administration, #8681/#8732: admin, backup/restore/recover, export, plugin,
  task/routine-load control, blacklist and TRANSLATE TRINO forms are preserved
  as Command. They are NOT structured semantic support. Provenance survives
  serde and cross-engine execution refuses. DELETE FROM, ALTER ADD COLUMN,
  ordinary TRANSLATE calls and identifiers spelled like command heads remain
  structured where they were already supported.
- UNNEST prerequisites, #8660/#8671: function parsing keeps all arguments and
  multi-array native UNNEST does not fabricate one column alias for multiple
  duplicate default names. This is not a new cross-engine zip/cardinality claim.

## Evidence tiers and safety

`tools/StarrocksNativeParserProbe.java` calls the released FE's SqlParser.parse
and read-only AST formatter only. It does not invoke an analyzer, planner,
statement executor or user session. `tools/probe_starrocks_remaining.py` uses
that shipped parser for DDL, refresh and administrative statements. Its separate
query helper rejects every non-SELECT, so those statements never reach the live
SQL executor. Only synthetic SELECTs execute for date, PIVOT and UNNEST values,
column labels and protocol types. Native parser rejection of an opaque command
is recorded, not treated as a result-equivalence pass.

The task-private container is the immutable StarRocks 4.1.4 `4a9848e` image with
network none, no host bind mounts or published ports. The report records the FE
jar/harness hashes and parser diagnostics. No host kernel, trust-store, user
database, job-control, plugin installation, refresh or DDL action is performed.
No new IDE production calls or public data-class constructor/copy shapes change.

Core regressions separate native structured nodes, opaque text, unknown static
outputs and executable refusal. The Refresh schema gate still checks the full
frozen base plus its exact reviewed suffix, alongside earlier Values/Match
suffixes; no global schema/corpus refresh is hidden here.

Starting state/native coverage are retained in `build/sg29-014-rest/`; logs use
`/var/tmp/jayson/opencode/sg29-014-rest-*.log`, including failed iterations.
Validation uses `--build-dir=build/sg29-014-rest-build` and the existing genuine
signed-window fixture. Commit/push follows completed validation. SG29-015 onward
remains outside this request.

## Native verification and audit disposition

`native-results.json` retains 14 original/generated read-only SELECT value/type
pairs, 12 structured DDL/refresh parser pairs and 25 opaque-preservation records.
Every native parser input is accepted by the shipped 4.1.4 jar. DDL and refresh
snapshots compare explicit parsed getters for names, keys, column datatypes,
aggregators, hash distribution, partition endpoints, FORCE and sync mode. The
native AST formatter was unavailable for these nodes; empty formatter results
are NOT counted as canonical-AST equivalence. A parse-only recheck added field
snapshots without rerunning the previously captured SELECTs or executing any
administrative statement. All 25 opaque texts remain whole but are still opaque.

`final-facade-replay.json` reproduces all 51 recorded renderings using the
published Java consumer facade on actual Java 17. All 2,860 compiled core and
143 metadata classes retain Java 17 bytecode. No public constructor/copy shape
changes; Refresh gains only optional map-based AST arguments.

`ledger-review.json` accounts for exactly three frozen-pin rows: two add only
serialized opaque-command provenance, and one selects native DAY DATEDIFF.
Pre-existing rows, reasons and signatures are unchanged. Despite the annotation
gate's generic type-mismatch label, the opaque command's type stays UNKNOWN.

`native-coverage-review.json` verifies all 24,606 native origin/SQL/status/type
tuples are unchanged under identical engine/JDBC/settings. Exactly three
unsupported warnings are added for already-opaque ALTER origins. No old local
disposition is removed or changed. Three ledger file hashes and the three
corresponding warning counters explain the inventory delta; restoring those
rows reconstructs the old complete inventory fingerprint. The audit enforces a
new exact reviewed baseline while retaining historical baselines, with no native
acceptance, exclusion or count allowance.

The server restarted during the remaining JVM run. Completed build/core/native
evidence was retained, no source was discarded, and the interrupted gate resumes
against the exact reviewed baseline. Final source hashes are saved in
`build/sg29-014-rest/source-manifest.json`.

## Final validation

The completed all-module build and preserved core run pass 1,818 tests without
failures, skips or assumption aborts. The resumed remaining JVM gate passes 760
tests with no failures and 72 existing optional assumptions. The shared IntelliJ
gate passes all 827 tests on `261.25134.95`, with no exclusions or new IDE
production API calls. Python discovery passes 228 tests with one optional skip.
The existing genuine signed-window fixture remains in use.

All 51 recorded renderings reproduce through the published consumer facade on
actual Java 17. Fifteen final source hashes and compiled-class hashes match the
tested tree. The private StarRocks container is stopped and removed. Commit/push
is authorized; the sequence ends at SG29-014, leaving six TODO items open.
