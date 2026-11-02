# CHSEM-020 bounded native integer addition/subtraction typing

The explicit native statement and script APIs now infer integer result types for
`+`, `-` and resolved unqualified `plus`/`minus` builtins. All Int/UInt8,16,32,64,128,256
operand types are covered, with supported singly/doubly nullable inputs.

The tagged NumberTraits rules are not generic SQLGlot numeric coercion:

- Result width is twice the larger input width while that width is below 64 bits.
  At 64 bits and above, it stays at the larger input width; UInt64 does not become
  UInt128 merely because the operation adds two values.
- Addition is signed if either input is signed. Subtraction is always signed,
  including two unsigned inputs and wide UInt128/UInt256 inputs.
- Ordinary nullable argument types propagate result nullability.

For example, native SELECT 1+2 is UInt16 and SELECT 1-2 is Int16. Generic
annotateTypes retains its earlier literal/coercion behavior. Declared/schema types,
derived queries, chained supported operations and lambda bodies use the same
native-only rules. Division can consume a proved intermediate addition width.
Integer-division settings do not change addition/subtraction itself.

## Boundaries and ownership

The arithmetic slice is enabled only by the exact-pin native APIs. Generic typing,
other dialects and historical parity fixtures are unchanged. Caller-owned operator,
literal, unary/wrapper policies and overwrite=false are not used as permission to
invent native facts. Cache clear/uncache and cancellation remain supported.

Operators are intrinsic. Named calls must resolve through the exact catalog and
the existing lexical/physical namespace checks. Shadowed, qualified/member and
malformed calls remain unproved rather than being assumed to be builtins.
Function-valued AST nodes still retain UNKNOWN; callable results remain separate.

Constant NULL dispatch, uncertain casts, unknown input types and unsupported
floating, Decimal, Boolean, temporal and other overloads are not newly admitted.
Unsupported addition/subtraction in native-only mode stays UNKNOWN instead of
inheriting a generic result guess. Other existing operator and NULL-dispatch
contracts are not widened. This round does not fold arithmetic values or add
runtime error diagnostics. The read-only diagnostic APIs retain their earlier
bounded coverage independently of the new type facts.

Types remain conditional, not acceptance or successful execution verdicts. Native
integer operations can wrap at their result width. UInt64-max plus one returns
zero with UInt64 type; UInt256-max plus one similarly wraps within UInt256.
Unsigned subtraction can produce negative signed results. These measured outcomes
are not mislabeled as overflow errors or used to widen types beyond NumberTraits.

No SQL/settings/source metadata is rewritten. AST serde, existing public JVM
signatures, Java/toolchain floors and IntelliJ API usage are unchanged.

## Evidence

`integer-add-sub-types/` contains 16 batches of 144 ordered pairs each, covering
both operators and both builtin forms, twelve integer types, and all four input
nullability combinations. All 2,304 Kotlin types match independent observations on
both digest pins. Expected types come from the engines, not a second implementation
of the Kotlin width/sign formula.

`integer-add-sub-cases.json` contains 30 original/generated finite type/value/error
witnesses per pin. They include native literal widths, wide saturation, mixed signs,
chaining, wraparound, lambda bodies, nullable declarations, excluded overloads,
constant NULL, malformed arity and a shadowed name. Eight whole-session script
witnesses cover derived values, nullable/wide results, division setting overrides,
integer wraparound independent of Decimal settings and consumption by division.
Older-pin setting differences are explicit outcomes, not execution skips. The public
native typing entrypoints remain restricted to 26.10.2.103.

`integer-add-sub-sources.json` pins NumberTraits nextSize, signedness and construction,
PlusImpl/MinusImpl delegates, primitive return construction and default nullable
handling. Apache-2.0 excerpts retain tagged URLs and full-file hashes.

`integer-add-sub-refinements.json` records the two strengthened unit expectations:
the native 1+2 result and a division expression with a newly proved UInt16 addition
intermediate. Generic expectations remain unchanged. Earlier captures are not
replaced; CHSEM-018's script typing capture regenerates byte-identically.

## Reproduction and validation

```sh
python3 tools/probe_clickhouse_integer_add_sub.py \
  --output /tmp/opencode/integer-add-sub-types-native
./kotlin test -m brikk-sql --include-classes='*ClickhouseIntegerAddSubTest' \
  --jvm-args='-Dbrikk.clickhouseIntegerAddSubTypesOut=/tmp/opencode/integer-add-sub-types-brikk.json -Dbrikk.clickhouseIntegerAddSubOut=/tmp/opencode/integer-add-sub-brikk.json -Dbrikk.clickhouseIntegerAddSubScriptOut=/tmp/opencode/integer-add-sub-script-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/integer-add-sub-cases.json --task CHSEM-020 \
  --brikk /tmp/opencode/integer-add-sub-brikk.json --output /tmp/opencode/integer-add-sub-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/integer-add-sub-script-cases.json \
  --brikk /tmp/opencode/integer-add-sub-script-brikk.json --output /tmp/opencode/integer-add-sub-script-native
```

Both pins match all 2,304 matrix types, 30 original/generated finite outcomes and
eight original/generated session scripts, without execution/version skips. New
matrix/finite/script reports and historical CHSEM-018 typing output regenerate
byte-identically. Full JVM regression passes 1,899 tests with 61 optional integration
assumption aborts and zero failures. Complete Python discovery passes 128 tests with
one opt-in skip using the unchanged SQLGlot pin. Build gates run sequentially with
a task-private temp directory. No corpus/ledger relaxation, publication, release
or consumer dependency update is included.
ClickHouse-only Python discovery passes 80 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. No new IntelliJ API call
or automatic statement/script mode switch is included.
