# SQL 0.22.0 and IntelliJ support 0.3.0

Released on Maven Central on 2026-11-02 from
`55334158c7936325dba438da0746bce3a700ab69`, tag `release/0.22.0`.

## What changed

DataFusion and Spice consumers gain native parser/generator support, structured
utilities, type/scope/parameter/lineage analysis, metadata, editor integrations
and connection-aware catalogs. The release includes the Trino/Doris Unicode
case-fold correction.

Qualification targets DataFusion `55.2.0` and Spice `v2.4.0` with the pinned
driver and explicit [support boundaries](datafusion-spice-support.md). Flight
execution and null-binding exclusions remain. This is a shared-library release,
not production IntelliJ plugin qualification.

## Coordinates and upgrade

Use `mavenCentral()`. All 12 SQL coordinates use group `dev.brikk.house` and
version `0.22.0`:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`

The three IntelliJ coordinates use version `0.3.0` and reference SQL `0.22.0`:

- `dev.brikk.house:brikk-sql-intellij-support:0.3.0`
- `dev.brikk.house:brikk-sql-intellij-jdbc-support:0.3.0`
- `dev.brikk.house:brikk-sql-intellij-test-support:0.3.0`

Keep SQL modules aligned and update all used IntelliJ helper modules together.
Follow the [DataFusion/Spice consumer guide](datafusion-spice-consumer.md#released-artifacts)
for plugin pins and integration checks. Historical release holds in research
notes do not apply to these stable libraries, but downstream adoption still
needs its own qualification. Existing stable coordinates are immutable.

## Compatibility

Core, metadata, verification API and Doris verifier support Java 17. IDE support
retains Java 21 bytecode. Trino and aggregate verifier runtimes require Java 25
through trino-parser; oracle also requires Java 25. Use individual compatible
modules on Java 17 rather than the aggregate or IDE support.

Publication does not remove Flight execution or null-binding limits, change
consumer plugin pins, or include the then-separate ClickHouse/DuckDB catch-ups.
See [Java runtime compatibility](java17-compatibility.md).

Release checks are retained in the
[SQL workflow](https://github.com/brikk/brikk-sql/actions/runs/41517590051) and
[IntelliJ workflow](https://github.com/brikk/brikk-sql/actions/runs/41521825409).
