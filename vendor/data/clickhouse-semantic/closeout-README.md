# CHSEM-023 qualification and scope freeze

This is the CHSEM-023 closeout record. Subsequent approved release preparation adds
[CHSEM-024 provenance](type-provenance-README.md) and
[CHSEM-025 primitive multiplication](multiplication-README.md). The historical
G01/G02 captures below remain intact; their stronger current results are named in
`multiplication-refinements.json`. Broader families and full validation remain out
of scope.

Current release-preparation status, including reconciliation with main and local
candidate qualification, is recorded in [SQL 0.23.0 preparation](../../../docs/sql-0.23.0.md).
The original closeout delivery statements below describe CHSEM-023's point in time.

This closes the requested CHSEM implementation batch. It does not authorize more
type families, merge/publication, consumer changes or automatic IDE mode changes.
The original composition/lambda goal is implemented. Later arithmetic, diagnostics
and public statement/script APIs are qualified only within their documented slice.

## Public contract being frozen

- annotateClickhouseSemanticTypes types an AST locally on exactly 26.10.2.103.
- annotateClickhouseScriptSemanticTypes explicitly opts into conditional sequential
  script analysis with fresh owned ASTs and per-statement diagnostic reports.
- clickhouseSemanticDiagnostics analyzes only the requested statement range;
  clickhouseScriptSemanticDiagnostics carries explicit preceding SET facts.
- Query-local settings override script facts without leaking. Missing external
  session state, DEFAULT, unresolved assignments and unsupported mutations remain
  unknown. DDL does not create a new schema/catalog model.
- Parser acceptance, conditional types, proved errors, unknown coverage and actual
  execution are distinct. Empty errors never mean valid or fully checked SQL.
- Generic annotateTypes, old JVM shapes, source ranges, serde and caller-owned
  policies stay compatible. Function-valued AST nodes remain UNKNOWN.

The frozen integrated corpus checks interactions between integer/floating arithmetic,
division/settings, nullable inputs, array/map callbacks, composition, Decimal guards,
scientific token boundaries and positioned semantic errors. Deferred examples are
labelled separately and are never counted as native-exact type qualification.

## Missing coverage for the final decision

### 1. Generic fallbacks inside the native-only annotator

Not every populated AST type slot is a native proof. A root multiplication can
still use generic width/nullability, for example SELECT 1*2 or
SELECT 1e0*(2e0+3.0). Contextual multiplication contracts exist, but they do not
constitute complete standalone-root propagation. There is no public per-node
native-proof/provenance result distinguishing all generic fallbacks from proved facts.
Do not consume every populated slot as authoritative native metadata.

The separately captured G01 example, `SELECT 1*2`, has frontend type
`Nullable(Int32)` but native type `UInt16`. G02, `SELECT 1e0*(2e0+3.0)`,
has frontend type `Nullable(Float64)` but native type `Float64`.

### 2. Unqualified overloads and type families

Decimal addition/subtraction, many other scalar/aggregate/window functions,
temporal arithmetic, broader literal/compound-unary forms, BFloat16 and more wrapper/
common-type combinations are outside this batch. Numeric LowCardinality and
constant-NULL dispatch are not broadly admitted by ordinary nullable propagation.
Most general catalog overload/signature resolution and arbitrary UDFs remain unknown.

### 3. Incomplete general semantic rejection coverage

The checker has positioned proved composition/higher-order and division/scale/
overflow rules. It is not a full server analyzer. A native-refused Float/wide-integer
pair can remain UNKNOWN without a static error. Arbitrary casts, parameter values,
permissions, execution-dependent errors and unresearched combinations are not validated.

### 4. Catalog, query and session context

Join/set-operation and complex alias/CTE resolution do not have complete native
analyzer parity. Caller-provided schema knowledge is bounded. External connection
settings, profiles, parameter bindings and preceding statements not supplied in the
explicit script are not invented. Script analysis tracks a bounded set of Boolean
settings, not all SET/DDL/session behavior or client stop/continue policy.

