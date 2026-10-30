# ClickHouse semantic validation

Prepared on branch `clickhouse-semantic` from `main`. CHSEM-001 evidence,
CHSEM-002 callable facts and CHSEM-003 contextual input typing are complete.
Composition validation, result inference and diagnostics remain separate work.
Keep these IDs stable so individual items can be discussed and resumed by label.

## Context

The CH26 catch-up added structured composition and implicit-lambda nodes, correct
argument binding, scope/lineage handling, SQL round trips and representative native
execution evidence. It deliberately left argument-aware higher-order typing,
function-specific argument validation and cross-dialect conversion unknown.
The ClickHouse catalog currently records names, aliases and kinds, not overloads.

Start with offline validation of composition and `arrayMap`, including the examples
in https://x.com/ClickHouseDB/status/2116123022285259254. This is a bounded extension,
not a claim of full ClickHouse analyzer parity. The existing `formatQuery` verifier
checks parsing only and must not be relabeled as semantic validation.

## First implementation slice

- [x] CHSEM-001: Establish version-pinned semantic fixtures and acceptance criteria. Reuse the existing 26.10.2.103 native probe infrastructure; capture valid and invalid composition, variadic designators, placeholder counts, mixed placeholder forms and nested bindings. Retain 26.8 controls and record actual results/errors rather than assuming rules from examples.
- [x] CHSEM-002: Define conservative callable-signature facts. Record proven argument-count, variadic and input/result rules for the initial functions: lower, upper, domainWithoutWWW, firstSignificantSubdomain, trim, multiply, toString and concat. Keep bare function designators distinct from explicit calls; preserve version, alias and case policy. Missing facts mean unknown, not valid or invalid.
- [x] CHSEM-003: Implement contextual lambda argument typing. Derive `_`, `_1` and `_2` types from the corresponding array elements; cover explicit lambdas, nested higher-order scopes, captured physical columns and multiple arrays. Leave ordinary underscore-named columns unchanged.
- [ ] CHSEM-004: Implement composition argument validation. Check stage argument counts, placeholder requirements and provable input incompatibilities using native-backed rules. Diagnose bare variadic designators such as `trim | upper` without rejecting the valid `trim(_) | upper` form. Do not infer runtime array-length equality from matching argument counts.
- [ ] CHSEM-005: Infer composition and arrayMap results. Propagate stage results through supported chains and compute array element types without inventing precision, nullability or overload facts. Track incomplete knowledge explicitly and preserve existing generic/dialect typing behavior.
- [ ] CHSEM-006: Expose positioned offline semantic diagnostics through the shared library. Keep semantic results separate from parser acceptance; distinguish proven errors from unavailable knowledge. Test statement/script offsets and consumer-facing integration without adding new internal or experimental IntelliJ API calls.
- [ ] CHSEM-007: Qualify and document the bounded slice. Add self-contained positive/negative, AST serde, scope/lineage, typing and diagnostic tests; compare original/generated finite results and errors against pinned native fixtures. Run affected modules, full regression and binary-compatibility gates, record skips, and document exact coverage and remaining unknowns.

## Follow-up scope, not part of the first slice

- [ ] CHSEM-008: Extend proven contracts to common array/map higher-order functions. Prioritize arrayFilter, arrayExists, arrayAll, arraySort and mapApply; account for their different argument binding and result rules. Establish separate native fixtures for each added family.
- [ ] CHSEM-009: Expand composite and overload typing. Research Nullable, LowCardinality, tuples, numeric promotion, decimal precision/scale, constant-dependent rules and settings/version boundaries. Add only measured rules; retain explicit unknowns for UDFs and unsupported overloads. Establish exact native literal-array element types before using generic integer hints in contextual lambda validation.
- [ ] CHSEM-010: Assess cross-dialect lowering separately. Identify which supported compositions can be rewritten as explicit lambdas on each target, gather paired result/error evidence and retain refusal for unproved conversions. No conversion is authorized merely by completing local validation.
- [ ] CHSEM-011: Fix the existing ClickHouse parser gap for parenthesized explicit lambda operands inside composition. Native 26.10 accepts `(x -> toString(x)) | (y -> toTypeName(y))`, but Brikk currently creates JSONExtract nodes. CHSEM-003-E26 records the native boundary. Add correct structured parsing, source/serde/scope tests and native original/generated result checks without changing other dialects' JSON arrow operators. Until then, do not claim this SQL form has contextual typing or certified lowering.

## Constraints and resumption

- User authorized CHSEM-003 after completing CHSEM-002. Commit and push this branch after each round; stop before CHSEM-004 until further direction. CHSEM-011 is recorded follow-up work, not authorization to expand this round.
- Resume at CHSEM-004; do not treat the unchecked follow-ups as implemented or certified.
- Do not fabricate overloads from documentation or finite probes that cannot prove a general rule.
- Preserve existing source ranges, safe verifier behavior and historical native evidence.
- Preserve public JVM signatures per AGENTS.md and the current Java/toolchain floor.
- Do not weaken corpus ledgers, suppress compatibility/API warnings or widen semantic certification to pass tests.
- Branch commits and pushes are authorized after each round. Publication, releases, consumer dependency changes and sibling-repository edits remain out of scope.

