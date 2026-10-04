# Work list — brikk-sql

This is the active **SQL-owned** index. Keep the exact cases, fixtures and
verification instructions in the linked detailed trackers; these `SQL-*` IDs
name the top-level outcomes. Do not relabel verifier-backed intentional
divergences as bugs ([extension registry](docs/brikk-extensions.md)). Compiler
integration is tracked separately in [Brikk Engine](https://github.com/brikk/brikk-house/blob/main/TODO.md);
native resource packaging is tracked in [brikk-chdb](https://github.com/brikk/brikk-chdb/blob/main/TODO.md).

## Design and correctness

- [ ] **SQL-01 — Decide and implement multi-output pipe semantics.** Get owner
  decisions on `FORK`/`TEE` and terminal operators; then land parsing/validation,
  multi-output desugaring, and fidelity certification in separate phases. Do
  **not** silently represent compute-once or atomic multi-write guarantees as
  independent statements. Details: [multi-output pipe design](docs/design/pipe-multi-output.md),
  especially §6–7. Engine's eventual DSL/runner surface is `ENG-07`.
- [ ] **SQL-02 — Close version-qualified transpile gaps.** Nine ledgered cases
  for older Postgres, DuckDB, Spark and ClickHouse versions remain because a
  `"dialect, version=..."` corpus key runs against the unversioned dialect.
  Model dialect-version settings in generation, then retire passing exact
  ledger signatures. Details: [rewrite tracker](TODO-BUGS-rewrites.md).
- [ ] **SQL-03 — Address BigQuery execution limitations.** `BQ-36`–`BQ-38` cover
  row-dependent `ARRAY_TO_STRING` delimiters, offset-preserving timestamp
  formatting, and schema-driven timestamp overloads. The current diagnostics
  are deliberate; do not replace them with unsafe SQL. There are **zero** open
  BigQuery parity-ledger failures. Details: [BigQuery tracker](TODO-bigquery.md).
- [ ] **SQL-04 — Decide whether to preserve Doris `BUCKETS AUTO`.** Its generator
  currently drops that spelling (omission has the same Doris default); keep this
  low priority unless a consumer needs text fidelity. Details:
  [Doris DDL tracker](TODO-doris-ddl.md).
- [ ] **SQL-05 — Deliver source-preserving composition/lowering to Engine.** The
  opt-in SQL API and native/text-diff/engine-result regressions are published on
  Maven Central in `0.18.0`, including the DDB-001 fix and DuckDB 1.5.6 reference.
  Remaining consumer integration is tracked as Engine `ENG-01`.
  Engine applies its slot/binding edits before lowering and decides
  *when* to call it, not how generic SQL is parsed or lowered. API, proofs,
  supported/refused boundaries and delivery: [source-preserving lowering](docs/source-preserving-lowering.md).
  Consumer policy:
  [Engine's SQL-preservation policy](https://github.com/brikk/brikk-house/blob/main/brikk-engine/README.md#sql-preservation).
- [ ] **SQL-06 — Prepare DuckDB 2.0 compatibility when the release is pinned.**
  Audit the new PEG parser, SQL grammar, function catalog and native verifier
  against a versioned 2.0 release; do not claim preview compatibility as a
  final contract. Details: [DuckDB 2.0 checklist](TODO-duckdb-2.0.md). This is a
  prospective checklist against the current 1.5.6 baseline, not certification
  of the preview or final 2.0 engine.

## Maintenance (lower priority)

- [x] **SQL-07 — Finish the remaining evaluation hygiene.** Completed 2026-10-03:
  compiler warnings removed; shared SemVer validation and Toolchain-native release
  overrides with CI regression tests; Doris parser JAR checksum/current-grammar
  check; structural hazard assertions; standalone mise compiler pin removed.
  Details: [EVAL-15](TODO-rectify-from-eval.md).

Completed/non-actionable trackers are kept for provenance:
[parser defects](TODO-BUGS-defects.md), [generator parity](TODO-BUGS-generation.md),
[new coverage](TODO-BUGS-newcoverage.md), [typing](TODO-BUGS-types.md),
[DataFusion fixtures](TODO-datafusion.md), and
[the current upstream pin](TODO-sqlglot-catchup.md).