### 5. Cross-dialect and delivery qualification

Composition/placeholder lowering remains refused where equivalence was not proved.
No blanket cross-dialect conversion is certified. Native semantic APIs currently
require 26.10.2.103; 26.8.34.13 is a control, not an enabled compatibility promise.
Shared IntelliJ tests target 261.25134.95; other IDE versions and actual consumer
plugin adoption were not qualified in this batch. Optional integration assumptions
remain explicitly reported, not silently converted to native successes.

The branch has not been merged/reconciled with current main, published or installed
into consumers here. Those steps require separate authorization and combined-tree/
artifact/consumer validation. Existing published snapshots are not evidence that
these feature-branch semantic changes have been released.

## Accepted decision

The result is useful as a bounded, opt-in semantic/type-hint foundation with
explicit unknowns and separately proved errors. It is not ready to be described
as a full ClickHouse validator or an all-node native-exact type certifier.

On 2026-11-02 the user accepted this bounded, opt-in scope and asked to track later
improvements. The [deferred backlog](../../../TODO-clickhouse-semantic.md#deferred-improvement-backlog)
records CHSEM-024 through CHSEM-034 with priorities, evidence and completion criteria;
[SQL-08](../../../TODO.md) is the top-level index. If authoritative all-expression
metadata or hard rejection is required, resolve generic-fallback provenance/root
propagation and the selected missing families before that use. No further feature
work, merge, publication or consumer changes start automatically from this decision.

## Evidence and freshness

closeout-cases.json separates qualified interaction witnesses from deferred cases.
Original and generated scripts run as complete isolated sessions on both digest
pins. The Kotlin replay verifies typed ASTs, report equality, code generation and
serde; separate tests retain statement/script separation, ownership, cancellation,
exact-pin checks and preservation of semantic errors after native parser acceptance.

Historical type matrices and fixtures are replayed against the current library by
the full JVM/Python suites. They are not all freshly re-executed against native
containers during closeout; the new integrated sample is freshly executed on both
pins. Source/native captures remain immutable, and no corpus ledger is weakened.

The integrated corpus contains 18 scripts, with 12 qualified interactions and six
explicitly deferred cases. Three qualified cases prove error behavior rather than
successful execution. Six additional G01-G06 controls distinguish generic fallback
deviations, unqualified Decimal addition, constant-NULL dispatch, unknown state after
DEFAULT and arbitrary UDF resolution. All original/generated outcomes match their
recorded expectations on both pins, without execution or version skips.

To regenerate the frontend reports into new paths:

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseSemanticCloseoutTest' \
  --jvm-args='-Dbrikk.clickhouseCloseoutOut=/tmp/opencode/closeout-brikk.json -Dbrikk.clickhouseCloseoutMissingOut=/tmp/opencode/closeout-missing-brikk.json'
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/closeout-cases.json \
  --brikk /tmp/opencode/closeout-brikk.json --output /tmp/opencode/closeout-native
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/closeout-missing-cases.json \
  --brikk /tmp/opencode/closeout-missing-brikk.json --output /tmp/opencode/closeout-missing-native
python3 -m unittest discover -s tools -p 'test_clickhouse_closeout.py'
```

The final command checks committed evidence. To validate a new capture, compare its
reports with the committed fixtures and run the provenance/outcome checks against
those new paths. Captures include hashes of their exact fixture and frontend input.

Final JVM regression passes 1,917 tests with 61 optional integration assumption
aborts and zero failures, including the existing constructor/copy compatibility
checks. Both closeout frontend reports regenerate byte-identically. Full Python
discovery passes 139 tests with one opt-in skip using the unchanged SQLGlot pin;
ClickHouse-only discovery passes 91 tests. No production code or public JVM
signature changed during CHSEM-023.
The sequential IntelliJ platform gate passes 757 tests on 261.25134.95. Other IDE
versions were not run. CHSEM-022 was committed/pushed first as `148ebfb`.
