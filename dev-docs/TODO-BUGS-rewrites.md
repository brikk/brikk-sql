# TODO - mixed-dialect transpile rewrites (in scope)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.29.0`. The reference clone at `reference/sqlglot/` is the
> oracle for this backlog.
>
> This file tracks only transpile parity cases that do not involve BigQuery.
> BigQuery work is in `dev-docs/TODO-bigquery.md`. Brikk-native DataFusion has its own
> support contract in `docs/datafusion-spice-support.md` because SQLGlot has no DataFusion dialect.
>
> The authoritative cases are the committed
> `brikk-sql/testResources/dialect-corpus/*-transpile-known-failures.json`
> ledgers. Each concrete `*TranspileTest` enforces both directions: new failures
> are rejected, and stale ledger entries must be removed when they start passing.

---

**9 previously tracked parity-ledger items, all in one cluster: version-qualified targets.**

The coherent SG29-020 target refresh has separate exact preview/compatibility
dispositions and owned unported-feature/correctness deferrals in
[its integration review](../vendor/data/sqlglot-sg29-020/README.md). They are not
silently folded into these nine or called native execution proof. Protected
verifier-backed divergences remain protected; genuine correctness deferrals do not.

The other non-BigQuery parity cases at that earlier checkpoint passed. Intentional semantic corrections
and their upstream status remain documented in `docs/brikk-extensions.md`.

## Version-qualified targets (brikk has no dialect-version model)

Upstream `validate_all` keys such as `"postgres, version=15"` select a dialect *with
settings* (sqlglot: `Dialect.get_or_raise` splits on `,`), and the generator emits a
downgraded form for older engine versions. Brikk has no `version` setting, so these
directions run under the plain dialect (EVAL-04 — they used to be silently skipped as
"unavailable") and are ledgered explicitly:

| Ledger | Key | Oracle (old version) | Brikk (current) |
|---|---|---|---|
| postgres | `write\|postgres, version=13.9` / `=15` | `MAX(1)` | `ANY_VALUE(1)` |
| duckdb | `write\|duckdb, version=1.0` | `SUM(CASE WHEN x THEN 1 ELSE 0 END)` | `COUNT_IF(x)` |
| spark | `write\|duckdb, version=1.1.0` (x2) | `([1, 2, 3])[2]` | `[1, 2, 3][2]` |
| spark | `write\|spark, version=3.0.0` (x2) | `ARRAY_JOIN(COLLECT_LIST(x), ', ')` | `LISTAGG(x, ', ')` |
| clickhouse | `write\|clickhouse, version=23.8` (x2) | `dateTrunc('week', ...)` | `dateTrunc('WEEK', ...)` |

The 10 other version-qualified directions (postgres 16/17.5, duckdb 1.1/1.2, clickhouse
24.1, spark 4.0.0) already pass because the "current" behaviour matches.

Closing these means porting sqlglot's dialect `version` setting (a `Dialect(version=...)`
knob consulted by the affected generator methods) and teaching
`test@jvm/.../CorpusDialects.kt` to pass the parsed settings through. Until then the
entries are legitimate, visible gaps — not intentional divergences.

## Workflow

1. Pick a route above and inspect matching entries in the source dialect's
   `*-transpile-known-failures.json` ledger. A `write|target|sql` key means
   `source -> target`; a `read|source|sql` key means `source -> ledger dialect`.
2. Compare with the pinned Python oracle and port the relevant target generator
   transform or source-aware rewrite.
3. Remove passing entries from the committed ledger.
4. Run the affected concrete `*TranspileTest`; periodically run all transpile
   gates with:

   `./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.*TranspileTest'`
