# ClickHouse semantic validation

Prepared on branch `clickhouse-semantic` from `main`. CHSEM-001 evidence,
CHSEM-002 callable facts, CHSEM-003 contextual input typing, CHSEM-004 internal
composition validation, CHSEM-005 conditional result annotation and CHSEM-006
public positioned diagnostics, CHSEM-007 qualification, CHSEM-008 higher-order
contracts, CHSEM-009 bounded composite/overload typing, CHSEM-010 lowering
assessment and CHSEM-011 parenthesized lambda parsing are complete.
CHSEM-012 adds exact division result typing; CHSEM-013 adds constant-error diagnostics.
CHSEM-014 adds preceding script SET state; CHSEM-015 adds bounded Decimal division typing.
CHSEM-016 adds positioned Decimal division failures with guard-order and NULL controls.
CHSEM-017 adds proved constant narrowing and scaling-overflow diagnostics.
CHSEM-018 adds an explicit script-aware native typing entrypoint; statement APIs stay local.
CHSEM-019 adds exact native scalar literal projection types and nullability.
CHSEM-020 adds bounded native integer addition/subtraction result typing.
CHSEM-021 adds supported floating/mixed addition/subtraction types and wide-integer controls.
CHSEM-022 fixes native scientific-number boundaries; CHSEM-023 qualifies and freezes the batch.
Keep these IDs stable so individual items can be discussed and resumed by label.

## Accepted scope

Latest main synchronization: the user authorized merging main `95f4fcd4` into
this branch, resolving differences, then committing/pushing and fast-forwarding/
pushing main after all gates pass. Stop there for another agent's final release
checks. The merge retains DuckDB/ClickHouse/DataFusion typing, including DuckDB's
unannotated native timestamp-cast exception and caller-owned-slot protection.
The ClickHouse tracker follows main's `dev-docs/` layout; evidence/test links are
updated. Final merged-tree gates pass 1,734 core tests, 732 other module tests
with 72 optional assumption aborts, 827 IntelliJ tests, 224 Python tests with one
opt-in skip, and 43 enabled release-model tests. The IDE gate uses a real signed
DuckDB window capture; ClickHouse CTE reports regenerate byte-identically.
Logs: `build/merge-main-final-gate/`. Earlier RC2 artifacts remain tied to
`c770484`, not this newer main merge; final release checks must rebuild/audit the
final main source. No formal release or consumer update is part of this step.

Release-preparation approval: the user subsequently authorized CHSEM-024 and
CHSEM-025 in order, with separate tested commits/pushes, followed by release-candidate
qualification and reconciliation with main as needed. P2 expansion stays deferred.
Publication and consumer updates still require approval. This supersedes the
unscheduled status below for those two P1 items and the preparation portion of
CHSEM-034 only.

The user then authorized CHSEM-030-A before release: a narrow non-recursive CTE
slice for ordinary/chained projections and aliases, stars/derived relations,
supported schema inputs and downstream arithmetic/lambdas. Broader CHSEM-030 and
other P2 families remain deferred. Earlier RC1 qualification does not certify the
new CTE change; refresh the gates and local candidates before a release handoff.

On 2026-11-02 the user accepted the closeout recommendation: keep this as a bounded,
opt-in semantic/type-hint foundation with explicit unknowns and separately proved
errors. It is not a full ClickHouse validator or a guarantee that every populated
AST type is native-exact. CHSEM-001 through CHSEM-023 are complete and pushed,
ending with `148ebfb` and `13505ff` for the final two rounds.

The items below are deferred improvements, not an active implementation plan or
blockers to accepting this bounded scope. No merge, publication, release, consumer
update or sibling-repository change is authorized by this decision. The SQL-owned
index is [SQL-08](TODO.md); the qualification record is the
[closeout report](../vendor/data/clickhouse-semantic/closeout-README.md).

## Deferred improvement backlog

CHSEM-024/025 and CHSEM-034's local release preparation are complete; P2 items
remain open and unscheduled. Publication/consumer delivery is still held for
approval. P1 means address before offering authoritative
all-expression metadata; P2 means select a concrete consumer need before expanding
coverage. Delivery has its own approval gate. Every implementation round needs an
explicitly agreed scope, tests and its own commit/push. Do not reopen completed
CHSEM IDs or silently broaden their contracts.

- [x] **CHSEM-024: Expose per-node native proof provenance.** P1. Distinguish
  native-proved conditional types, generic fallbacks and unknowns without treating
  an error or parser acceptance as a proof. Define how consumers inspect these
  facts and how AST ownership/copy/serde affects them. Done when tests cover each
  category and consumers can avoid treating G01/G02 fallbacks as native metadata.
  Preserve existing entrypoints and public JVM shapes.
- [x] **CHSEM-025: Qualify standalone multiplication and root propagation.** P1.
  Use closeout G01 and G02 as regression seeds: `1*2` currently yields frontend
  `Nullable(Int32)` versus native `UInt16`; `1e0*(2e0+3.0)` yields frontend
  `Nullable(Float64)` versus native `Float64`. Prove selected operator/builtin,
  width, signedness, floating and nullable rules and nested/root propagation.
  Done when the selected cases have source/native-backed types and named
  refinements, generic `annotateTypes` stays unchanged, and CHSEM-024 identifies
  any remaining fallbacks. Depends on the provenance contract in CHSEM-024.
- [ ] **CHSEM-026: Qualify Decimal addition/subtraction.** P2. Start with G03 and
  closeout E17. Research precision, scale, mixed integer/Decimal dispatch,
  nullability and relevant settings; keep conditional result types separate from
  overflow/error outcomes. Done when a bounded operator/resolved-builtin matrix
  has tagged source rules, exact-pin observations and unsupported controls.
- [ ] **CHSEM-027: Qualify constant-NULL and wrapper dispatch.** P2. Start with
  G04 and closeout E18. Separate constant NULL masking from ordinary nullable
  propagation; research only admitted LowCardinality/common-type combinations.
  Done when guard order, nullability and refusal/unknown boundaries have positive
  and negative native witnesses without weakening existing NULL controls.
- [ ] **CHSEM-028: Expand selected function and type contracts.** P2. Choose
  individual scalar/aggregate/window or temporal families, broader literal/unary
  forms, BFloat16 or catalog/UDF signatures only for a demonstrated use case.
  G06 remains unknown until a real signature source is supplied. Done per family
  when resolution/alias policy, argument/result rules and unsupported combinations
  have source/native evidence. Do not turn the name catalog into guessed overloads.
- [ ] **CHSEM-029: Add selected semantic rejection rules.** P2. Start with the
  native-refused Float/wide-integer addition in closeout E13. Add positioned
  diagnostics only for proved overload/cast/value failures; distinguish missing
  knowledge from success. Done per rule when native failures, valid neighbors,
  ranges, guard order and parser-accepted semantic failures have tests. Runtime,
  permission and unknown parameter failures must not be invented offline.
