# IntelliJ support 0.4.1

Released on Maven Central on 2026-11-05 from
`2a46bd08efcde21e080e8f0a88d68fc997383fc8`.

## What changed

LiveCatalogService now fences requests before opening helper connections,
harvesting, and scheduling session/background work. Forgetting a source
atomically invalidates older work and removes memory/disk inventory. Only
newer-started successful requests advance accepted-result order. Failed, partial,
timed-out or cancelled work keeps the last good snapshot. Shared and private
session inventories have independent ordering.

Identity, reconnect, helper startup-script suppression and cache privacy guards
remain. See [refresh ordering](live-catalog-refresh-ordering.md).

## Coordinates and upgrade

Use `mavenCentral()` and update all used helper modules together:

- `dev.brikk.house:brikk-sql-intellij-support:0.4.1`
- `dev.brikk.house:brikk-sql-intellij-jdbc-support:0.4.1`
- `dev.brikk.house:brikk-sql-intellij-test-support:0.4.1`

SQL core, metadata and verification API remain at `0.23.0`. Java targets and
runtime requirements are unchanged; IDE helpers retain Java 21 bytecode.
There is no new stable SQL release or parser, Flight protocol or native
harvester change.

DataFusion/Spice, DuckDB, Trino, ClickHouse and Doris plugin services use the
affected code and should adopt the patch. Trino needs its live catalog enabled.
SQL Transpiler has no direct LiveCatalogService use. Update stable pins and
notices, rebuild, and run each plugin's integration tests. Do not relabel an old
qualification ZIP as a stable build or assume Marketplace publication.

## Compatibility and qualification

The public binary and source JARs match the accepted local DataFusion/Spice
candidate byte-for-byte, including all 620 classes and 113 Kotlin source files.
That consumer's qualification covers real helpers/consoles, refresh races,
credential reload, TLS/auth/hostname checks and stream cancellation/recovery.
It does not qualify every other plugin's adoption or public plugin distribution.
Shared tests cover 839 IntelliJ cases on `261.25134.95`.

The public-API race tests are in
`brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.catalog/LiveCatalogRefreshRaceTest.kt`.
Release checks are retained in the
[release workflow](https://github.com/brikk/brikk-sql/actions/runs/42065832531).

Public binary JAR SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| brikk-sql-intellij-support | `8400fa9e25462716c085bb7c00801600b8c5ce2cf162148b3792b90ad1a61323` |
| brikk-sql-intellij-jdbc-support | `19bacdaa249fa9408fb7aef5f09df7f80800ab2c1beeddc7565ebc27d259dd05` |
| brikk-sql-intellij-test-support | `127f5478841f986c3f518ffe63e1ab5a4a73ad909cba741fcfa4e97e8d0945ac` |
