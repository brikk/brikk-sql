# DuckDB 2.0 support checklist

DuckDB 2.0 ("Cyanoptera") is expected in fall 2026. This document tracks work needed to
keep the shared `brikk-sql` DuckDB dialect, function metadata, and native verifier aligned
with the release. The preview is not the final compatibility contract: syntax, extension
APIs, function signatures, and the documented breaking changes can still change.

Current baseline:

| Surface | Current pin/source |
|---|---|
| Parser and generator | Port of sqlglot v30.18.0-43-g3ca82489 DuckDB dialect |
| Function catalog | DuckDB v1.5.6 `duckdb_functions()`; 876 definitions / 2,677 overloads |
| Native parser oracle | `org.duckdb:duckdb_jdbc:1.5.6.0` |

Status terms below mean:

| Status | Meaning |
|---|---|
| **Add** | No DuckDB-specific support was found; new AST/parser/generator work is likely. |
| **Audit** | Generic or partial support exists, but the exact DuckDB 2.0 form is not pinned. |
| **Refresh** | Generated/versioned data must be regenerated or repinned. |
| **Monitor** | No immediate `brikk-sql` change, but consumers or verification may be affected. |

## Release gate

- [ ] Track the final 2.0 release notes and migration guide, especially the promised list of
  breaking changes and the completed lambda syntax transition. Do not declare parity from
  the preview posts alone.
- [ ] Test against at least one late 2.0 preview, then repeat against the final Maven/Python
  artifacts. Record the exact engine version, JDBC artifact version, and source ID.
- [ ] Refresh the sqlglot reference before adding brikk-native behavior. Port upstream
  DuckDB changes where they exist; keep only still-missing 2.0 behavior as local extensions.
- [ ] Keep the Python engine used for catalog generation and the JDBC engine used by
  `brikk-sql-verify` on the same DuckDB engine release.
- [ ] Run a 1.5.6-versus-2.0 acceptance comparison over all DuckDB parser, generator, token,
  and AST corpora. Investigate every acceptance change rather than automatically changing a
  known-failure ledger.

## PEG parser replacement

DuckDB 2.0 replaces the PostgreSQL-derived parser with a PEG parser. DuckSQL is intended to
remain compatible, but keyword classification, ambiguities, malformed-input behavior, and
source locations can still shift.

- [ ] Diff the 2.0 PEG grammar and keyword categories against `DuckdbParser.kt` and generated
  `DuckdbTokenizerTables.kt`; regenerate tokenizer/token corpora after updating sqlglot.
- [ ] Re-run every DuckDB identity, transpile, annotated-serde, and parser corpus case through
  the final 2.0 engine.
- [ ] Add differential cases for precedence, associativity, prefix aliases, `FROM`-first SQL,
  simplified `PIVOT`, `GROUP BY ALL`, `ORDER BY ALL`, `* EXCLUDE`/`REPLACE`, lambdas, JSON
  arrows, parameter markers, and nested parentheses.
- [ ] Add invalid-query cases that stress PEG backtracking, including long runs of unmatched
  opening parentheses. Confirm both brikk and the engine reject promptly.
- [ ] Recheck whether `json_serialize_sql` still returns the same `error`, `error_type`,
  `error_message`, and `position` fields used by `DuckdbVerifier`.
- [ ] Recheck the JDBC fallback's `Parser Error` prefix and line/column format. The verifier
  accepts known binding/catalog/not-implemented failures as grammar acceptance, leaves
  unexpected failures unverified, and parses locations with `DUCKDB_LINE_MARKER`;
  improved 2.0 errors must not turn rejects into false accepts.
- [ ] Check whether `json_serialize_sql` supports more non-`SELECT` statements in 2.0. Keep the
  prepare fallback side-effect-free and add tests for every newly modeled statement family.
- [ ] Revalidate `DuckdbGrammarBuiltins.kt`. Its comments and membership are explicitly based
  on the PostgreSQL-derived v1.5.6 parser.

### Runtime-extensible grammar policy

The PEG parser lets loaded extensions register tokens, keywords, grammar alternatives, and
transformers. A static shared library cannot know which extensions a caller loaded.

- [ ] Define the support boundary: `duckdb` should mean core DuckSQL 2.0, not the union of all
  possible runtime extension grammars.
- [ ] Do not add extension-reserved keywords globally unless they are also core DuckSQL
  keywords; doing so can break valid identifiers for users without that extension.
- [ ] Decide whether unknown extension statements remain unsupported/opaque `Command` nodes,
  or whether a future API accepts an extension grammar profile. Avoid silently assigning
  semantics to syntax brikk does not understand.
- [ ] Add one opt-in extension smoke test, such as pipe syntax, to prove that the native oracle
  can validate a connection's loaded grammar without claiming that the static parser supports
  it.

## SQL grammar and AST work

