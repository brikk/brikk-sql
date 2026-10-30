# CHSEM-002 callable facts

`brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseCallableFacts.kt` adds an
internal, hand-reviewed set of callable facts for the initial eight functions.
It does not populate `FunctionDef.overloads`, add a public metadata API or change
parsing, type annotation, diagnostics, verification or cross-dialect generation.
Those integrations remain later checklist items.

## Contract

`clickhouseCallableFacts(name, engineVersion)` requires an exact supported pin:
`26.8.34.13` or `26.10.2.103`. Other versions, including unverified patches and
unknown versions, return no fact. This is a knowledge limit, not proof that a
function is absent or invalid. Known but unresearched functions such as `abs`
also return no fact.

Lookup uses the captured registry's per-spelling case and alias policy via
`FunctionCatalog.getAll`, not its case-insensitive convenience lookup.
`lower`/`upper`, `lcase`/`ucase` and `concat` have case-insensitive spellings;
`multiply`, `toString` and the URL names retain their captured case sensitivity.
`trim` resolves to canonical `trimBoth`. SQL's `TRIM(...)` special form can parse
despite the registered raw function designator's case policy; a caller must use
the resolved builtin identity, not invent a case-insensitive registry alias.

The caller must first resolve the name as an unqualified builtin. These facts
cannot override a lambda, expression alias, column or UDF bound to the same name.
The source capture includes the native composition resolver's scope precedence.

Each record separates:

- Native designator arity, from `isVariadic` and `getNumberOfArguments`.
- Explicit-call counts within the supported non-NULL input subset, including
  `toString`'s argument-dependent option. These are not universal count bounds.
- A supported base-input rule, whether its plain-type domain is exhaustive,
  and its corresponding result rule.
- Any separately recorded execution restriction. No recorded restriction does
  not guarantee that every value or evaluation context succeeds.
- Stable source evidence IDs shared by both independently captured source pins.

The three variadic designators are `trimBoth`, `toString` and `concat`. Their
explicit-call minimum is not their bare designator arity. Composition's right
operand can supply a known arity of one, but the left cannot infer one from that
minimum. CHSEM-001's E03/E04, E33/E34 and E40/E41 retain the native distinction;
S13 captures the tagged resolver that makes it.

## Supported type rules and limits

- `lower` and `upper` accept String/FixedString base inputs and preserve the
  exact input base type, including FixedString width.
- `domainWithoutWWW` and `firstSignificantSubdomain` have the same analyzer type
  rule as lower/upper, but a separate FixedString execution restriction. E05/E06
  and S14 preserve the runtime failure. E54/E55 show that a type-only query with
  `materialize` can still report the FixedString return type without evaluating
  the extractor. Without `materialize`, constant folding can itself evaluate the
  function and fail. This restriction must not become a blanket argument-type
  error, and a known return type must not certify successful execution.
- `trimBoth` takes one or two arguments. The input can be String/FixedString;
  the optional character set must be a constant String, not just a string-typed
  column or a FixedString constant. The base result is String.
- `multiply` has two arguments. The result rule records the tagged
  `NumberTraits::ResultOfAdditionMultiplication` promotion for signed/unsigned
  8/16/32/64-bit integers and Float32/64. Width doubles below eight bytes and stops widening at eight;
  signedness/floating status comes from either input. No promotion evaluator is
  wired into the annotator in this step. Decimal, big integer, interval, tuple
  and array overloads remain outside this rule.
- `toString` has one argument, or a second String timezone for DateTime/DateTime64
  inputs only. The supported one-argument subset is primitive numeric, String,
  FixedString, Date/Date32 and DateTime/DateTime64. Validating timezone values and
  session-dependent rendering is not inferred from those argument types. E56's
  out-of-scope `toString(NULL, 1, 2)` succeeds through an early-NULL branch. The
  supported subset's one/two count rule must not reject that query by exclusion.
