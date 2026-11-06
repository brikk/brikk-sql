# SQL 0.23.0 and IntelliJ support 0.4.0

Released on Maven Central on 2026-11-03 from
`b406a1bd894963f3a09f63f02f6b04e606f083f2`, tag `release/0.23.0`.
These replace SQL 0.22.0 and IntelliJ support 0.3.0.

## What changed

DuckDB support targets final 2.0.0 LTS with
`org.duckdb:duckdb_jdbc:2.0.0.0`. The catch-up includes nested core SQL names,
DML and COPY CTEs, MERGE, NEAREST joins, trigger and extension-management syntax,
JSON/VARIANT contracts, native regex/session policies, binary literals, temporal
precision and conservative executable boundaries. See the
[bounded feature tracker](../dev-docs/TODO-duckdb-2.0.md) for per-feature tests.
Recursive nested-schema IDE browsing and editing remain excluded. Core SQL/name/
scope support and existing safeguards remain available.

ClickHouse adds opt-in semantic APIs for exactly `26.10.2.103`:

- Structured composition and contextual lambda contracts with bounded array/map
  result typing and positioned diagnostics.
- Native scalar literal projection types, integer/floating arithmetic, and
  setting-aware integer/Decimal division with separately proved constant errors.
- Explicit script analysis with preceding SET facts and independently owned ASTs.
- Correct scientific-number token boundaries in ClickHouse mode.
- Per-node proof provenance and primitive standalone multiplication through
  supported 256-bit integer and floating/nullable combinations.
- Bounded non-recursive CTE propagation through chains, aliases, stars, derived
  relations, supported schema inputs and downstream arithmetic/lambdas. Simple
  qualified INNER/CROSS joins have measured controls.

DataFusion/Spice inference and strict encoded-string rules remain intact alongside
ClickHouse number boundaries. Native DuckDB timestamp precision reconciliation
does not overwrite caller-owned type slots. Existing tokenizer constructors,
generic typing, serialized forms and public result-class JVM shapes remain compatible.

## Coordinates and upgrade

Use `mavenCentral()`. All SQL artifacts below use group `dev.brikk.house` and
version `0.23.0`; keep their versions aligned:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`

The independently versioned IntelliJ coordinates use version `0.4.0` and depend
on SQL `0.23.0`:

- `dev.brikk.house:brikk-sql-intellij-support:0.4.0`
- `dev.brikk.house:brikk-sql-intellij-jdbc-support:0.4.0`
- `dev.brikk.house:brikk-sql-intellij-test-support:0.4.0`

For example, JVM metadata consumers use
`dev.brikk.house:brikk-sql-metadata-jvm:0.23.0`. Choose individual verifier modules
for the runtime you deploy. Core, metadata, verification API and Doris verifier
support Java 17. IDE helpers retain Java 21 bytecode. Trino and aggregate verifier
runtimes require Java 25 through trino-parser; oracle also requires Java 25.

The requested downstream Quack JDBC baseline is `0.12.1`. Quack JDBC is not a
dependency of these POMs. Consumers must qualify their own driver integration;
this library release neither changes plugin pins nor certifies Quack adoption.
Earlier local SQL `0.23.0-rc.1`/`0.23.0-rc.2` and support
`0.3.1-rc.1`/`0.3.1-rc.2` labels are not published stable versions.

## Behavioral boundaries

Use `annotateClickhouseSemanticTypesWithProvenance` for a supplied AST. Choose
`annotateClickhouseScriptSemanticTypesWithProvenance` explicitly when preceding
SET statements should contribute facts. Existing statement APIs stay local and
never infer external session state. No IDE mode or plugin is activated automatically.

Only `NATIVE_CONDITIONAL` facts identify native conditional proofs. Populated type
slots alone are not proof. Provenance belongs to one unchanged AST analysis;
copy, serde, mutation and independent calls require reanalysis. Function values
remain unknown even when callable-result facts exist.

The ClickHouse `26.8.34.13` pin is a control only. Unknown overloads/context are
not errors or success. Empty diagnostics do not certify acceptance or execution,
and a conditional type can coexist with a runtime error. `formatQuery` remains
parse-only. Recursive/set-operation CTEs, outer joins, ambiguous/incomplete bindings,
conflicting definition/consumer division modes and unproved functions remain
outside the narrow CTE contract. General rejection coverage, broader session/
catalog inference, cross-dialect lowering and additional versions remain deferred.

DuckDB's native/source-aware APIs deliberately refuse unproved executable
translations rather than emit plausible SQL. Historical SQLGlot parse/render
parity is a separate contract. Native parse-only verification is not binding or
execution. Review the tracker boundaries before adopting native mode.

## Compatibility and consumer checks

Shared libraries do not qualify a production IntelliJ plugin or replace its
integration suite. Update stable dependency pins and notices, rebuild, and test
the actual packaged consumer with its engine, driver and IDE targets.
CHSEM-034 remains open for consumer delivery, not library publication.

- [Java runtime compatibility](java17-compatibility.md)
- [ClickHouse accepted scope](../vendor/data/clickhouse-semantic/closeout-README.md)
- [Proof provenance](../vendor/data/clickhouse-semantic/type-provenance-README.md)
- [Primitive multiplication](../vendor/data/clickhouse-semantic/multiplication-README.md)
- [Non-recursive CTEs](../vendor/data/clickhouse-semantic/cte-context-README.md)
- [Deferred ClickHouse work](../dev-docs/TODO-clickhouse-semantic.md#deferred-improvement-backlog)

The packaged Java 17 API check is `tools/release/ClickhouseSemanticSmoke.java`,
run by `tools/verify_clickhouse_semantic_candidate.py`. It covers CTE propagation,
proof categories, script diagnostics and the old result constructor shape.
Public release gates are retained in the
[SQL workflow](https://github.com/brikk/brikk-sql/actions/runs/41570918595) and
[IntelliJ workflow](https://github.com/brikk/brikk-sql/actions/runs/41573875618).
