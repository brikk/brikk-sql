# CHSEM-009 composite and overload typing

This round adds bounded source-backed rules for literal arrays, nullable scalar
results, LowCardinality string inputs, tuple argument unpacking and two-Decimal
multiplication. It does not infer every ClickHouse overload or settings combination.

## Explicit native literal policy

```kotlin
val typed = annotateClickhouseSemanticTypes(
    parseOne(sql, "clickhouse"), engineVersion = "26.10.2.103",
    schema = clickhouseSchema,
)
```

The new shared-library entrypoint requires exactly the researched pin and a
ClickHouse-specific schema when supplied. It annotates the AST in place, preserves
authored/generated SQL and serde, and propagates cancellation. The engine pin is
passed to inference, not silently replaced by whichever metadata release is newest.

The standard `annotateTypes` entrypoint retains its existing literal/parity policy.
It still uses separately proved declared/schema types, including the new wrapper
and two-Decimal rules, but does not replace its generic literal widths. Exact native
literal-array element inference requires the explicit entrypoint above. The parser
and annotated corpora, all existing comparison rules and ledgers remain unchanged.
No public constructor or data-class shape was replaced to add this policy.

Public offline semantic diagnostics already require an exact engine pin, so they
can use the native literal facts without treating generic integer hints as proofs.
Recognized higher-order call results without a native proof are UNKNOWN under
this entrypoint, rather than inheriting a generic container fallback.
Types remain conditional results, never parser acceptance, validity, complete
coverage or successful-execution verdicts. Unknown overloads remain unknown.

## Added contracts

- Decimal-spelled integer literals use the tagged FieldToDataType UInt/Int8,16,32,64
  boundaries. Positive values are unsigned; negative literal magnitudes establish
  the signed width. Supported finite floating literals are Float64. Unary -0,
  larger integers and unresearched literal spellings remain unknown.
- Supported integer joins follow getLeastSupertype, not value-range minimization.
  `[1,-1]` becomes Array(Int16), because UInt8 and Int8 need a wider signed type.
  Known null elements add scalar nullability; nested arrays and compatible unnamed
  tuple fields join recursively when their field types are established. Raw
  NULL-only tuple fields remain unknown. Floating/integer mixtures, Variant fallbacks,
  named-field merges, empty/Nothing arrays and newly invented nullable containers
  remain unknown. An unsupported join records unavailable result-family knowledge.
- Lambda argument declarations recursively remove LowCardinality for researched
  String/FixedString dictionary types, including nested tuple fields. Filter/sort
  result types account for the native array wrapper implementation as well.
  Numeric dictionary constructors depend on suspicious-type settings and stay
  unknown, even when the underlying primitive would otherwise be supported.
  Fresh LowCardinality construction in lambda results is also unresearched;
  input normalization is not a license to guess return-wrapper behavior.
- A single typed array of tuples supplies either one tuple parameter or matching
  component parameters. Explicit casts and schema declarations retain field types;
  unsupported tuple field access and arbitrary map key types stay unknown.
- Unary lower, upper and toString, plus researched multiply forms, propagate a
  declared Nullable scalar using the default NULL implementation. This is separate
  from a literal/constant NULL, which may yield Nullable(Nothing) or bypass variadic
  checks. Constant-NULL branches remain unknown. Nullable(UInt8) lambda predicates
  are accepted by filter/exists/all, and NULL predicate values act as false in the
  finite witnesses. Unresearched predicate aliases, including Bool, are not errors
  merely because their names differ from UInt8.
- Multiplication of two explicit Decimal operands uses the wider storage precision
  and the sum of scales from DecimalUtils. Decimal32/64/128/256 storage limits are
  9/18/38/76 digits. Scales beyond the storage limit stay unknown instead of producing
  a fabricated type. Decimal overflow and settings remain runtime restrictions,
  even when a result type is proved.

Mixed Decimal/integer and Decimal/Float overloads are deliberately not covered.
The native controls show why: a Decimal/integer result can preserve its declared
precision rather than the storage maximum, while Decimal/Float can return Float64.
The two-Decimal rule must not be reused for them. Decimal-to-string conversion,
arbitrary decimal joins and wider integer arithmetic also remain unresearched.

