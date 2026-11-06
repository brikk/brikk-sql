# ClickHouse semantic tracker

## Baseline and contract

Native semantic APIs target exactly ClickHouse `26.10.2.103`.
`26.8.34.13` is a control, not another enabled semantic target. Engine digests
are in `vendor/data/clickhouse-2026-audit/engines.json`. SQLGlot parity remains
pinned to `a00dc2e5c489214bea53bacbbeba8713f21ebce1`. Shared IntelliJ qualification
uses `261.25134.95`; it does not qualify every consumer or IDE version.

This is opt-in type inference and separately proved error checking, not a full
ClickHouse analyzer. Unknown is not valid or invalid. Conditional types can
coexist with errors. Empty diagnostics never certify execution. `formatQuery`
verification remains parse-only. The name catalog supplies names, aliases and
kinds, not guessed overloads or UDF signatures.

IDs are stable. Never renumber, reuse or remove one. Each ID has one row,
including the bounded child CHSEM-030-A. Paths are repository-relative.

| State | Meaning |
|---|---|
| DONE | The bounded deliverable exists in the linked tests or source. |
| AUDIT | Existing support needs focused proof. |
| ADD | A selected deliverable still needs implementation or integration. |
| MONITOR | A version or client contract needs observation. |
| DEFER | Unscheduled expansion, with a reason in its row. |

