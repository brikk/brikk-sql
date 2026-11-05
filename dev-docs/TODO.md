# Work list — brikk-sql

This is the active **SQL-owned** index. Keep the exact cases, fixtures and
verification instructions in the linked detailed trackers; these `SQL-*` IDs
name the top-level outcomes. Do not relabel verifier-backed intentional
divergences as bugs ([extension registry](../docs/brikk-extensions.md)). Compiler
integration is tracked separately in [Brikk Engine](https://github.com/brikk/brikk-house/blob/main/TODO.md);
native resource packaging is tracked in [brikk-chdb](https://github.com/brikk/brikk-chdb/blob/main/TODO.md).

## Design and correctness

- [ ] **SQL-09: Review and implement the SQLGlot 30.29 catch-up.** Proposed scope
  and ordered batches are in [the catch-up tracker](TODO-sqlglot-30.29.md).
  Approved Batches A and B implement robustness, identifier escaping and
  precedence/grouping in worktree `sqlglot-30-29-batch-a`. Separately approved
  SG29-008 population/sample aggregates and SG29-009 temporal arithmetic are also
  verified and committed/pushed as `70a9f1c0`. SG29-010 JSON-valued ClickHouse
  extraction is separately verified. Commit/push of SG29-010 and sequential
  SG29-011 through SG29-013 are verified, with separate commit/push between items.
  SG29-014 LIKE/map wrong-result fixes are verified, with commit/push authorized.
  Other SG29-014 features and SG29-015 onward are not authorized.
  Native coverage drift is individually reviewed; the
  [evidence guide](../vendor/data/sqlglot-30.29-batch-b/README.md) retains limits.
  Preserve the separate DuckDB native/executable safeguards; global
  oracle migration, publication and consumer adoption are not yet authorized.
- [ ] **SQL-08: Improve bounded ClickHouse semantic analysis.** The user accepted
  the CHSEM-001 through CHSEM-023 scope as opt-in hints and separately proved
  checks, not full validation or native-exact metadata for every AST node.
  CHSEM-024/025 now provide proof provenance and primitive root multiplication
  typing. CHSEM-030-A adds bounded non-recursive CTE propagation, qualified in
  local RC2 candidates. Publication and consumer updates
  still need approval. Deferred work, evidence, dependencies and completion criteria:
  [CHSEM-024 through CHSEM-034](TODO-clickhouse-semantic.md#deferred-improvement-backlog).
  The remaining P2 items are not authorized for implementation by this approval.
- [ ] **SQL-01 — Decide and implement multi-output pipe semantics.** Get owner
  decisions on `FORK`/`TEE` and terminal operators; then land parsing/validation,
  multi-output desugaring, and fidelity certification in separate phases. Do
  **not** silently represent compute-once or atomic multi-write guarantees as
  independent statements. Details: [multi-output pipe design](design/pipe-multi-output.md),
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
  supported/refused boundaries and delivery: [source-preserving lowering](../docs/source-preserving-lowering.md).
  Consumer policy:
  [Engine's SQL-preservation policy](https://github.com/brikk/brikk-house/blob/main/brikk-engine/README.md#sql-preservation).
- [ ] **SQL-06 — Integrate and release the DuckDB 2.0 catch-up.** The reference is final
  DuckDB 2.0.0 / JDBC 2.0.0.0, not a preview or the former 1.5.6 baseline. The bounded
  audit has 75 of 78 items complete; remaining broad-suite failures and release checks
  are explicit in the [DuckDB checklist](TODO-duckdb-2.0.md). Recursive IDE schemas,
  general matching-session helpers and a new executor remain deferred. No release or
  downstream dependency adoption follows from checklist completion alone.

## Maintenance (lower priority)

- [x] **SQL-07 — Finish the remaining evaluation hygiene.** Completed 2026-10-03:
  compiler warnings removed; shared SemVer validation and Toolchain-native release
  overrides with CI regression tests; Doris parser JAR checksum/current-grammar
  check; structural hazard assertions; standalone mise compiler pin removed.
  Details: [EVAL-15](archive/TODO-rectify-from-eval.md).

Completed plans with useful detail are in the [archive](archive/README.md).
Empty completed trackers have been removed. Protected dialect differences remain in the
[extension registry](../docs/brikk-extensions.md), not in the active backlog.
