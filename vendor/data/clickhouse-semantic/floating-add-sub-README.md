# CHSEM-021 bounded native floating addition/subtraction typing

The explicit native statement and script APIs now infer Float64 for supported
Float32/Float64 addition and subtraction, including mixtures with Int/UInt8,16,32,64.
Both operators and resolved unqualified plus/minus builtins share these facts.
Supported nullable inputs propagate result nullability.

Tagged NumberTraits nextSize promotes two Float32 arguments to an eight-byte result,
not Float32. All admitted pairs with Float64 or a standard-width integer also yield
Float64. The wider Int/UInt128/256 mixtures are not implicitly converted to Double:
native analysis rejects them with ILLEGAL_TYPE_OF_ARGUMENT. They remain UNKNOWN
in the type API rather than receiving fabricated Float64 types or new diagnostics.

## Scope, values and compatibility

Declared/schema, derived-query, chained, lambda and explicit-script contexts reuse
the same result facts. Floating addition/subtraction is independent of integer
division mode; subsequent floating division keeps its existing delegate behavior.
Integer-only +/- still uses CHSEM-020's unchanged helper and result rules.

Constant NULL dispatch, uncertain casts, unknown argument types, BFloat16, Decimal,
Boolean, temporal and other overloads are not newly qualified. Source/native type
refusal boundaries are not a blanket whole-query acceptance/rejection API.
Named-call namespace checks, qualified/member refusal, caller-owned operator/literal/
wrapper policies, overwrite=false, cache invalidation and cancellation remain intact.
Generic annotateTypes and other dialects keep their earlier policies. The public
statement/script signatures and data-class shapes are unchanged.

These are type facts, not exact-value or finite-value guarantees. Native Float32
casts and UInt64-to-Double arithmetic can round. Float64 overflow can produce inf,
and inf-inf or NaN inputs can produce nan without a runtime exception. The finite
controls retain those measured values instead of inventing overflow diagnostics.
Read-only diagnostic coverage remains separate from the new native-only type facts.
SQL/settings/source metadata and AST serde are preserved. No new IntelliJ API use,
automatic statement/script mode switch, release, publication or consumer edit occurs.

## Evidence

`floating-add-sub-types/` holds 576 independent accepted type observations and
256 independent wide-integer rejection controls per exact pin. Sixteen forms cover
operators/builtins and all four input-nullability combinations. The accepted set
contains every ordered Float32/64 pair and both operand orders with standard-width
integers. Every corresponding wide integer pair is independently executed and
recorded as native category 43, including nullable forms.

`floating-add-sub-cases.json` adds 31 original/generated finite type/value/error
witnesses per pin. They include promotion, nullable inputs, literal fallback,
chaining, named delegates, array lambdas, precision loss, inf/nan, constant NULL,
unsupported overloads, arity and shadowing. Eight complete-session scripts exercise
state/overrides, derived/nullable results, division consumption and native refusals.
Older-pin setting differences are explicit controls, not execution skips. Only the
exact 26.10.2.103 semantic target is enabled by the public APIs.

`floating-add-sub-sources.json` pins NumberTraits construction/promotion, binary
operation selection, plus/minus implementation and return construction, and nullable
dispatch. Apache-2.0 excerpts retain tagged URLs and full-file hashes. A named
refinement strengthens CHSEM-020-E24 to Float64 while preserving its old capture.
The integer matrix and historical CHSEM-018 typing output regenerate byte-identically.

## Preserved parser follow-up, not fixed in this round

Compact scientific expressions such as SELECT 1e0+2.0 can be tokenized as a merged
Literal; the large-double variant can instead become a Dot expression. Native
analysis accepts both and returns Float64. The correctly separated/spaced forms
parse as addition and are covered by this round. This is a pre-existing lexical
boundary issue, not a floating result-type rule.

`floating-add-sub-parser-gap-brikk.json` preserves the actual frontend AST shape,
literal texts, generated SQL and UNKNOWN result; the corresponding native capture
records successful Float64 controls on both pins. CHSEM-022 is proposed and unchecked
in dev-docs/TODO-clickhouse-semantic.md. No lexer/parity change is hidden inside CHSEM-021.

## Reproduction and validation

```sh
python3 tools/probe_clickhouse_floating_add_sub.py \
  --output /tmp/opencode/floating-add-sub-types-native
./kotlin test -m brikk-sql --include-classes='*ClickhouseFloatingAddSubTest' \
  --jvm-args='-Dbrikk.clickhouseFloatingAddSubTypesOut=/tmp/opencode/floating-types-brikk.json -Dbrikk.clickhouseFloatingAddSubOut=/tmp/opencode/floating-brikk.json -Dbrikk.clickhouseFloatingAddSubScriptOut=/tmp/opencode/floating-script-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/floating-add-sub-cases.json --task CHSEM-021 \
  --brikk /tmp/opencode/floating-brikk.json --output /tmp/opencode/floating-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/floating-add-sub-script-cases.json \
  --brikk /tmp/opencode/floating-script-brikk.json --output /tmp/opencode/floating-script-native
```

Both pins match 576 accepted types, 256 rejection controls, 31 original/generated
finite outcomes and eight original/generated session scripts without execution/
version skips. New reports, the named refinement/parser counterexample and historical
integer/CHSEM-018 typing output regenerate byte-identically. Full JVM regression
passes 1,907 tests with 61 optional integration assumption aborts and zero failures.
Python discovery passes 133 tests with one opt-in skip using the unchanged SQLGlot
pin. Build gates run sequentially with a task-private temp directory. Historical
corpus/ledger evidence and public JVM signatures are preserved.
ClickHouse-only Python discovery passes 85 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. No new IntelliJ API calls
or automatic statement/script mode changes are introduced.

CHSEM-022 subsequently closes the compact scientific-token gap. Its old Literal/Dot
captures remain as historical evidence; the new scientific-number refinement records
Add ASTs and Float64 types without rewriting those captures.