## Implemented contracts

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| CHSEM-001 | DONE | Establish exact-pin composition fixtures with syntax, result and error controls. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseSemanticFixturesTest.kt`; `vendor/data/clickhouse-semantic/README.md` |
| CHSEM-002 | DONE | Define conservative callable facts with argument counts, alias policy and version gating. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseCallableFactsTest.kt`; `brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseCallableFacts.kt` |
| CHSEM-003 | DONE | Bind explicit and implicit lambda inputs from proved array element types. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseLambdaTypingTest.kt`; `vendor/data/clickhouse-semantic/lambda-input-README.md` |
| CHSEM-004 | DONE | Report proved composition argument errors separately from unresolved checks. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseCompositionValidationTest.kt`; `vendor/data/clickhouse-semantic/composition-README.md` |
| CHSEM-005 | DONE | Propagate supported composition and arrayMap result contracts without generic width guesses. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseResultFixturesTest.kt`; `vendor/data/clickhouse-semantic/result-README.md` |
| CHSEM-006 | DONE | Expose positioned statement/script semantic diagnostics separately from parser outcomes. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseSemanticDiagnosticsTest.kt`; `vendor/data/clickhouse-semantic/diagnostics-README.md` |
| CHSEM-007 | DONE | Qualify the bounded composition slice through public APIs, serde, scope and lineage. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseSemanticQualificationTest.kt`; `vendor/data/clickhouse-semantic/qualification-README.md` |
| CHSEM-008 | DONE | Add bounded arrayFilter, arrayExists, arrayAll, arraySort and mapApply contracts. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseHigherOrderContractsTest.kt`; `vendor/data/clickhouse-semantic/higher-order-README.md` |
| CHSEM-009 | DONE | Add native literal-array, wrapper, tuple and selected Decimal overload typing. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseOverloadTypingTest.kt`; `vendor/data/clickhouse-semantic/overload-README.md` |
| CHSEM-010 | DONE | Assess lowering candidates on DuckDB, Trino and Doris while retaining production refusal. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseLoweringAssessmentTest.kt`; `vendor/data/clickhouse-semantic/lowering-README.md` |
| CHSEM-011 | DONE | Parse parenthesized explicit lambda operands as lambdas rather than JSON extraction. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseParenthesizedLambdaTest.kt`; `vendor/data/clickhouse-semantic/paren-lambda-README.md` |
| CHSEM-012 | DONE | Infer exact setting-aware integer division types and supported nullable dispatch. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseDivisionTypingTest.kt`; `vendor/data/clickhouse-semantic/division-README.md` |
| CHSEM-013 | DONE | Diagnose proved integer zero divisors and quotient overflow with native category 153. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseDivisionDiagnosticsTest.kt`; `vendor/data/clickhouse-semantic/division-diagnostics-README.md` |
| CHSEM-014 | DONE | Carry explicit preceding SET facts within one script-analysis call. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseScriptSettingsTest.kt`; `vendor/data/clickhouse-semantic/script-settings-README.md` |
| CHSEM-015 | DONE | Infer bounded Decimal/Decimal and Decimal/integer division precision and scale. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseDecimalDivisionTest.kt`; `vendor/data/clickhouse-semantic/decimal-division-README.md` |
| CHSEM-016 | DONE | Diagnose proved Decimal zero, scale dispatch and scale-overflow failures in guard order. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseDecimalDivisionDiagnosticsTest.kt`; `vendor/data/clickhouse-semantic/decimal-diagnostics-README.md` |
| CHSEM-017 | DONE | Diagnose proved Decimal narrowing, multiplier and numerator overflow. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseDecimalRuntimeOverflowTest.kt`; `vendor/data/clickhouse-semantic/decimal-runtime-README.md` |
| CHSEM-018 | DONE | Return owned typed statement ASTs through an explicit script-aware entrypoint. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseScriptSemanticTypesTest.kt`; `vendor/data/clickhouse-semantic/script-typing-README.md` |
| CHSEM-019 | DONE | Infer native scalar projection literal widths, types and nullability. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseProjectionLiteralTypesTest.kt`; `vendor/data/clickhouse-semantic/projection-literal-README.md` |
| CHSEM-020 | DONE | Infer bounded integer plus/minus types through 256-bit declarations. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseIntegerAddSubTest.kt`; `vendor/data/clickhouse-semantic/integer-add-sub-README.md` |
| CHSEM-021 | DONE | Infer supported floating/mixed plus/minus types and retain wide-integer rejection controls. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseFloatingAddSubTest.kt`; `vendor/data/clickhouse-semantic/floating-add-sub-README.md` |
| CHSEM-022 | DONE | Keep binary signs after scientific exponent digits outside numeric tokens. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseScientificBoundaryTest.kt`; `vendor/data/clickhouse-semantic/scientific-number-README.md` |
| CHSEM-023 | DONE | Freeze bounded public API coverage with explicit deviations and unknown controls. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseSemanticCloseoutTest.kt`; `vendor/data/clickhouse-semantic/closeout-README.md` |
| CHSEM-024 | DONE | Expose per-node native conditional proof, generic fallback and unknown provenance. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseTypeProvenanceTest.kt`; `vendor/data/clickhouse-semantic/type-provenance-README.md` |
| CHSEM-025 | DONE | Prove primitive standalone multiplication and supported root/nested propagation. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseMultiplicationTest.kt`; `vendor/data/clickhouse-semantic/multiplication-README.md` |

## Deferred improvement backlog

Deferred rows link counterexamples or boundary tests, not implementations of
their proposed expansion. P1 provenance and multiplication are complete in
CHSEM-024/025. P2 work needs a concrete consumer requirement before expanding
the bounded contract. CHSEM-034 is delivery work, not a demand to complete P2.

| ID | State | Deliverable and remaining reason | Tests or source |
|---|---|---|---|
| CHSEM-026 | DEFER | Qualify Decimal addition/subtraction; precision, scale and dispatch are not yet proved. | `vendor/data/clickhouse-semantic/closeout-missing-cases.json`; `vendor/data/clickhouse-semantic/closeout-cases.json` |
| CHSEM-027 | DEFER | Qualify constant-NULL and wrapper dispatch; masking and LowCardinality common types need native witnesses. | `vendor/data/clickhouse-semantic/closeout-missing-cases.json`; `vendor/data/clickhouse-semantic/closeout-cases.json` |
| CHSEM-028 | DEFER | Expand selected function/type families; no demonstrated family scope or UDF signature source exists. | `vendor/data/clickhouse-semantic/closeout-missing-cases.json` |
| CHSEM-029 | DEFER | Add selected semantic rejection rules; conditional types do not yet prove general overload/cast/value errors. | `vendor/data/clickhouse-semantic/floating-add-sub-types/input.json`; `vendor/data/clickhouse-semantic/closeout-cases.json` |
| CHSEM-030 | DEFER | Expand catalog and complex-query context; broader joins, set operations and correlation remain unqualified. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseCteTypingTest.kt`; `vendor/data/clickhouse-semantic/cte-context-README.md` |
| CHSEM-030-A | DONE | Propagate native types through bounded ordinary non-recursive CTE contexts. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseCteTypingTest.kt`; `vendor/data/clickhouse-semantic/cte-context-README.md` |
| CHSEM-031 | DEFER | Extend explicit session facts; external defaults, profiles and parameters need a scoped precedence contract. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseScriptSettingsTest.kt`; `vendor/data/clickhouse-semantic/closeout-missing-cases.json` |
| CHSEM-032 | DEFER | Prove selected cross-dialect lowering; current finite matches do not prove general equivalence. | `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseLoweringAssessmentTest.kt`; `vendor/data/clickhouse-semantic/lowering-README.md` |
| CHSEM-033 | DEFER | Qualify more engine and IDE versions; current semantic proofs enable only the exact target pin. | `vendor/data/clickhouse-2026-audit/engines.json`; `brikk-sql/test@jvm/dev.brikk.house.sql/ClickhouseTypeProvenanceTest.kt` |
| CHSEM-034 | ADD | Qualify consumer adoption of the published bounded APIs with explicit statement/script policy. | `docs/sql-0.23.0.md`; `tools/release/ClickhouseSemanticSmoke.java` |

