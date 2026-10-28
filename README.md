# brikk-sql

SQL parsing, transpilation, optimization, metadata, and verification for Kotlin.
The generic SQL implementation is a multiplatform port of
[sqlglot](https://github.com/tobymao/sqlglot), with first-class pipe syntax and
documented [Brikk extensions](docs/brikk-extensions.md).

Extracted from [brikk-house](https://github.com/brikk/brikk-house), preserving
relevant Git history and existing Maven coordinates. Kotlin Engine integration
remains in brikk-house; embedded chDB is maintained in
[brikk-chdb](https://github.com/brikk/brikk-chdb).

## Modules and artifacts

All artifacts use the **`dev.brikk.house`** group:

| Module | Maven artifact | Responsibility |
| --- | --- | --- |
| [brikk-sql](brikk-sql/README.md) | `brikk-sql-jvm` | Tokenizer, AST, dialects, parser, generator, optimizer, lineage, and schema/shape analysis. |
| [brikk-sql-metadata](brikk-sql-metadata/) | `brikk-sql-metadata-jvm` | Function/type catalogs and evidence-backed semantic metadata; exported by core. |
| [brikk-sql-verify-api](brikk-sql-verify-api/) | `brikk-sql-verify-api` | `SqlVerifier` contract, four-state `VerifyResult`, and editor-ready error positions; re-exports `brikk-sql-jvm`. |
| [brikk-sql-verify-trino](brikk-sql-verify-trino/) | `brikk-sql-verify-trino` | Trino verifier over trino-parser 484 (Java 25 runtime). |
| [brikk-sql-verify-duckdb](brikk-sql-verify-duckdb/) | `brikk-sql-verify-duckdb` | DuckDB corpus-gate verifier, restricted standalone editor parser, and connection-backed parse-only verifier. |
| [brikk-sql-verify-doris](brikk-sql-verify-doris/) | `brikk-sql-verify-doris` | Doris FE parser verifier, single statement or every error in a script. |
| [brikk-sql-verify-shardingsphere](brikk-sql-verify-shardingsphere/) | `brikk-sql-verify-shardingsphere` | Advisory ShardingSphere grammars for PostgreSQL, MySQL, Hive and ClickHouse. Exclude `org.apache.groovy:groovy`: parsing never uses it and Toolchain cannot publish the exclusion. |
| [brikk-sql-verify-clickhouse](brikk-sql-verify-clickhouse/) | `brikk-sql-verify-clickhouse` | Parse-only ClickHouse verifier over the user's own server (`formatQuery`, never executes); the caller supplies the connection. |
| [brikk-sql-verify](brikk-sql-verify/) | `brikk-sql-verify` | Aggregate of every self-contained verifier plus the `SqlVerifiers` registry (not ClickHouse, which needs the user's server). |
| [brikk-sql-oracle](brikk-sql-oracle/) | `brikk-sql-oracle` | Heavyweight PostgreSQL and chDB ClickHouse engine checks. |
| [brikk-sql-intellij-support](brikk-sql-intellij-support/README.md) | `brikk-sql-intellij-support` | Embedded IntelliJ action, snapshot and console-preparation mechanics; independently versioned. |

Core and metadata target JVM for current publications and retain the `-jvm`
coordinates. They emit Java 21-compatible bytecode, as do the verification
modules; trino-parser itself, and so the Trino verifier and the aggregate, need a
Java 25 runtime. The heavyweight oracle consumes `dev.brikk.house:brikk-chdb:0.16.0`
from Maven Central, without a sibling source checkout or bundled native engine.

Latest core, metadata, verify and oracle release: **`0.20.1`**. SQL IntelliJ
support is independently versioned at **`0.2.4`**. Current core development
snapshot: **`0.20.0-SNAPSHOT`**.

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.brikk.house:brikk-sql-jvm:0.20.1")
    // Optional: implementation("dev.brikk.house:brikk-sql-verify:0.20.1")
}
```

## Dialects

Supported names: `sqlglot` (also `""`), `mysql`, `doris`, `starrocks`, `postgres`
(`postgresql`), `duckdb`, `presto`, `trino`, `clickhouse`, `hive`, `spark2`, `spark`
(`sparksql`), `bigquery`, and `datafusion` (`arrow-datafusion`). Names are
case-insensitive. Use `read=<dialect>` when parsing and `write=<dialect>` when
transpiling.

SQLGlot-backed dialects are checked differentially against the pinned Python
oracle. DataFusion is Brikk-native: its gates use polyglot fixtures, DataFusion
sqllogictest parse acceptance, and hand assertions instead. StarRocks is pinned
to 4.1.4 with a versioned catalog and a deliberately partial evidence-backed
semantic matrix; unprobed functions do not receive inferred certification.

See the [core API guide](brikk-sql/README.md),
[corpus coverage policy](docs/corpus-coverage.md), and
[vendor provenance](vendor/README.md). Current priorities and ownership:
[work list](TODO.md) (`SQL-*`).

The development Trino target is **484**, including its SQL/JSON constructor and
serialization forms, interval precision, window-frame exclusion and NEAREST
relations. The native parser and captured metadata use the same version.
See [the compatibility notes](docs/brikk-extensions.md#58-trino-484-sql-and-metadata).

ClickHouse `STREAM` reads and the `-Tuple` aggregate combinator have native
parse/finite-result checks against pinned 26.8.34.13 LTS and 26.10.2.103 builds.
Streaming execution still requires the server setting `enable_streaming_queries`.
Function/keyword metadata remains at 26.5.1.1 pending the separate catalog refresh.
See [the catch-up evidence](docs/research/clickhouse-2026-catchup.md).

New in `0.17.0`: [source-preserving executable lowering](docs/source-preserving-lowering.md)
retains proved native text while lowering pipes and reports regenerated intervals.
The opt-in API leaves the existing rendering paths unchanged.

`0.18.0` adds the DDB-001 DuckDB template-projection fix and updates the DuckDB
reference engine/catalogs to 1.5.6 (JDBC 1.5.6.0).

`0.18.1` registers researched DuckDB ↔ Doris CAST coverage: `::BIGINT` and
explicit CAST now receive a conditional semantic warning instead of an unknown-
coverage refusal. Rounding, parsing and error/NULL divergences remain visible;
the pair stays PARTIAL, and TRY_CAST cannot borrow plain CAST evidence.
See the [CAST research report](docs/research/REPORT-duckdb-doris-cast-coverage-2026-10-10.md).

`0.19.0` adds editor-facing script snapshots, parse-only parameter occurrence
ranges and zero-argument `rows()` relation slots. The independently published
IntelliJ support 0.1.0 uses platform types and callbacks, with no engine dependency.
See [the release record](docs/intellij-support-0.1.0.md).
IntelliJ support [0.1.1](docs/intellij-support-0.1.1.md) adds a shared rich
column-completion formatter with aliases, nullability and origin labels.
The matching verify/oracle artifacts followed in a
[separate scoped release](docs/verify-oracle-0.19.0.md).

`0.19.1` rejects unterminated block comments, including nested comments and
comment-only script tails. Editor snapshots now mark these ranges incomplete so
consumers cannot execute a valid prefix after malformed input.

`0.20.1` targets DuckDB 2.0.0 (`duckdb_jdbc:2.0.0.0`), with final-engine
function/type/keyword captures, PEG-parser error locations, Python-style lambda
generation, `NEAREST` joins, session routing, triggers, nested schemas, DML CTEs,
`FETCH FIRST`, grouping by `UNNEST`, JSON mutation functions and `VARIANT` metadata.

`0.20.1` adds `ConnectionDuckdbVerifier` for parse-only checks on a caller's
DuckDB instance and active extension grammar profile. Unlike the restricted standalone
editor parser, it can use registered custom PEG grammars. The IDE consumer owns grammar
selection and connection setup; the shared parser does not infer or install extensions.
SQL IntelliJ support `0.2.4` exposes its existing query and trusted session-initialization helpers
so consumers can configure isolated helpers without duplicating remote JDBC handling.
Both releases are published on Maven Central from commit `1387aad`.

## Build and test

Use the pinned **Kotlin Toolchain 0.13.0** wrapper. Kotlin remains pinned to
**2.4.10**. On a fresh checkout, create a placeholder credentials file for the
publish-only repository configuration; real credentials are needed only to publish:

```sh
printf 'brikk.mavencentral.user=x\nbrikk.mavencentral.pass=x\n' > .env
./kotlin build
./kotlin test
./kotlin publish mavenLocal
```

No Engine plugin assembly, sibling repository, Python oracle checkout, or native
chDB installation is needed for the normal build/test suite. Committed fixtures
and the vendored Doris parser are sufficient. The optional explicit-library
ClickHouse integration test skips when its library property is not configured.
Corpus failures write review artifacts under `build/ledger-actual/`.

The IntelliJ helper build provisions a checksum-pinned IDEA 261 SDK on Linux x64,
or accepts `BRIKK_INTELLIJ_SDK` on other hosts. SDK classes are build inputs only.
Core-only commands such as `./kotlin build -m brikk-sql` do not provision that SDK.

Generators live in `tools/`. Some require pinned, gitignored upstream checkouts
under `reference/`; see the research and vendor documentation before refreshing
them. The [consumer verification harness](docs/consumer-verification.md) validates
candidate SQL JARs against a separate Doris IDE consumer.

## Publishing

The SQL engine, metadata and verification artifacts share the publishing template.
IntelliJ support has its own version and scoped release command; build tooling is
never published. The template owns sources, POM metadata and repository URLs.

- Push to `main`: build, full tests, and Maven Local publication gate, then publish
  `0.20.0-SNAPSHOT` to `https://central.sonatype.com/repository/maven-snapshots/`.
- Push `release/<version>` or manually dispatch the release workflow: the same
  gate, then signed Maven Central release bundles.
- Local snapshot: `./kotlin publish centralSnapshots`.
- Local release: `./publish-release.sh <version>` for all aligned SQL modules (core,
  metadata, the verify API, engine and aggregate artifacts, oracle), or name a subset,
  for example `./publish-release.sh 0.19.0 brikk-sql-verify brikk-sql-oracle`. A subset's
  in-repo dependencies must already be released at that version.
  Manual release workflow dispatch accepts the same selection as a comma-separated
  `modules` input. Selected releases preserve the existing core artifacts.
- IDE support release: `python3 tools/publish_intellij_support.py <support-version>`.
  Add `--core-version <version>` only when explicitly releasing core and metadata
  prerequisites too. This command never releases verify/oracle.

Required GitHub organization/repository secrets:
`KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME`, `KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD`,
`KOTLIN_TOOLCHAIN_SIGNING_KEY`, and `KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE`.
The workflows also accept `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME/PASSWORD`.
For local snapshots, `.env` uses `brikk.mavencentral.user/pass`; local releases
use Python 3 (standard library only) and the corresponding `KOTLIN_TOOLCHAIN_*`
environment variables. The release launcher validates SemVer 2.0.0, composes a
temporary Toolchain template override for the version/Portal/signing settings,
and restores the original even on failure or termination. It invokes the explicit
Portal tasks rather than the repository-validation-sensitive `publish mavenCentral`
command. Do not reuse an already published version. SQL and chDB release versions
can now advance independently.

Snapshots resolve by adding the snapshots repository explicitly. Releases
resolve from Maven Central with no extra repository.

## License

Apache-2.0; see [LICENSE](LICENSE) and [ATTRIBUTIONS.md](ATTRIBUTIONS.md), including
the retained MIT notices for SQLGlot-derived code and fixtures.
