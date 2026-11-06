# CHSEM-012 exact division result typing

The ClickHouse annotator now infers division results from the exact 26.10.2.103
rules instead of returning UNKNOWN for every enabled-mode integer division.
The original CHSEM-001–011 work stays complete. Arithmetic follow-ups have their
own stable IDs in dev-docs/TODO-clickhouse-semantic.md.

## Result rules and integration

- `/` and the resolved builtin `divide` use literal query-local
  sql_compatible_division_for_integers settings, including ancestor inheritance
  and inner-query overrides.
- With integer mode enabled, two supported integer operands produce the first
  operand's width, signed if either operand is signed. All Int/UInt8,16,32,64,128,256
  combinations are covered. A wider divisor does not widen the result.
- Explicit `divideExact` uses that integer rule independently of the query setting.
  Its floating delegate and enabled-mode floating operands return Float64.
- With integer mode disabled, supported integer/floating division returns Float64.
  Supported nullable operands propagate result nullability; constant NULL dispatch
  is kept distinct from ordinary nullable input types.

The existing contextual proof pass handles standalone expressions, schema columns,
derived queries and lambda bodies. The type-rule helper does not depend on operand
values and does not execute SQL. SQL text, settings, source metadata and serde are
unchanged. Named functions respect exact catalog spelling and unresolved shadows.
Caller-owned typing policies, overwrite=false and cache invalidation are tested.
Existing public constructor/function signatures and toolchain floors are unchanged.

Ordinary annotateTypes now computes the enabled-mode result when declarations or
schema facts establish its operand types. Its generic literal policy is unchanged.
Use the existing exact-pin entrypoint for native literal widths:

```kotlin
val ast = annotateClickhouseSemanticTypes(
    parseOne("SELECT 7/2 SETTINGS sql_compatible_division_for_integers=1", "clickhouse"),
    engineVersion = "26.10.2.103",
)
// The quotient expression has the proved UInt8 result type.
```

The shared native type carrier can retain wide integer declarations without
silently admitting them to other scalar-function or multiplication contracts.
Generic arithmetic annotations are not used as native division operand proofs.

## Evidence

`division-types/input.json` defines seven batches over fourteen numeric types:
196 ordered pairs for each of enabled `/`, enabled `divide`, explicit divideExact,
disabled `/`, and three both-nullable variants. The 1,372 expected types come from
the pinned engine, not a second copy of the Kotlin formula. `division-types/brikk.json`
matches those observations exactly. Main fixtures also cover singly nullable inputs.

`division-cases.json` contains 22 original/generated finite value/error witnesses
on both exact pins. The 26.8 controls explicitly reject unavailable settings or
divideExact rather than being execution skips. The 26.10 cases include negative
truncation, mixed signed/wide inputs, floating operands, nullable operands, nested
settings, lambda bodies, zero and quotient-overflow errors. `division-brikk.json`
records current conditional types independently of those execution outcomes.

`division-sources.json` pins implementation URLs, full-file hashes and excerpts for
the divideExact delegate, exact integer dispatch, NumberTraits result rules and
default NULL return handling. Source capture is reproducible with
`capture_clickhouse_semantic_contract_sources.py --round division`.
The earlier CH26-B4-E07 native witness already proves Int64 for the formerly UNKNOWN
enabled-mode cast case; its current regression assertion is strengthened accordingly.
Earlier raw captures, corpus fixtures, ledgers and comparison rules remain intact.

## Recorded boundaries and next work

CHSEM-013 owns offline zero/overflow diagnostics. The E13–E15 controls deliberately
have known conditional types and native execution errors without inventing those
new diagnostics in this typing-only round.

CHSEM-014 owns script/session state from preceding SET statements. Query-local
settings remain the facts used here; native typing does not guess external state.
Unknown mode, uncertain casts, constant NULL branches, Decimal overloads and
unsupported wrappers remain explicitly outside the proved result subset. The
standard annotator's established fallback policy and cross-dialect guards are not
silently rewritten. These boundaries are retained here and in TODOs, not repeated
as new blockers for each later round. No warning suppression or conversion
certification change is part of this work.

## Reproduction

```sh
python3 tools/probe_clickhouse_division_types.py \
  --output build/division-types-native
./kotlin test -m brikk-sql --include-classes='*ClickhouseDivision*Test' \
  --jvm-args='-Dbrikk.clickhouseDivisionOut=build/division-brikk.json -Dbrikk.clickhouseDivisionMatrixOut=build/division-types-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/division-cases.json --task CHSEM-012 \
  --brikk build/division-brikk.json --output build/division-native
```

Use new output paths for rechecks. The existing isolated clickhouse-local runner
keeps exact cached digests, no network/host mounts, resource limits and deadlines.

## Validation

All 1,372 matrix result types match independent native observations. Both pins
passed 22 original and 22 generated finite value/error assertions, with no execution
or version skips. Matrix and fixture reports regenerate byte-identically.

Full JVM regression passed 1,852 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility and unchanged corpus gates.
Full IntelliJ regression passed 757 tests on 261.25134.95; other IDE versions were
not run. Complete Python discovery passed 99 tests with one opt-in Toolchain-model
skip using the unchanged SQLGlot pin; ClickHouse-only discovery passed 51.
No old public JVM signature, new IntelliJ API use, toolchain floor, release version,
publication or consumer dependency change was introduced. Future arithmetic work
is recorded as CHSEM-013 and CHSEM-014.