- [ ] **CHSEM-030: Improve catalog and complex-query context.** P2. Select
  bounded schema-backed alias/CTE, join or set-operation cases. Done per slice
  when scope/capture/ambiguity handling, schema ownership and original/generated
  native results have tests, and missing catalog information stays explicit.
  This does not promise full native analyzer parity.
  CHSEM-030-A is the separately authorized ordinary non-recursive CTE slice;
  its contract/evidence is in `vendor/data/clickhouse-semantic/cte-context-README.md`.
  - [x] **CHSEM-030-A: Propagate supported native types through ordinary CTEs.**
    Completed and qualified with local RC2 artifacts. Covers chains, projection
    aliases/stars, nested scope/shadowing, supported schema facts and downstream
    arithmetic/lambdas, plus measured qualified INNER/CROSS joins. The parent item
    remains open for broader catalog, join, set-operation and correlated context.
- [ ] **CHSEM-031: Extend explicit session facts when needed.** P2. G05 remains
  intentionally unknown after `SET ...=DEFAULT` without external default facts.
  Design caller-supplied settings/profile/parameter facts or additional SET/DDL
  behavior only for a scoped need. Done when precedence, reset, invalidation and
  statement isolation have tests. Never infer session state from semicolons,
  editor contents or previous API calls; keep client execution policy separate.
- [ ] **CHSEM-032: Prove selected cross-dialect lowering.** P2. Continue from
  CHSEM-010's assessment rather than blanket-enabling composition/placeholder
  conversion. Done for a named source/target/version slice when bindings,
  evaluation behavior, types and errors match paired native evidence. Retain
  explicit refusal everywhere equivalence remains unproved.
- [ ] **CHSEM-033: Qualify additional engine and IDE versions.** P2. Current
  native APIs require exactly `26.10.2.103`; `26.8.34.13` is only a control.
  Current shared IntelliJ qualification is `261.25134.95`. Done for each proposed
  version when it is pinned, relevant historical matrices are freshly executed,
  changed contracts are labelled, and API/ABI/toolchain/platform gates pass.
  Report optional integration skips separately; do not treat them as successes.
- [ ] **CHSEM-034: Deliver the accepted bounded scope.** Separate approval gate,
  not feature expansion. After authorization, reconcile with current main and
  validate the combined tree, then publish approved artifacts and qualify each
  opted-in consumer separately. Done when exact merge/artifact/consumer revisions,
  integration tests and explicit statement/script policy are recorded. Publication
  alone is not adoption. Do not expose the result as a full validator or require
  every P2 family before a bounded consumer can adopt it.

### Evidence and completion rules

The starting witnesses are `closeout-cases.json`, `closeout-missing-cases.json`,
their `*-brikk.json` reports and `*-native/` captures under
`vendor/data/clickhouse-semantic/`. Keep historical captures intact; record stronger
facts as named refinements. Qualified successes, conditional types, proved errors,
intentional unknowns and known deviations must remain separate counts.

Before closing an implementation item, record its exact scope, dependencies,
source/native evidence, JVM/ABI and affected consumer/platform gates, Python checks,
freshness and skips. Preserve generic parity, source ranges, serde, cancellation,
ownership, explicit mode selection and public binary compatibility. Acceptance of
this backlog does not authorize work on its items.

## CHSEM-030-A result and current release handoff

The narrow non-recursive CTE slice is complete. Native output inference uses
structural lexical scope bindings and existing native contracts, never generic
type slots. Chained/repeated CTEs, aliases/stars, nested WITH/derived queries,
supported schema inputs and downstream arithmetic/lambdas carry native conditional
proofs. Simple qualified INNER/CROSS joins are measured. Unknown/ambiguous outputs,
recursive/set-operation CTEs, outer joins, unsupported functions and conflicting
definition/consumer division modes remain conservative. Generic typing, API/ABI,
source/serde/ownership and explicit statement/script separation are preserved.

Evidence: 28 finite cases with 20 qualified and eight deferred controls, plus six
script cases with four qualified and two deferred. Original/generated outcomes
match both exact native pins without execution/version skips. Tagged analyzer
source excerpts and a named refinement for the original CTE counterexample are
retained under `vendor/data/clickhouse-semantic/cte-context-*`.

Final gates pass 1,545 core tests, 455 other module tests with 61 optional engine
assumption aborts, 817 IntelliJ tests on 261.25134.95, and 214 Python tests with one
opt-in skip. The separate enabled release-model gate passes 38 tests. Both CTE
frontend reports regenerate byte-identically, and source/test fingerprints stay
unchanged through all final gates and packaging. No ledger or skip was weakened.

Unsigned local SQL `0.23.0-rc.2` and IntelliJ `0.3.1-rc.2` candidates pass all
15-coordinate alignment, verifier, Java 17 and IDE audits. All 60 payloads/JAR
integrity and eight packaged-source comparisons pass. The packaged Java 17 API
smoke now exercises ordinary/chained/array/star CTEs. RC2 supersedes the earlier
RC1 qualification for this source. Logs, source fingerprints and payload SHA-256
manifests are in `build/chsem-030-final-gate/`; handoff: `docs/sql-0.23.0.md`.

Stop expansion here. The next agent should release the final ClickHouse semantic
branch source including CHSEM-030-A, not current main alone or the older RC1
source. CHSEM-034 still needs final source/version approval, remote release gates
and publication; consumer adoption remains separately authorized and qualified.

## CHSEM-034 release preparation, RC1 baseline

The user authorized P1 fixes plus release-candidate preparation, not publication
or consumer updates. CHSEM-024/025 are committed/pushed as `f58dc3d`/`3ae9205`.
Current main `0572f6d` is reconciled into this feature branch. Conflict resolution
retains both DataFusion encoded-string and ClickHouse scientific-number policies,
with a combined-policy regression test, and both dialects' native typing rules.

The combined tree builds all modules and passes 1,536 core tests, 455 other module
tests with 61 optional-engine assumption aborts, and 817 IntelliJ tests on
261.25134.95. Python discovery passes 210 tests with one opt-in skip; the separate
enabled release-model run passes all 38 release tests. Multiplication reports
regenerate byte-identically. CI now includes offline ClickHouse evidence tests.

Unsigned local SQL `0.23.0-rc.1` and IntelliJ `0.3.1-rc.1` candidates pass alignment,
verifier, Java 17 and IDE audits across all 15 coordinates. All 60 payloads and
ZIP integrity are checked, representative packaged sources match the checkout,
and the packaged semantic APIs compile/run on actual Java 17. Release helpers
restore development descriptors; no remote artifacts or consumer repositories
are changed. Logs, SHA-256 manifests and smoke evidence are under
`build/clickhouse-release-gate/`; the release record is `docs/sql-0.23.0.md`.

CHSEM-034 remains open for approval of final source/version selection, remote
CI/publication and separately qualified consumer adoption. P2 items remain deferred.
Local candidate preparation is not release or adoption, and does not authorize
the remaining delivery steps.