## Notes and limits by ID

### CHSEM-001

Variadic designators differ on the left and right of composition. Anonymous
placeholders must be direct call arguments. Mixed placeholder conventions can
fail within one operand but work across stages. Runtime array-length failures
are distinct from callable argument counts. Historical fixture IDs remain stable.

### CHSEM-002

The initial eight callable families are lower, upper, domainWithoutWWW,
firstSignificantSubdomain, trim, multiply, toString and concat. Facts do not
invent registry overloads. `toString(NULL, 1, 2)` disproves a universal two-argument
bound; non-NULL subset guards matter. FixedString runtime failures are not blanket
type errors for empty-array queries.

### CHSEM-003

Real underscore-named columns are not placeholders. Scope bindings preserve
capture and shadowing. Known argument types do not imply a valid call.

### CHSEM-004

Only proved intrinsic errors are findings. Unused WITH definitions are not
eagerly treated as executed calls. Missing scopes, shadowed names, NULL wrappers,
constness and unresearched contexts remain unknown.

### CHSEM-006

Offsets are UTF-16 in the authored script. Native parser verdicts affect parser
findings only, not semantic or consumer checks. Existing diagnostic constructor,
copy and enum-ordinal contracts remain available. APIs do not activate plugin modes.

### CHSEM-008

Filter/sort preserve the first array; exists/all return UInt8. mapApply binds
key/value or tuple parameters and rebuilds the result. Unsupported wrappers and
unresearched overloads stay unknown rather than inheriting arrayMap rules.

### CHSEM-009

The native entrypoint leaves missing call/binding proofs UNKNOWN. Generic
annotateTypes keeps its existing literal policy. Whole-call NULL dispatch differs
from nullable lambda results. Historical observations change only through named refinements.

### CHSEM-010

Candidates use DuckDB 2.0.0, Trino 484 and Doris 4.1.5. Unicode differs on all
three targets; numeric width and padding differ on DuckDB/Trino. Finite ASCII
matches or matching errors are not universal equivalence. Production lowering
remains refused, including volatile and captured forms.

