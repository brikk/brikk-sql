# CHSEM-008 higher-order contracts

The contextual checker and annotator now share a small contract table for arrayMap,
arrayFilter, arrayExists, arrayAll, arraySort and mapApply. The semantic target stays
exactly 26.10.2.103. Separate tagged source guards and original/generated native
witnesses on both pins back the newly added families.

## Distinct rules

- arrayFilter requires a UInt8 predicate in the researched non-null subset and
  returns the first source array's element type, not its predicate type. Additional
  arrays supply additional lambda parameters; their lengths remain unchecked.
- arrayExists and arrayAll have the same predicate requirement but return UInt8.
  Their single-array overload uses its elements as predicates. Empty typed arrays
  are witnessed separately because exists returns zero while all returns one.
- arraySort accepts a callable sort key or a single array without a callable. It
  returns the first array's type, not the key's type. Multi-array keys do not change
  which array is returned. Key evaluation/comparison is not execution certification.
- mapApply is supported for one known Map input. Two lambda parameters receive
  its key and value; one receives the key/value tuple. The result must be a
  two-element tuple. New key and value types come from the tuple result, not from
  copying the original map. Only known non-null numeric/string/FixedString keys
  are rebuilt in this slice. Multiple maps and unproved key types stay unknown.

Explicit casts of finite non-null map literals can establish input declarations,
as can schema facts. Arbitrary map expressions do not get guessed key/value types.
Typed tuple construction supports mapApply results. Comparisons of proved plain
numeric operands, or proved String/FixedString operands, return native UInt8;
other comparison families remain unknown. Existing caller overrides, overwrite
policy, caches and function-valued UNKNOWN slots retain their contracts.

Intrinsic predicate, map input, map result and callable-count errors have separate
public rule codes. Proven errors also stop generic container typing from filling
in a rejected call. Unknown bodies do not become proven errors. Existing generic
fallback types are not native proofs and do not certify a predicate or execution.
Unresearched higher-order families still do not eagerly validate their bodies.

Nullable predicates, recursive LowCardinality removal and single-array tuple
unpacking are left for CHSEM-009. A single array of tuples with a multi-parameter
lambda is unknown here, never incorrectly rejected for receiving only one array.
Missing catalogs, local shadowing, settings-dependent resolution, unsupported
wrappers and runtime length equality keep their unknown reasons. No cross-dialect
lowering, server execution path or automatic plugin activation was added.

## Evidence and refinements

`higher-order-sources.json` records full-file hashes and implementation excerpts
from FunctionArrayMapped, the four family headers, the map adapter and comparison
return rules on both tags. Regenerate with
`tools/capture_clickhouse_semantic_contract_sources.py --output <new-path>`.

`higher-order-cases.json` has separate family vectors, including filtering/sorting
by secondary arrays, empty exists/all inputs, map key/value swapping, tuple versus
scalar map results, wrong predicate types and wrong callable arity. Each original
and generated query has an explicit finite output or native error expectation.
`higher-order-brikk.json` records the current types and public rules;
`higher-order-native/` retains both exact engine pins' raw observations.

`higher-order-refinements.json` records the intentional CHSEM-003-E23 improvement.
Its nested arrayFilter parameter now receives String independently of the outer
UInt8 parameter. Its length(x)>0 predicate succeeds natively, but length's result
is outside the bounded callable catalog. A separate unit control uses an invalid
String identity predicate with the same input binding. Historical snapshots are not
overwritten. The old unsupported-context regression now uses arrayCount, which is
still outside the researched families.

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseHigherOrderContractsTest' \
  --jvm-args='-Dbrikk.clickhouseHigherOrderOut=/tmp/opencode/clickhouse-higher-order.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/higher-order-cases.json --task CHSEM-008 \
  --brikk /tmp/opencode/clickhouse-higher-order.json \
  --output /tmp/opencode/clickhouse-higher-order-native
```

## Validation

Both pins passed all eighteen original and eighteen generated finite result/error
checks plus native formatting assertions, with no execution skips. Full JVM tests
passed 1,831 with 61 optional integration assumption aborts and zero failures.
Affected IntelliJ native/relation diagnostic suites passed 42 tests on 261.25134.95.
Full platform regression was run in CHSEM-007, not repeated here; other IDE versions
were not run. Complete Python discovery passed 88 tests with one opt-in Toolchain
model skip using the unchanged SQLGlot pin. Existing rule/result captures regenerate
byte-identically. Old ABI shapes, corpus fixtures/rules, IDE API usage, releases and
consumer dependencies remain unchanged.
