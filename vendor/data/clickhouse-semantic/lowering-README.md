# CHSEM-010 cross-dialect lowering assessment

This is an assessment of explicit-lambda candidates, not a new converter.
Production generation still refuses ClickHouse composition, implicit placeholders
and function-designator nodes on DuckDB, Trino and Doris. Local semantic facts and
finite matching results do not authorize a cross-dialect rewrite.

## Candidates and evidence

`lowering-cases.json` contains eight authored composition queries, their explicit
ClickHouse lambda references, and separate candidate SQL for DuckDB 2.0.0,
Trino 484 and Doris 4.1.5. These candidates are research fixtures, not SQL produced
by an enabled production lowering path. Both ClickHouse digest pins are controls:
26.10 executes the composition and reference; 26.8 rejects the new composition
syntax and executes the explicit reference. Parser acceptance is checked separately
from values and errors.

The candidates cover ASCII case/trim chains, a Unicode case counterexample,
multiplication width, unequal array lengths, a native URL-function gap, nullable
numeric-to-string input and a repeated-placeholder pure stage. DuckDB values/errors
are captured through the existing pinned JDBC engine, including the jar hash.
The disposable Trino/Doris probe captures image digests, actual server versions,
original/reference ClickHouse results and all target candidate results.

Ordered arrays of strings and SQL NULLs use JSON only as a transport. Decoding does
not sort arrays, normalize Unicode, fold case, coerce numbers or equate NULL with
the string `null`. Raw results and errors remain alongside decoded values. Two
errors are always `both-error-not-equivalence`, never proof of equivalent behavior.
For the ClickHouse length-negative reference, the actual native error codes must
also match. Infrastructure/startup failures cannot become semantic counterexamples.

## What can be expressed, and what is not qualified

- Single-array scalar chains can be written as `x -> g(f(x))` using list_transform,
  transform or array_map. ASCII examples are useful finite controls, not proof that
  those engines' string functions match on all String values. ClickHouse's ASCII
  case functions and target Unicode case functions require separate domain guards.
- A bare variadic operand is not silently repaired into a different valid call.
  Invalid, unresearched and shadowed callable forms still refuse production
  generation. Type compatibility alone does not resolve native function names.
- Multi-array functions need target-specific argument binding. DuckDB list_zip and
  Trino zip_with are candidates, but padding unequal arrays with NULL changes the
  source's length-error behavior. Merely matching lambda arity is insufficient.
- Numeric inputs and intermediate result widths need explicit handling. DuckDB's
  UInt8 product can overflow where ClickHouse widens to UInt16. Matching final
  string types says nothing about that intermediate operation.
- Native URL extractors require researched target implementations. Copying a
  ClickHouse function name into target SQL does not establish name availability,
  host parsing, www stripping or public-suffix semantics.
- Nullable element handling, toString formatting, FixedString, decimals, settings,
  timezones and whole-call NULL dispatch are separate contracts. The finite nullable
  example does not prove every conversion/NULL branch.
- Repeated placeholders require evaluation-count and capture hygiene. Duplicating
  a pure lower(x) stage happens to work in the witness; doing so for a volatile or
  user-defined stage is not authorized. Nested lambdas, captures, alias settings
  and fresh-name collision avoidance need their own lowering proof.

Consequently no automatic cross-dialect composition lowering is enabled by this
round. The refusal tests cover both the finite positive examples and invalid,
unresolved, captured, nested and volatile forms. Existing hazard/certification
ledgers, comparison rules, public signatures and consumers remain unchanged.

The captured results make those limits concrete. E01, E03, E07 and E08 match on
all three targets for their finite inputs. E02 differs on Unicode case on all
three. E04 overflows on DuckDB and Trino but matches on Doris for this input;
that is not a general numeric contract. E05 pads NULLs on DuckDB/Trino while
ClickHouse errors. Doris also errors, recorded as both-error without equivalence.
E06 fails to resolve the copied native URL function on every target.

## Reproduction and isolation

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseLoweringAssessmentTest'
./kotlin test -m brikk-sql-verify \
  --include-classes='*ClickhouseLoweringDuckdbAssessmentTest' \
  --jvm-args='-Dbrikk.clickhouseLoweringDuckdbOut=build/lowering-duckdb.json'
python3 tools/probe_clickhouse_lowering.py \
  --duckdb build/lowering-duckdb.json \
  --output build/lowering-assessment.json
```

Use new output paths for rechecks. The probe uses only cached exact images, UUID
containers, no host ports/mounts or external database, and bounded startup/query
deadlines. Doris BE shares the FE's network namespace; both use loopback, with no
network access outside the containers. Its bootstrap registers only this disposable
backend. Heap, native memory, CPU and PID limits are explicit. File caching is
disabled and kernel preflight is skipped inside the test container; no host kernel
or desktop configuration changes are made. Every owned container is removed in
finally, including failed startups. A failed startup report is not execution evidence.

CHSEM-011 follows in its own commit. Fixing its parser gap does not change this
cross-dialect assessment or enable any of these production conversions.

## Validation

All eight original/reference pairs ran on both exact ClickHouse pins, and all
eight target candidates ran on each pinned target. Version, image/JDBC hashes,
raw results and explicit failure categories are retained in lowering-assessment.json
and lowering-duckdb.json. Native target grammars accept all 24 candidate SQL forms;
the execution counterexamples show why this is not semantic acceptance.

Full JVM regression passed 1,840 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility. Python discovery passed
93 tests with one opt-in Toolchain-model skip using the unchanged SQLGlot pin;
ClickHouse-only discovery passed 45. No production or IntelliJ code changed, so
platform suites were not repeated for this assessment-only step. Existing corpus,
hazard and certification rules remain unchanged. All disposable containers were
removed. Failed startup diagnostics do not count as semantic results.
