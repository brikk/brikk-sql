# CHSEM-016 positioned Decimal division diagnostics

The offline checker now reports three bounded Decimal division failures on exactly
26.10.2.103, using CHSEM-015's resolved function, declared type and setting facts:

- `DIVISION_BY_ZERO`, native category 153, for proved non-NULL constant division by zero.
- `DECIMAL_DIVISION_SCALE`, native category 69, for proved scale-factor dispatch
  where the result scale is below the divisor scale.
- `DECIMAL_DIVISION_SCALE_OVERFLOW`, native category 407, when two Decimal scales
  exceed the wider result storage precision and decimal_check_overflow is explicitly true.

These are intrinsic expression findings, not whole-query execution/acceptance
verdicts. `/`, resolved `divide` and `divideExact` share the Decimal delegate.
Integer-division mode does not change these rules.

## Guard order and NULL handling

Native return-type scale overflow is checked before execution masking. A typed
constant NULL therefore does not suppress that explicit-setting failure. In
contrast, a NULL numerator can mask a later scale-factor failure. The checker
does not report that later failure without non-NULL dispatch facts. A NULL divisor
can still produce a native scale-factor failure, but that branch remains unknown
in this bounded implementation rather than being guessed from the type alone.

The source checks return-type scale overflow before scale-factor setup. When both
conditions apply, category 407 takes precedence under an explicit true setting;
explicit false can expose category 69. Unknown settings do not become invented
defaults or a guessed native error category.

At runtime, narrowing and numerator scaling can fail before the divisor is tested.
Zero diagnostics therefore require exact non-NULL constants, valid input casts and
proof that these earlier operations cannot overflow, or an explicit false overflow
setting. Integer/Decimal division squares the Decimal multiplier before applying
the numerator, including a zero numerator; that guard is checked too.

The constant subset accepts the existing exact UInt64-range numeric literal and
lossless integer-cast proof, plus direct Decimal casts of those values or bounded
ordinary signed decimal strings. Decimal strings must have digits on each side of
an authored decimal point, fit the declared precision, and require no rounding or
truncation. Exponents, float conversion, invalid/wrapping casts, arbitrary constant
folding and data-dependent values remain unproved. Nullable column types alone do
not prove an unmasked zero division.

## Integration and evidence

Query-local/nested SETTINGS and CHSEM-014's preceding script SET state are reused.
DEFAULT and unresolved assignments remain unknown; local overrides do not leak.
Diagnostics preserve the existing UTF-16 positions, parser separation and native
parser combination. Parser acceptance cannot erase these semantic errors.
The checks are read-only and bounded, with cancellation propagation unchanged.
No public constructor/function signature or data-class field is changed; rule enum
members are appended. No new IntelliJ API calls are added.

Known conditional types remain known for proved runtime zero errors. Type/dispatch
failures that CHSEM-015 left unknown remain unknown rather than gaining a fabricated
result type. Invalid constant casts are not used to diagnose a later division error.

`decimal-diagnostics-cases.json` contains 47 finite original/generated witnesses
per digest pin. Controls cover all Decimal storage sizes, both mixed operand orders,
named delegates, ordinary/nullable zero constants, NULL masking, scale guard order,
narrowing/scaling failures, invalid casts and excluded floating delegates. Older
pin differences and unavailable settings/functions are explicit native outcomes.

Eight additional complete-session scripts test preceding SET values, query-local
overrides, resets, unresolved parameters and NULL handling. They run as whole
scripts in isolated local sessions, not separate processes per statement.

`decimal-diagnostics-sources.json` pins the return-type/scale-factor checks, nullable
dispatch, constant and vector narrowing, multiplier overflow and zero exception
guards. Tagged Apache-2.0 excerpts retain full-file hashes. Historical CHSEM-015
captures are unchanged; five stronger findings are recorded in
`decimal-diagnostics-refinements.json` and `decimal-diagnostics-refinement-brikk.json`.
Their conditional types are preserved.

## Reproduction

```sh
./kotlin test -m brikk-sql --include-classes='*Clickhouse*Division*Test' \
  --jvm-args='-Dbrikk.clickhouseDecimalDiagnosticsOut=/tmp/opencode/decimal-diagnostics-brikk.json -Dbrikk.clickhouseDecimalDiagnosticsScriptOut=/tmp/opencode/decimal-diagnostics-script-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/decimal-diagnostics-cases.json --task CHSEM-016 \
  --brikk /tmp/opencode/decimal-diagnostics-brikk.json --output /tmp/opencode/decimal-diagnostics-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/decimal-diagnostics-script-cases.json \
  --brikk /tmp/opencode/decimal-diagnostics-script-brikk.json --output /tmp/opencode/decimal-diagnostics-script-native
```

Use new output paths for rechecks. Native probing retains exact digest/version
checks, finite inputs, no network/host mounts, resource limits and deadlines.
Run JVM and IntelliJ build gates sequentially to avoid shared-output races.

## Validation

Both exact pins pass all 47 original and generated finite outcomes plus eight
original and generated session scripts, with no execution or version skips.
Every reported error matches its own native category; no native success receives
a static error. Fixture, script and refinement reports regenerate byte-identically.
Full JVM regression passes 1,869 tests with 61 optional integration assumption
aborts and zero failures. Complete Python discovery passes 110 tests with one
opt-in skip. Historical corpus/ledger/source/native captures and public JVM
signatures remain intact. No new IntelliJ API calls, release, publication,
toolchain or consumer dependency changes were made.
ClickHouse-only Python discovery passes 62 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run.