The constant-character-set trim rule now also recognizes authored SQL TRIM(BOTH
... FROM ...), the generator's canonical form. A constant String character set is
proved separately from its type. A nonconstant array-supplied character set stays
unknown with CONSTANT_ARGUMENT, matching a native runtime rejection.

## Settings and source boundaries

The paired cast_keep_nullable controls differ in successful output versus a NULL
conversion error. Neither gets an invented cast result when the bounded cast proof
cannot establish wrapper retention. Wide signed/unsigned joins remain unknown
under both explicit use_variant_as_common_type settings; the exercised higher-order
queries reject with NO_COMMON_TYPE. Observing a Variant in another literal context
does not authorize that result here. Default-NULL behavior and tuple/LowCardinality
binding come from tagged implementation guards, not from these finite examples alone.

Whole-call NULL dispatch is also separate from a lambda returning a nullable value.
The `arrayFilter(NULL, array)` control succeeds with Nullable(Nothing), whereas a
known lambda with NULL/non-array data can reject before that default dispatch.
The checker retains these branches as unknown, and a known predicate alone cannot
invent an ordinary UInt8 result when the array input itself is unproved.

No broad native acceptance or cross-dialect certification was added. CHSEM-010
lowering and CHSEM-011's parenthesized lambda parser gap remain separate work.

## Evidence and intentional refinements

`overload-cases.json` defines thirty-three finite original/generated query witnesses on
both digest pins. Positive results include their native toTypeName output. Controls
cover successful but unresearched mixed overloads, excessive decimal scale, unsafe
joins, settings-dependent casts and nonconstant trim characters. There are no
version/execution skips. `overload-brikk.json` records types/rules/unknowns from the
new entrypoint; `overload-native/` retains raw parser and execution observations.

`literal-types/` independently captures sixteen integer width boundary types from
both engines and compares them with Kotlin output. Expected widths come from the
native observations, not another copy of the Kotlin formula.

`overload-sources.json` records exact tag URLs, full-file hashes and excerpts for
FieldToDataType, getLeastSupertype, DecimalUtils, default NULL return typing and
FunctionArrayMapped. Regenerate to a new output path with
`tools/capture_clickhouse_semantic_contract_sources.py --round overload`.

`overload-refinements.json` names the only changed historical current assertions:
CHSEM-004-E41 now proves the Nullable(UInt8) lower input incompatible, and
CHSEM-005-E15/E16 now know Decimal multiplication and unary nullable-toString
results. Their existing exact native captures already witness those outcomes.
Historical fixture files, raw reports and corpus ledgers are not overwritten.
The CHSEM-008 E23 explanation is corrected to distinguish the valid native
length(x)>0 fixture from the separate invalid identity-predicate unit control.

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseOverloadTypingTest' \
  --jvm-args='-Dbrikk.clickhouseOverloadOut=/tmp/opencode/clickhouse-overloads.json -Dbrikk.clickhouseLiteralTypesOut=/tmp/opencode/clickhouse-literal-types.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/overload-cases.json --task CHSEM-009 \
  --brikk /tmp/opencode/clickhouse-overloads.json \
  --output /tmp/opencode/clickhouse-overload-native
python3 tools/probe_clickhouse_literal_types.py \
  --output /tmp/opencode/clickhouse-literal-types-native
```

## Validation

Both pins passed all thirty-three original and thirty-three generated finite
result/error assertions plus native formatting checks, without execution skips.
All sixteen independently observed integer boundary types agree with Kotlin on
both pins. The new reports regenerate byte-identically.

Full JVM regression passed 1,836 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility. Full IntelliJ regression
passed 757 tests on 261.25134.95; the final affected native/relation diagnostic
controls passed 42. Other IDE versions were not run. Complete Python discovery
passed 91 tests with one opt-in Toolchain-model skip using the unchanged SQLGlot pin.
The ClickHouse annotated corpus retains its original fixtures, ledger and exact
comparison rules. Only the three named historical semantic assertions refine their
current expectations; old raw reports and native captures remain untouched.

No old public JVM signature, Java/toolchain floor, IntelliJ API usage, release,
publication or consumer dependency changed. The only new public typing entrypoint
requires the exact researched engine version. Stop before CHSEM-010/011.