Release preparation is complete locally. A post-reconciliation native refresh of
the 1,440-type matrix plus 19 finite/six script original/generated outcomes matches
the committed CHSEM-025 evidence on both pins. The 95-test ClickHouse-only offline
gate also passes. No further feature changes are planned for this candidate.

## CHSEM-024 result

Additive statement/script `WithProvenance` APIs report each walked node as native
conditional proof, generic fallback or unknown using the native proof pass's own
facts. Type spellings/IDs form an external analysis-time snapshot, not serialized
AST metadata. Copy/serde/new calls do not transfer it; mutation requires reanalysis.
Conditional types still coexist with errors and explicit script settings remain
isolated. Existing entrypoints, result constructors and generic typing are unchanged.
Guide: `vendor/data/clickhouse-semantic/type-provenance-README.md`.

Full JVM/ABI regression passes 1,921 tests with 61 optional assumption aborts;
Python passes 139 with one opt-in skip. The sequential IntelliJ gate passes 757
tests on 261.25134.95. No new native typing rules or native captures in this round.
Commit/push separately, then perform the authorized CHSEM-025 round.

## CHSEM-025 result

Standalone primitive `*` and resolved unqualified `multiply` now reuse native
integer/floating promotion and ordinary nullable dispatch, including supported
nested, schema, callback, script and derived-relation propagation. Unsupported
overloads stay unknown; generic typing and existing contextual Decimal contracts
are preserved. G01/G02 now have native-conditional operation proofs. Their old
captures remain unchanged; `multiplication-refinements.json` records stronger facts.
Guide: `vendor/data/clickhouse-semantic/multiplication-README.md`.

Both pins match 1,440 independently captured pair types and 19 finite/six script
original/generated outcomes. Version-dependent control errors stay separate from
successful type matches. Full JVM/ABI regression passes 1,926 tests with 61 optional
assumption aborts; Python passes 143 with one opt-in skip. All three frontend
reports regenerate byte-identically. The sequential IntelliJ gate passes 757
tests on 261.25134.95; other IDE versions were not run. Commit/push separately,
then perform the authorized CHSEM-034 release-preparation portion. No P2 expansion.

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
- [x] CHSEM-004: Implement composition argument validation. Check stage argument counts, placeholder requirements and provable input incompatibilities using native-backed rules. Diagnose bare variadic designators such as `trim | upper` without rejecting the valid `trim(_) | upper` form. Do not infer runtime array-length equality from matching argument counts.
- [x] CHSEM-005: Infer composition and arrayMap results. Propagate stage results through supported chains and compute array element types without inventing precision, nullability or overload facts. Track incomplete knowledge explicitly and preserve existing generic/dialect typing behavior.
- [x] CHSEM-006: Expose positioned offline semantic diagnostics through the shared library. Keep semantic results separate from parser acceptance; distinguish proven errors from unavailable knowledge. Test statement/script offsets and consumer-facing integration without adding new internal or experimental IntelliJ API calls.
- [x] CHSEM-007: Qualify and document the bounded slice. Add self-contained positive/negative, AST serde, scope/lineage, typing and diagnostic tests; compare original/generated finite results and errors against pinned native fixtures. Run affected modules, full regression and binary-compatibility gates, record skips, and document exact coverage and remaining unknowns.

## Follow-up scope, not part of the first slice

- [x] CHSEM-008: Extend proven contracts to common array/map higher-order functions. Prioritize arrayFilter, arrayExists, arrayAll, arraySort and mapApply; account for their different argument binding and result rules. Establish separate native fixtures for each added family.
- [x] CHSEM-009: Expand composite and overload typing. Research Nullable, LowCardinality, tuples, numeric promotion, decimal precision/scale, constant-dependent rules and settings/version boundaries. Add only measured rules; retain explicit unknowns for UDFs and unsupported overloads. Establish exact native literal-array element types before using generic integer hints in contextual lambda validation.
- [x] CHSEM-010: Assess cross-dialect lowering separately. Identify which supported compositions can be rewritten as explicit lambdas on each target, gather paired result/error evidence and retain refusal for unproved conversions. No conversion is authorized merely by completing local validation.
- [x] CHSEM-011: Fix the ClickHouse parser gap for parenthesized explicit lambda operands inside composition. Native 26.10 accepts `(x -> toString(x)) | (y -> toTypeName(y))`; Brikk previously created JSONExtract nodes. CHSEM-003-E26 retains that historical boundary. Structured parsing, source/serde/scope tests and native original/generated checks now cover the named form without changing other dialects' JSON arrows. Unresearched bodies remain unknown and cross-dialect lowering remains refused.

## Arithmetic follow-ups

- [x] CHSEM-012: Infer exact setting-aware integer division results. Use pinned source/native rules for `/`, `divide` and explicit `divideExact`, including width, signedness and supported nullability. Preserve query-local setting inheritance, caller typing policies and generic literal parity; use the explicit native typing entrypoint for native literal widths.
- [x] CHSEM-013: Add offline diagnostics for provable division-by-zero and integer quotient overflow. Keep value-dependent/runtime cases unknown; reuse the exact type/mode facts from CHSEM-012 without claiming whole-query acceptance.
- [x] CHSEM-014: Track explicit preceding `SET` statements in script analysis with clear scope/reset rules. Do not infer external connection state. Record unknown or parameterized assignments explicitly and preserve query-local overrides.
- [x] CHSEM-015: Infer exact Decimal/Decimal and Decimal/integer division precision and scale from pinned source/native evidence. Cover supported nullable dispatch and mode differences; preserve unknowns outside the proved overloads. User authorized CHSEM-013–015 in order, with tests and a separate commit/push for each.
- [x] CHSEM-016: Add positioned Decimal-division diagnostics for proved zero divisors, invalid scale dispatch and scale overflow under explicit decimal_check_overflow settings. Reuse CHSEM-015 facts, account for NULL masking and guard order, preserve conditional types and unknowns, capture exact-pin native witnesses, then test/commit/push separately.
- [x] CHSEM-017: Diagnose proved Decimal runtime overflow from operand narrowing, multiplier squaring and numerator scaling. Require exact constant/dispatch facts and explicit decimal_check_overflow=true, preserve NULL masking, error order and conditional result types, capture native controls, then test/commit/push separately.
- [x] CHSEM-018: Add an explicit script-aware native typing entrypoint. Reuse shared script framing, parsing, diagnostics and preceding SET state; return independently owned typed ASTs with positioned per-statement reports. Do not infer script/session execution from semicolons or editor files, change existing statement API behavior, rewrite authored settings or guess external state. Preserve signatures, add native witnesses, then test/commit/push separately.
- [x] CHSEM-019: Add exact native scalar-literal projection types and nullability to native-only statement/script APIs. Research integer/sign/float boundaries, strings, Boolean and NULL constants; preserve generic annotateTypes policy, arithmetic/NULL-dispatch boundaries, source/serde/caller ownership and signatures. Capture pinned native comparisons, run gates, then commit/push separately and present the next option.
- [x] CHSEM-020: Add bounded native integer addition/subtraction result typing for operators and resolved plus/minus builtins. Prove width/signedness/nullability through pinned source/native matrices, including 64/128/256-bit boundaries and conditional overflow outcomes. Preserve generic typing, resolution, NULL/unsupported overload boundaries, signatures and source/serde; test, commit/push separately, then present the next option.
- [x] CHSEM-021: Extend native-only addition/subtraction typing to Float32/Float64, supported mixed integers and nullable inputs. Prove promotion and wide-integer refusal boundaries with tagged source/native evidence, preserve generic typing and existing ownership/resolution/NULL contracts, then test/commit/push separately. Present an overall coverage/delivery summary and the next recommended option afterward.
- [x] CHSEM-022: Fix scientific-number token boundaries around compact +/- expressions such as 1e0+2.0. CHSEM-021 found the shared lexer can consume the binary sign into the first token. Preserve the captured counterexample, qualify a scoped fix with parser/type/source/serde/native controls, and keep generic parity/ABI unchanged rather than weakening ledgers. Authorized as a separate round after CHSEM-021; commit/push before CHSEM-023.
- [x] CHSEM-023: Consolidated qualification and closeout. Freeze the supported scope, exercise the public statement/script APIs together, rerun regression/native/ABI gates, and report concrete missing coverage and delivery work for the user's final decision. Defer further feature expansion. Commit/push separately after CHSEM-022; no merge/publication/consumer changes implied.

