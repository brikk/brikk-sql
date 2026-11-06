# CHSEM-017 proved Decimal runtime-overflow diagnostics

The exact 26.10.2.103 checker now reports native category 407 for three proved
constant failures in Decimal division:

- `DECIMAL_DIVISION_NARROWING_OVERFLOW`: either operand cannot fit the signed
  physical width used by the Decimal result.
- `DECIMAL_DIVISION_MULTIPLIER_OVERFLOW`: integer/Decimal division overflows while
  squaring its scale multiplier, even with a zero numerator.
- `DECIMAL_DIVISION_NUMERATOR_OVERFLOW`: scaling the numerator overflows that width.

The checks require explicit decimal_check_overflow=true and reuse CHSEM-016's
exact non-NULL constant and valid-cast proofs. No new constant-evaluation grammar
or arithmetic dependency is introduced. Query-local/nested overrides and preceding
script SET facts retain their existing scope; missing, DEFAULT and parameterized
values do not become invented defaults.

## Native guard order and storage differences

CHSEM-016's type-scale and NULL dispatch checks still run first. Runtime constant
processing then narrows the left and right operands, constructs a squared multiplier
when required, scales the numerator, and finally divides. A proved earlier failure
is reported instead of mislabeling it as division by zero. Cast failures or unknown
data are not used to assert a later overflow. Both NULL operands retain masking
controls, and nullable column types alone never prove an unmasked runtime failure.

The arithmetic bounds are signed physical storage bounds, not Decimal's declared
precision. For example, native division can return a value outside Decimal(9,0)'s
usual precision without overflowing its 32-bit storage. Boundary fixtures distinguish
the largest positive and minimum negative signed values from actual narrowing errors.

Decimal128/256 multiplication overflow checks are not implemented by the tagged
native mulOverflow specializations: they wrap and return false. Mathematical
multiplier/numerator overflow there therefore remains unproved, even with the
setting enabled. Two native controls deliberately produce wrapped negative results
and no static error. This does not change CHSEM-016's separate return-type scale
overflow guard, which can still reject those types before execution.

Known conditional result types are retained alongside runtime errors. Diagnostics
are read-only and positioned through the existing UTF-16 adapter; native parser
acceptance cannot erase them. Cancellation, SQL/source metadata, serde, caller typing
ownership and public JVM signatures remain intact. New rule enum members are appended;
the earlier internal zero helper remains available. No IntelliJ API calls are added.

## Evidence

`decimal-runtime-cases.json` contains 56 finite original/generated controls on both
digest pins. They cover both narrowing operand positions and signs, Decimal32/64
boundaries, multiplier and numerator overflow, earlier-error priority, zero guards,
named delegates, nullable constants, disabled/unknown/parameterized settings,
integer-division mode independence, invalid casts and big-Decimal wrapping.

Eight complete-session scripts cover preceding SET values, local overrides, resets,
unresolved parameters and NULL masking. They execute as whole scripts in isolated
sessions, not separate processes per statement. Older-pin setting/function differences
are explicit observed outcomes. Only the exact 26.10 semantic target is enabled.

`decimal-runtime-sources.json` pins the constant/narrowing and multiplication guards,
their order and the arithmeticOverflow big-integer specializations. Apache-2.0 tagged
excerpts retain full-file hashes. Historical CHSEM-016 captures are unchanged; a named
refinement records the three previously unproved runtime findings at E24/E25/E46.

## Reproduction and validation

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseDecimalRuntimeOverflowTest' \
  --jvm-args='-Dbrikk.clickhouseDecimalRuntimeOut=build/decimal-runtime-brikk.json -Dbrikk.clickhouseDecimalRuntimeScriptOut=build/decimal-runtime-script-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/decimal-runtime-cases.json --task CHSEM-017 \
  --brikk build/decimal-runtime-brikk.json --output build/decimal-runtime-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/decimal-runtime-script-cases.json \
  --brikk build/decimal-runtime-script-brikk.json --output build/decimal-runtime-script-native
```

All 56 original/generated finite outcomes and eight original/generated session
scripts pass on both pins, with no execution/version skips. Every new reported
runtime failure matches native DECIMAL_OVERFLOW on the 26.10.2.103 semantic target;
no success on that target is flagged. The 26.8 observations remain version controls.
Finite, session and refinement reports regenerate byte-identically. Full JVM
regression passes 1,875 tests with 61 optional integration assumption aborts and
zero failures. Python discovery passes 115 tests with one opt-in skip.

The first full JVM run hit a temp-directory quota during DuckDB library extraction.
The complete rerun passed with java.io.tmpdir pointing to a task-private directory
under build/ on the project filesystem. No tests, native oracles or gates were removed.
The initial failure log is retained separately. Build gates run sequentially.
Historical corpus/ledger evidence, release/toolchain floors and consumer dependencies
are unchanged; publication and consumer adoption remain separate.
ClickHouse-only Python discovery passes 67 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95 with the same task-private temp directory. Other IDE
versions were not run; no new IntelliJ API calls or old public signatures changed.
