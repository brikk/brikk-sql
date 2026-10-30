# CHSEM-003 contextual lambda argument typing

`annotateTypes` now binds ClickHouse `arrayMap` parameters from known array element
types. `ClickhouseLambdaTyping.kt` supplies a ClickHouse-only iterative walk;
`AnnotateTypes.kt` retains the shared node rules and the existing walk for every
other dialect. No public method or constructor signature changed.

## What is typed

- Explicit lambda declarations and value references receive the corresponding
  input array's element type.
- `_` occurrences and numbered `_1`, `_2` references use their owning implicit
  lambda's input slots. Numbered reuse and gaps retain their ordinal meaning.
- Nested calls type their input expressions before entering the inner lambda.
  Explicit names and implicit ordinal slots have independent lexical bindings.
  Captured outer parameters keep their types, while inner declarations shadow
  outer names, even when the inner type is unknown.
- Physical columns continue through ordinary schema/scope annotation. Actual
  underscore-named columns, field names, table names, aliases and type declarations
  do not become parameter references. A SQL subquery clears lambda bindings.
- Known element types are copied with their existing shape, parameters and
  nullability. Copying `Nullable(UInt8)`, FixedString width or an array element
  shape is not new nullable/composite result inference.

The walk uses linked scopes rather than copying the entire outer name map at each
lambda. A 1,000-level caller-supplied AST exercises stack-safe traversal and deep
capture lookup. Existing overwrite, clear and uncache behavior is tested.

## Knowledge limits

This round uses known non-null Array types, currently supplied by casts, schema
facts or parameters copied from those types. Unspecified array nullability and
generic literal-array integer widths are not native type proofs. Bare literal
arrays therefore remain unknown here; this does not change the existing general
literal annotator or claim exact ClickHouse integer-literal inference.

Mismatched parameter counts, mixed placeholder conventions and unsupported input
types do not produce fabricated bindings. Partially known multiple-array inputs
can retain the known slots while the others stay unknown. A known argument type
is still not proof that the call is valid: E28 binds a String input but native
ClickHouse rejects the deeply nested anonymous placeholder.

Arithmetic that depends on newly refined parameters remains unknown. Generic
SQLGlot arithmetic does not prove ClickHouse widening, signedness or overflow
behavior. The full corpus gate initially caught two numeric-body regressions;
the implementation was corrected instead of adding ledger exceptions or stripping
type payloads from comparisons. Existing scalar rules otherwise remain in place.
Caller-supplied result-type overrides are respected rather than masked by that
guard. No generic arithmetic fallback is treated as a native width proof.

Only `arrayMap` gets this new input binding. An inner unsupported higher-order
function still shadows its parameter names with unknowns instead of inheriting
an outer type. E23 records a valid native `arrayFilter` whose inner argument is
deliberately unknown in this round. Broader families remain CHSEM-008.

Composition receives array inputs only at its first stage. Later stages require
the preceding result and remain unknown until CHSEM-005. The same applies to an
outer map whose array input is another map's still-unknown result, E27. Lambda,
composition and arrayMap result types are not certified by this parameter pass.
No callable-fact evaluator, argument-error diagnostics or cross-dialect lowering
is introduced. Server-version availability checks are also separate: the dialect
can model an implicit-lambda AST while the pinned 26.8 engine rejects that query.

## Existing parser gap, CHSEM-011

E26 is valid on native 26.10:

```sql
SELECT arrayMap(
    (x -> toString(x)) | (y -> toTypeName(y)),
    CAST([1] AS Array(UInt8))
)
```

The current Brikk parser treats those parenthesized `->` operands as JSONExtract
nodes, not Lambda nodes. This pre-existing grammar gap is explicitly excluded
from the SQL input-typing claims and has its own stable follow-up ID. The native
case still executes on both pins; it is not silently dropped or ledgered as a
passing structured parse. Correct caller-supplied explicit Lambda AST operands
are tested separately for first-stage binding, without claiming that SQL parsing
or executable generation of this form has been fixed.

## Evidence

- `lambda-input-cases.json` contains 28 stable CHSEM-003-E01 through E28 cases.
  Formal and implicit-reference type lists are in AST depth-first order. UNKNOWN
  entries record incomplete knowledge, not native rejection or an acceptance verdict.
- `lambda-input-brikk.json` records this round's annotations for the 27 structurally
  supported queries. The E26 row records its input SQL and named parser gap, with
  no generated SQL or invented binding claims.
- `lambda-input-native/<version>.json` records all 28 parser observations and all
  28 finite original executions on each exact digest pin, with fixture hashes.
  No native execution is version-skipped. Old-engine identifier/syntax errors and
  current-engine argument errors remain explicit.
- These probes use casts to make input element types unambiguous and `toTypeName`
  inside lambdas to observe the native argument types. Negative cases also record
  exact error codes/names and relevant messages. The subquery-capture vector
  produces NOT_FOUND_COLUMN_IN_BLOCK, not an assumed UNKNOWN_IDENTIFIER error.
- Ordinary Kotlin tests check binding, capture/shadowing, unknowns, physical scope,
  source positions, SQL stability, serialization, cache behavior and other-dialect
  isolation. Python tests validate the saved annotations and exact native evidence.

Historical CHSEM-001/002 fixtures and captures remain unchanged. The new native
captures concern original queries, not new generated-execution certification.

## Reproduction

Capture current annotations to a new path, leaving this round's saved evidence intact:

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseLambdaTypingTest' \
  --include-classes='*ClickhouseSemanticFixturesTest' \
  --jvm-args='-Dbrikk.clickhouseLambdaInputOut=/tmp/opencode/clickhouse-lambda-inputs.json'

python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/lambda-input-cases.json \
  --task CHSEM-003 \
  --output /tmp/opencode/clickhouse-lambda-input-native

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

The existing native runner uses the same digest-pinned engines, owned isolated
containers, no network or host mounts, bounded resources and deadlines. No user
server, credentials or DDL execution is involved. Native output is derived from
Apache-2.0 ClickHouse; see `ATTRIBUTIONS.md`.

## Validation on 2026-10-30

Full `./kotlin test` passed 1,783 tests with zero failures and 61 optional
assumption aborts for unconfigured live-engine/chDB integrations. All existing
parser, generator, annotation and transpilation corpus gates pass without changing
their fixtures, ledgers or comparison rules. Public binary-compatibility tests pass.

The ClickHouse Python suite passed 26 tests. The complete tool suite passed 74
tests with one opt-in Toolchain-model skip after supplying the unchanged pinned
SQLGlot source from a temporary checkout. Both native runs passed all 28 parser
and 28 execution assertions, including the parser-gap control. The 26.8 pin has
13 successful executions and 15 expected errors; 26.10 has 23 successes and five
expected errors. No native execution is skipped or relabeled as an inapplicable
later-release feature.

The CHSEM-001 Brikk observation file remains byte-identical, and historical
native/source captures are untouched. There is no publication, consumer dependency
change, public JVM signature change or new IntelliJ API use. Custom IntelliJ
platform suites were not run.