Closeout observation to retain, not expand during CHSEM-022: a root multiplication
such as `SELECT 1e0*(2e0+3.0)` parses correctly after the lexer fix but can retain
the generic `Nullable(Float64)` annotation instead of native `Float64`. Existing
multiplication proofs are contextual rather than a complete scalar-root contract.
CHSEM-023 must include this concrete fallback in the remaining-coverage report.

The earlier eleven items remain complete. These are fixes to pursue, not merely
documentation caveats. Keep settled boundaries in their guides and these TODOs;
do not repeatedly surface them as new warnings or block unrelated work.

## Constraints and resumption

- User authorized the last two items, CHSEM-010 and CHSEM-011, in order, with a separate commit/push between them and after CHSEM-011.
- CHSEM-001 through CHSEM-023 are complete and pushed. The user accepted the bounded opt-in scope on 2026-11-02. CHSEM-024 through CHSEM-034 track deferred improvements and delivery; none is started or authorized for implementation. No further expansion, merge, publication or consumer adoption without approval.
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

## CHSEM-004 result

Internal checker: `brikk-sql/src/dev.brikk.house.sql/optimizer/ClickhouseCompositionValidation.kt`.
Evidence, precise limits and reproduction:
`vendor/data/clickhouse-semantic/composition-README.md`.
Read-only reports separate proven intrinsic argument errors from unresolved checks,
without an accepted/isValid flag. Checks cover left variadic designators, unary
right operands, arrayMap arity, placeholder bounds/conventions/depth/member access,
non-callable operands, native-backed call counts and known input incompatibilities.
Family-only propagation can prove a later-stage mismatch without computing widths
or changing AST result types. Schema/cast/literal-family evidence is kept separate
from generic annotation guesses.

Resolution respects potentially shadowed names, unknown/foreign schemas, body
aliases, SQL query boundaries and alias/column collisions. NULL/wrappers, incomplete
constness, timezone values, unsupported contexts and CHSEM-011 remain unknown.
WITH definitions may be unused; they are not eagerly diagnosed as executed calls.
Array-length equality is never inferred from parameter counts. URL FixedString
runtime restrictions do not become blanket type errors against empty-array queries.
Only the exact 26.10.2.103 semantic target is enabled; other versions remain unknown.

CHSEM-004-E01 through E50 have parser and original execution evidence on both exact
digest pins, with zero execution skips. Self-contained gates verify that no native
success gets a static error, and every reported rule failure matches a native error
class. Unknown native failures are not relabeled as successful validation. Finding
identity, read-only AST/SQL behavior, cancellation and bounded deep analysis are tested.

Validation: full `./kotlin test` passed 1,799 tests with 61 optional integration
assumption aborts and zero failures. The complete Python tool suite passed 78 tests
with one opt-in Toolchain-model skip, using the unchanged temporary SQLGlot pin;
the ClickHouse-only suite passed 30 tests. CHSEM-001 and CHSEM-003 Brikk captures are
byte-identical to their saved versions. Historical fixtures/ledgers/native/source
evidence, public JVM signatures, IntelliJ API use, release versions and consumer
dependencies are unchanged. Custom IntelliJ platform suites were not run.
This is the internal core check; public positioned diagnostics remain CHSEM-006.
Commit/push this round, then stop before CHSEM-005.

## CHSEM-005 result

The callable analysis now has a read-only exact-result mode; TypeAnnotator applies
its proofs through the existing ClickHouse contextual walk. Details and evidence:
`vendor/data/clickhouse-semantic/result-README.md`.
Supported string chains feed later-stage argument types and arrayMap results;
primitive multiplication follows the source-pinned native rule, not generic SQLGlot
widening. Function values keep UNKNOWN in the AST and have separate callable-result
facts. Identity copies known nullability, precision/scale, widths and containers.
Unknown numeric widths are not invented even when a final String can be proved.

Nested maps, captures/shadowing, derived-scope reuse, source/SQL/serde stability,
caller overrides, partial policies, overwrite=false and proof-cache invalidation
are covered. Decimal multiplication, nullable conversions, unsupported wrappers,
missing facts/scopes, unproved arithmetic and CHSEM-011 remain unknown. Known result
types do not erase runtime length, timezone or URL FixedString execution unknowns.
The ordinary CHSEM-004 validation report remains byte-identical.

CHSEM-005-E01 through E40 have original type-witness/error observations on both pins,
with no execution skips. All 100 ordered pairs of the ten supported primitive
numeric types match independently captured engine result types on both pins.
Two improved CHSEM-003 input assertions are recorded in a separate refinement file,
supported by their existing native argument witnesses. Historical input fixtures,
annotation snapshots and native/source evidence are not overwritten or relabeled.

Validation: full `./kotlin test` passed 1,811 tests with 61 optional integration
assumption aborts and zero failures. The complete Python tool suite passed 83 tests
with one opt-in Toolchain-model skip using the unchanged temporary SQLGlot pin;
the ClickHouse-only suite passed 35 tests. Existing corpus fixtures, ledgers and
comparison rules are unchanged. Public JVM signatures, IntelliJ API use, release
versions, publication and consumer dependencies are unchanged. Custom IntelliJ
platform suites were not run. Commit/push this round, then stop before CHSEM-006.

## CHSEM-006 result

The shared diagnostics package now exposes `clickhouseSemanticDiagnostics` and
`clickhouseScriptSemanticDiagnostics`. Reports separate parser failures, proven
errors with stable rule/native-category codes, and positioned unknown reasons.
All offsets are UTF-16 in the authored script. Exact engine versions are required;
missing knowledge is not an error and empty findings never certify validity.
Details and consumer example: `vendor/data/clickhouse-semantic/diagnostics-README.md`.

