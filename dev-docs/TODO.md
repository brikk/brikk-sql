# SQL work list

This is the SQL-owned index. Stable SQL IDs name outcomes; detailed trackers
hold the cases, proof paths and remaining boundaries. Verifier-backed intentional
differences belong in the [extension registry](../docs/brikk-extensions.md), not
the bug backlog. Compiler integration lives in
[Brikk Engine](https://github.com/brikk/brikk-house/blob/main/TODO.md), and native
packaging in [brikk-chdb](https://github.com/brikk/brikk-chdb/blob/main/TODO.md).

## Design and correctness

- [ ] SQL-01. Decide multi-output pipe semantics for FORK/TEE and terminal operators,
  then implement parsing, validation, desugaring and certification. Independent
  statements do not prove compute-once or atomic multi-write behavior. See
  [the design](design/pipe-multi-output.md), sections 6 and 7. Engine tracks ENG-07.
- [ ] SQL-02. Close version-qualified transpile gaps. Nine ledgered cases use
  versioned corpus keys against unversioned dialects. Model dialect-version
  settings, then retire exact passing signatures. See [rewrites](TODO-BUGS-rewrites.md).
- [ ] SQL-03. Address BigQuery execution limits BQ-36 through BQ-38 for dynamic
  ARRAY_TO_STRING delimiters, offset-preserving timestamp formatting and
  schema-driven overloads. Current refusals are intentional; no BigQuery parity
  ledger failures remain. See [BigQuery](TODO-bigquery.md).
- [ ] SQL-04. Decide whether Doris BUCKETS AUTO needs text fidelity. Omitting it
  retains the engine default. See [Doris DDL](TODO-doris-ddl.md).
- [ ] SQL-05. Deliver source-preserving lowering to Engine. The opt-in API and
  DDB-001 fix are published in 0.18.0 with DuckDB 1.5.6 evidence. Remaining
  integration is Engine ENG-01. Engine edits slots/bindings before lowering and
  chooses when to call it. See [the API and boundaries](../docs/source-preserving-lowering.md)
  and [Engine policy](https://github.com/brikk/brikk-house/blob/main/brikk-engine/README.md#sql-preservation).
- [ ] SQL-06. Qualify downstream DuckDB 2.0 adoption and deferred integrations.
  SQL 0.23.0 and support 0.4.0 publish the library catch-up. The
  [DuckDB tracker](TODO-duckdb-2.0.md) has 75 of 78 bounded deliverables DONE.
  Recursive IDE schemas, general matching-session helpers and executor work stay
  deferred. Library publication does not qualify a plugin or change its driver pin.
- [ ] SQL-08. Improve bounded ClickHouse semantic analysis and consumer adoption.
  Provenance, primitive root multiplication and ordinary CTE propagation are
  implemented and published in SQL 0.23.0. These are opt-in hints and separately
  proved checks, not full validation or exact metadata for every AST node.
  See [the tracker](TODO-clickhouse-semantic.md) and its
  [deferred backlog](TODO-clickhouse-semantic.md#deferred-improvement-backlog).
  CHSEM-034 still needs consumer qualification; P2 expansion remains unscheduled.
- [x] SQL-09. SQLGlot 30.29 catch-up, SG29-001 to SG29-020, released as SQL 1.0.0 and
  IntelliJ support 1.0.0 from branch `sqlglot-30-29-batch-a`. Owned feature and correctness
  deferrals are listed in [the integration evidence](../vendor/data/sqlglot-sg29-020/README.md).
  See [the tracker](TODO-sqlglot-30.29.md).

## Maintenance

- [x] SQL-07. Finish evaluation hygiene. Compiler warnings, SemVer/release override
  tests, Doris parser checksums, hazard assertions and the standalone compiler pin
  are resolved. See [EVAL-15](archive/TODO-rectify-from-eval.md).

Completed plans are in the [archive](archive/README.md). Research files and
their current references are listed in the [research inventory](research-inventory.md).
