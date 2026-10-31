# CHSEM-007 bounded-slice qualification

This round qualifies the first six CHSEM items together. It adds no semantic
rules and does not change the parser, type facts, verifier, corpus ledgers or
consumer dependencies. Earlier captures remain historical evidence.

`qualification-cases.json` contains twelve small original queries. Every query
has an authored expectation on both exact digest pins. `qualification-brikk.json`
records generated SQL, conditional result types, positioned diagnostic rule names
and unknown reasons. `qualification-native/` records native parsing and actual
finite execution of both original and generated SQL, including negative cases.
There are no execution/version skips. Native error text may quote different
formatting, so error equivalence means the same explicitly asserted native error
code/category, not byte-identical error prose. Successful output is byte-identical.
Formatting equivalence is a separate native parser assertion, not semantic proof.

The integrated JVM test checks authored/generated AST equality, annotated serde,
unchanged generated SQL after annotation, result types, public semantic rules,
source ranges, and absence of phantom physical-column references from lambdas.
A physical-table control checks both array-input and captured-column lineage after
annotation and serde. The native vectors use only finite literal inputs, with no
credentials, DDL, server or external database.

## Coverage and limits

- The researched slice is arrayMap and composition on exactly 26.10.2.103, using
  the eight pinned scalar callable contracts described in `callables-README.md`.
  The older 26.8.34.13 engine is an explicit syntax/behavior control, not a fallback
  semantic version. Bare variadic left operands and explicit placeholder calls
  remain distinct.
- Contextual arguments, supported string chains, primitive multiplication and
  identity results have separate native-backed rules. Public diagnostics separate
  proven argument errors, unavailable evidence and parser failures. Function-valued
  nodes remain UNKNOWN and are not scalar results.
- E09 deliberately knows an Array(UInt16) type but executes with an array-length
  error. E10 deliberately keeps an unresearched reverse result UNKNOWN although
  the engine executes it. These controls prevent equating known types, empty errors
  or successful parsing with successful execution or complete validation.
- Identity preserves explicitly declared nullable, decimal and container shapes.
  This does not qualify nullable conversion rules, decimal arithmetic or automatic
  native literal widths. Generic numeric hints are not native type evidence.
- Settings-dependent name resolution, unavailable catalogs, unresearched overloads,
  timezone values, wrapper propagation and runtime restrictions retain their
  explicit unknowns. The parenthesized explicit-lambda composition parser gap
  remains CHSEM-011 and is not included as generated-SQL certification.
- Existing cross-dialect refusals stay in force. No translation is authorized by
  these local rules or finite same-dialect witnesses. Plugin activation, releases
  and dependency adoption remain separate work.

CHSEM-008 and CHSEM-009 extend this baseline in separate commits and evidence sets.
They must record intentional refinements without rewriting the older captures.

## Reproduction

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseSemanticQualificationTest' \
  --jvm-args='-Dbrikk.clickhouseQualificationOut=/tmp/opencode/clickhouse-qualification.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/qualification-cases.json \
  --task CHSEM-007 --brikk /tmp/opencode/clickhouse-qualification.json \
  --output /tmp/opencode/clickhouse-qualification-native
python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

Use new output paths to preserve historical reports. The runner enforces the
existing isolated containers, no network/mounts, resource limits and deadlines.

## Validation

Full JVM regression passed 1,828 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility. Full IntelliJ platform
regression passed all 757 tests on 261.25134.95. Other IDE versions were not run.
The complete Python suite passed 87 tests with one opt-in Toolchain-model skip
using the unchanged temporary SQLGlot pin; ClickHouse-only discovery passed 39.
Both engines passed twelve original and twelve generated finite execution checks,
plus separate formatting assertions. No historical fixtures/captures or comparison
rules changed. No production code, IntelliJ API usage, public signature, toolchain
floor, release version or consumer dependency changed.