Native parser acceptance/rejection now suppresses only parser findings, preserving
semantic, scope and consumer checks. Existing IntelliJ diagnostic hooks can render
the appended OFFLINE_SEMANTIC source with a distinct message. The platform contract
uses the public API through an existing hook; no automatic plugin activation or
unproved server-version inference was added. No new internal/experimental IntelliJ
API calls, profile methods, consumer pins or sibling-repository changes were added.
Old diagnostic constructors/copy signatures and source ordinals are retained.

The public 50-case report matches CHSEM-004's historical rules and unknown reasons.
Tests cover statement/script offsets with CRLF, surrogate pairs, repeated names,
inline INSERT data, lexical/parser failures, unsafe boundaries, schema facts,
version gating, cancellation and consumer-native outcome combinations. CHSEM-004
and CHSEM-005 rule/result/matrix captures remain byte-identical; no historical
native/source evidence, corpus fixture, ledger or comparison rule changed.

Validation: full `./kotlin test` passed 1,826 tests with 61 optional integration
assumption aborts and zero failures. The affected IntelliJ NativeErrorAnnotatorTest
suite passed all 33 tests on 261.25134.95; other IDE suites/versions were not run.
The complete Python tool suite passed 86 tests with one opt-in Toolchain-model
skip using the unchanged temporary SQLGlot pin; ClickHouse-only discovery passed
38 tests. Existing native captures back the same rules, with no new native execution
or qualification claim this round. No release version, publication or toolchain
floor changed. Commit/push this round, then stop before CHSEM-007.

## CHSEM-007 result

The first slice is qualified together by twelve self-contained vectors and an
integrated serde/type/scope/public-diagnostic test, plus physical capture/array
lineage after annotation and serde. Both exact pins passed original/generated
formatting and all twelve finite result/error assertions, with no execution skips.
Known type plus runtime failure and successful execution plus unknown result are
explicit controls. No new semantic rules or production behavior were added.
Coverage and remaining limits: `vendor/data/clickhouse-semantic/qualification-README.md`.

Validation: full JVM suite passed 1,828 tests with 61 optional integration aborts
and zero failures, including binary compatibility. Full IntelliJ platform suite
passed 757 tests on 261.25134.95; other IDE versions were not run. Complete Python
suite passed 87 tests with one opt-in Toolchain-model skip using the unchanged
temporary SQLGlot pin; ClickHouse-only discovery passed 39. Historical evidence,
corpus rules, public signatures, IntelliJ API usage, releases and consumer pins are
unchanged. Commit/push CHSEM-007 separately, then continue authorized CHSEM-008.

## CHSEM-008 result

Source-pinned contracts now distinguish arrayFilter, arrayExists, arrayAll,
arraySort and mapApply from arrayMap. The contextual walk and checker share call
shapes; predicate, callable-count, map-input and map-result errors have public rule
codes. Filtering/sorting preserve the first array, exists/all return UInt8, and
single-map mapApply binds key/value or tuple parameters and rebuilds its result.
Plain numeric/string comparisons have native UInt8 results. Missing evidence,
nullable predicates, tuple-array unpacking, wrappers and unresearched families stay
unknown. Caller policies, caches and function-valued UNKNOWN slots are preserved.
Details: `vendor/data/clickhouse-semantic/higher-order-README.md`.

Eighteen family-specific original/generated finite queries pass on both digest
pins, with no execution skips. Tagged source excerpts/hashes back binding, predicate
and return rules. CHSEM-003-E23's nested parameter improvement is separately recorded;
old snapshots are not overwritten. CHSEM-004 rule and CHSEM-005 result reports remain
byte-identical. Existing corpus fixtures and comparison rules are unchanged.

Validation: full JVM regression passed 1,831 tests with 61 optional integration
aborts and zero failures, including binary compatibility. Affected IntelliJ native
and relation diagnostic suites passed 42 tests on 261.25134.95; full IDE regression
was run in CHSEM-007, not repeated here. Complete Python suite passed 88 tests with
one opt-in Toolchain-model skip using the unchanged SQLGlot pin. No new IntelliJ
APIs, old public constructor changes, release versions, publication or consumer
dependency edits. Commit/push separately, then continue authorized CHSEM-009.

## CHSEM-009 result

Added the explicit pin-aware `annotateClickhouseSemanticTypes` entrypoint. Native
integer literal widths, bounded common types, nullable scalar results, String/
FixedString LowCardinality input removal, typed tuple unpacking and two-Decimal
multiplication use tagged implementation guards. Standard annotateTypes literal
policy and all corpus fixtures, ledgers and comparison rules stay unchanged.
Missing native call/binding proofs stay UNKNOWN in the new entrypoint, not generic
container guesses. Details: `vendor/data/clickhouse-semantic/overload-README.md`.

Mixed Decimal/integer and Decimal/Float overloads, excessive decimal scales, numeric
dictionary settings, unsafe joins/casts, constructor NULL dispatch and unsupported
return wrappers remain unknown. Known predicates alone cannot prove an ordinary
Boolean result without Array input evidence. Whole-call NULL handling is distinct
from nullable lambda results and composition operand rules. Overflow, lengths,
timezone values and unresearched constant rules are not execution certification.

Thirty-three original/generated finite witnesses pass on each exact pin, with no
execution/version skips. Sixteen integer boundary observations per pin independently
match Kotlin. Historical current assertions change only for CHSEM-004-E41 and
CHSEM-005-E15/E16, explicitly recorded in overload-refinements.json and supported by
their unchanged native captures. The E23 explanation is corrected to distinguish
the native-valid length predicate from an invalid identity-predicate unit control.
Historical snapshots and native evidence remain intact.

Validation: full JVM regression passed 1,836 tests with 61 optional integration
assumption aborts and zero failures, including binary compatibility. Full IntelliJ
regression passed 757 tests on 261.25134.95, followed by final affected native/relation
diagnostic controls passing 42 tests. Other IDE versions were not run. Complete
Python discovery passed 91 tests with one opt-in Toolchain-model skip using the
unchanged temporary SQLGlot pin. No new IntelliJ API calls, old public signatures,
toolchain floors, release versions, publication or consumer dependencies changed.
Commit/push CHSEM-009 separately, then stop before CHSEM-010.

## CHSEM-010 result

Assessed eight explicit-lambda research candidates on DuckDB 2.0.0, Trino 484 and
Doris 4.1.5, with original/reference execution on both exact ClickHouse pins.
The captures retain versions, image/JDBC hashes, ordered String/NULL values and
raw errors. Unicode differs on all targets; numeric width and padding differ on
DuckDB/Trino. Doris's matching numeric witness and length error are not universal
equivalence evidence. Both-error is never labeled equivalent. ASCII, trim, nullable
and repeated pure-stage matches remain finite controls only.

No production lowering was enabled. Refusal remains for all composition/implicit
placeholder candidates, including invalid, unresolved, volatile and captured forms.
Evaluation count, scope hygiene, wrappers, settings and URL-function semantics
still need separate proofs for any future converter. Details and reproduction:
`vendor/data/clickhouse-semantic/lowering-README.md`.

