# IntelliJ support 0.4.1 release

Published and independently verified on Maven Central on 2026-11-05 from source
`2a46bd08efcde21e080e8f0a88d68fc997383fc8`. The owner authorized the public release
after the DataFusion/Spice consumer accepted the exact local integration candidate.
The [full-gate release workflow](https://github.com/brikk/brikk-sql/actions/runs/42065832531)
passed on its first attempt, without a preverified bypass.

## Coordinates

Use `mavenCentral()` and group `dev.brikk.house`:

- `brikk-sql-intellij-support:0.4.1`
- `brikk-sql-intellij-jdbc-support:0.4.1`
- `brikk-sql-intellij-test-support:0.4.1`

The common library still exports SQL core, metadata and verification API at released
**0.23.0**. Publication used `publish_core=false`; no immutable SQL prerequisite was
republished. Java targets and runtime requirements are unchanged. The independently
versioned support development stream advances to `0.4.2-SNAPSHOT`; SQL development
remains `0.24.0-SNAPSHOT`, with no new stable SQL release.

## Fix and plugin impact

`LiveCatalogService` now fences requests before helper connection/harvesting and
session/background scheduling. Forgetting a source atomically invalidates older work
and removes its memory/disk inventory. Only newer-started successful requests advance
the accepted-result order. Failed, partial, timed-out or cancelled work keeps the last
good snapshot. Shared and private session inventories have independent ordering.
Identity, reconnect, helper startup-script suppression and cache privacy guards remain.
See [the full recording policy](live-catalog-refresh-ordering.md).

Current source inspection confirms the following plugin services use the affected code:

- DataFusion & Spice: `DatafusionLiveCatalogService` and `SpiceLiveCatalogService`.
- DuckDB: `DuckdbLiveCatalogService`.
- Trino: `TrinoLiveCatalogService`, with its live catalog enabled.
- ClickHouse: `ClickHouseLiveCatalogService`.
- Doris: `DorisLiveCatalogService`.

All five database plugins should pick up the patch. This is not a claim that all five
have completed integration qualification. SQL Transpiler has no direct use of the
service. No SQL core, engine parser, Flight protocol or native harvester semantics change.

## Upstream and consumer qualification

The local upstream gate and remote release gate passed 2,466 JVM tests, with 72
expected optional assumption aborts and no failures, plus 839 IntelliJ tests on
261.25134.95. The genuine signed DuckDB window capture was generated before the
IDE suite. The remote gate passed 45 enabled release-model tests and all configured
DataFusion, Spice and ClickHouse evidence checks. Local full Python discovery passed
227 tests with the release model enabled.

The retained red baseline runs the new public-API race suite against immutable
support `0.4.0`: nine tests, four assertion failures including both desired contracts.
The fixed tests pass. Broader local runs also exposed two existing test-fixture
lifecycle races. The streaming fixture now observes a held cancel's return before
destroying its driver, and the physical-column fixture initializes its model before
registration can freeze it. No production streaming code, expected assertions or
error filtering changed. Original failure logs remain recorded in the candidate manifest.

The consumer's final acknowledgement for
`0.4.1-livecatalog-races-20261104-g2a46bd08efcd-SNAPSHOT` reports zero failures,
errors or skips across 504 IDEA ordinary executions with Kotlin absent/present,
168 actual DataGrip ordinary executions, 328 live executions including all 20 race
contracts, 12 peer checks, 84 installed-IDE tests and nine Python tests. Live cases
cover real helpers/consoles, PIPE/manual/out-of-band refresh, encrypted credential
reload across JVMs, TLS/auth/hostname checks and active-stream cancel/recovery.
All five Plugin Verifier targets were Compatible with unchanged usage counts and
no exemptions. That acknowledgement qualified local dogfood, not a public plugin release.

Consumer implementation `52908a14aabc` produced the accepted qualification ZIP with
SHA-256 `8239c5cdf1da9312a0d0dbe2fca104ff66d87862532438bd3e211e59951fa6ec`.
The subsequent public-library authorization is separate. Consumer finalization must
adopt the stable coordinates, update notices and rebuild rather than relabel that ZIP.
No consumer source was edited upstream; only the DataFusion/Spice agent was notified.
The owner is coordinating the other plugins. Marketplace publication is not implied.

## Public artifact verification

Downloaded all 36 POM/module/binary/source payload and signature/checksum files
directly from `https://repo.maven.apache.org/maven2/`. All 12 detached signatures
verify with the preceding release signer,
`C8079FC776361E633B091E534E674B3D5F551356`. Dependency metadata has no SNAPSHOT or
stale Brikk coordinates; packaging, notices, ZIP integrity and Java 21 ceilings pass.

All three public binary JARs and all three source JARs have exactly the same SHA-256
hashes as the consumer-accepted local candidate. All 620 classes and 113 Kotlin
source files also compare byte-identically. POM/module coordinates change to the
stable version; the accepted runtime code does not change.

Public binary SHA-256 hashes:

- Common: `8400fa9e25462716c085bb7c00801600b8c5ce2cf162148b3792b90ad1a61323`
- JDBC: `19bacdaa249fa9408fb7aef5f09df7f80800ab2c1beeddc7565ebc27d259dd05`
- Test support: `127f5478841f986c3f518ffe63e1ab5a4a73ad909cba741fcfa4e97e8d0945ac`

The full public manifest with URLs, SHA-256s, signature results and comparison counts
is `build/intellij-041-public/verified.json`. The accepted local candidate and consumer
acknowledgement remain in `build/live-catalog-races-candidate/manifest.json`.