| DuckDB 2.0 feature | Current brikk state | Required work |
|---|---|---|
| Expression statements, e.g. `date: current_date(), time: current_localtime()` | **Audit:** prefix expression aliases are parsed, but the new statement form has no focused test | Confirm a comma-separated expression statement parses as the intended statement/AST rather than a partial expression; preserve the no-`SELECT` form on DuckDB round-trip and define cross-dialect generation. |
| `CONNECT` / `DISCONNECT` | **Add:** existing `Connect` AST support is for `CONNECT BY`, not DuckDB session routing | Add distinct statement nodes or an unambiguous model for URI/attached-database targets; cover `CONNECT qk`, `CONNECT 'postgres://...'`, `DISCONNECT`, and routing scope. |
| External resources | **Add** | Model and round-trip `CREATE`, `REGISTER`, `SHOW`, `CONNECT TO`, and `DESTROY EXTERNAL RESOURCE`; include resource type, name, handle, and options without flattening them into generic commands. |
| Triggers | **Audit:** generic trigger nodes/parser exist and are PostgreSQL-shaped | Verify DuckDB's `BEFORE`/`AFTER`, row/statement modes, transition tables, multiple triggers per event, inline DML body, `RETURNING`, and `DROP TRIGGER`. The example body is not PostgreSQL's `EXECUTE FUNCTION` form, so expect parser/generator changes. |
| `APPROX NEAREST ... BY SIMILARITY` joins | **Add** | Add join syntax and AST fields for approximation, top-k, and similarity expression; test join types, aliases, precedence, and transpilation refusal where no equivalent exists. Do not confuse this with scalar `approx_*` functions. |
| DML and `COPY` inside CTEs | **Audit:** CTE bodies call the generic statement parser | Pin `INSERT`, `UPDATE`, `DELETE`, and `COPY` CTE bodies, including `MATERIALIZED` and `RETURNING`; verify generator placement and scope/lineage behavior for non-query CTE bodies. |
| Nested schemas | **Audit:** multipart table names already exist | Test `CREATE SCHEMA finance.reports` and `CREATE TABLE finance.reports.q3`; ensure catalog/schema/table qualification is not collapsed or quoted as one identifier. |
| `$name` variable references | **Audit:** `SET VARIABLE` is covered; `$...` currently shares placeholder machinery | Represent DuckDB variables separately from JDBC/bind placeholders if needed. Round-trip `$threshold`, positional parameters, named parameters, and dollar-quoted strings without collisions. |
| `WITH RECURSIVE ... USING KEY` aggregation | **Audit:** `key_expressions` parsing/generation and corpus cases already exist | Add the aggregate key form from the 2.0 example, `USING KEY (a, avg(b))`, and execute semantic probes, not only parse/render tests. |
| `FETCH FIRST n ROWS ONLY` | **Audit:** generic `Fetch` parsing exists | Add DuckDB identity and native-verifier cases for `FIRST`/`NEXT`, omitted count if legal, `ROW`/`ROWS`, offsets, and invalid combinations. |
| `OVERLAY()` | **Audit:** generic AST/parser support exists | Add DuckDB parse/generate, catalog-signature, and result-semantic tests. |
| `UNNEST` in `GROUP BY` | **Audit:** `Unnest` is modeled, but this placement is not pinned | Add identity, serde, typing, and native verification cases; confirm scalar-position `UNNEST` handling is not incorrectly rewritten as a table source. |
| `COPY TO (... PARTITION BY ..., ORDER BY ...)` | **Audit** | Pin both options together and separately, expressions versus column lists, option ordering/canonicalization, and output AST shape. |
| `VARIANT` end-to-end support | **Audit:** type token/model, casts, and some v1.5 functions exist | Test storage DDL, JSON-to-`VARIANT` casts, nested values, extraction, comparisons/containment, Parquet read/write syntax, typing metadata, and cross-dialect hazards. |
| Completed lambda syntax transition | **Audit:** both generic arrow lambdas and legacy `LAMBDA ... : ...` rendering exist | From the final migration guide, identify removed forms. Stop generating any form rejected by 2.0, preserve accepted legacy input only if useful, and update corpus expectations and function signatures. |
| Custom extension repositories | **Add/Audit** | Model and round-trip `CREATE EXTENSION REPOSITORY ... FROM ... [USING PUBLIC KEY ...]`, `INSTALL ... FROM repo`, repository-qualified `LOAD`, and `DROP EXTENSION REPOSITORY`; never log or normalize key material unsafely. |

For each grammar item that lands:

- [ ] Add focused assertions to `DuckdbDialectTest.kt`.
- [ ] Add identity/transpile fixtures to `testResources/dialect-corpus/duckdb.json`.
- [ ] Add token and AST-serde cases where the node or keyword surface changes.
- [ ] Feed generated SQL to the final engine through `brikk-sql-oracle`; ledger only a known,
  explained limitation, never a newly introduced parser bug.