### CHSEM-011

Only ClickHouse's parenthesized-lambda hook changes. Other dialects retain their
JSON-arrow grammar. Native-valid CHSEM-003-E26 remains a historical parser-gap
capture, with a separate refinement for the repaired parser.

### CHSEM-012

Integer division uses the first operand's width and signedness from either
operand, rather than ordinary arithmetic widening. Its 1,372-type matrix proves
conditional result types, not successful execution or diagnostics.

### CHSEM-014

Script facts assume successful sequential execution within one call. Query-local
settings shadow them without leaking forward. DEFAULT, parameterized assignments,
profiles and unsupported mutations can invalidate knowledge. New calls and
standalone APIs never inherit editor contents or external connection state.

### CHSEM-015

Two Decimals use wider storage precision and the first scale. Mixed integer and
Decimal division preserves the Decimal declaration. Constant NULL and unsupported
wrapper/dispatch combinations remain unknown.

### CHSEM-016

Return-type scale overflow precedes NULL masking. Later zero, narrowing,
multiplier and numerator failures require their own dispatch/value facts.
Native categories are 69 for scale dispatch, 407 for overflow and 153 for zero.

### CHSEM-017

Decimal128/256 multiplication can wrap without an implemented overflow check in
the tagged source. Native successes there must not receive fabricated overflow
errors. Runtime diagnostics require explicit decimal_check_overflow=true.

### CHSEM-018

Script mode is explicit. `ClickhouseScriptTypeAnnotation` retains its frozen
two-field public shape. Returned ASTs are independently owned, not a mutation of
the caller's SQL or a session-setting rewrite.

### CHSEM-019

Native integer -0 normalizes to zero. Finite large-number fallback is Float64;
unsupported non-finite or overlong numeric tokens remain UNKNOWN. String, Bool
and Nullable(Nothing) facts do not establish arbitrary argument/NULL dispatch.

### CHSEM-020

Width promotion stops at 64 bits instead of automatically widening into bigger
integers. Subtraction is signed. Native wraparound retains its result type and
does not create an overflow diagnostic.

### CHSEM-021

Float32/64 plus/minus and admitted Int/UInt8 through 64 mixes return Float64.
Int/UInt128/256 mixes stay UNKNOWN with category-43 native rejection witnesses.
Rounding, inf and nan are values, not invented failures. Old parser-gap captures
remain intact after CHSEM-022.

### CHSEM-023

Closeout includes 18 scripts and six G01 to G06 controls. G01/G02's original
generic multiplication deviations are historical, not current native facts.
CHSEM-025 records stronger results without overwriting those captures. Runtime
errors, conditional types, unknowns and deviations remain separate counts.

### CHSEM-024

Per-node provenance is an analysis-time snapshot for one unchanged AST. Copy,
serde, mutation and independent calls require reanalysis. Populated type slots
are not proof; consumers must inspect NATIVE_CONDITIONAL versus fallback/unknown.
Function values remain UNKNOWN even when callable result facts exist.

### CHSEM-025

The 1,440-pair multiplication matrix covers supported primitive rules. Decimal
overloads outside earlier contextual proofs remain unknown. G01/G02 use named refinements.

### CHSEM-030-A

Ordinary CTE chains, aliases/stars, nested lexical scopes, supported schema
inputs and downstream arithmetic/lambdas carry native proofs. Simple qualified
INNER/CROSS joins have measured controls. Recursive/set-operation CTEs, outer
joins, ambiguity, unsupported functions and conflicting definition/consumer
division modes remain conservative. The parent CHSEM-030 is still deferred.

### CHSEM-034

SQL 0.23.0 and IntelliJ support 0.4.0 publish the bounded APIs. Remaining work is
consumer-specific integration and qualification, not another library publication
or full-server-validator claim. Generic typing, source/serde ownership, existing
JVM signatures and explicit statement/script selection remain compatibility contracts.
