# SQL 0.22.0 and IntelliJ support 0.3.0 releases

Published and verified on Maven Central on 2026-11-02 from source commit
`55334158c7936325dba438da0746bce3a700ab69`, frozen at `release/0.22.0`.
The [SQL release workflow](https://github.com/brikk/brikk-sql/actions/runs/41517590051)
and [IntelliJ release workflow](https://github.com/brikk/brikk-sql/actions/runs/41521825409)
both passed their full merged-tree gates before publication.

## Coordinates

All artifacts use group `dev.brikk.house`. Resolve them with `mavenCentral()`.

SQL `0.22.0` includes all 12 aligned coordinates:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`

IntelliJ `0.3.0` includes `brikk-sql-intellij-support`,
`brikk-sql-intellij-jdbc-support` and `brikk-sql-intellij-test-support`.
Their dependency graph references SQL `0.22.0`. IntelliJ publication used
`publish_core=false`; it did not republish the immutable SQL prerequisites.
The [consumer guide](datafusion-spice-consumer.md#released-artifacts) lists the
plugin-facing coordinates and adoption checks.

## Scope and limits

The release merges DataFusion and Spice work at `1417133` with main's
Trino/Doris Unicode case-fold correction at `4716b17`. It includes native
parser/generator support, structured utilities, type/scope/parameter/lineage
analysis, metadata, editor integrations and connection-aware catalogs.

Qualification targets DataFusion 55.2.0 and Spice v2.4.0 with the pinned driver
and explicit [support boundaries](datafusion-spice-support.md). Publication does
not remove the documented Flight execution and null-binding exclusions or
qualify a production IntelliJ plugin. No plugin repository, consumer dependency
pins or unrelated ClickHouse/DuckDB feature branches changed for this release.
Original plans and evidence records retain their historical release-hold language.
This approved shared-library release supersedes that hold, not the separate
authorization required for downstream plugin work.

Core, metadata, verification API and Doris verifier retain Java 17 compatibility.
IDE support retains Java 21 bytecode. Trino and aggregate verifier runtimes need
Java 25 because of trino-parser; oracle also requires Java 25.

## Verification

The local merged-tree gate and both CI gates built every module, ran the core
suite separately, checked the remaining modules, ran offline release and
DataFusion/Spice evidence tests, and audited Maven Local publications.
The SQL CI gate recorded 1,370 successful core tests, 453 successful other
module tests, 61 expected optional-engine aborts and 816 IntelliJ platform tests
on IDE 261.25134.95. No test expectations or resource limits were weakened.

The first IntelliJ upload failed with `UnresolvedAddressException`, before any
accepted deployment was reported. All three coordinates were checked absent
from Central before retrying only the failed publish job on the same source.
The second attempt succeeded while retaining the successful test gate.

The public audit downloaded all 15 coordinates from
`https://repo.maven.apache.org/maven2/`, independently of Maven Local. It checked:

- POMs, Gradle module metadata, binary and source JARs, with no SNAPSHOT references
  or misaligned release dependencies.
- ZIP integrity, packaged bytecode targets, verifier and Java 17 dependency
  contracts, IntelliJ notices/packaging and representative sources against the
  frozen source tree.
- All 60 payload checksums and detached PGP signatures. The verified signer is
  `C8079FC776361E633B091E534E674B3D5F551356`, the same signer as SQL 0.21.0.

Local evidence is under `build/release-022-030-gate/` and
`build/release-022-030-public/`, including `sql-verified.json` and
`ide-verified.json` with URLs, SHA-256 hashes and signature results.

Development resumes at SQL `0.23.0-SNAPSHOT` and IntelliJ `0.3.1-SNAPSHOT` so
the development bases sort after the latest stable versions. The published
`0.22.0` and `0.3.0` coordinates remain immutable.
