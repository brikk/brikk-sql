# SG29-009 temporal arithmetic and TIMESTAMPADD

This item reviews SQLGlot #8815, commit
`3cca3ea47592c84b23636aff196ca0bbaf4f1905`, and #8809, commit
`2d4b5a5b0aea46e7f5a4fbe09c1d60fcafad76b3`. The global oracle stays at
`v30.22.0-5-ga00dc2e5c`; generated tables and corpora are not refreshed.
SG29-010 and later items are outside this authorization.

## Implementation

`TemporalArithmetic.kt` renders a function lowered to binary interval arithmetic
with parentheses when its original parent requires them. The left operand of
addition/subtraction keeps natural left associativity; right operands, unary
operators, other binary parents and AT TIME ZONE keep the function's grouping.
An existing explicit parenthesis is not doubled. Binary interval/count operands
also remain grouped. PostgreSQL, DuckDB and Spark's existing shared interval
lowerings use this helper.

The helper works on detached copies. PostgreSQL no longer converts the input's
numeric literal to a string in place or reparents a dynamic interval count.
TsOrDsAdd casting operates on a copy as well. DuckDB's existing typed floating
count rounding changes a local expression rather than the caller's AST. No
rounding policy, nanosecond contract or executable safeguard is relaxed.

The handwritten MySQL registry now maps TIMESTAMPADD to its canonical node and
normalizes SQL_TSI prefixes. TIMESTAMPDIFF is unchanged. Doris/StarRocks inherit
the parser normalization as syntax compatibility; this is not a claim of native
acceptance of every inherited alias or unit on those backends.

PostgreSQL adds canonical DatetimeAdd/Sub and TimestampAdd/Sub mappings.
Numeric, signed, literal-string, explicit-interval and dynamic integer counts
retain their operands. For newly supported timestamp/datetime deltas, a string
timestamp is explicitly cast to TIMESTAMP. A bare unknown PostgreSQL string
beside an interval can otherwise resolve as an interval rather than a timestamp.
DuckDB also casts timestamp-delta string arguments to TIMESTAMP instead of DATE,
preserving their time component.

No public data-class fields or constructor/copy shapes change. Existing parser
and generator entry points retain their JVM signatures.

## Qualification limits

Tests distinguish successful native execution from parse-only rendering and
from executable refusal. DuckDB native DATE_ADD queries are compared with their
generated and serialized forms, including result types. PostgreSQL results are
compared with independent typed interval/timestamp references. Dynamic integer
and NULL counts, month-end/leap-day arithmetic and timestamp time-of-day values
are included.

Existing cross-DuckDB boundary-counting, fractional-interval and zone/calendar/
collation refusals remain. A PostgreSQL typed-timestamptz DST control executes
the public API's generated SQL. The AT TIME ZONE example executes preview SQL
as a finite value/type control and separately asserts that the strict executable
API still refuses that cross-zone mapping. A single DST result does not qualify
arbitrary zone/calendar semantics.

MySQL-native TIMESTAMPADD/DATE_ADD controls cover the eight reviewed SQL_TSI
units, fractional seconds, negative and NULL counts, dynamic counts, typed DATE
versus DATETIME arguments and leap/month-end values. Original and generated SQL
must both execute and have matching rows, metadata types, decimals and flags.
The native runner accepts only the task-private, network-isolated
`brikk-sg009-mysql` container and refuses host bind mounts or published ports.

This is not a general cross-engine temporal equivalence claim. In particular,
MySQL DATE inputs may retain DATE for day/month units and become DATETIME for
sub-day units, while date-plus-interval in PostgreSQL/DuckDB can yield TIMESTAMP.
MySQL datetime subtraction can also be numeric rather than PostgreSQL/DuckDB
interval subtraction. The right-nested subtraction controls use native DuckDB
expressions and typed PostgreSQL references, not a false comparison of those
different MySQL arithmetic contracts.
Dynamic string coercion, arbitrary fractional units, SQL_TSI_FRAC_SECOND,
nanosecond mappings and new Spark temporal functions are not qualified here.
Spark tests cover its existing generator paths, not a live Spark backend.

## Workflow and authorization

The SG29-008 starting source hashes, diff and verified native-coverage report
are saved under `build/sg29-009/`. Logs use
`/var/tmp/jayson/opencode/sg29-009-*.log`, including failed iterations. Source
files remain fixed while each compilation/test run executes.

The owner authorized SG29-009 and then commit/push of the accumulated branch,
which includes A/B and SG29-008. Earlier evidence guides' uncommitted-status
statements describe their original validation checkpoints. Publication and
downstream adoption remain separate decisions. No PR creation was requested.

## Frozen-pin and audit review

One exact MySQL-to-PostgreSQL transpile assertion differs from the frozen pin.
Its dynamic interval-times-count operand now has parentheses, matching #8815.
The exact assertion ID and output signature are recorded in
`dialect-corpus/mysql-transpile-known-failures.json`. A native PostgreSQL control
executes that exact fixture and checks its timestamp result. There is no general
output-mismatch allowance and no generated corpus refresh.

`native-coverage-review.json` verifies that all 24,606 native origin/SQL/status/
type tuples and every local disposition remain unchanged, under the same native
engine, JDBC hash and complete settings. Only that ledger file's inventory hash
changes. Replacing its new hash with the starting hash reconstructs the complete
old inventory fingerprint. The strict audit selects a new exact inventory-only
baseline, `native-coverage-baseline-sg29-009.json`; B's historical baseline is
retained unchanged. Native acceptance counts and fingerprints are not relaxed.

`mysql-native-results.json` retains all 20 successful original/generated MySQL
value/type pairs. `final-facade-replay.json` reproduces their ASTs and generated
SQL through the Java 17 public facade and records the compiled class hashes.
`upstream-controls.json` records four frozen v30.29 generation references, not
native engine evidence. The private container was stopped and removed after
capture, with no host kernel, port, trust-store or user-database changes.

## Validation record

- The reviewed core suite passes 1,781 tests with no failures, skips or assumption
  aborts. Eight new core methods exercise grouping, signed/dynamic counts,
  canonical classes, source positions, serialization, parent links, typed-float
  preparation, Spark generation and unchanged executable refusals.
- The final all-module build passes. Other JVM modules pass 747 tests with zero
  failures and 72 existing optional assumption aborts. The shared IntelliJ gate
  passes all 827 tests on `261.25134.95`, without exclusions or new IDE API calls.
  No IntelliJ production source changed. Validation completed November 4, 2026.
- Two new private DuckDB result methods and one embedded PostgreSQL method check
  actual values and metadata. The existing genuine signed-window fixture is used
  for the shared JVM/IntelliJ checks.
- Python discovery passes 228 tests with one optional skip out of 229, using the
  unchanged a00dc2e5c reference. The new native probe also passes `py_compile`.
- The Java 17 consumer bridge reproduces the 20 captured native statements.
  All 2,857 compiled core classes and 143 metadata classes retain Java 17
  bytecode, major version 61.
- Final source hashes are retained in
  `build/sg29-009/final-source-manifest.json`. Historical A/B/SG29-008 evidence,
  safeguards and code remain except the documented temporal method bodies,
  parser mapping, exact ledger row and inventory-only audit selector.