Validation: all 24 target candidate grammars parse, but execution captures include
the expected differences. Full JVM regression passed 1,840 tests with 61 optional
integration aborts and zero failures, including binary compatibility. Complete
Python suite passed 93 tests with one opt-in Toolchain-model skip; ClickHouse-only
discovery passed 45. No production/IDE code, public signatures, corpus/hazard/
certification rules, releases or consumer pins changed. Platform suites were not
repeated. All owned probe containers were removed; startup failures are not treated
as semantic evidence. Commit/push separately, then continue CHSEM-011.

## CHSEM-011 result

Parenthesized explicit ClickHouse lambda operands now reuse the existing lambda
header/parser binding machinery instead of becoming JSONExtract nodes. Only the
ClickHouse paren-item hook opts in; other dialects retain ordinary expression/JSON
arrow parsing. Existing public parser JVM signatures remain available. Captures,
shadowing, tuple fields, multi-parameter stages, nested parentheses, mixed operands,
UTF-16 script positions, source origins, serde, typing and diagnostics are covered.
Function values remain UNKNOWN and unresearched bodies are not accepted by inference.

Thirteen original/generated finite execution vectors and two syntax negatives pass
on both exact pins, with no execution/version skips. The 26.8 syntax rejections are
explicit controls; its zero-eligible generated-format batch is typed, not a false
parser acceptance. New refinements name bindings E26; rule/knowledge E29/E32/E46;
and result/generated-SQL E32/E33. Existing native witnesses support the stronger
current types. Historical fixtures, flags, raw captures, corpus ledgers and exact
comparison rules remain intact. Details: paren-lambda-README.md in the semantic data
folder. CHSEM-010's production cross-dialect refusal is unchanged.

Validation: full JVM regression passed 1,846 tests with 61 optional integration
assumption aborts and zero failures, including binary compatibility. Full IntelliJ
platform regression passed 757 tests on 261.25134.95; other IDE versions were not
run. Complete Python discovery passed 96 tests with one opt-in Toolchain-model skip
using the unchanged SQLGlot pin; ClickHouse-only discovery passed 48. No new IDE API
calls, old public signature removals, toolchain floor, release, publication or
consumer dependency changes. Commit/push separately, then stop; all eleven IDs are
complete within their documented bounds.

## CHSEM-012 result

The exact 26.10.2.103 type pass now covers `/`, resolved `divide` and explicit
`divideExact`: first-operand integer width, signedness from either integer operand,
Int/UInt8 through 256 bits, supported nullable propagation and the floating delegate.
It applies to declared/schema expressions, nested setting scopes and lambda bodies.
The standard literal policy, caller overrides, overwrite=false, caches, SQL/serde
and old public signatures remain intact. The explicit native entrypoint supplies
native literal widths. Division uses its own type rules, without widening other
scalar contracts. Guide: `vendor/data/clickhouse-semantic/division-README.md`.

Independent native observations match all 1,372 matrix types. Twenty-two original/
generated value/error cases pass on each digest pin, including availability controls
on 26.8, negative/mixed-width values, nullability, nested settings, zero and overflow.
Conditional types do not become execution verdicts or unimplemented diagnostics.
Tagged source excerpts/hashes establish the rule; older captures and corpus/ledger
files remain unchanged. The earlier cast-Int64 regression is strengthened from
UNKNOWN to Int64 using its existing native witness.

Validation: full JVM suite passed 1,852 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility. Full IntelliJ suite passed
757 tests on 261.25134.95; other IDE versions were not run. Complete Python discovery
passed 99 tests with one opt-in Toolchain-model skip using the unchanged SQLGlot pin;
ClickHouse-only discovery passed 51. Reports regenerate byte-identically. No new
IntelliJ API calls, toolchain/release/publishing or consumer dependency changes.
Commit/push this round. CHSEM-013/014 retain the remaining work without reopening
the earlier eleven items or repeatedly surfacing their settled boundaries.

## CHSEM-013 result

Added positioned `DIVISION_BY_ZERO` and `INTEGER_DIVISION_OVERFLOW` findings with
native code 153, sharing CHSEM-012's mode, resolution and exact type facts. Bounded
constant evaluation accepts UInt64-range numeric literals, signs, parentheses and
lossless integer casts, and checks first-operand result bounds through 256-bit
declarations. Nullable NULL masking, dynamic values, wrapping/string casts and
floating delegates do not become false integer errors. Conditional types remain
known independently of errors. Existing public signatures and UTF-16/parser/source
handling stay intact. Details: `division-diagnostics-README.md` under the evidence folder.

Nineteen original/generated native vectors pass on each exact pin with no execution
skips. Historical CHSEM-012 files are unchanged; a named refinement records the new
E13–E15 findings. Full JVM regression passes 1,856 tests, with 61 optional integration
assumption aborts and zero failures. Complete Python discovery passes 101 tests with
one opt-in skip. The isolated IntelliJ gate passes 757 tests on 261.25134.95.
The first concurrent build attempt failed on a missing SqlScriptSnapshot class;
the isolated retry passed without production changes. Run build gates sequentially.
No release, dependency, IntelliJ API or consumer changes.

## CHSEM-014 result

The script diagnostics API carries bounded preceding SET facts within one analysis
call, conditionally on successful sequential execution. New calls and the standalone
statement API never inherit external state. Query-local SETTINGS and nested overrides
shadow the script facts without leaking into later statements. Unknown/parameterized
assignments, DEFAULT resets, profile/compatibility changes and unsupported mutations
remain explicitly unknown. Native reset ordering is preserved for duplicate entries.
No AST, source/type metadata, connection or old public signature changes.
Guide: `vendor/data/clickhouse-semantic/script-settings-README.md`.

Eighteen original/generated whole-session scripts match on both exact pins. Source
captures establish parameter handling, session vs query contexts and reset order.
Full JVM regression passes 1,860 tests, with 61 optional integration assumption aborts
and zero failures; Python passes 103 tests with one opt-in skip. The isolated IntelliJ
gate passes 757 tests on 261.25134.95. No new IntelliJ API calls or signature changes.
Historical evidence
and corpus gates are unchanged. CHSEM-015 remains the authorized next round.

## CHSEM-015 result

Added exact bounded Decimal/Decimal and both Decimal/integer result typing for /,
resolved divide and divideExact. Two Decimals use wider storage precision and first
scale; mixed integer/Decimal preserves the Decimal's declared precision and scale.
Supported nullable propagation, schema/derived/lambda contexts and integer-mode
independence are covered. Setting-dependent scale-overflow guards reuse local/script
facts without guessing defaults. Unsupported dry-run scale dispatch, uncertain casts,
constant NULL and wrappers remain unknown. Existing public signatures, literal
policy, caller overrides, SQL/source/serde and corpus gates stay intact.
Guide: `vendor/data/clickhouse-semantic/decimal-division-README.md`.

