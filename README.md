# brikk-sql

SQL parsing, transpilation, optimization, metadata, and verification for Kotlin.
The generic SQL implementation is a multiplatform port of
[sqlglot](https://github.com/tobymao/sqlglot), with first-class pipe syntax and
documented [Brikk extensions](docs/brikk-extensions.md).

Parse SQL into an editable AST, render it for another dialect, resolve columns and
output types, or lower pipe queries while preserving the surrounding SQL text.

## Modules and artifacts

All artifacts use the **`dev.brikk.house`** group:

| Module | Maven artifact | Responsibility |
| --- | --- | --- |
| [brikk-sql](brikk-sql/README.md) | `brikk-sql-jvm` | Tokenizer, AST, dialects, parser, generator, optimizer, lineage, and schema/shape analysis. |
| [brikk-sql-metadata](brikk-sql-metadata/) | `brikk-sql-metadata-jvm` | Versioned function/type/keyword catalogs and semantic diagnostics; exported by core. |
| [brikk-sql-verify-api](brikk-sql-verify-api/) | `brikk-sql-verify-api` | `SqlVerifier` contract, four-state `VerifyResult`, and editor-ready error positions; re-exports `brikk-sql-jvm`. |
| [brikk-sql-verify-trino](brikk-sql-verify-trino/) | `brikk-sql-verify-trino` | Trino verifier over trino-parser 484 (Java 25 runtime). |
| [brikk-sql-verify-duckdb](brikk-sql-verify-duckdb/) | `brikk-sql-verify-duckdb` | DuckDB native-grammar and parse-only editor verifiers. |
| [brikk-sql-verify-doris](brikk-sql-verify-doris/) | `brikk-sql-verify-doris` | Doris FE parser verifier, single statement or every error in a script. |
| [brikk-sql-verify-shardingsphere](brikk-sql-verify-shardingsphere/) | `brikk-sql-verify-shardingsphere` | Advisory ShardingSphere grammars for PostgreSQL, MySQL, Hive and ClickHouse. Exclude `org.apache.groovy:groovy`: parsing never uses it and Toolchain cannot publish the exclusion. |
| [brikk-sql-verify-clickhouse](brikk-sql-verify-clickhouse/) | `brikk-sql-verify-clickhouse` | Parse-only ClickHouse verifier over the user's own server (`formatQuery`, never executes); the caller supplies the connection. |
| [brikk-sql-verify](brikk-sql-verify/) | `brikk-sql-verify` | Aggregate of every self-contained verifier plus the `SqlVerifiers` registry (not ClickHouse, which needs the user's server). |
| [brikk-sql-oracle](brikk-sql-oracle/) | `brikk-sql-oracle` | Heavyweight PostgreSQL and chDB ClickHouse engine checks. |
| [brikk-sql-intellij-support](brikk-sql-intellij-support/README.md) | `brikk-sql-intellij-support` | Shared SQL editing, completion, validation and console integration for IntelliJ plugins. |
| [brikk-sql-intellij-jdbc-support](brikk-sql-intellij-jdbc-support/README.md) | `brikk-sql-intellij-jdbc-support` | Optional generic JDBC introspector routing for IntelliJ database dialect plugins. |
| [brikk-sql-intellij-test-support](brikk-sql-intellij-test-support/README.md) | `brikk-sql-intellij-test-support` | Shared IntelliJ plugin test fixtures and contracts; test scope only, never bundled in plugins. |

Core, metadata, the verification API and the Doris verifier require **Java 17**.
Other verifiers and IntelliJ support require Java 21; Trino, the aggregate verifier
and the oracle require Java 25. See [Java 17 embedding](docs/java17-compatibility.md)
for dependency guidance.

Latest core, metadata, verify and oracle release: **`1.0.0`**. SQL IntelliJ
support is independently versioned at **`1.0.0`**.

All SQL and IntelliJ support artifacts are published on Maven Central at `1.0.0`.
Development versions are aligned at `1.0.1-SNAPSHOT`.

In development, `SqlVerifier` now extends `AutoCloseable` with a JVM-default no-op `close()`.
Kotlin implementers with their own `close()` must mark it `override`; Java implementers' `close()`
cannot declare checked exceptions. Callers own registry results: reuse them, then close them (or use
`use {}`); closed resource-holding verifiers return
`VerifyResult.Unavailable`, while stateless verifiers inherit the no-op.

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.brikk.house:brikk-sql-jvm:1.0.0")
    // Optional, Java 25 runtime: implementation("dev.brikk.house:brikk-sql-verify:1.0.0")
}
```

## Dialects

Supported names: `sqlglot` (also `""`), `mysql`, `doris`, `starrocks`, `postgres`
(`postgresql`), `duckdb`, `presto`, `trino`, `clickhouse`, `hive`, `spark2`, `spark`
(`sparksql`), `bigquery`, `datafusion` (`arrow-datafusion`), and `spice`. Names are
case-insensitive. Use `read=<dialect>` when parsing and `write=<dialect>` when
transpiling.

Function catalogs provide engine-specific names, overloads, keywords and types.
Semantic diagnostics flag translations whose behavior differs between engines.

### DuckDB 2.0 support

DuckDB support targets **2.0.0 LTS**, with JDBC **2.0.0.0**. It covers UPDATE/MERGE,
DML CTEs, keyed recursion, nested SQL names, NEAREST joins, VARIANT, COPY options,
and session/resource statements, with scope resolution and type inference for supported forms.

Editor verification parses SQL without running it. Cross-dialect executable generation
rejects unsupported translations. See the [DuckDB API guide](brikk-sql/README.md#duckdb-20-native-support)
for feature-specific behavior and limits.

### Other engine profiles

The Trino target is **484**, including its SQL/JSON constructor and
serialization forms, interval precision, window-frame exclusion and NEAREST
relations. The native parser and captured metadata use the same version.
See [the compatibility notes](docs/brikk-extensions.md#58-trino-484-sql-and-metadata).

ClickHouse support includes STREAM reads, Tuple aggregate combinators, structured DDL,
name-filtered stars, higher-order functions and versioned metadata for 26.5, 26.8 LTS
and 26.10. Streaming and experimental LATERAL depend on server settings.

DataFusion 55.2.0 and Spice 2.4.0 have separate dialects, function catalogs and IntelliJ
integrations. See the [support guide](docs/datafusion-spice-support.md) and
[consumer wiring guide](docs/datafusion-spice-consumer.md). StarRocks metadata targets 4.1.4.

## Guides

- [Core API and examples](brikk-sql/README.md)
- [Source-preserving SQL lowering](docs/source-preserving-lowering.md)
- [IntelliJ integration](brikk-sql-intellij-support/README.md)
- [SQL and IntelliJ support 1.0.0 release record](docs/sql-1.0.0.md)
- [SQL 0.23.0 and IntelliJ 0.4.0 release record](docs/sql-0.23.0.md)
- [IntelliJ 0.4.1 catalog-race fix](docs/intellij-support-0.4.1.md)
- [Dialect differences and extensions](docs/brikk-extensions.md)
- [Building, testing and publishing](dev-docs/CONTRIBUTING.md)
- [Developer work list](dev-docs/README.md)

## License

Apache-2.0; see [LICENSE](LICENSE) and [ATTRIBUTIONS.md](ATTRIBUTIONS.md), including
the retained MIT notices for SQLGlot-derived code and fixtures.