- [ ] Define behavior when writing the AST to every supported target dialect: faithful mapping,
  explicit unsupported warning, or preserved anonymous syntax. Avoid silent semantic rewrites.

## Functions and type metadata

### Catalog refresh

- [ ] Install the final Python `duckdb==2.0.x` package and run
  `python3 tools/generate_duckdb_functions.py`.
- [ ] Inspect the `duckdb_functions()` schema before regenerating. If 2.0 exposes defaults,
  null handling, stability, side effects, named parameters, or richer table-function results,
  extend `FunctionDef` deliberately instead of discarding new metadata.
- [ ] Determine whether the JSON and other bundled extensions are visible on a clean connection
  or only after `LOAD`. Make the generator's extension-loading policy explicit and reproducible.
- [ ] Review every changed function kind, overload, argument name, return type, alias, macro,
  table macro, and operator row. Pay special attention to changes caused by the lambda
  transition and the first-class `VARIANT` pipeline.
- [ ] Update count and shape assertions in
  `brikk-sql-metadata/test/.../FunctionCatalogTest.kt`; add direct assertions for important
  new functions rather than relying only on total counts.

### New JSON functions

The preview announces these mutation functions:

- `json_set(json, path, value)`
- `json_insert(json, path, value)`
- `json_replace(json, path, value)`
- `json_remove(json, path)`

It also announces these reconciliation functions:

- `json_merge_patch_diff(orig, modified)`
- `json_deep_merge(original, patch, ...)`
- `json_normalize(json)`
- `json_strip_nulls(json)`

- [ ] Confirm final overloads, variadic behavior, argument names, return types, and extension
  availability from `duckdb_functions()`.
- [ ] Audit generic nodes already present for `JSON_SET`, `JSON_REMOVE`, and
  `JSON_STRIP_NULLS`. DuckDB's signatures and semantics may not match the dialect that caused
  those generic nodes to be introduced.
- [ ] Decide whether `json_insert`, `json_replace`, `json_merge_patch_diff`,
  `json_deep_merge`, and `json_normalize` need semantic AST nodes or should remain anonymous
  functions. Add nodes when cross-dialect generation, typing, or hazard detection needs to
  distinguish their behavior.
- [ ] Pin path handling for JSONPath and JSON Pointer forms, multiple path/value pairs, missing
  paths, arrays, SQL `NULL` versus JSON `null`, invalid JSON, and duplicate keys.
- [ ] Pin the documented semantic distinctions: RFC 7396 delete-on-null versus
  `json_deep_merge` skip-on-null; recursive key sorting while preserving array order; and
  stripping object nulls without removing null array elements.
- [ ] Update function gap reports and probe new mappings against Trino, Doris, and ClickHouse.
  Add hazard entries where names exist on both sides but null/path/mutation semantics differ.

### `VARIANT` function family

- [ ] Diff the full final `variant_*` family against the v1.5.6 catalog. The current catalog has
  low-level entries such as `variant_extract`, `variant_normalize`, and `variant_typeof`; the
  preview names user-facing functions including `variant_type`, `variant_keys`, and
  `variant_contains`.
- [ ] Add typing rules for functions whose result can be stated accurately (`BOOLEAN`, lists of
  keys, type-name text, extracted `VARIANT`, and so on).
- [ ] Test containment and null/missing behavior before adding cross-dialect mappings to JSON or
  semi-structured types. `VARIANT` is not merely textual JSON.

## JDBC and Java API impact

These APIs are client/execution features, not changes to core DuckSQL. They should not cause
unrelated parser abstractions to leak into the multiplatform `brikk-sql` module.

### Chunked query results

`DuckDBChunkedResult` and `DuckDBDataChunkReader` provide lazy, columnar chunks through
`DuckDBPreparedStatement.query()`. The API first appeared in JDBC 1.5.3.0, so it is available
before the 2.0 engine release.

- [ ] Keep `DuckdbVerifier` on ordinary JDBC `ResultSet`: it reads one JSON cell, so chunking
  provides no benefit and would couple the verifier to a DuckDB-specific execution API.
- [ ] When bumping JDBC, smoke-test the ordinary `ResultSet`, streaming-result setting,
  prepared statements, error reporting, and connection cleanup used by the verifier.
- [ ] If a brikk execution/result API is added later, evaluate chunked results there. Document
  zero-based vector indexing, lazy close/ownership, supported scalar types, and the current
  lack of `LIST`/`STRUCT` readers before exposing it as a stable API.

### Java scalar and table UDFs

Pure-Java table functions are registered per connection and may use positional and named
parameters. They are runtime objects, not DuckDB built-ins.

