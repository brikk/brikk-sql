# CHSEM-005 composition and arrayMap result inference

The internal callable analysis now has a separate exact-result mode. It shares
CHSEM-004's name, scope, argument-count and supported-input checks, while its
ordinary validation mode retains the same reports. Inference returns value types,
callable result types and the existing error/unknown report. None is an acceptance
or successful-execution verdict.

`TypeAnnotator` uses these proofs in its ClickHouse contextual pass. Other dialects
keep their existing behavior. The annotation target is the current default pinned
ClickHouse metadata, 26.10.2.103, not an availability claim about an older server.
The internal inference API requires an exact version and returns unknown for other
versions. No public method/constructor signature or data-class shape changed.

## What is inferred

- Supported string chains propagate their proved results to later stage arguments
  and the final arrayMap element type. The URL example with a literal-string WITH
  array now produces Array(String).
- lower/upper and the URL functions preserve String or FixedString input types.
  trimBoth, toString and scalar concat produce String within the researched input
  subsets. FixedString widths are not fabricated or converted into generic strings
  unless the native function actually returns String.
- The pinned NumberTraits primitive multiplication rule computes signedness and
  widening for Int/UInt8,16,32,64 and Float32/64 inputs. Typed multiplication
  operators use the same rule. UInt8 times UInt8 yields UInt16; Float32 times
  Float32 yields Float64. Decimal/big-integer promotion remains outside this slice.
- Family-only numeric knowledge can still prove a final String after toString,
  without inventing the preceding numeric width. Bare numeric-literal identity
  maps remain unknown until native literal-type inference is researched.
- Identity lambdas copy known nullability, width, precision/scale and container
  shape. Explicit nullable cast targets can be copied without inferring a function's
  null propagation. Nullable conversion functions and decimal multiplication remain
  unknown rather than borrowing the identity rule.
- Nested maps, captures and shadowing propagate proved results. Results already
  proved in a derived-query scope can feed an outer scope without trusting generic
  annotation guesses.

Function-valued Lambda, ClickhouseImplicitLambda, ClickhouseFunctionReference and
ClickhouseComposition nodes retain UNKNOWN in their AST type slots. Their result
types are recorded separately in the internal inference report. Assigning String
to a function value would incorrectly make it a scalar.

An opaque outer scalar function does not acquire a result merely because a supported
subexpression is typed. Conditional types for supported atomic subexpressions can
be recorded without eagerly diagnosing an unresearched parent's argument evaluation.
Arithmetic outside the researched multiplication rule stays unknown.

## Conservative boundaries

Proven argument errors prevent a map result, including a constant lambda supplied
with an invalid array input or parameter count. Unknown callables, potentially
shadowed names, incomplete namespaces, wrappers, unmapped aliases, the CHSEM-011
parser gap and unresearched result rules do not get invented result types.

Unknown nullability stays unknown, not Nullable or non-null by default. Precision
and type parameters must be known declarations; no decimal result precision/scale
is calculated here. LowCardinality, Dynamic, Variant, user-defined and unknown type
shapes remain unsupported for exact result inference. Casts retain the existing
non-null input proof requirements, except when an explicit nullable target already
accounts for a known source wrapper or literal NULL.

Runtime unknowns remain visible alongside known conditional types. E29 knows
Array(UInt16), but still records unchecked array lengths. E30 knows
Array(FixedString(3)), but still records the URL extractor's execution restriction.
E39 knows Array(String), but does not certify timezone values. The toTypeName
witnesses inspect result types, not successful production of those underlying values.

Caller-supplied typing overrides and partial replacement policies retain ownership.
Native inference does not fill omitted caller policies with guessed map results.
`overwriteTypes=false` preserves existing known type slots. Native-proof caches are
private, copied, and invalidated with the existing clear/uncache contract. Normal
node annotation still records metadata before applying a proved result. SQL text,
source positions and serialization retain their existing contracts.

## Evidence

- `result-cases.json` has CHSEM-005-E01 through E40 type witnesses, errors and
  explicit unknown controls. Every original query runs on both digest pins, without
  version skips. Known inferred types match native 26.10 toTypeName output.
- `result-native/<version>.json` retains raw formatting and finite original-query
  outcomes with exact engine identity and fixture hashes.
- `result-brikk.json` records current annotation types and error/unknown categories.
  The CHSEM-011 control has no purported generated SQL.
- `result-matrix/input.json` defines all 100 ordered pairs of the ten supported
  primitive numeric types. The expected outputs come from each engine's raw query,
  not another copy of the Kotlin formula. Native observations and the Kotlin result
  matrix agree on both pins. Input hashes tie each report to its exact SQL.
- `lambda-input-results-refinements.json` records two intentional improvements to
  CHSEM-003's current assertions: its later toTypeName argument receives String,
  and an outer map receives UInt8 from an inner identity map. Existing native
  argument witnesses support both refinements. The historical CHSEM-003 fixture,
  raw annotation capture and native reports are not overwritten or relabeled.

Existing corpus fixtures, ledgers and comparison rules remain unchanged. The old
numeric-literal lambda cases stay unknown because exact literal widths have not
been researched. CHSEM-004's saved rule report also remains byte-identical.

## Reproduction

Write fresh observations to new paths, not over historical evidence:

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseResultInferenceTest' \
  --include-classes='*ClickhouseResultFixturesTest' \
  --jvm-args='-Dbrikk.clickhouseResultOut=/tmp/opencode/clickhouse-results.json -Dbrikk.clickhouseResultMatrixOut=/tmp/opencode/clickhouse-result-matrix.json'

python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/result-cases.json \
  --task CHSEM-005 --output /tmp/opencode/clickhouse-result-native

python3 tools/probe_clickhouse_result_matrix.py \
  --output /tmp/opencode/clickhouse-result-matrix-native

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

The tools reuse isolated clickhouse-local containers, exact cached image digests,
no network/host mounts, resource limits and query/host deadlines. No user database,
credentials or DDL execution is involved. These are original-query type witnesses,
not new generated-value equivalence certification. Native output derives from the
same Apache-2.0 ClickHouse builds; see `ATTRIBUTIONS.md`.

## Validation

Full `./kotlin test` passed 1,811 tests with zero failures and 61 optional
live-engine/chDB assumption aborts. Existing parser, generator, annotated-serde and
transpilation corpora retain their original fixtures, ledgers and comparison rules.
Public binary-compatibility tests pass. Custom IntelliJ platform suites were not run.

ClickHouse Python discovery passed 35 tests. The complete tool suite passed 83
tests with one opt-in Toolchain-model skip using the unchanged pinned SQLGlot
source from a temporary checkout. Both native result runs passed all 40 parser
and 40 original-query assertions. The 26.8 control has 11 successes and 29 expected
errors; 26.10 has 30 successes and ten expected errors, with no execution skips.
Both primitive matrices contain all 100 observed result types and agree with Kotlin.

CHSEM-004's current rule report remains byte-identical. Historical CHSEM-001/002/003
fixtures, captures and source evidence remain intact; new input refinements are
separate, explicit assertions. No public JVM signature, IntelliJ API call, release
version, publication or consumer dependency changed.
