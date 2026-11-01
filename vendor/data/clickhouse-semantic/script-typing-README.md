# CHSEM-018 explicit script-aware native typing

`annotateClickhouseScriptSemanticTypes(script, engineVersion, schema, checkCanceled)`
is a separate, explicit script entrypoint. It carries preceding SET facts into
the existing bounded native type annotator. It never chooses script/session semantics
from semicolons, an editor file, AST metadata or earlier API calls.

```kotlin
val units = annotateClickhouseScriptSemanticTypes(
    "SET sql_compatible_division_for_integers=1; SELECT 7/2;",
    engineVersion = "26.10.2.103",
)
val statement = units.last()
val expression = statement.expression!!  // Owned typed AST; quotient has UInt8.
val report = statement.diagnostics      // Positioned parser/errors/unknowns, separate from types.
```

## Who chooses statement or script processing?

The caller chooses the API. A selected/current statement uses the existing
`annotateClickhouseSemanticTypes(expression, ...)` or statement diagnostic range.
Neither scans preceding file text or inherits settings from a prior call.

The new script entrypoint is an opt-in to conditional sequential analysis of the
supplied text within one session, assuming preceding statements execute successfully.
An IDE must not use that policy merely because a file contains several statements.
It does not execute SQL, inspect a connection, predict client stop/continue behavior,
or establish that the supplied statements were or will be executed together.
Even a one-statement script starts with unknown external state. SELECT 7/2 without
setting evidence remains UNKNOWN, including when followed by a semicolon.

## Results, ownership and failures

Each `ClickhouseScriptTypeAnnotation` contains a fresh independently owned AST and
the existing `ClickhouseSemanticDiagnostics` report. Parser errors, proved semantic
errors and unavailable knowledge stay separate. Parser/framing failures have a null
AST; unsafe suffixes are not presented as independently executable statements.
Trivia-only spans do not produce units. Inline INSERT payload is framed as data,
not searched for SET commands or statement separators.

Report start/end positions are UTF-16 script offsets, ends exclusive. AST source
ranges remain relative to that unit's parsed substring; add diagnostics.start to
translate them to script offsets. Type annotation does not rewrite source metadata,
authored settings or SQL. Inherited settings are passed as read-only analysis facts,
never injected into the AST. Returned AST mutation cannot affect another unit/call.
Reannotating a returned AST through the statement API does not inherit script state.

Every statement gets a fresh annotator and the same incoming setting snapshot used
by its diagnostic report. Script framing, parsing and SET advancement are shared
with `clickhouseScriptSemanticDiagnostics`; the two APIs produce identical reports.
This is not a new state parser or a second interpretation of reset/override rules.
The caller's schema is reused under the existing schema contract, not inferred or
modified to follow DDL. Other unsupported mutations conservatively invalidate facts.
Cancellation propagates unchanged; the whole operation does not return a partial
list when canceled.

## Typing policy and compatibility

Preceding integer division mode applies to native literal widths, schema columns,
derived queries and lambda bodies in the researched subset. Query-local/nested
SETTINGS shadows it without leaking to later statements. Unknown/parameterized
assignments and DEFAULT/profile/compatibility resets retain the existing unknown
state policy, including native reset ordering for duplicate assignments.
Decimal overflow settings use the same context.

Known conditional types can coexist with runtime errors. A proved type is not a
successful-execution verdict, and empty diagnostics do not certify acceptance.
The entrypoint reuses the existing bounded statement annotator, not broader native
analyzer parity. Unresearched literal-projection nullability and generic fallbacks
retain the statement API's existing policy; the exact String fixture uses a declared
cast rather than claiming a new literal-nullability rule.

The exact 26.10.2.103 pin and a ClickHouse-specific schema remain mandatory. Existing
statement/generic API signatures, behavior, caller ownership and literal policy are
unchanged. The new result's two-field constructor/copy shape and the existing statement
entrypoint/default signature are covered by BinaryCompatibilityTest. No IntelliJ
callsite is switched to script mode and no new IntelliJ API call is added.

## Evidence and reproduction

`script-typing-cases.json` and `script-typing-native/` contain 24 complete-session
original/generated type, value and error controls on both digest pins. They cover
mode selection, overrides, inner queries, lambda bodies, nullable/wide declared
types, duplicate/reset order, unknown parameters, Decimal settings, runtime errors
with retained conditional types, and SET-looking text inside a string.
Successful known types match independently observed native toTypeName results.
UNKNOWN results are not upgraded from observed defaults. Older-pin setting/function
differences are explicit outcomes, not execution/version skips.

The native setting/type rules reuse the tagged captures from CHSEM-012/014/015/016/017.
No new native arithmetic rule is claimed. Historical captures remain unchanged;
CHSEM-014, CHSEM-016 and CHSEM-017 script captures regenerate byte-identically after
sharing the traversal. The new typing report regenerates byte-identically too.

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseScriptSemanticTypesTest' \
  --jvm-args='-Dbrikk.clickhouseScriptTypesOut=/tmp/opencode/script-typing-brikk.json'
python3 tools/probe_clickhouse_script_settings.py \
  --cases vendor/data/clickhouse-semantic/script-typing-cases.json \
  --brikk /tmp/opencode/script-typing-brikk.json --output /tmp/opencode/script-typing-native
```

Full JVM regression passes 1,884 tests with 61 optional integration assumption
aborts and zero failures. Complete Python discovery passes 118 tests with one opt-in
skip, using the unchanged SQLGlot pin. Build gates run sequentially with a task-private
temp directory under build/ to avoid the previously observed shared-temp quota.
No corpus/ledger relaxation, toolchain floor, release, publication or consumer
dependency change is introduced.
ClickHouse-only Python discovery passes 70 tests. The isolated IntelliJ gate passes
757 tests on 261.25134.95; other IDE versions were not run. No new IntelliJ API calls
or automatic changes to an IDE's statement/script execution mode were introduced.

CHSEM-019 subsequently closes the standalone scalar-literal type/nullability fallback
boundary described above. See `projection-literal-README.md`; the CHSEM-018 captures
remain unchanged, and generic annotateTypes retains its earlier policy.
