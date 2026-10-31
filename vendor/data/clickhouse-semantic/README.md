# CHSEM-001 semantic fixtures

This is the reference evidence for the first ClickHouse semantic-validation slice,
not an implementation of that validator. The cases were authored for this task;
the SQL examples E01–E06 reproduce the forms in the ClickHouse announcement linked
in `cases.json`, with explicit-lambda controls and result-type columns added.
ClickHouse is Apache-2.0 licensed; see `ATTRIBUTIONS.md`.

CHSEM-002 adds a separate [callable-fact guide](callables-README.md), source excerpts
and native argument/type probes. It leaves all CHSEM-001 inputs and captures intact.

CHSEM-003 adds [contextual lambda input typing](lambda-input-README.md), with separate
annotation/native evidence and an explicit CHSEM-011 parser-gap control.

CHSEM-004 adds the internal [composition rule checker](composition-README.md), with
separate error/unknown reports and 50 original-query controls on both pins.

CHSEM-005 adds [result inference](result-README.md), with 40 result/unknown witnesses,
an engine-observed 100-pair primitive matrix, and separately recorded refinements to
the current input-binding assertions. Historical captures are preserved.

CHSEM-006 exposes [positioned offline diagnostics](diagnostics-README.md) through
the shared library. Public errors and unknowns retain the existing 50-case rules;
parser acceptance cannot suppress semantic findings.

CHSEM-007 [qualifies the bounded slice](qualification-README.md) with integrated
scope/lineage, serde, typing and diagnostic controls plus twelve original/generated
finite execution pairs on each pin. This is not whole-analyzer or cross-dialect certification.

CHSEM-008 adds [distinct higher-order contracts](higher-order-README.md) for
arrayFilter, arrayExists, arrayAll, arraySort and mapApply, with their own source
guards and original/generated witnesses.

## Inputs and evidence

- `cases.json` contains stable CHSEM-001-P01–P02 syntax negatives and
  CHSEM-001-E01–E48 finite execution cases. Each execution case has independent
  parser and result/error expectations for both exact engine versions.
- `brikk-before.json` captures SQL generation, composition/implicit-lambda node
  counts and current type annotations before implementing semantic validation.
  It is historical observation, not a requirement to keep unknown types forever.
- `native/26.8.34.13.json` and `native/26.10.2.103.json` contain raw formatting,
  original execution and generated execution observations. Each records the
  exact image digest, version query, capture time and hashes of both input files.
- The engine definitions are reused unchanged from
  `../clickhouse-2026-audit/engines.json`. Historical CH26 evidence is untouched.

The 26.8 pin is deliberately executed for every case, not skipped for later
features. It establishes whether the old engine rejects syntax, rejects name
resolution or already supports a control. Native formatting is a syntax check
only; a formatted expression may still fail argument validation or execution.

Brikk renders explicit `concat(...)` calls as `CONCAT(...)`, and ClickHouse's
formatter preserves that spelling. E05/E06/E34/E48 therefore list exact
`native_generated_format` strings using the existing probe facility. Tests require
that the sole difference is that function spelling, and both captured registries
mark `concat` case-insensitive. Original/generated execution also agrees for each
case, including the E48 argument error. No general SQL case-folding, production
generator change or existing corpus-ledger exception is introduced.

## Measured rules to preserve

- E01/E02 and E05/E06 match on 26.10 for the specific announcement inputs. The
  explicit-lambda controls also execute on 26.8. These pairs do not certify a
  general cross-dialect rewrite or complete function signatures.
- E04/E33/E40 reject bare variadic designators on the left of composition.
  E03/E34/E41 supply explicit placeholders and succeed. E43/E44 show that the
  same blanket rejection would be wrong on the right: `lower | trim` and
  `lower | concat` succeed, since the right stage takes one value.
- E10 consumes two inputs for two anonymous occurrences. E12 reuses a single
  numbered argument. E13 shows that `_2` implies two arguments even without
  an occurrence of `_1`. E14/E15/E37 distinguish missing/extra input arrays
  from successful placeholder binding.
- E16/E17 reject mixed anonymous/numbered placeholders in one operand.
  E47 accepts the two forms in different composition stages. E42 binds each
  stage independently, with two initial inputs and one intermediate value.
- E19 rejects `_129`; E18 treats `_0` as an unresolved ordinary identifier,
  not as argument zero. This is not evidence that every underscore-prefixed
  identifier should become a placeholder.
