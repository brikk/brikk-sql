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

Development candidates use the existing SQL 0.20.0-SNAPSHOT coordinates and the
separately versioned IntelliJ support 0.2.3-SNAPSHOT coordinates. Keep the SQL
dependency graph aligned when testing; published stable 0.21.0 does not contain
these ClickHouse additions. Maven Local publication is followed by artifact audits,
a Java 17 packaged-core smoke and a repeated packaged IntelliJ API-delta check.

Main's normal push workflow may publish a snapshot after its own gates. No stable
release/tag or consumer dependency update is authorized by this integration. Wait
on main for the remaining DuckDB work and further user direction.
