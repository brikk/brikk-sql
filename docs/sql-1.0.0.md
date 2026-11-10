# SQL and IntelliJ support 1.0.0 release

Published and independently verified on Maven Central on 2026-11-06 UTC from
`99bd49004e788c64ffd0e8af28968b1cc3782b71`. The owner authorized all SQL and
IDE helper artifacts at `1.0.0`.

## Coordinates

All artifacts use group `dev.brikk.house` and resolve from `mavenCentral()`.
The 13 publishable modules produce 15 coordinates, including KMP root/JVM pairs:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`
- `brikk-sql-intellij-support`
- `brikk-sql-intellij-jdbc-support`
- `brikk-sql-intellij-test-support`

Every coordinate above is version `1.0.0`. IntelliJ support takes released core,
metadata and verification API at `1.0.0`. Its workflow uses `publish_core=false`
and does not republish the SQL prerequisites. External engines and brikk-chdb
retain their own pinned versions.

## Release qualification

Both workflows passed their full gates on the same frozen commit, without a
preverified bypass:

- [SQL release](https://github.com/brikk/brikk-sql/actions/runs/42296840851)
- [IntelliJ support release](https://github.com/brikk/brikk-sql/actions/runs/42296854623)

Each gate built all modules, passed 2,633 JVM tests and all 841 IntelliJ tests on
261.25134.95, with zero failures. The 72 existing optional JVM assumptions remain.
The native DuckDB window fixture was built and checked before the unfiltered IDE
gate. Each workflow also passed the enabled release-model tests and configured
DataFusion, Spice and ClickHouse evidence tests. Formal release candidates and
publication packaging were audited before upload.

The preceding local validation passed all 248 Python tooling tests with the
release-model check enabled. The main integration and its original failing logs
are recorded in [the integration record](../dev-docs/archive/main-integration-20261106.md).

## Independent public verification

The audit downloaded all 15 coordinates directly from
`https://repo.maven.apache.org/maven2/`, independently of Maven Local and upload
status. All 60 POM/module/binary/source payloads passed SHA-1 sidecar, ZIP
integrity and detached PGP signature checks. The signer matches preceding
releases: `C8079FC776361E633B091E534E674B3D5F551356`.

All candidate dependency versions align at `1.0.0`, with no SNAPSHOT or stale
Brikk candidate dependencies. Seven representative packaged source files match
the frozen commit, including the tokenizer, MySQL parser, VALUES renderer,
legacy sort node, live-catalog fencing and streaming cancellation fix.

The downloaded verifier, Java 17 and IntelliJ publication audits pass. Core,
metadata, verification API and Doris verifier retain Java 17 compatibility.
IDE helpers retain Java 21 bytecode. Engine/runtime floors are unchanged.

The public audit manifests and downloaded repository were written to the local scratch location
`/var/tmp/jayson/opencode/release-100-public/`. `sql-verified.json` and
`ide-verified.json` record public URLs, SHA-256 hashes, signature results and source
comparisons. Release CI logs were written to `release-100-sql-ci.log` and
`release-100-ide-ci.log` in `/var/tmp/jayson/opencode/`. These paths were on the
author's machine and are not part of the repository.

## Scope and next development version

The release includes the coherent SQLGlot 30.29 integration, main's regression
harvest and catalog-refresh ordering fix, and the tested parser/VALUES fixes.
Published sort class identities, compatibility bridges, native execution
safeguards and the documented dialect limitations remain. A 1.0 version does not
turn deferred grammar, inference or engine semantics into verified support.
No consumer-plugin adoption or Marketplace publication is claimed.

Development resumes at `1.0.1-SNAPSHOT` for SQL and all three IDE helper modules,
after public verification. Published `1.0.0` coordinates are immutable.