- E20/E21/E22/E27 distinguish nested implicit lambdas, captures and explicit
  shadowing. E23 captures a physical column. E24 leaves physical underscore
  column names outside lambda positions alone.
- E45 rejects a deeply nested anonymous placeholder in `upper(trim(_))`;
  E46 accepts the numbered equivalent. This is separate from mixed-placeholder
  validation and must not be lost when types are propagated through nested calls.
- E31/E48 reject a two-argument right operand. E35 rejects a known numeric input
  to `lower`. E39 rejects a scalar call used as a composition operand.
- E36's unknown function name produces BAD_ARGUMENTS in this composition context,
  not UNKNOWN_FUNCTION. A partial offline catalog must still distinguish missing
  knowledge from proof that a user's function does not exist.
- E38 reaches a different check: the number of arrays is correct, but their
  runtime lengths differ. Matching lambda arity does not prove equal array lengths.
- E28 rejects integer-looking `|` on 26.10 and fails parsing on 26.8; E29's
  explicit `bitOr` works on both pins. It must not become integer OR in Brikk.

The tagged `FunctionCompositionRewrite.cpp` linked in `cases.json` corroborates
operand-local placeholder collection, the 128-argument bound, direct anonymous
arguments and unary right operands. CHSEM-002 must separately establish the
callable facts it intends to expose. Finite successes alone are not overload
declarations or proofs of numeric promotion/nullability for all input types.

## Acceptance criteria

CHSEM-001 is complete when:

1. Every execution case has an explicit expectation on both pins, including
   result values and `toTypeName` output for successes, or native error code/name
   and relevant rule text for semantic failures. No LTS execution is skipped.
2. Every execution input is also formatted as data. Syntax rejection is recorded
   independently of semantic rejection; the two malformed syntax controls fail.
3. Every execution input, even one that the native analyzer rejects, retains a
   structured Brikk SELECT and survives parse/generate/parse and AST serialization.
   This is not a claim that Brikk diagnoses its semantic errors yet.
4. Generated SQL matches the native canonical formatting or the four exact
   documented CONCAT spellings where accepted, and
   preserves each pinned original result/type or expected error on execution.
5. Ordinary self-contained tests verify fixture IDs/categories, exact evidence
   inputs and engines, result/error assertions and absence of skipped cases.
   They require neither Docker nor network access.
6. No production validator, callable-signature catalog, typing behavior, verifier
   execution policy, public JVM signature or semantic-equivalence certification
   changes as part of this fixture-only step.

## Reproduction

From the repository root, capture current Brikk observations to a new location.
Do not overwrite the historical `brikk-before.json` during later implementation:

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseSemanticFixturesTest' \
  --jvm-args='-Dbrikk.clickhouseSemanticOut=/tmp/opencode/clickhouse-semantic-current.json'

python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/cases.json \
  --task CHSEM-001 \
  --brikk /tmp/opencode/clickhouse-semantic-current.json \
  --output /tmp/opencode/clickhouse-semantic-native

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

The probe requires the two digest-pinned images to be present locally. It never
pulls images itself. Each `clickhouse local` invocation uses an owned container,
no network or host mounts, CPU/memory/PID limits, a five-second query limit and
a thirty-second host deadline. Cleanup targets only that invocation's UUID name.
No user server, credentials, DDL or external tables are involved.

## Validation on 2026-10-30

- Both native runs passed. Each recorded 50 original parser observations,
  48 original executions and 48 generated executions, with zero version skips.
  Generated formatting was checked for all 29 syntax-accepted execution inputs
  on 26.8 and all 48 on 26.10. Successes and expected errors are both assertions.
- `./kotlin test -m brikk-sql -m brikk-sql-metadata` passed 1,437 tests with
  zero failures or aborts. This includes the new 48-case structured round-trip
  and serialization gate, existing analysis regressions and binary compatibility.
- The ClickHouse Python discovery command above passed 19 tests without Docker
  or network access. It checks the committed raw evidence, not a live server.
- The complete Python tool suite passed 67 tests, with one opt-in Toolchain-model
  skip. Its SQLGlot generator tests needed the existing pinned
  `a00dc2e5c489214bea53bacbbeba8713f21ebce1` source, supplied from a temporary
  checkout without changing repository pins or regenerating sources. Discovery
  without that checkout initially failed to import SQLGlot.
- Full verifier and IntelliJ suites were not run. No production library/IDE source,
  public JVM signature, runtime floor or historical semantic certification changed.
