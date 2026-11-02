# CHSEM-019 exact native scalar literal projections

The explicit native statement and script typing APIs now assign proved scalar
literal projection types instead of inheriting generic literal nullability/widths.
Strings are non-nullable String, TRUE/FALSE are non-nullable Bool, and NULL is
Nullable(Nothing). Ordinary numeric literals follow tagged ParserNumber and
FieldToDataType rules, including the unsigned/signed width boundaries, integer -0
normalization, finite Float64 literals and large decimal-integer fallback to Float64.

```kotlin
val statement = annotateClickhouseSemanticTypes(
    parseOne("SELECT 'hello' AS v", "clickhouse"),
    engineVersion = "26.10.2.103",
)
// The projected value is String, not the generic Nullable(String) fallback.
```

## Scope and compatibility

The rule applies to scalar literal projections, optionally wrapped by aliases or
parentheses, and directly supplied literal AST roots. Derived queries can copy the
proved projection types through the existing scope machinery. Script typing uses
the same rule, without inferring session mode from strings, semicolons or prior calls.

It does not refine arbitrary function arguments, compound unary/arithmetic expressions,
container joins or constant-NULL dispatch. Those retain their existing bounded rules.
Base-prefixed/special literal nodes outside this ordinary decimal/string/Boolean/NULL
subset are not newly qualified. This avoids turning corrected literal widths into
guessed arithmetic result widths or treating a typed NULL as an accepted function input.

Numeric proof admits plain decimal tokens within the native 319-character bound.
Non-finite overflow and overlong tokens remain UNKNOWN rather than keeping a generic
numeric guess; native syntax-rejection controls are recorded separately. This is
typing, not parser acceptance: for example, the pin accepts parenthesized .5 but
rejects unparenthesized SELECT .5 in the tested first-projection context. The value
fixtures use accepted parenthesized scalar expressions. No parser rule is changed.

The existing contextual literal helper is unchanged, so this does not silently
expand higher-order, arithmetic or diagnostic proofs. Generic annotateTypes remains
unchanged, including after reannotating a previously native-typed AST. Other dialects,
caller-owned metadata overrides, overwrite=false and clear/uncache behavior are tested.
No authored SQL, settings or source metadata is rewritten; AST serde and existing
public JVM signatures/data-class shapes remain intact. No new IntelliJ API call or
automatic switch to script processing is introduced.

## Evidence

`projection-literal-types/` contains 42 scalar cases in two projection forms:
84 independently observed native types per digest pin, matching Kotlin exactly.
It covers all existing integer literal boundaries, unsigned/signed zero, finite
fraction/exponent forms, finite large-number fallback, strings including Unicode
and escaped NUL, Bool and Nullable(Nothing). Expected types are engine observations,
not a second implementation of the Kotlin formula.

`projection-literal-cases.json` contains 44 original/generated finite value/type/error
witnesses per pin, including two native syntax rejections with UNKNOWN static types.
Six additional whole-session scripts cover native literals alongside script settings,
division, resets of analysis context, SET-looking string text and a runtime failure
after a successful NULL projection. Known successful types agree with toTypeName.
Conditional division types and native failures remain independent.

The probe helper now supports an explicitly expected stdout prefix on an error
outcome. Existing error fixtures still require empty stdout by default; a unit gate
proves that unexpected output is rejected. The new partial-output fixture checks
the exact earlier NULL row and the later error code/name, without weakening any
historical acceptance or ledger assertion.

`projection-literal-sources.json` pins Apache-2.0 ParserNumber/Null/Bool handling and
FieldToDataType constructors, signed/unsigned thresholds, finite floating fallback,
zero normalization and token limits, with tagged URLs and full-file hashes.
Historical captures remain unchanged; CHSEM-018's script typing output regenerates
byte-identically. The new matrix, finite and script reports also regenerate identically.
CHSEM-018's documented standalone-literal fallback boundary is closed by this round,
not by editing its earlier captures or changing the generic annotator policy.

## Reproduction and validation

```sh
python3 tools/probe_clickhouse_projection_types.py \
  --output /tmp/opencode/projection-types-native
./kotlin test -m brikk-sql --include-classes='*ClickhouseProjectionLiteralTypesTest' \
  --jvm-args='-Dbrikk.clickhouseProjectionTypesOut=/tmp/opencode/projection-types-brikk.json -Dbrikk.clickhouseProjectionLiteralsOut=/tmp/opencode/projection-brikk.json -Dbrikk.clickhouseProjectionLiteralsScriptOut=/tmp/opencode/projection-script-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/projection-literal-cases.json --task CHSEM-019 \
  --brikk /tmp/opencode/projection-brikk.json --output /tmp/opencode/projection-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/projection-literal-script-cases.json \
  --brikk /tmp/opencode/projection-script-brikk.json --output /tmp/opencode/projection-script-native
```

Both exact pins match all 84 matrix types, 44 original/generated finite outcomes
and six original/generated session scripts without execution/version skips.
Full JVM regression passes 1,891 tests with 61 optional integration assumption
aborts and zero failures. Complete Python discovery passes 123 tests with one
opt-in skip, using the unchanged SQLGlot pin. Build gates run sequentially with
a task-private temp directory. No corpus/ledger relaxation, release, publication,
toolchain floor or consumer dependency change is introduced.
ClickHouse-only Python discovery passes 75 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. No new IntelliJ API call
or automatic statement/script mode change is introduced.

CHSEM-020 subsequently qualifies integer addition/subtraction result typing as a
separate native-only contract. See `integer-add-sub-README.md`; the earlier literal
projection captures and generic typing policy remain unchanged.
