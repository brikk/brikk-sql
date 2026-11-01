# DFS-012 qualification

This round joins the shared-library consumer fixture, fresh pinned native checks and the
final support exclusions. See [the support matrix](../../../../docs/datafusion-spice-support.md)
and [consumer guide](../../../../docs/datafusion-spice-consumer.md).

## Captures

- `brikk-datafusion.json` and `brikk-spice.json`: current generator observations for the
  stable 76-case corpus at `../cases.json`.
- `datafusion-native.json`: pinned upstream parser and selected execution results for
  original and regenerated SQL.
- `spice-native.json`: pinned product parser reference plus the checksum-verified Spice
  v2.4.0+models runtime over disposable loopback HTTP. Search/model calls remain parser-only.
- `datafusion-jdbc.json` and `spice-jdbc.json`: real Arrow Flight SQL JDBC 19.0.0 metadata
  and production Kotlin harvester results.
- `datafusion-execution.json` and `spice-execution.json`: fresh binding, session, error,
  EXPLAIN, bounded cancellation and ten Brikk-stage execution controls per product.
- `qualification.json`: hashes, completed gates, exact profiles and exclusions.

T1 through T4 remain explicit exclusions. The fresh runs retain their negative controls.
The earlier cross-join stress failures stay in round10; this round reruns bounded cancellation,
not those stress queries. RESET's 25-case parser-only evidence stays in `../reset`.
`DFS-012-S1` explicitly excludes ten DataFusion controls that still have opaque `Command`
nodes from structured-syntax support. Passing native round trips never overrides that exclusion.
The evidence gate enumerates these cases and requires Spice's corpus to remain free of opaque commands.

## Reproduction

Use a new output directory. Build the pinned binaries as described in
`tools/datafusion-reference/README.md`, and verify the released Spice binary checksum.
Run Kotlin commands serially because they share build outputs.

```sh
./kotlin build
TEST_KOTLIN_RELEASE_MODEL=1 python3 -m unittest discover -s tools -p 'test_*release*.py'
./kotlin test -m brikk-sql --jvm-args="-Xmx1g -XX:ActiveProcessorCount=2"
./kotlin test --exclude-module=brikk-sql
BRIKK_INTELLIJ_SDK=/path/to/idea-261 ./kotlin check intellijPlatform -m brikk-sql-intellij-support
./kotlin publish mavenLocal
python3 tools/verify_verifier_publication.py
python3 tools/verify_java17_publication.py
python3 tools/verify_intellij_publication.py
python3 -m unittest discover -s tools -p 'test_*datafusion*.py'
python3 -m unittest discover -s tools -p 'test_spice_native_evidence.py'

./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/cases.json build/review/brikk-datafusion.json
./kotlin run -m dialect-audit -- spice vendor/data/datafusion-spice/cases.json build/review/brikk-spice.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference \
  --brikk build/review/brikk-datafusion.json --output build/review/datafusion-native.json
python3 tools/probe_datafusion_spice.py --spice-parser build/spice-parser-reference/debug/brikk-spice-parser-reference \
  --spiced /path/to/verified/spiced --brikk build/review/brikk-spice.json --output build/review/spice-native.json
```

Use `BRIKK_INTELLIJ_RUNTIME_SDK` for the 262 and 263 runtime runs. The focused matrix includes
`DatafusionConsumerTest`, all other DataFusion platform contract classes, the live-catalog
ABI contracts and `InternalApiContainmentTest`. The latter audits packaged support bytecode
and the consumer fixture, including inherited internal/experimental annotations.

Run `probe_flight_catalog.py` with `--harvester-classpath-file` and
`probe_flight_execution.py` with `--classpath-file`, each pointing at the platform run's
`test.log.classpath`. Supply `--output` under the new directory. For DataFusion, supply
`--datafusion-flight` pointing to `brikk-datafusion-flight` for catalog checks and
`brikk-datafusion-flight-execution` for execution checks. For Spice, supply `--spiced`.
Use `--java` for the tested driver JDK and, on Java 25,
`--java-option=--sun-misc-unsafe-memory-access=allow`.

## Environment recovery

The first repository-wide run passed build, release-configuration checks and all 1,363 core
tests. The remaining-module run failed during native-library extraction with `Disk quota
exceeded`; the subsequent machine interruption cleared `/tmp`, including temporary gate logs
and runtimes. Source edits survived. The resumed run keeps logs and the verified Spice binary
under `build/qualification/dfs012` and uses a disk-backed `java.io.tmpdir` for native tests.
Those infrastructure failures are not counted as passed or skipped dialect evidence.

Local packaging also found that the non-published `dialect-audit` module lacked the shared
publish-target declarations required by Toolchain 0.12. Applying the existing
`publish-targets.module-template.yaml` fixes the project-wide `mavenLocal` gate while keeping
that tool's `publishing.enabled` false.

## Completed gates

- Repository build and 38 release-configuration checks passed.
- All 1,363 core tests passed. The remaining modules passed 452 tests, including 129 metadata
  tests, with zero failures. They aborted 61 optional tests for unconfigured unrelated engines;
  `qualification.json` lists every reason and count. None supplies DataFusion/Spice evidence.
- The full shared IDE suite passed 814 tests on 261.25134.95. The final focused suite passed
  89 tests on each of 261.25134.95, 262.10968.63 and 263.5701.42, including ABI, packaged API
  containment and the new consumer-fixture API audit. That audit uses class resources because
  newer IDE classloaders omit the fixture's `CodeSource.location`.
- Local Maven packaging and verifier, Java 17 and IntelliJ publication audits passed. No
  descriptor or test-only consumer fixture leaks into the shared artifacts.
- All 57 DataFusion Python contracts and 3 Spice native-evidence contracts passed.
- Both native references replayed the 76-case corpus. All accepted originals have accepted
  regenerated parses. Measured successful original/generated executions agree in 22 DataFusion
  and 30 Spice controls. Missing generated execution controls correspond to rejected native
  originals, enumerated in `qualification.json`; they are not counted as supported execution.
- Both production Kotlin harvesters passed against the pinned real JDBC driver, retaining
  320 DataFusion and 447 Spice name/kind entries. All twenty lowered stage controls return
  expected rows. Negative transport controls and opaque syntax exclusions remain explicit.

Remote publication, dependency updates and a weekly scheduler are not part of this qualification.
