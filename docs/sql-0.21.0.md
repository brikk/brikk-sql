# SQL 0.21.0 release

Published and verified on Maven Central from source commit
`fcd1b91aea72f34de51136927e6cd8d5b9256125`, with the source frozen at
`release/0.21.0`. The [full-gate release workflow](https://github.com/brikk/brikk-sql/actions/runs/40772800553)
succeeded. Versions 0.20.0 and 0.20.1 were already published and immutable; the
user explicitly selected 0.21.0 for this release.

## Coordinates and scope

All of these use group `dev.brikk.house` and version `0.21.0`:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`

Resolve them using `mavenCentral()`; no snapshot repository is needed. Independently
versioned IntelliJ support artifacts were not republished and remain at 0.2.4.
Unmerged ClickHouse/DuckDB feature branches are not part of this release.

The release includes Trino 484 SQL/parser/catalog support, Doris 4.1.5 parser and
metadata, the [47-case paired JSON evidence](research/trino-484-doris-4.1.5-json.md),
retained Doris JSON_PARSE generation and [Java 17 embedding support](java17-compatibility.md).
The five refreshed JSON hazard entries remain constraints, not blanket equivalence
claims. No global certification-policy change is included.

## Runtime boundaries

Core, metadata, verification API and Doris verifier contain only class-file major
61 and support Java 17. Their public release JARs were used in a standalone Java
17.0.20 smoke covering metadata/hazard initialization, AST parsing/serde,
Trino-to-Doris generation and native Doris parser acceptance/rejection.

Other verifier JARs contain major 65. The Trino verifier/aggregate still require
a Java 25 runtime through trino-parser 484. Oracle contains major 69 and requires
Java 25. Java 17 deployments must use the individual compatible modules rather
than the aggregate or IDE support.

## Public artifact verification

Public Central POMs and Gradle metadata for all 12 coordinates were downloaded
and audited: no SNAPSHOT references, consistent 0.21.0 candidate dependency versions.
JVM binaries, source JARs and armored signature sidecars were downloaded; packaged
class versions were checked. Relevant sources match the exact release commit.
The Java 17 publication and verifier audits pass on the public downloads, not just
on Maven Local outputs. This checks signature-sidecar availability, not an
independent public-key trust policy.

Java 17 JAR SHA-256 values:

```text
brikk-sql-jvm
  a801f140827f913b10b394b220bc62c4bfaff2e6309edbc5376bb5a66742b5c6
brikk-sql-metadata-jvm
  506ea02a67bbf983122a978fb3fb0657ff6d606a801820b0e247aa509f5678b2
brikk-sql-verify-api
  80688aca1fd5532098bd4580099995a8cb0b9042450901f62e524a424f6d7cf9
brikk-sql-verify-doris
  43602940ba8ae1bb2960ad8783b77d7ffb7f068fe45ddf5bfbd4ee2fbc834e87
```

The corresponding Java 17 snapshot and formal JAR payloads are byte-identical;
the formal POM/module dependency graph uses immutable 0.21.0 release coordinates.
Verification records are retained at
`/tmp/opencode/maven-central-0.21.0/verified-artifacts.json`.

## Consumer handoff

Use `dev.brikk.house:brikk-sql-metadata-jvm:0.21.0`, and core/API/Doris verifier at
the same version where needed. No consumer pin was edited by this release.
The connector must reconcile its existing JSON guards with the changed scalar
verdicts and run its own Doris 4.1.5 live suite. Duckbridge's Java 17 embedding
qualification also remains its responsibility. Library/CI success does not replace
those integration checks. The connector's own SQL renderer is separate from the
Doris JSON_PARSE generator correction.
