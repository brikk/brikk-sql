# SQL and IntelliJ support 1.0.1 release

Published to Maven Central on 2026-11-09 UTC from `0ecace0f` on `main`. All SQL and IDE
helper artifacts are at `1.0.1`. This is the first release cut under the rule that releases
come from `main` ([AGENTS.md](../AGENTS.md)); 1.0.0 was cut from the `sqlglot-30-29-batch-a`
branch, which is now merged.

## Coordinates

Group `dev.brikk.house`, resolved from `mavenCentral()`, the same 15 coordinates as 1.0.0:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`
- `brikk-sql-intellij-support`
- `brikk-sql-intellij-jdbc-support`
- `brikk-sql-intellij-test-support`

IntelliJ support takes released core, metadata and verification API at `1.0.1`
(`publish_core=false`).

## What changed since 1.0.0

1.0.1 is a cleanup release on top of 1.0.0's SQLGlot 30.29 catch-up. Generated SQL, parse
results and analysis facts are unchanged for every corpus case and for every input the six
IntelliJ plugins probed; the changes are structural.

New public type:

- `dev.brikk.house.sql.BrikkSqlError`, the common base of `ParseError`, `TokenError`,
  `UnsupportedError`, `OptimizeError`, `SchemaError`, `ShapeError`,
  `TranspileCertificationError` and `SqlglotError`. Callers can catch the family in one clause.
  Existing specific catches, constructors, messages and causes are unchanged.
  `UnknownDialectException` stays an `IllegalArgumentException` because it reports a bad
  argument, not a SQL error.

Internal changes consumers may notice only in stack traces:

- DuckDB executable-boundary refusals moved out of `Generator.generate()` into
  `dialects/DuckdbExecutableGuards.kt` behind a `checkExecutableBoundaries` hook. Every
  refusal message, firing order and direction is pinned by a 1,894-row golden file
  (`DuckdbExecutableGuardsTest`). One tree walk replaces 23.
- 142 `dialect.name == "..."` string checks in the optimizer, scope and shape passes became
  typed `internal open` capability flags and hooks on `Dialect`, overridden by the dialect
  that owns the behavior. DuckDB typing and nullability rules now live in
  `DuckdbExpressionTypes.kt` and `DuckdbNullability.kt`.
- Duplicate dialect helpers (`seqGet`, `unitToStr`, the per-dialect `*FormatTime` wrappers,
  `SAFE_IDENTIFIER_RE`, Doris/StarRocks date builders) share one definition each. Five dead
  private functions were removed.
- Three `open val` flags that nothing read are `@Deprecated`, not removed:
  `Generator.aggregateFilterSupported`, `Generator.dialectIdentifiersCanStartWithDollar`,
  `Parser.colonIsVariantExtract`.
- Per-node regex literals are hoisted to constants; compressed-style DuckDB files are
  reformatted; restating comments and ticket narration are removed.

Removed, not visible to Kotlin callers: the top-level `internal fun`s `bqFormatTime` and
`hiveFormatTime` no longer exist in bytecode. A `javap` diff of the 1.0.0 and 1.0.1 jars
shows no other public or protected member removed. `brikk-sql-verify-api`, every
`brikk-sql-verify-*` engine jar and `brikk-sql-intellij-jdbc-support` are byte-identical to
1.0.0.

Also in 1.0.1 relative to 1.0.0:

- A test-only race fix in `ConnectionDuckdbVerifierTest` (shared worker pool).
- Developer docs rewritten as trackers and changelogs; `dev-docs/CONTRIBUTING.md` names the
  SQLGlot pin `v30.29.0` (`2fe25297`).

## Release qualification

Both workflows passed their full gates on `0ecace0f` without a preverified bypass:

- [SQL release](https://github.com/brikk/brikk-sql/actions/runs/42705949471)
- [IntelliJ support release](https://github.com/brikk/brikk-sql/actions/runs/42710651683)

The SQL gate ran 2,722 JVM tests with 2,650 successful, 0 failed and the 72 existing
optional-engine assumption aborts, plus all 841 IntelliJ platform tests on 261.25134.95.
Locally, the core module is 1,880 tests at 0 failures and the Python tooling gate is 251 tests
at the `v30.29.0` pin.

## Consumer verification

Before this release, each of the six IntelliJ plugins (DuckDB, Doris, Trino, ClickHouse,
DataFusion/Spice, SQL Transpiler) was built and tested against 1.0.0 from Central and then
against the `1.0.1-SNAPSHOT` of `0ecace0f` from Maven Local. No plugin test changed result
between 1.0.0 and 1.0.1-SNAPSHOT, every plugin verifier reported Compatible on its target
IDEs, and direct probes through each plugin's own entry point produced byte-identical output
between the two. Moving from 0.23.0 to 1.0.x changes output for inputs the plugin suites do not
cover: `13END` lexing, the VALUES alias before a nested join, MySQL `PRIMARY KEY (col DESC)`,
Doris `MATCH_ANY`, MySQL `!x` as `(NOT x)`.

## Adopting 1.0.1

- Set all `dev.brikk.house` pins to `1.0.1`; IntelliJ support shares the SQL version line.
- Nothing needs to change to compile. Optional: catch `BrikkSqlError` where a consumer today
  lists several brikk error classes.
- Plugins that bundle the jars must update third-party notices to the 1.0.1 coordinates.