## CHSEM-001 result

Evidence and acceptance criteria: `vendor/data/clickhouse-semantic/README.md`.
Stable IDs CHSEM-001-P01–P02 cover syntax negatives; CHSEM-001-E01–E48 cover
finite results/types and expected errors. Both engine pins execute all 48 cases
as originally authored and as generated by Brikk, with no version skips.
Native formatting is checked separately from execution. Four exact CONCAT casing
differences are documented and checked against both native registries, not hidden
by generic case-folding or corpus-ledger changes.

Notable constraints for the next items: variadic designators behave differently
on the left and right; anonymous placeholders must be direct call arguments;
mixed placeholders fail within an operand but may work across separate stages;
and runtime array-length errors are distinct from lambda argument-count errors.
The native guide links individual cases and the tagged analyzer source.

Validation: 1,437 core/metadata JVM tests passed, including binary compatibility;
19 ClickHouse Python tests passed. The complete Python tool suite passed 67 tests
with one opt-in Toolchain-model skip after supplying the existing SQLGlot pin
from a temporary checkout. No production code, published APIs, consumer pins,
historical semantic evidence or IntelliJ API usage changed. Full verifier/IDE
suites were not run for this fixture-only step.

## CHSEM-002 result

Internal facts: `brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseCallableFacts.kt`.
Evidence, exact input subsets and reproduction:
`vendor/data/clickhouse-semantic/callables-README.md`.
Facts cover the initial eight functions on the two exact pins only, use captured
case/alias policy, distinguish variadic designators from explicit calls and return
no knowledge for missing entries or unverified versions. They do not populate
registry overloads, override scope resolution or integrate with typing/diagnostics.

Both tagged source captures retain declaration excerpts and full-file hashes.
CHSEM-002-E01 through CHSEM-002-E56 retain 56 original-call parser/execution outcomes per pin, with
no version skips. URL FixedString analyzer types and execution failures are separate;
the type-only materialize controls must not receive blanket type errors. Variadic
explicit-count rules apply only inside the supported non-NULL input subset:
`toString(NULL, 1, 2)` is a measured counterexample to a universal two-argument bound.
Array concat and other out-of-subset overloads remain unknown rather than rejected.

Validation: full `./kotlin test` passed 1,766 tests with 61 optional live-engine/chDB
assumption aborts and zero failures; final focused tests passed 23. The complete
Python tool suite passed 71 tests with one opt-in Toolchain-model skip, using the
unchanged SQLGlot pin from a temporary checkout. All tagged source excerpts/hashes
reproduce. Current CHSEM-001 Brikk observations are byte-identical to the saved
baseline. No public JVM signature, IntelliJ API use, historical capture, publication
or consumer dependency changed. Custom IntelliJ platform suites were not run.

## CHSEM-003 result

Implementation: `brikk-sql/src/dev.brikk.house.sql/optimizer/ClickhouseLambdaTyping.kt`
and the ClickHouse branch of `TypeAnnotator`. Details and evidence:
`vendor/data/clickhouse-semantic/lambda-input-README.md`.
Known non-null Array inputs from casts/schema bind explicit parameters and implicit
ordinals, with nested capture/shadowing, multiple arrays, copied element shapes,
physical columns and real underscore names kept distinct. The walk and scope chain
are iterative; a 1,000-level AST is covered. Overwrite, clear/uncache, serialization,
source positions, custom result overrides and other-dialect behavior are tested.

Literal-array generic numeric hints remain unknown, as do arithmetic widths that
depend on newly refined parameters. No corpus ledgers or oracle comparisons were
weakened. Non-arrayMap families, later composition-stage inputs and arrayMap results
remain unknown for their respective later steps. Argument types are not acceptance
verdicts; malformed calls can still have a known parameter type and native error.

CHSEM-003-E01 through CHSEM-003-E28 have native parser and original execution
evidence on both pins, with no execution skips. Twenty-seven SQL forms have saved
Brikk binding assertions; E26 is the explicit native-valid parser-gap control for
new follow-up CHSEM-011. Correct caller-supplied explicit composition Lambda ASTs
are tested, but that parenthesized SQL form is not claimed as fixed or certified.

Validation: full `./kotlin test` passed 1,783 tests, with 61 optional integration
assumption aborts and zero failures. The complete Python tool suite passed 74 tests
with one opt-in Toolchain-model skip, using the unchanged temporary SQLGlot pin;
the ClickHouse-only suite passed 26 tests. The saved CHSEM-001 Brikk capture remains
byte-identical. Earlier native/source captures, public JVM signatures, IntelliJ API
use, release versions and consumer pins are unchanged. Custom IntelliJ platform
suites were not run. Commit/push this round, then stop before CHSEM-004.

References: `docs/research/clickhouse-2026-batch3.md`,
`docs/research/clickhouse-2026-batch4.md`,
`brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseHigherOrder.kt`,
`brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateTypes.kt`,
`brikk-sql/src/dev.brikk.house.sql/diagnostics/SqlDiagnostics.kt`.
