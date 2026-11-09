# SQLGlot 30.29 batch A evidence

The adjacent `sqlglot-30.29-batch-a-baseline.json` retains three observations from
the original 23-case review against published Brikk SQL 0.23.0. These are the
confirmed batch A gaps, not the unimplemented candidates from later batches.
Artifact hashes and upstream identities are explicit. The old results are
historical evidence, not expectations to rewrite after implementing the fixes.

The SQL examples are hand-owned reproductions. The ports reference SQLGlot's
MIT-licensed changes, whose notice is retained in `ATTRIBUTIONS.md`.

BigQuery's [GoogleSQL lexical specification](https://cloud.google.com/bigquery/docs/reference/standard-sql/lexical#quoted_identifiers),
checked November 3, 2026, independently states that quoted identifiers use the
same escape sequences as string literals. Its escape table explicitly lists
backslash-backtick and doubled backslash. The exact-spelling tests therefore do
not rely only on agreement between Brikk's writer and reader.

The DESCRIBE port also rejects a leading bare dot locally. Brikk's general
table-parts parser tolerates empty dot components for other syntax, so the
upstream rewind guard alone stopped the recursion but still accepted `DESC.`.
The local check supplies the required positioned error without changing those
shared table rules.

Regression tests live in `brikk-sql/test/dev.brikk.house.sql/`:

- `DescribeRobustnessCatchupTest.kt` checks malformed edits, positioned errors,
  parser reuse, qualified kind/style names and Brikk's described-call extensions.
- `JsonPathIndexCatchupTest.kt` checks invalid and overflowing integer indexes,
  valid Int bounds, slice/union handling, serde and SQL raw-path fallback.
- `BigqueryIdentifierEscapingTest.kt` checks exact native spellings independently
  of round-trip tests, runtime parser defaults, repeated escapes, qualified names,
  string independence, source spans and non-mutation.

Run with the repository's pinned toolchain:

```sh
./kotlin test -m brikk-sql --include-classes='*DescribeRobustnessCatchupTest' --include-classes='*JsonPathIndexCatchupTest' --include-classes='*BigqueryIdentifierEscapingTest' --jvm-args='-Xmx1g -XX:ActiveProcessorCount=2'
```

Run the full core corpus and ABI gates before accepting the batch. The global
oracle, generated tables and exact ledgers remain pinned to a00dc2e5c. Runtime
BigQuery escape policy is an explicit overlay until a coherent future refresh.

No live BigQuery, MySQL or other engine execution is represented by these parser
and renderer tests. In particular, retaining a malformed JSONPath as a raw SQL
literal is the existing syntax fallback, not a new execution capability claim.

## Validation on November 3, 2026

- Before the fixes, the new tests reproduced DESCRIBE stack overflow, JSONPath
  numeric-conversion leakage and the BigQuery spelling/decoding errors. The red
  run also exposed one incorrect test expectation for Doris's existing DESCRIBE
  spelling, which was corrected without changing Doris behavior.
- All modules build. The full core suite passes 1,747 tests with no skips or
  aborts, including the 13 new regression methods, original corpus/ledger gates,
  Engine analysis contracts and constructor/default-bridge compatibility tests.
- Remaining JVM modules pass 732 tests with zero failures after provisioning the
  genuine signed-window fixture. Another 72 tests are assumption-aborted for
  other optional live engines or signed native fixtures. The first run without
  the window template had 730 passes and 74 assumption aborts. No
  assumptions were removed or supplied with fabricated evidence.
- Full Python discovery passes 224 tests with one opt-in skip out of 225, using
  the unchanged a00dc2e5c Python reference from a task-private source checkout.
- The final shared IntelliJ gate passes all 827 tests on `261.25134.95`, with
  the genuine native window capture supplied and no test exclusions. No other
  IDE versions or downstream plugins were qualified in this batch. IntelliJ
  production sources and external API call sites are unchanged.
- Core's 2,855 and metadata's 143 compiled classes all retain Java 17 bytecode,
  major version 61. A Java consumer compiled against the published 0.23.0 core
  JAR links against the new compiled classes and passes all three original gap
  probes on actual Temurin 17.0.21+9. This is a facade compatibility smoke, not a
  new publication or an exhaustive binary API comparison.
- The ten changed Kotlin source/test hashes are frozen in the local
  `build/sqlglot-batch-a/source-manifest.json`. Generated tokenizer tables,
  oracle fixtures, exact ledgers, the global pin and IntelliJ production sources
  remain unchanged.

Task logs are retained under `/var/tmp/jayson/opencode/` with the prefix
`sqlglot-batch-a-`: `red.log`, `focused.log`, `core.log`, `build.log`,
`other-jvm.log`, `other-jvm-with-window.log`, `python.log`, `java17-smoke.log`,
`window-build.log`, `ide.log` and `ide-with-window.log`. The initial
focused run retains the discovery that the rewind guard alone still accepted a
bare dot, before the scoped DESCRIBE validation was added. A test also initially
assumed general placeholders carried SourceMap metadata; the final test checks
the existing exact parameter-occurrence API instead. These are retained failed
iterations, not passing evidence. The full core result above is the final run.

The first headless IntelliJ attempt ran 827 tests and failed only the two
live-window controls because `BRIKK_DUCKDB_WINDOW_CAPTURE` was absent. These
JUnit 3 assumption exceptions count as failures, not successful optional skips.
The documented CI setup uses the real window fixture built by
`tools/build_duckdb_window_fixture.py` from exact DuckDB revision
`d54cbbe3df17e8e5ce68312f483b4155836112aa` and its checksum-pinned static archive.
No capture or signed rows are hand-written, and no test/trust guard is relaxed.

After building the documented fixture, reproduce the real capture and IDE gate
from the same checkout:

```sh
export BRIKK_DUCKDB_WINDOW_EXTENSION="$PWD/build/duckdb-window-fixture/brikk_window.duckdb_extension"
export BRIKK_DUCKDB_WINDOW_CAPTURE="$PWD/build/duckdb-window-fixture/catalog.json"
./kotlin test -m brikk-sql-verify-duckdb --include-classes='*DuckdbWindowCatalogResultTest'
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

The native test signs only scratch copies with repository test keys, rejects the
unsigned template and tampered payloads, executes the registered window functions
and checks isolation from a separate database. It does not alter a user's trust
store or connection. The capture carries the real version, source revision,
template hash and executed-result provenance that the IDE tests validate.

The freshly built template SHA-256 is
`bd9492c54b9f2adbea785d54a57c4f297e917f16aea665afb953fc1458df930f`.
The real capture reports DuckDB `v2.0.0`, the required source revision,
`allow_unsigned_extensions=false` and `executed_window_results=true`; its
template hash matches `build/duckdb-window-fixture/build.json` and the binary.
The final native/JVM and IDE reruns use that capture. Both succeed; the first
IDE attempt remains recorded as a fixture-setup failure rather than being erased
or relabeled as a pass. No existing IDE test or fixture-building code was changed.

Batch A stops here. Batches B through F, commits/pushes, publication and consumer
adoption remain outside the approved work. All implementation, tests, TODO
updates and retained evidence are uncommitted in `sqlglot-30-29-batch-a`.
