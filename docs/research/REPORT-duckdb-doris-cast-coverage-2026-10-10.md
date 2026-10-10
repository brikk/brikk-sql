# DuckDB ↔ Doris CAST coverage (0.18.1 fix)

## Repro and root cause

```sql
SELECT o_orderkey::BIGINT FROM lake.s.orders
```

Doris generation already correctly produces
`SELECT CAST(o_orderkey AS BIGINT) FROM lake.s.orders`. CAST is a `Func` node,
but the DuckDB/Doris PARTIAL semantic scope contained no bare `CAST` evidence
key. Certification therefore refused it as `SEMANTIC_COVERAGE_UNKNOWN(CAST)`.
Registering a grammar builtin or bypassing the coverage gate is not a fix.

## CAST

**Verdict: conditionally-equivalent, not identical.** The registry operates at
concept granularity, not source/target type and value-domain granularity. The
query does not supply a schema proving that `o_orderkey` is an integer. Even if
the name suggests an order key, it cannot be assumed to be BIGINT, integral, or
in range. All CAST calls for this pair therefore retain a visible conditional
`SEMANTIC_HAZARD` warning, including the repro. Under the existing certification
policy, warnings are non-blocking (`ok=true`, `orThrow()` succeeds); that is not
a promise that every CAST preserves values or failure behavior. Consumers needing
unconditional equivalence must escalate the warning or prove its preconditions.

### Live evidence

Read-only scalar SELECT probes on **DuckDB JDBC 1.5.6.0** (engine 1.5.6) and the
already-running **Doris FE 4.1.4.1-rc02-5dbec55b399 / BE image 4.1.4.1**,
2026-10-10. `SHOW FRONTENDS` identifies the real Doris version; `VERSION()` is
the MySQL compatibility string `5.7.99`, not the engine version.

The 22 input expressions and all three outcomes are retained in
[`brikk-sql-verify/testResources/semantics/duckdb-doris-cast.tsv`](../../brikk-sql-verify/testResources/semantics/duckdb-doris-cast.tsv).
`NULL` means SQL NULL; `ERROR` means query failure (error-message wording is
not treated as a semantic result). DuckDB outcomes use JDBC `getString(1)`;
Doris outcomes use `mysql -N -B`. Successful numeric/date/string outputs are
compared directly, not by executing Doris SQL on DuckDB as a substitute oracle.

For each expression, run `SELECT <expression>` in a fresh DuckDB in-memory
connection, and these statements in separate Doris client sessions:

```sql
SET enable_strict_cast = false;
SELECT <expression>;
```

```sql
SET enable_strict_cast = true;
SELECT <expression>;
```

Only session-local settings were changed; no global configuration, tables,
catalogs, cluster restarts, or shared data were modified. Both Doris modes were
probed explicitly rather than inferred from the server default.

| Domain | DuckDB | Doris non-strict / strict | Interpretation |
|---|---|---|---|
| NULL; signed INT → BIGINT; signed BIGINT bounds; `' +42 '` → BIGINT | same values | same values | Common compatible/in-range subset |
| DECIMAL/DOUBLE `3.5`, `-1.7` → BIGINT | `4`, `-2` | `3`, `-1` in both modes | Rounding vs truncation, **divergent** |
| `'1.5'` → BIGINT | `2` | `1` / error | Text parsing/rounding, **divergent** |
| `'1e2'` → BIGINT | `100` | NULL / error | Parser domain, **divergent** |
| Bad/empty integer text, overflow, NaN/Infinity → BIGINT | error | NULL / error | Failure contract depends on session mode |
| `42` → VARCHAR | `'42'` | `'42'` in both modes | Compatible ordinary numeric text |
| TRUE → VARCHAR | `'true'` | `'1'` in both modes | Formatting, **divergent** |
| DATE `'2024-01-02'` → BIGINT | error | `20240102` in both modes | Conversion availability/meaning, **divergent** |
| `'2024-02-29'` → DATE | same date | same date | Common valid date subset |
| `'2024-02-30'` → DATE | error | NULL / error | Invalid-date failure contract |

Enabling strict CAST does **not** repair rounding, text grammar, boolean
formatting, or the DATE-to-integer conversion mismatch. Replacing CAST with
TRY_CAST would change DuckDB's error contract and is not a mitigation.

### Scope limits

This is evidence for a conditional **CAST concept**, not exhaustive coverage of
the type-conversion matrix. Other temporal ranges, precisions and time zones,
DECIMAL rescaling, unsigned/HUGEINT mappings, binary/JSON/nested types and
string formatting require separate review. No `TRY_CAST` alias is registered.
Doris inherits MySQL's TRY_CAST → plain CAST renderer. Certification excludes
that emitted `CAST` key for a `TryCast` node: an emitted name must not lend CAST's
evidence to a different NULL-on-failure contract. TRY_CAST therefore remains an
unknown-coverage refusal for this pair rather than silently borrowing the new
conditional verdict. This guard does not change SQL generation.
The pair remains PARTIAL, other concepts remain closed-world, and no other
engine pair gains CAST coverage. Reverse Doris→DuckDB lookup retains the same
observed caveats (the divergence is symmetric, not a safe reverse mapping).

### Documentation cross-check

- [DuckDB typecasting](https://duckdb.org/docs/current/sql/data_types/typecasting.html):
  lossy numeric-to-integer rounding and overflow errors; `::` and CAST share semantics.
- [Doris 4.x CAST](https://doris.apache.org/docs/4.x/sql-manual/basic-element/sql-data-types/conversion/cast-expr/):
  strict mode and the distinction from TRY_CAST.
- [Doris 4.x integer conversions](https://doris.apache.org/docs/4.x/sql-manual/basic-element/sql-data-types/conversion/int-conversion/):
  truncation, strict/non-strict parsing and errors, DATE → YYYYMMDD.

Docs corroborate, but do not replace, the pinned live outcomes.

## Regression checks

- `DuckdbDorisCastCoverageTest`: exact customer repro and explicit CAST spelling,
  conditional warning/provenance, divergent inputs, reverse lookup, PARTIAL scope
  and isolation from unresearched concepts/other pairs.
- `DuckdbDorisCastEvidenceTest`: replays the 22 DuckDB outcomes in pinned JDBC,
  checks generated Doris SQL against the native grammar, and verifies that
  certification retains the conditional CAST finding. Headless tests do not
  claim to re-execute the retained live Doris outcomes.
- Generated hazard registry and coverage scope stay derived from the JSON
  source of truth; the TRY_CAST key guard prevents evidence leakage, not a
  certification bypass. There is no generator change.

Validation: `./kotlin test` passes **1,096 tests** across all four modules;
one optional native-library grammar smoke test is aborted when unconfigured.
Both metadata generators are byte-deterministic after regeneration, and
`git diff --check` is clean. On request, all four public artifacts were subsequently
installed to Maven local as `0.18.1-SNAPSHOT` using `./kotlin publish mavenLocal`;
the shared development version was restored to `0.19.0-SNAPSHOT`. The earlier
Central snapshot attempt failed with HTTP 401; it was not retried.
