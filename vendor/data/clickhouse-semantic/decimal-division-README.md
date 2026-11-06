# CHSEM-015 exact Decimal division typing

The native semantic pass now infers the supported Decimal/Decimal and both
Decimal/integer division results for `/`, resolved `divide` and `divideExact`.
Integer-division mode does not alter these Decimal rules. Ordinary annotateTypes
also uses the proved declared/schema types without changing its literal policy.

For two Decimal operands, the result uses the wider physical storage precision
9, 18, 38 or 76, and the first operand's scale. For one Decimal and one integer,
the result preserves that Decimal's declared precision and scale. A declared
Decimal(5,2) mixed with UInt256 therefore remains Decimal(5,2), not Decimal(9,2)
or a widened integer type. Decimal32/64/128/256 constructors normalize to their
corresponding Decimal precision. Supported nullable inputs propagate nullability.

The implementation uses the existing contextual facts, so these rules also apply
to schema columns, derived queries and lambda inputs. Caller-owned typing policies,
overwrite/cache handling, SQL generation, source metadata and serde are unchanged.
No public constructor, data-class field or method signature changed.

## Dispatch and unknowns

The type contract admits two-Decimal pairs whose second scale does not exceed the
first. Other scale-factor dry-run dispatch stays unknown rather than being treated
as a proved type or a blanket error. The native controls include a real code-69
ARGUMENT_OUT_OF_BOUND counterexample for a greater divisor scale.

When the sum of Decimal scales exceeds the result storage precision, native type
construction can fail with DECIMAL_OVERFLOW depending on decimal_check_overflow.
The type remains unknown unless that setting is explicitly false. Query-local
setting inheritance and CHSEM-014's preceding script SET facts are reused, without
assuming a connection default. The source guard and native enabled/disabled controls
are captured separately from value-dependent runtime overflow.

Constant NULL dispatch, uncertain casts, unsupported wrappers and Decimal/float
delegates remain outside this bounded contract. Decimal zero/overflow diagnostics
are not added here. Known conditional Decimal result types can coexist with native
runtime division-by-zero errors, just as CHSEM-012 keeps type and execution separate.

## Evidence

`decimal-division-types/` contains six batches of 228 admitted pairs each, all 1,368
independently observed native types matching Kotlin. Eight Decimal spellings cover
declared and constructor precision across all storage sizes. Twelve integer types
cover both signs and widths 8 through 256. The matrix includes both operand orders,
two-Decimal pairs, enabled/disabled integer modes, resolved named functions and
both-nullable variants. Expected types come from the exact engine, not a duplicate
implementation of the Kotlin precision formula.

`decimal-division-cases.json` and `decimal-division-native/` contain 22 original/
generated value/error witnesses on both digest pins. They cover declared precision,
storage widening, mode independence, singly/doubly nullable inputs, constructor
aliases, lambda bodies, Decimal zero, scale errors, checked/unchecked scale overflow,
constant NULL and excluded dispatch. Availability failures on 26.8 are explicit
controls, not execution skips. The public type entrypoint still requires 26.10.2.103.

`decimal-division-sources.json` pins DecimalUtils result traits, mixed-overload
precision copying, scale-factor checks, overflow guards, divideExact's delegate
and nullable return dispatch. Apache-2.0 source excerpts retain tagged URLs and
full-file hashes.

Historical captures are unchanged. `decimal-division-refinements.json` and
`decimal-division-refinement-brikk.json` strengthen CHSEM-012-E19 from UNKNOWN to
Decimal(18,3), agreeing with its original native witness. Earlier integer error
refinements remain intact.

## Reproduction and validation

```sh
python3 tools/probe_clickhouse_decimal_division.py \
  --output build/decimal-division-types-native
./kotlin test -m brikk-sql --include-classes='*ClickhouseDecimalDivisionTest' \
  --jvm-args='-Dbrikk.clickhouseDecimalDivisionOut=build/decimal-division-brikk.json -Dbrikk.clickhouseDecimalDivisionTypesOut=build/decimal-division-types-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/decimal-division-cases.json --task CHSEM-015 \
  --brikk build/decimal-division-brikk.json --output build/decimal-division-native
```

All 1,368 matrix types match. Both pins pass all 22 original and 22 generated
finite outcomes, without execution/version skips. Matrix, witness and refinement
reports regenerate byte-identically. Full JVM regression passes 1,864 tests with
61 optional integration assumption aborts and zero failures. Complete Python
discovery passes 106 tests with one opt-in skip. Historical corpus/ledger gates,
toolchain floors, release versions and consumer dependencies are unchanged.
ClickHouse-only Python discovery passes 58 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95. Other IDE versions were not run; no new IntelliJ API calls
or old public signature changes were introduced.
