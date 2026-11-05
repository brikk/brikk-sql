# SG29-012 PIVOT after VALUES

Port of SQLGlot #8876, `90659e63fc8f3c4626f41b162961210b9d619fe4`, on top of
the independently committed/pushed SG29-011. The global oracle remains frozen.

The parser attaches a following PIVOT to the Values relation. Its existing AST
class gains one optional `pivots` argument without changing constructor/JVM
shape. This is a bounded schema addition, not a generated registry/table or
corpus refresh. Native VALUES rendering retains pivots after aliases. The
SELECT/UNION lowering also retains pivots and relation-local ORDER/LIMIT/OFFSET.
It copies row/alias nodes instead of changing the caller's parent links or
dropping cells through zip truncation. Incomplete alias lists refuse strict
SELECT lowering rather than silently lose columns.

BigQuery's separate VALUES-to-UNNEST path must not discard PIVOT. That combination
remains explicitly unqualified and refuses executable generation. Existing
unsupported-PIVOT targets remain refused. No new PIVOT emulation is inferred.

Core regressions cover both parenthesized and bare VALUES forms, alias lists,
serde, non-mutation, native/lowered/pretty generation, scope, output columns,
types and lineage. VALUES pivots receive an output alias like pivoted subqueries,
and row cells are typed before pivot lookup can cache their source-column types.
Type controls use homogeneous INTEGER/MAX inputs; all-row common-type inference
remains the separate SG29-017 review, not a new general coercion claim here.
Private DuckDB 2.0.0 result tests check actual grouped pivot
columns, missing pivot entries and relation-local pagination. Forced generic
SELECT/UNION SQL is replayed on DuckDB as a lowering control. This project does
not register Redshift/Snowflake; no new dialect or native Redshift qualification
is added. Spark rendering is not native Spark execution evidence.

Starting state and native-coverage report are saved under `build/sg29-012/`.
Logs use `/var/tmp/jayson/opencode/sg29-012-*.log`, including failed iterations.
The existing genuine signed-window fixture is used for shared integration gates.
No user database, host setting, trust store or IDE production API is changed.
Commit/push is authorized after validation, before SG29-013 begins.

The old global arg-types manifest remains unchanged. Its gate still checks all
1,038 classes and the exact original Values shape, then requires exactly the
reviewed optional `pivots=false` suffix. A future pin refresh must reconcile this
explicit schema disposition. Both legacy Values constructors remain available.

A Kotlin incremental classpath-diff error interrupted one attempt. Validation
uses a fresh `--build-dir=build/sg29-followup-build`, without deleting old outputs
or the genuine signed-window fixture. Failed debug/compilation runs are retained;
no temporary debug output remains in the source.

## Final validation

All modules build in the fresh build directory. Core passes 1,798 tests with
zero failures, skips or assumption aborts. Other JVM modules pass 758 tests with
72 existing optional assumptions and no failures. Shared IntelliJ passes all
827 tests on `261.25134.95`; its classpath uses the fresh build artifacts and
the existing genuine signed-window fixture. Python passes 228 tests with one
optional skip. No IDE production API calls or sources change.

`native-coverage-review.json` verifies that all 24,606 native origin/SQL/status/
type tuples, inventory, local dispositions and engine/JDBC/settings remain
unchanged. No new native-audit baseline or parity ledger is needed. Nine final
source hashes are saved in `build/sg29-012/source-manifest.json`.
