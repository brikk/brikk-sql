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
| [brikk-sql-verify](brikk-sql-verify/) | `brikk-sql-verify` | Native-grammar verification using Trino, DuckDB, and Doris; ShardingSphere advisory parsers. |
| [brikk-sql-oracle](brikk-sql-oracle/) | `brikk-sql-oracle` | Heavyweight PostgreSQL and chDB ClickHouse engine checks. |

Core and metadata target JVM for current publications and retain the `-jvm`
coordinates. They emit Java 21-compatible bytecode. Verification modules require
JDK 25. The heavyweight oracle consumes `dev.brikk.house:brikk-chdb:0.16.0`
from Maven Central, without a sibling source checkout or bundled native engine.

Release: **`0.18.0`**. This release branch pins the publication version to `0.18.0`;
development on `main` advances to `0.19.0-SNAPSHOT` after publication.

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.brikk.house:brikk-sql-jvm:0.18.0")
    // Optional: implementation("dev.brikk.house:brikk-sql-verify:0.18.0")
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
[vendor provenance](vendor/README.md).

New in `0.17.0`: [source-preserving executable lowering](docs/source-preserving-lowering.md)
retains proved native text while lowering pipes and reports regenerated intervals.
The opt-in API leaves the existing rendering paths unchanged.

`0.18.0` adds the DDB-001 DuckDB template-projection fix and updates the DuckDB
reference engine/catalogs to 1.5.6 (JDBC 1.5.6.0).

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

Generators live in `tools/`. Some require pinned, gitignored upstream checkouts
under `reference/`; see the research and vendor documentation before refreshing
them. The [consumer verification harness](docs/consumer-verification.md) validates
candidate SQL JARs against a separate Doris IDE consumer.

## Publishing

This repository publishes **only the four SQL artifacts** above. The module
template owns the version, sources, POM metadata, and repository URLs.

- Push to `main`: build, full tests, and Maven Local publication gate, then publish
  the current development version to `https://central.sonatype.com/repository/maven-snapshots/`.
- Push `release/<version>` or manually dispatch the release workflow: the same
  gate, then signed Maven Central release bundles.
- Local snapshot: `./kotlin publish centralSnapshots`.
- Local release: `./publish-release.sh <version>`.

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
