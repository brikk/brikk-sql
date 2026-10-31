# CHSEM-006 positioned offline diagnostics

The shared library exposes the existing bounded composition/arrayMap checker as
plain positioned data. It does not add semantic rules, execute queries or turn
`formatQuery` acceptance into semantic acceptance.

## Public API

`dev.brikk.house.sql.diagnostics` provides:

- `clickhouseSemanticDiagnostics(script, range, engineVersion, schema, checkCanceled)`
  checks exactly one safely framed statement. `range` is a non-empty inclusive
  `IntRange` in the original script.
- `clickhouseScriptSemanticDiagnostics(script, engineVersion, schema, checkCanceled)`
  uses the shared ClickHouse statement framer, skips trivia-only spans and keeps
  inline INSERT data opaque. Each result describes one statement.
- `ClickhouseSemanticDiagnostics` keeps `parseDiagnostics`, `errors` and `unknowns`
  separate. Its `start` and `end` delimit the statement in the original script,
  with an exclusive end. No `accepted`, `isValid` or coverage-complete flag exists.
- `ClickhouseSemanticError` carries a stable `ClickhouseSemanticRule`, the
  researched rule's native error category, and an error-severity `SqlDiagnostic`
  from `DiagnosticSource.OFFLINE_SEMANTIC`. The category is not a server response.
- `ClickhouseSemanticUnknown` carries a `ClickhouseSemanticUnknownReason` and a
  range. It is not an error or warning diagnostic. Consumers may show unknowns
  separately as information, but must not present them as invalid SQL.

The exact engine version is mandatory. Only `26.10.2.103` has semantic analysis
in this slice; other versions return `ENGINE_VERSION` knowledge gaps. A parser
failure prevents semantic checking and stays in `parseDiagnostics`. Unsafe or
multiple-statement input to the statement API returns `STATEMENT_BOUNDARY`.
Cancellation and unexpected failures propagate rather than pretending the SQL is
clean. The script API returns no results for empty or trivia-only text.

All positions are UTF-16 offsets in the authored script. The checker prefers an
offending node's token, then captured syntax, then positioned descendant bounds,
then a containing syntax span. A final fallback marks the requested statement.
It does not search generated SQL or match repeated names by text. A token span is
not necessarily a complete call-expression span. For example, a bad `toString`
argument count can mark the function name. CRLF and surrogate pairs retain their
original offsets; the returned span never splits a surrogate pair.

Explicit schema facts may establish input families and closed name resolution.
Missing schemas, unresolved names, unknown callables, wrappers, runtime lengths,
timezone values and the CHSEM-011 parser gap stay unknown. The API does not use
generic annotator output as native evidence. It exposes CHSEM-004's rule report,
not CHSEM-005's conditional result inference. Empty error and unknown lists do not
mean an unrelated query was validated or a supported query will execute.

## Consumer integration

```kotlin
val report = clickhouseSemanticDiagnostics(
    script, statementRange, engineVersion = "26.10.2.103",
)
val offline = report.parseDiagnostics + report.errors.map { it.diagnostic }
val combined = combineStatementDiagnostics(
    script, statementRange, offline, nativeParserOutcome,
)
// Display combined.diagnostics. Keep report.unknowns as separate information.
// combined.nativelyAccepted refers to the native outcome, not semantic validity.
```

`combineStatementDiagnostics` now removes only local/editor parser findings when
an authoritative native parser accepts the statement or proves its prefix parses.
It preserves semantic, scope and consumer checks. A positionless native rejection
uses a local parser error as its fallback position, never an unrelated semantic
finding. No native outcome erases offline semantic evidence.

The existing IntelliJ consumer can render the new source using its current
`SqlDiagnostic` hooks. Its message identifies offline semantics separately from
native and local-parser errors. A platform contract feeds the public API's error
through the existing file-diagnostic hook and verifies its text, range and removal
on replacement. Core tests cover combination with native acceptance, rejection,
no verdict and advisory outcomes. No new IntelliJ API calls or profile methods
were added, and no sibling plugin or consumer dependency was changed. Automatic
plugin activation is not implied: a consumer must supply its exact engine version
and choose how to obtain schema facts.

Existing public data classes keep their constructors and `copy` methods. The new
diagnostic source is appended, preserving existing enum ordinals. Consumers with
exhaustive source switches must handle the new value when adopting this API;
the in-repository consumer does so. No release or publication is part of this round.

## Evidence and reproduction

`diagnostics-brikk.json` records the public errors and unknowns for all 50 unchanged
CHSEM-004 cases. Rule names, native categories and unknown reasons match the
historical report. The existing exact-pin `composition-native/` captures remain
the native evidence; this round neither overwrites them nor claims new execution
certification. Python checks tie every exposed error to that corpus and verify
authored UTF-16 spans. JVM fixture tests also prefix every case with a statement,
CRLF, a surrogate pair and a misleading comment to check script offset translation.

Additional tests cover repeated designators, parser/lexical failures, missing and
explicit schemas, trivia-only scripts, invalid API ranges, statement framing,
inline INSERT payloads, runtime unknowns, exact version gating, cancellation and
preservation of existing diagnostic JVM signatures.

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseSemanticDiagnostic*Test' \
  --include-classes='*SqlDiagnosticsTest' \
  --include-classes='*BinaryCompatibilityTest' \
  --jvm-args='-Dbrikk.clickhouseDiagnosticsOut=/tmp/opencode/clickhouse-diagnostics.json'

python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'

BRIKK_INTELLIJ_TESTS=NativeErrorAnnotatorTest \
  ./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

CHSEM-007 remains a separate qualification round. CHSEM-008 through CHSEM-011 are
not implemented or certified by exposing these diagnostics.

## Validation

Full `./kotlin test` passed 1,826 tests with zero failures and 61 optional integration
assumption aborts. The diagnostic binary-compatibility gate passed. Regenerated
CHSEM-004 rules, CHSEM-005 results and primitive matrix remain byte-identical to
their saved captures; this round's public report also regenerates byte-identically.

The affected `NativeErrorAnnotatorTest` suite passed all 33 tests in real IDE
261.25134.95, including the new public-finding consumer contract. Other platform
suites and IDE versions were not run. No new IntelliJ API calls were introduced.
ClickHouse Python discovery passed 38 tests. The complete Python suite passed 86
tests with one opt-in Toolchain-model skip using the unchanged pinned SQLGlot
checkout. No new native executions were needed for this adapter-only round; the
existing exact-version native evidence and its assertions remain unchanged.