- [ ] Keep generated `DUCKDB_FUNCTION_CATALOG` limited to the reproducible engine/extension
  registry used during generation. Do not add application-specific Java UDFs to static shared
  metadata.
- [ ] Verify that an unknown table function with named arguments, for example
  `FROM mongo_query('tab', '{}', columns = '["id"]', port = 27017)`, parses and round-trips
  without needing a hard-coded function name.
- [ ] Verify that scalar and table functions with the same name remain distinguishable where
  context or runtime metadata provides the distinction.
- [ ] If consumers need completion or validation for registered UDFs, design a connection-local
  catalog overlay on top of the built-in catalog rather than mutating `DuckdbDialect` globally.
- [ ] Monitor the Java vector API's composite-type support and bind/init state lifecycle. These
  affect a future executor integration, not current dialect correctness.

## Semantic regression probes

Even syntax-compatible engine replacements can change results. Re-run or add probes for:

- [ ] Timezone-aware timestamps, calendars, and collations after removal of ICU internals,
  including DST gaps/overlaps, historical zones, locale collation, and unknown zone/collation
  errors.
- [ ] `MERGE` and `UPDATE ... FROM` with multiple source matches, whose semantics are now
  explicitly defined.
- [ ] Recursive CTEs, including `USING KEY`, after the recursive execution rewrite.
- [ ] `VARIANT` shredding, extraction pushdown, Parquet round trips, missing versus SQL/JSON
  null, and numeric type preservation.
- [ ] JSON mutation/reconciliation functions as described above.
- [ ] `NEAREST` joins for ties, null vectors, metric direction, top-k per left row, and
  deterministic ordering guarantees.
- [ ] Existing DuckDB hazard pairs in `testResources/semantics` when a changed implementation
  touches a previously probed function. Update generated hazard registries after evidence, not
  merely because the engine version changed.

The storage format, asynchronous I/O, Quack wire protocol, and stable C API do not directly
change `brikk-sql` parsing. Monitor them only for SQL surface or test-fixture consequences.
Never migrate a checked-in DuckDB database fixture to storage v2.0 without first documenting
backward/forward compatibility expectations.

## Version and documentation updates

When final 2.0 support is ready, update every version-bearing location together:

- [ ] `brikk-sql-verify/module.yaml`: `org.duckdb:duckdb_jdbc` dependency and comments.
- [ ] `brikk-sql-verify/src/.../DuckdbVerifier.kt`: engine-pin KDoc and any error parsing.
- [ ] `tools/generate_duckdb_functions.py`: version-specific modeling comments if still needed.
- [ ] `brikk-sql-metadata/src/.../GeneratedDuckdbFunctionCatalog.kt`: regenerate, do not edit.
- [ ] `brikk-sql-metadata/src/.../DuckdbGrammarBuiltins.kt`: parser description and verified
  version.
- [ ] `brikk-sql-metadata/src/.../FunctionCatalog.kt` and
  `brikk-sql-metadata/test/.../FunctionCatalogTest.kt`: version-specific assumptions/counts.
- [ ] `vendor/README.md` and `ATTRIBUTIONS.md`: source version, extraction behavior, and counts.
- [ ] Run `tools/publish_maven_local.sh` and inspect the Toolchain-generated verifier
  POM/module metadata for the updated JDBC pin. The launcher delegates to Toolchain;
  do not reintroduce manually emitted dependency metadata or duplicated version pins.
- [ ] DuckDB verify ledgers and generated actual ledger: review diffs and retain reasons for all
  exceptions.

## Definition of done

- [ ] Final DuckDB 2.0 Python and JDBC engines are pinned to matching engine versions.
- [ ] Generated function metadata is reproducible and tests pin important 2.0 additions.
- [ ] Every final release-note grammar addition is classified as supported, intentionally
  opaque, or explicitly unsupported, with a test for that decision.
- [ ] All DuckDB parser/generator/token/serde corpora pass.
- [ ] Native verifier and oracle tests pass, and no unexplained failure was ledgered.
- [ ] Cross-dialect generation for new semantic AST nodes either has a verified mapping or
  emits an explicit unsupported diagnostic.
- [ ] Existing semantic hazards affected by 2.0 have fresh live evidence.
- [ ] Public docs and published dependency metadata report the same DuckDB version.

## Sources

- [A Preview of DuckDB v2.0](https://duckdb.org/2026/08/17/duckdb-20-highlights)
- [DuckDB v2.0: Your Database Deserves a Better Parser](https://duckdb.org/2026/08/20/duckdb-20-peg-parser)
- [Reconciling JSON in DuckDB, One Patch at a Time](https://duckdb.org/2026/08/18/reconciling-json)
- [Chunked Query Results in the DuckDB Java Driver](https://duckdb.org/2026/08/21/chunked-query-results-java-driver)
- [DuckDB Table Functions in Java](https://duckdb.org/2026/08/25/table-functions-in-java)