All 1,368 independent native matrix types match; 22 original/generated finite cases
per exact pin match values/errors without execution skips. A named refinement
strengthens CHSEM-012-E19 to Decimal(18,3) without replacing old captures. Matrix,
fixture and refinement reports regenerate byte-identically. Full JVM regression
passes 1,864 tests, with 61 optional integration assumption aborts and zero failures.
Python passes 106 tests with one opt-in skip; ClickHouse-only discovery passes 58.
The isolated IntelliJ gate passes 757 tests on 261.25134.95. No new IntelliJ API
calls or public signature changes. No toolchain, release, dependency or
consumer changes. This completes the requested three-item batch.

## CHSEM-016 result

Added `DECIMAL_DIVISION_SCALE` with native category 69 and
`DECIMAL_DIVISION_SCALE_OVERFLOW` with category 407, plus Decimal zero findings
through the existing `DIVISION_BY_ZERO` category 153. The checks reuse resolved
CHSEM-015 type facts and query/script overflow settings. Return-type overflow is
checked before NULL masking; later scale-factor and zero failures require their
own dispatch/value proofs. Narrowing, multiplier squaring and numerator scaling
are checked before reporting zero. Invalid casts, uncertain values/settings and
unsupported dispatch remain unknown. Known conditional types survive runtime
zero errors. UTF-16/source/serde/parser separation and public signatures stay intact.
Guide: `vendor/data/clickhouse-semantic/decimal-diagnostics-README.md`.

Forty-seven original/generated finite witnesses plus eight original/generated
whole-session scripts pass on each exact pin, without execution/version skips.
Every reported failure matches its own native category and no native success is
flagged. Source guard captures are tagged/hashed; five CHSEM-015 findings are
strengthened in a named refinement without replacing old evidence or types.
Full JVM regression passes 1,869 tests with 61 optional integration assumption
aborts and zero failures. Python discovery passes 110 tests with one opt-in skip.
Fixture, script and refinement reports regenerate byte-identically. No toolchain,
release, dependency, consumer or IntelliJ API changes. ClickHouse-only Python
discovery passes 62 tests; the isolated IntelliJ gate passes 757 on 261.25134.95.
Other IDE versions were not run. Commit/push this round, then stop.

## CHSEM-017 result

Added native-category-407 runtime rules for proved Decimal operand narrowing,
integer/Decimal multiplier squaring and numerator scaling overflow, using the
existing bounded constant proof and explicit query/script decimal_check_overflow=true.
Type/NULL dispatch precedes narrowing, multiplier, numerator and divisor checks.
Signed physical storage bounds are preserved, including minimum negative values.
Tagged Decimal128/256 multiplication wraps without an implemented overflow check;
those cases remain unproved and have native success controls rather than false
errors. Known conditional types, read-only UTF-16 diagnostics, parser combination,
serde/source metadata, public signatures and caller policy remain intact.
Guide: `vendor/data/clickhouse-semantic/decimal-runtime-README.md`.

Both pins pass 56 original/generated finite outcomes and eight original/generated
complete-session scripts, without execution/version skips. Every new runtime
finding matches native DECIMAL_OVERFLOW and no native success is flagged. Source
captures include the big-integer multiplication limitation. Historical CHSEM-016
captures stay intact; named refinements strengthen E24/E25/E46. Finite, session and
refinement reports regenerate byte-identically. Full JVM regression passes 1,875
tests with 61 optional integration assumption aborts and zero failures; Python
passes 115 tests with one opt-in skip. The first JVM attempt hit a temp-directory
quota during DuckDB library extraction; a complete rerun with a task-private temp
directory under build/ passed without dropping tests or changing gates. No release,
toolchain, dependency, consumer or IntelliJ API changes. ClickHouse-only Python
discovery passes 67 tests; the isolated IntelliJ gate passes 757 on 261.25134.95.
Other IDE versions were not run. Commit/push this round, then stop.

## CHSEM-018 result

Added explicit `annotateClickhouseScriptSemanticTypes` and the two-field
`ClickhouseScriptTypeAnnotation` result. Callers choose script processing; no mode
is inferred from semicolons, editor files or prior API calls. A shared traversal
frames/parses statements, advances CHSEM-014 SET facts and produces the existing
positioned diagnostics. Fresh annotators receive the same immutable incoming facts
and return owned ASTs without injecting settings or rewriting SQL/source metadata.
Statement APIs, generic literal policy, schema/connection state and old JVM
signatures stay unchanged. The new result shape is frozen in BinaryCompatibilityTest.
Guide: `vendor/data/clickhouse-semantic/script-typing-README.md`.

Twenty-four original/generated whole-session type/value/error vectors pass on each
exact pin, with no execution/version skips. Successful known types agree with
native toTypeName; runtime failures retain conditional types independently of
their diagnostics. New typing output and historical CHSEM-014/016/017 script
captures regenerate byte-identically. Unit gates cover caller mode selection,
fresh state, overrides, reset/parameter unknowns, schema/lambda/derived queries,
parse/framing failures, inline data, UTF-16 positions, ownership, serde and cancellation.
Full JVM regression passes 1,884 tests with 61 optional integration assumption
aborts and zero failures. Python passes 118 tests with one opt-in skip using the
unchanged SQLGlot pin. No corpus/ledger, release, toolchain, consumer or IntelliJ
API changes; no IDE callsite is silently switched to script processing.
ClickHouse-only Python discovery passes 70 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. Final log verification
was briefly blocked by a tool-location shutdown; access was restored and the saved
passing log verified before committing. Commit/push this round, then stop.

## CHSEM-019 result

Native-only statement/script APIs now type scalar literal projections with exact
integer sign/width, finite Float64 fallback, String/Bool non-nullability and
Nullable(Nothing), including alias/parenthesis wrappers and direct literal AST roots.
Tagged parser rules establish integer -0 normalization and large-number fallback.
Unsupported non-finite/overlong numeric tokens remain UNKNOWN. Arbitrary argument,
arithmetic, compound-unary, container and NULL-dispatch proofs are not expanded.
Generic annotateTypes, other dialects, caller overrides, overwrite/cache policy,
SQL/source/serde and public signatures remain intact. Guide:
`vendor/data/clickhouse-semantic/projection-literal-README.md`.

All 84 independent matrix types match on both pins. Forty-four original/generated
finite value/type/error cases and six original/generated whole-session scripts
pass without execution/version skips, including native syntax rejections and
exact partial stdout before an error. The probe extension requires an authored
stdout expectation; existing error fixtures still require empty stdout by default.
New matrix/finite/script reports and historical CHSEM-018 script typing output
regenerate byte-identically. Source excerpts are tagged/hashed. Full JVM regression
passes 1,891 tests with 61 optional integration assumption aborts and zero failures.
Python passes 123 tests with one opt-in skip using the unchanged SQLGlot pin. No
corpus/ledger, toolchain, release, publication, dependency or IntelliJ API changes.
ClickHouse-only Python discovery passes 75 tests; the isolated IntelliJ gate passes
757 tests on 261.25134.95. Other IDE versions were not run. Commit/push this round,
then present the next option for approval rather than starting it automatically.

