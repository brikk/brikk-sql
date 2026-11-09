# SG29-016 BigQuery/Trino translations

The owner approved SG29-016 through SG29-018 in order, with tested commit/push
between items. SG29-015 is pushed as `321a3883`. SG29-019 onward, publication
and downstream adoption remain unauthorized. The oracle pin stays unchanged.

## Dispositions

- `d17c7680`/#8639 and `a8a7610d`/#8778 date parts are ported. Day/month/year,
  quarter/hour, day-of-year and ISO week use EXTRACT. Sunday-based DayOfWeek is
  distinct from Trino's ISO weekday. The latter uses the grouped modulo formula,
  including in arithmetic. Seven calendar weekdays and a NULL control check the
  contract. The executable JDBC control is DuckDB after parsing the emitted
  BigQuery expression, not a claim of executing a BigQuery backend.
  This control exposed DuckDB's Sunday=0 interpretation of the same EXTRACT
  spelling. Source-aware BigQuery-to-DuckDB lowering adds one, grouped so nested
  arithmetic keeps its meaning. Native DuckDB-source extraction is unchanged.
  Generator-synthesized LAST_DAY weekday arithmetic is explicitly tagged as
  native DuckDB arithmetic, so it is not shifted again. Existing BQ-9 and all
  week-start controls must remain unchanged.
- Decimal casts widen to BIGNUMERIC before dropping precision when known integer
  or scale digits exceed NUMERIC's limits. Unknown precision/scale does not
  trigger an invented bound. Generation copies the cast/type rather than mutating
  caller-owned nodes, including copy=false generation. Existing APIs are unchanged.
- `f536a823`/#8674 weighted Trino three-argument APPROX_PERCENTILE is ported.
  Presto's three-argument accuracy contract remains separate. Unit weight and
  bounded literal quantiles can render a bucket/index preview. Text length,
  exponent/scale and [0,1] bounds are checked before integral arithmetic. A direct
  window aggregate cannot acquire APPROX_QUANTILES window support.
  DuckDB also refuses weighted ApproxQuantile rather than silently dropping the
  now-correctly parsed weight. This guard survives AST serde through the weight
  argument itself, without a new metadata/schema requirement.
- Percentile execution remains refused. Different native approximate algorithms
  and accuracy contracts are not equivalent merely because a bucket fraction can
  be rendered. No BigQuery credentials/backend are available for new native proof.
- Signed base-16 FROM_BASE gets the upstream sign-before-prefix regex/cast preview.
  Other bases refuse. Base-16 execution also refuses pending native range/error
  qualification; overflow/error semantics are not hidden behind a cast.
- ARRAY_SORT, legacy SortArray and ARRAY_DISTINCT now explicitly refuse BigQuery
  execution rather than emitting an unqualified native-looking call. The upstream
  aggregate folding port is deliberately not enabled: null elements can error in
  BigQuery, DISTINCT/order combinations have restrictions, and DuckDB removes
  nulls and distinguishes all-NULL from zero rows. Comparator/order/limit inputs
  likewise gain no execution mapping. This keeps the compatibility-sensitive
  ArraySort migration separate for SG29-018.

The array and approximate paths are preview controls with explicit execution
refusals, not structured native qualification. BQ-36 through BQ-38 stay open:
row-dependent delimiters, offset-preserving timestamp formatting and unknown
timestamp/datetime overload resolution are not changed by this round.

## Validation

New core and JDBC regressions live in BigqueryTrinoCatchupTest and
BigqueryTrinoCatchupResultTest. Logs use `/var/tmp/jayson/opencode/sg29-016-*`;
the build root is `build/sg29-016-build`. The pre-round native audit is retained
in `build/sg29-016/native-before.json`. No global generated refresh is allowed;
any frozen-parity/native-audit difference must be reviewed exactly.

`native-coverage-review.json` accounts for exactly five new warnings on an
existing weighted Presto percentile fixture: loaded/current annotated and plain
ASTs plus the current identity input. No old local disposition is removed or
changed. All 24,606 native outcomes and engine/settings are identical, as are all
corpus file hashes and parity ledgers. Only three derived local-warning inventory
counters change. The audit enforces the new exact summary, not an allowance;
the prior baseline remains in place as historical evidence.

The final all-module build, 1,825 core tests, 761 other JVM tests and all 827
IntelliJ tests pass. The existing 72 optional JVM assumptions remain documented;
Python discovery passes 228 tests with one optional skip. Final source hashes
match `build/sg29-016/source-manifest.json`. No production IDE API calls, public
constructor/copy changes, global oracle update or parity-ledger changes occur.
SG29-016 is complete; commit/push is authorized before SG29-017 starts.
