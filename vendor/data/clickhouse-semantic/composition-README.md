# CHSEM-004 composition argument validation

`optimizer/ClickhouseCompositionValidation.kt` adds a read-only internal rule checker
for structured `arrayMap` callable arguments. It consumes CHSEM-002's pinned facts
and returns separate proven-error and unknown finding lists. It does not expose an
accepted/isValid flag or declare a query natively accepted when those lists are empty.

No parser, type annotation, SQL generation, execution or editor diagnostic hook is
changed. Exact result annotation remains CHSEM-005; the public positioned diagnostic
entry point remains CHSEM-006. Findings retain their original AST expression identity
for that later work. Messages do not render authored argument values.

## Checked rules

- Bare variadic designators on the left, including trim, trimBoth, toString and
  concat, require explicit placeholder calls. Right-hand variadics receive a
  known arity of one. A nested right composition still gives its own left operand
  the native left-operand rules, not the outer right operand's arity shortcut.
- Right operands must be unary. The first callable's arity must match the number
  of arrays supplied by arrayMap. Known non-array inputs are rejected separately.
- Numbered placeholder bounds, mixed anonymous/numbered forms, anonymous member
  access and anonymous placeholders below the direct-argument level are checked.
  Numbered reuse and gaps retain their native meaning. Nested implicit lambdas,
  compositions and SQL queries do not donate their placeholders to an outer operand.
- A plain scalar call without placeholders is not a composition function operand.
  The existing CHSEM-011 JSON-arrow parser ambiguity remains unknown, not a false
  invalid-operand finding against a native-valid explicit lambda.
- Native-backed call-count and input-family rules apply to the eight researched
  functions. String/fixed-string requirements can reject known numeric inputs.
  Supported result families can prove a later-stage mismatch, for example a numeric
  multiply result fed to lower. They do not compute a numeric width or write any
  AST result type.

Rule IDs and corresponding native error classes are explicit. They describe
intrinsic argument violations, not a promise about native whole-query error order,
authorization or runtime success.

## Input and scope proofs

The checker reads syntax, known native casts and explicit ClickHouse MappingSchema
facts. It does not trust generic expression annotations as native proofs. Homogeneous
literal arrays supply families only, including nested-container shape; no integer
literal width is invented. This is separate from CHSEM-003's deliberately unknown
literal-array type annotations.

Cast proofs require non-null input evidence as well as a known target. A target
spelling alone cannot prove that cast_keep_nullable did not preserve a wrapper.
TRY_CAST, NULL, nullable/low-cardinality/dynamic/variant values, unmapped type aliases
and other unproven plain inputs stay unknown. CHAR and TIMESTAMP controls prevent
unmapped string/datetime aliases from becoming false incompatibility/count errors.

Lexical aliases, lambda parameters and physical names can override a bare builtin
name. Potentially shadowed names never borrow builtin arity facts. Physical relations
need a closed namespace from a ClickHouse schema or supported explicit derived
projections; missing/foreign schemas and stars remain uncertain. Duplicate or
alias/column-colliding value names remain unknown, including the
prefer_column_name_to_alias control. SQL query boundaries do not inherit lambda
parameter values. Lambda-body aliases are also considered before borrowing a builtin.

WITH definitions may be unused. They are recorded as unchecked rather than eagerly
diagnosed as executed expressions. Unresearched enclosing lambda contexts and
set-operation scope resolution are likewise not guessed. Traversal is iterative;
recursive callable/value analysis has a 128-level limit that produces an unknown
finding, not a stack overflow or invented error. Cancellation propagates to the caller.

## Unknown is not valid

- Matching argument counts do not prove runtime array lengths. Every multi-array
  map retains an ARRAY_LENGTHS unknown. E26 has correct arity but fails natively
  with SIZES_OF_ARRAYS_DONT_MATCH.
- The NULL literal paths in E24/E50 bypass normal variadic argument checks, while
  the fixed multiply arity in E49 is still rejected. Typed nullable/TRY_CAST paths
  remain unknown; a runtime NULL value is not assumed to bypass analyzer checks.
- URL extraction on FixedString retains a runtime-restriction finding, not a
  blanket type error. E27 fails when evaluated on a nonempty array, but E28's
  empty-array query succeeds.
- A scoped lambda called trim can be valid, E29. A scoped scalar called trim is
  rejected natively, E30. This round leaves both unresolved rather than applying
  the unrelated builtin variadic rule to either name.
- E31 defines an unused bad composition and succeeds. E32's parenthesized explicit
  composition lambdas are valid natively but retain the known CHSEM-011 parser gap.
- Missing callable facts do not prove that a function is absent; UDFs/extensions
  may exist. Unresearched names and composite overloads stay unknown. Constant-string
  requirements and timezone values also stay unknown when not proved.

Only the exact `26.10.2.103` rule target is enabled. Other versions, including the
26.8 pin, return ENGINE_VERSION unknown and no semantic rule errors. The 26.8 native
run still executes every vector as a version-boundary control. There is no nearest-
version fallback or new semantic-equivalence certification.

## Evidence and reproduction

`composition-cases.json` contains stable CHSEM-004-E01 through E50 inputs, expected
rule IDs, required unknown categories and reviewed native results/errors.
`composition-brikk.json` records the internal reports. The ordinary test gate checks
that no native-success vector receives a static error, and that every reported rule
failure has a matching native error class. Native failures with only unknown findings
are explicitly allowed and are not relabeled as successful validation.

`composition-native/<version>.json` contains all 50 original parser observations and
50 original finite execution outcomes per exact digest pin, with fixture hashes.
There are no version-skipped executions. These are original-query checks, not new
generated-execution certification. Historical CHSEM-001/002/003 evidence is untouched.

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseCompositionValidationTest' \
  --include-classes='*ClickhouseCompositionFixturesTest' \
  --jvm-args='-Dbrikk.clickhouseCompositionOut=/tmp/opencode/clickhouse-composition.json'

python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/composition-cases.json \
  --task CHSEM-004 \
  --output /tmp/opencode/clickhouse-composition-native

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

Capture reruns to new paths instead of overwriting historical evidence to approve
changed behavior. The existing runner retains digest pins, owned containers, no
network/host mounts, resource limits and query/host deadlines. No user database,
credentials or DDL execution is used. Native output derives from Apache-2.0
ClickHouse; see `ATTRIBUTIONS.md`.

## Validation

Full `./kotlin test` passed 1,799 tests with zero failures and 61 optional
live-engine/chDB assumption aborts. Existing parser, generator, annotation and
transpilation corpora retain their fixtures, ledgers and comparison rules. Public
binary-compatibility tests pass. Custom IntelliJ platform suites were not run.

ClickHouse Python discovery passed 30 tests. The complete tool suite passed 78
tests with one opt-in Toolchain-model skip using the unchanged pinned SQLGlot
source from a temporary checkout. Both native runs passed all 50 parser and 50
execution assertions. The 26.8 control has two successful executions and 48 expected
errors; 26.10 has 21 successes and 29 expected errors, with zero execution skips.

All recorded native successes have zero static error findings. Earlier CHSEM-001
and CHSEM-003 Brikk captures remain byte-identical, and previous native/source
evidence is untouched. No public JVM signature, IntelliJ API call, release version,
publication or consumer dependency changed.
