# ClickHouse main integration

The user authorized merging current main into clickhouse-catchup, checking the
combined result, then integrating it into main and waiting for DuckDB updates
before another stable release. Snapshots and Maven Local candidates are allowed.

Source baseline: main `825066f`, including Java 17 embedding support from `fcd1b91`.
Combined implementation: merge `b413763`, containing ClickHouse completion `cf7c365`.
No production fix was needed after merging main.

## Combined checks

- Full `./kotlin test` with the batch-4 audit properties: 1,817 discovered,
  1,756 passed, 61 optional engine assumptions aborted, zero failures. The Java 17
  minimum-runtime and bytecode tests pass for core, metadata, verifier API and Doris.
- Actual ClickHouse audit output is identical to the saved 63-row final capture.
  Fresh pinned native checks pass on both engines: 23 original/generated outcomes
  on 26.8.34.13 and 41 on 26.10.2.103, including expected native errors.
- Full IntelliJ platform contracts on 261.25134.95: 753 passed. The focused
  PipeStagePhrasesTest also passes all five methods.
- All Python tooling tests with the release-model check enabled: 57 passed.
  Generator tests need the pinned SQLGlot development dependency. The missing
  local dependency was supplied from exact commit
  `a00dc2e5c489214bea53bacbbeba8713f21ebce1` under /tmp/opencode via PYTHONPATH;
  no source generation, global installation or gate suppression was performed.

The IDE suite initially found an obsolete assertion that ClickHouse NATURAL pipe
joins must be refused. It is now a positive capability/lowering test tied to the
batch-4 native evidence. The unsupported STRAIGHT_JOIN assertions remain. No
production IntelliJ behavior was changed to satisfy the test.

## IntelliJ API-risk boundary

The user explicitly prohibited new internal/base-class API calls or increased
experimental API usage unless unavoidable and reviewed first. No such addition
was needed.

There is no production source difference from baseline main in:

- brikk-sql-intellij-support/src
- brikk-sql-intellij-tooling/src
- brikk-sql-intellij-test-support/src

Static bytecode inventories of IntelliJ/JetBrains references and direct callsites
show no additions or increased counts versus published support 0.2.4, for both the
506-class common support artifact and the one-class optional JDBC support artifact.
This preserves their existing risk baseline and optional-internal-gate separation;
it does not claim the baseline has no warnings or replace a plugin's own release
qualification. No new internal IntelliJ calls or experimental callsites are added.

## Handoff and publication boundary

The ClickHouse plugin receives the release-note/adoption handoff in its OpenChamber
Project Notes. Direct session delivery requires that project folder to be trusted.
The mistakenly addressed DuckDB project note was removed after the user corrected
the recipient. Neither plugin's source nor dependency pins were changed.

The original integration candidates used SQL 0.20.0-SNAPSHOT coordinates and
IntelliJ support 0.2.3-SNAPSHOT coordinates. Those lower-numbered development
streams were subsequently corrected to SQL 0.22.0-SNAPSHOT and IDE support
0.2.5-SNAPSHOT, ahead of the published stable versions. Keep the SQL
dependency graph aligned when testing; published stable 0.21.0 does not contain
these ClickHouse additions. Maven Local publication is followed by artifact audits,
a Java 17 packaged-core smoke and a repeated packaged IntelliJ API-delta check.

Main's normal push workflow may publish a snapshot after its own gates. No stable
release/tag or consumer dependency update is authorized by this integration. Wait
on main for the remaining DuckDB work and further user direction.

## Corrected snapshot interval blocker

Before publishing the corrected versions, the user reported a core scope crash on
`SELECT CAST(NULL AS INTERVAL DAY(3) TO SECOND(12))` in Trino. The previous packaged
snapshot reproduces it on Java 17, outside IntelliJ. Base type generation cannot
render `TrinoIntervalQualifier`.

Scope computed types and fact canonicalization, fragment output shapes, and DDL
catalogs now share a type renderer. Types containing the native qualifier use their
source dialect's generator; other types retain the existing base-normalized
spelling. This preserves interval precision without changing ordinary type names
or claiming cross-engine equivalence. Regression coverage includes leading and
fractional precision, year-to-month intervals, fact/computed unions, output shapes,
DDL catalog reuse, and IDE diagnostics/highlighting. No production IntelliJ source
or public data-class signature changed.

The first corrected-version workflow, `40921787130`, failed timing assertions in
tokenizer and completion linearity tests, independently of this crash. Its publish
job was skipped. Both timing tests pass in the local blocker-fix run; no timing
threshold or gate was relaxed.

Blocker-fix validation: 1,820 JVM tests discovered, 1,759 passed, 61 optional
assumptions aborted, zero failures; 754 IDE platform contracts passed on
261.25134.95; all 60 tooling/model tests passed. Maven Local publication and the
Java 17, verifier and IDE artifact audits passed. The actual packaged core passes
the exact interval scope/shape smoke on Java 17, as well as the ClickHouse smoke.
Packaged common/JDBC IDE support still adds no IntelliJ/JetBrains references or
callsites versus published 0.2.4. Local manifests and logs are under
`/tmp/opencode/snapshot-version-correction/`; public availability is checked
separately after main CI publishes the fixed build.

The fixed-build workflow `40925135049` again stopped before publication, solely on
tokenizer scaling. The scaling tests previously warmed and measured the small
input before warming the large one, carrying discarded token/AST allocations
between samples. They now warm both sizes first and request collection before
each sample, outside its timer. Input sizes, sample counts, wall-clock measurement,
the 10x limit for 4x input, and all tested entry points remain unchanged. This is a
test-only correction; the packaged production artifacts are unchanged.
Three repeated scaling runs with a 512 MB heap and two active processors passed,
followed by another successful full JVM suite.