- `concat` permits zero or more arguments. The String result rule covers scalar
  String/FixedString/primitive-numeric inputs only. A single argument delegates
  to `toString`; all-array/map/tuple inputs select different native overloads.
  E28 returns `Array(UInt8)` for arrays, so an unconditional String rule would
  be wrong. Other serializable input types are not claimed by this subset.

NULL, Nullable, LowCardinality, Dynamic, Variant and unknown types are not stripped
or guessed. Only the string-case, URL and trim rules have exhaustive plain-type
domains backed by native guards. A type outside the numeric multiplication,
toString or scalar concat subsets stays unknown, not rejected by exclusion.
Constness and value constraints still apply independently. A missing fact is never a successful
validation verdict. There is no new equivalence, determinism or nullability fact.

## Evidence and acceptance

- `callable-sources.json` holds excerpts, original line ranges, exact release-tag
  URLs and whole-file SHA-256 hashes. S01 through S12 and S14 cover both pins. S13 covers
  only the 26.10 composition resolver; it is not backdated to 26.8.
- `callables-cases.json` holds 56 finite SELECT vectors, covering valid types,
  result types, argument-count failures, constness, aliases and incorrect casing.
  Their reviewed outcomes are identical on these two pins. Shared expectations
  are asserted independently on both, not extrapolated to untested releases.
- `callables-native/<version>.json` records all 56 parser observations and all
  56 native executions on each pin, with exact image identity and fixture hash.
  No case is version-skipped. These are original-call facts, not generated-SQL
  evidence; the unchanged CHSEM-001 suite retains that separate coverage.
- Kotlin tests check exact version/case/alias lookup, count/type/result rules,
  source links, explicit-vs-designator arity and unchanged registry overloads.
  Python tests check raw results/errors, complete IDs, pins, input hashes and
  declaration guards. Normal tests need no server, network or source checkout.

Acceptance is restricted to these researched facts and subsets. CHSEM-003 through CHSEM-006
must still bind lambda input types, apply the rules, propagate results and emit
positioned diagnostics. Public JVM signatures and historical evidence remain
unchanged; all new production declarations are Kotlin-internal.

## Reproduction

Capture the exact tagged sources to a new file, then compare it with the committed
source evidence. Do not overwrite historical evidence to approve changed behavior:

```sh
python3 tools/capture_clickhouse_callable_sources.py \
  --output /tmp/opencode/clickhouse-callable-sources.json

python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/callables-cases.json \
  --task CHSEM-002 \
  --output /tmp/opencode/clickhouse-callables-native

./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseCallableFactsTest' \
  --include-classes='*ClickhouseSemanticFixturesTest'

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

For offline source recapture, use `--source-dir <root>` where files are laid out
as `<root>/<version>/<upstream path>`. Capture fails if a selected declaration
marker disappears. The native probe retains its isolated-container deadlines,
resource limits and no-network/no-host-mount policy from CHSEM-001. The source
excerpts are Apache-2.0 ClickHouse code; see `ATTRIBUTIONS.md`.

## Validation on 2026-10-30

Both native runs passed all 56 parser and 56 execution assertions, with no skips.
Each pin produced 30 successful outcomes and 26 expected errors. Source excerpts
and original-file hashes reproduce exactly on both tags.

Full `./kotlin test` passed 1,766 tests with zero failures and 61 optional
assumption aborts for unconfigured live engines/chDB. The final focused callable,
fixture, analysis and binary-compatibility run passed 23 tests. Current CHSEM-001
Brikk observations remain byte-identical to `brikk-before.json`.

ClickHouse Python discovery passed 23 tests. The complete tool suite passed 71
tests with one opt-in Toolchain-model skip after supplying the unchanged SQLGlot
pin from a temporary checkout. Custom IntelliJ platform suites were not run.
No public JVM signature, IntelliJ API use, metadata overload catalog, historical
capture, dependency pin or publishing version changed.