## CHSEM-020 result

Native-only statement/script APIs now infer exact integer +/- and resolved
unqualified plus/minus widths, signedness and supported nullability through 256 bits.
Width promotion stops at 64 bits rather than auto-widening into bigger integers;
subtraction is always signed. Schema/derived/lambda/chained contexts and division
consumption reuse the same facts. Unknown/NULL/unsupported overloads and unresolved
names stay unproved. Generic typing, caller-owned policies, overwrite/cache behavior,
read-only diagnostic coverage, function-valued AST policy, source/serde and signatures
remain intact. Guide: `vendor/data/clickhouse-semantic/integer-add-sub-README.md`.

Both exact pins match all 2,304 independently observed operator/builtin and nullable
pair types. Thirty original/generated finite value/type/error cases and eight whole-
session scripts pass with no execution/version skips. Wraparound values retain
their native result types without fabricated overflow errors. Tagged source guards
and two named unit refinements record the stronger facts; historical captures remain
unchanged. New reports and historical CHSEM-018 typing output regenerate byte-
identically. Full JVM regression passes 1,899 tests with 61 optional integration
assumption aborts and zero failures. Python discovery passes 128 tests with one
opt-in skip using the unchanged SQLGlot pin. No corpus/ledger, release, toolchain,
publication, dependency, consumer or IntelliJ API changes. ClickHouse-only Python
discovery passes 80 tests; the isolated IntelliJ gate passes 757 on 261.25134.95.
Other IDE versions were not run. Commit/push this round, then present the next
option for approval rather than starting it automatically.

## CHSEM-021 result

Native-only statement/script APIs now infer Float64 for Float32/64 +/- and supported
mixed Int/UInt8–64 inputs, with ordinary nullable propagation. Int/UInt128/256 mixes
remain UNKNOWN, backed by native category-43 rejection controls, not blanket Double
conversion. Operators and resolved unqualified plus/minus share the rules across
schema/derived/lambda/chained/script contexts. Integer/generic typing, caller ownership,
resolution, NULL/unsupported overload boundaries, source/serde and signatures remain
intact. Types do not imply exact/finite values or execution success. Guide:
`vendor/data/clickhouse-semantic/floating-add-sub-README.md`.

Both exact pins match 576 independent accepted types, 256 independent rejection
controls, 31 original/generated finite outcomes and eight whole-session scripts,
without execution/version skips. Native rounding, inf and nan outcomes are retained
without fabricated runtime errors. Tagged source captures and a named CHSEM-020-E24
refinement preserve old evidence. New reports, the refinement/parser counterexample,
the integer matrix and historical CHSEM-018 typing output regenerate byte-identically.
Full JVM regression passes 1,907 tests with 61 optional integration assumption aborts
and zero failures; Python passes 133 tests with one opt-in skip, using the unchanged
SQLGlot pin. No corpus/ledger, release, toolchain, publication, consumer or IntelliJ
API changes.

A separate lexer gap was captured: compact scientific +/- can merge a sign into
the numeric token or yield a Dot AST despite valid native Float64 arithmetic.
The spaced form works; CHSEM-022 is proposed, not authorized or implemented here.

Overall, the original CHSEM-001–011 composition/lambda target and the approved
CHSEM-012–021 type/diagnostic/script extensions are implemented as bounded contracts,
not full ClickHouse analyzer parity. This work remains on clickhouse-semantic;
no merge, publication or consumer adoption was performed. The concrete proposed
next item is CHSEM-022, while broader overload/catalog/settings coverage and
delivery/integration qualification remain separate decisions.
ClickHouse-only Python discovery passes 85 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. Commit/push this round,
then present the overall status and proposed CHSEM-022 for approval.

## CHSEM-022 result

ClickHouse's native tokenizer config now enables scientific number boundaries:
exponent signs require an immediately preceding E/e, and exponent text cannot
consume another decimal point. Binary +/- after exponent digits becomes a separate
token. Raw generated/base tokenizer parity is unchanged. An additive copied policy
preserves all existing TokenizerConfig constructor/default signatures and builder
state. Token/AST/type/source/UTF-16/editor/cancellation/serde tests close both captured
counterexamples; their earlier captures are preserved with a named refinement.
Guide: `vendor/data/clickhouse-semantic/scientific-number-README.md`.

Both pins pass 22 original/generated value/type/parser witnesses without execution/
version skips. Tagged source excerpts and current reports are retained; current
reports regenerate byte-identically. Full JVM regression passes 1,913 tests with
61 optional integration assumption aborts and zero failures; Python discovery
passes 136 tests with one opt-in skip on the unchanged SQLGlot pin. No corpus or
ledger relaxation, public signature, release, toolchain, consumer or IntelliJ API
changes. The IntelliJ gate passes 757 tests on 261.25134.95; other IDE versions were
not run. ClickHouse-only Python passes 88 tests. Commit/push CHSEM-022 before the separate CHSEM-023 closeout; do not expand
further feature scope during qualification.

## CHSEM-023 result

The feature batch is frozen. The combined public API closeout corpus contains
18 scripts, with 12 qualified interactions and six explicitly deferred cases.
Six additional G01-G06 examples retain known generic fallback deviations and
intentional/unqualified unknowns. Original/generated whole-session outcomes match
recorded expectations on both exact digest pins without execution/version skips.
Guide and final-decision report: `vendor/data/clickhouse-semantic/closeout-README.md`.

The report distinguishes per-node proof/provenance and root-propagation gaps,
unqualified functions/overloads/type families, incomplete general semantic errors,
catalog/query/session context, cross-dialect lowering and delivery qualification.
For example, root `1*2` has frontend `Nullable(Int32)` versus native `UInt16`;
root `1e0*(2e0+3.0)` has frontend `Nullable(Float64)` versus native `Float64`.
These are recorded deviations, never counted as native-exact matches.

Full JVM/ABI regression passes 1,917 tests with 61 optional integration assumption
aborts and zero failures. Both new frontend reports regenerate byte-identically.
Python discovery passes 139 tests with one opt-in skip on the unchanged SQLGlot
pin; ClickHouse-only discovery passes 91 tests. Historical fixtures are replayed
against the current library; not every historical native Docker matrix was freshly
executed during closeout. No production code or public signature changes here.
The sequential IntelliJ gate passes 757 tests on 261.25134.95. Other IDE versions
were not run. CHSEM-022 was committed/pushed first as `148ebfb`.

Stop feature expansion after the separate CHSEM-023 commit/push. The next option
is the user's scope/integration decision, not another automatically started typing
round. No main merge/reconciliation, release, publication, consumer dependency
update or sibling-repository change has occurred or is implied.

References: `docs/research/clickhouse-2026-batch3.md`,
`docs/research/clickhouse-2026-batch4.md`,
`brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseHigherOrder.kt`,
`brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateTypes.kt`,
`brikk-sql/src/dev.brikk.house.sql/diagnostics/SqlDiagnostics.kt`.
