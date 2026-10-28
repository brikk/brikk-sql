# Vendored dependencies

Policy: everything under `vendor/` is a pinned, licensed, point-in-time artifact that cannot
(yet) be resolved from a public repository. Each entry documents provenance (source, SHA,
license) and the exact refresh procedure. Replace with normal dependency coordinates the
moment upstream publishes.

This is distinct from `reference/` (gitignored full clones of upstream repos used as porting
oracles) — `vendor/` contents are committed and shipped-adjacent.

## `lib/doris-fe-sql-parser-4.1.5-g917b20be8387.jar`

The standalone Doris SQL parser (`fe-sql-parser`) from Apache Doris — the authoritative ANTLR
grammar, planned to back the JVM-only native-parser adapter (fragment validation /
engine-exact parsing; see docs/parsing-research-and-plan.md Phase 7). **Pre-release, not
published to any public Maven repository** — Doris publishes connectors/SDKs, not FE core
modules — so we vendor a locally-built jar.

The original artifact was coordinated with doris-intellij-plugin. This refresh pins
the grammar to the Doris 4.1.5 release, using the upstream standalone facade from a
separate commit because the release branch has no `fe-sql-parser` module.

| | |
|---|---|
| Source | Apache Doris — https://github.com/apache/doris, module `fe/fe-sql-parser` |
| Grammar SHA | `917b20be83878e98f94e3ebf7efccc80346e3e50`, tag `4.1.5-release` |
| Facade/build SHA | `2a58ad96ac3ca6330a6df3d0b90f437351f63701` |
| Refreshed | 2026-10-28 |
| Artifact version | `1.2-SNAPSHOT` (internal Doris build version — NOT a stable coordinate) |
| JAR SHA-256 | `9f28a480f17da4a100164acdbb461c2352d7af962632e14c9ca663302903ee75` |
| License | Apache License 2.0 (see ATTRIBUTIONS.md at repo root) |
| Runtime dependency | `org.antlr:antlr4-runtime:4.13.2` (from Maven Central, not bundled; compatible with this 4.13.1-generated parser) |

Checked 2026-10-28 against [Doris 4.1.5](https://doris.apache.org/releases/v4.1/release-4.1.5/)
and tag `4.1.5-release`. Compared with 4.1.4, the grammar adds `CREATE OR REPLACE INDEX`,
Lance-only `USING BTREE | BITMAP`, and unreserved `BTREE`. CREATE FUNCTION and
CREATE ALIAS FUNCTION no longer accept ellipsis argument lists; DROP FUNCTION still does.
The facade/build source pin is unchanged. File names use
`doris-fe-sql-parser-<release>-g<12-character grammar commit>.jar`; the internal
Maven version remains upstream's `1.2-SNAPSHOT`.

Separately verified 2026-10-11: the catch-up native gate executed official
digest-pinned FE/BE images reporting `doris-4.1.4.1-rc02-5dbec55b399`, including
the hotfix's nullable JSON Boolean OR/CASE regression. See
[native execution evidence](../docs/doris-native-catchup-verification.md); the
grammar artifact above and historical probe provenance are not relabeled. This 4.1.5
refresh builds and tests locally only. It does not claim live 4.1.5 execution, rerun
the semantic behavior matrix, publish artifacts, or change Brikk release versions.

Local validation on 2026-10-28: `./kotlin build` succeeded; `./kotlin test` reported
1,685 passing tests, zero failures, and 55 assumption-aborted opt-in tests. Function
and keyword generation, and signature extraction, reproduced their outputs
byte-for-byte. All five vendored registries match the release source exactly.

Verify the committed binary before use or refresh:

```bash
printf '%s  %s\n' \
  9f28a480f17da4a100164acdbb461c2352d7af962632e14c9ca663302903ee75 \
  vendor/lib/doris-fe-sql-parser-4.1.5-g917b20be8387.jar | sha256sum --check
```

### Rebuilding / refreshing

With Maven 3.9.16 and JDK 17 or later, create a disposable Doris checkout. The
script replaces its two grammar files with the exact release-tag files and writes
the jar to `vendor/lib/`. Brikk itself still builds with Kotlin Toolchain.

```bash
git clone --filter=blob:none --no-checkout --depth 1 \
  --revision 2a58ad96ac3ca6330a6df3d0b90f437351f63701 \
  https://github.com/apache/doris.git /tmp/opencode/doris-parser-build
git -C /tmp/opencode/doris-parser-build sparse-checkout set fe/fe-sql-parser
git -C /tmp/opencode/doris-parser-build checkout --detach 2a58ad96ac3ca6330a6df3d0b90f437351f63701
git -C /tmp/opencode/doris-parser-build fetch --depth 1 origin 917b20be83878e98f94e3ebf7efccc80346e3e50
mise exec maven@3.9.16 java@17.0.2 -- bash tools/build_doris_parser.sh /tmp/opencode/doris-parser-build
```

The release grammar emits ANTLR warnings for its implicit GET_FORMAT token and
nullable `warmUpSingleTableRef` alternative. The build script allows these warnings,
matching the release's fe-core build, rather than modifying the grammar. The facade
and postprocessor Java sources are unchanged. `Doris414GrammarTest` retains the 4.1.4
syntax checks and rejection of PLAN REPLAYER PLAY. `Doris415GrammarTest` checks Lance
index forms, the new TVFs, static Hive partition overwrite, and UDF ellipsis rejection.

After a refresh, update the three verifier modules' `brikk.doris.parser.jar` values
and `tools/metadata-capture/src/Keywords.kt`, then capture and generate keywords:

```bash
./kotlin run -m metadata-capture -- doris-keywords
python3 tools/generate_keywords.py
```

Remove the superseded keyword dump and JAR, select the new keyword catalog in
`DorisMetadata.kt`, and update this checksum and ATTRIBUTIONS.md. Historical scanner
fixtures and engine probe results keep the provenance of the artifact actually used.

Replace this artifact with a published Maven coordinate once a parser with the required
release grammar is available. Update ATTRIBUTIONS.md and this file with its provenance.

## `data/doris-registry/Builtin*Functions.java`

Pinned copy of Apache Doris's runtime function registry sources (Apache-2.0), so the Doris
function catalog is regenerable from this repo alone (no Doris clone needed):

| | |
|---|---|
| Source | https://github.com/apache/doris — `fe/fe-core/src/main/java/org/apache/doris/catalog/` |
| Pin | tag `4.1.5-release` (commit `917b20be83878e98f94e3ebf7efccc80346e3e50`; matches `VENDORED_VERSION` in tools/generate_doris_functions.py) |
| Refresh | copy the five files from a newer checkout, update the pin in the script + here, re-run the generator |

## StarRocks function registry (pinned to 4.1.4)

The StarRocks function catalog
(`brikk-sql-metadata/.../GeneratedStarrocksFunctionCatalog.kt`) is generated by
`tools/generate_starrocks_functions.py`, version-pinned to **StarRocks 4.1.4** — the
current 4.1.x patch at harvest (2026):

| | |
|---|---|
| Source tag | `4.1.4` (annotated tag `e61f80120b9c655b91749b1dfe7edf7d2d3b8d4a` -> commit `4a9848edf03f5c936dac664b2d52527f48e72eb0`) |
| Docker engine | `starrocks/allin1-ubuntu:4.1.4` @ `sha256:faf7ce9c24d9c29c9431b4e8cbd4bb7a74cd169907c63f0c5ebaacc7f9df276b` (`current_version()` = `4.1.4-4a9848e`) |
| License | Apache-2.0 (Apache StarRocks) — see ATTRIBUTIONS.md |

Data sources (all committed under `data/`, so the catalog is regenerable from this repo
alone):

- **`data/starrocks-builtin-functions-4.1.4.tsv`** — the AUTHORITATIVE registry dump from
  the pinned live engine: `SHOW FULL BUILTIN FUNCTIONS` (columns Signature | Return Type |
  Function Type | Intermediate Type | Properties). 6242 overloads across 820 names
  (scalar/aggregate/window/table). This is exactly what the engine registers — more
  complete and version-exact than source parsing alone.
- **`data/starrocks-registry/functions.py`** — StarRocks' declarative vectorized scalar
  registry (`gensrc/script/functions.py`). Used to mark variadic scalar overloads
  (`'...'` args) and cross-check.
- **`data/starrocks-registry/FunctionSet.java`** — used only for the
  `onlyAnalyticUsedFunctions` set, so window functions (reported as `Aggregate` by the
  live dump) are reclassified `WINDOW`.
- **`data/starrocks-registry/TableFunction.java`** — table-function default output column
  names.

Refresh: pull the next 4.1.x patch's `starrocks/allin1-ubuntu` image, run
`SHOW FULL BUILTIN FUNCTIONS` into `data/starrocks-builtin-functions-<ver>.tsv`, re-copy
the three registry files from the matching source tag, update the pins in
`tools/generate_starrocks_functions.py` + here, and re-run the generator. `profile` and
`sinceVersion` stay null (the engine exposes no null-propagation or introduced-in
metadata — honest UNKNOWN, as for DuckDB/Trino).

## `data/behavior-matrix/` — StarRocks↔Doris semantic behavior matrix

The shared per-engine behavior-matrix probe (`tools/probe_behavior_matrix.py`, vectors
from `tools/gen_behavior_vectors.py`):

- `data/behavior-matrix/vectors.json` — the deterministic semantic test-vector catalog
  (NULL algebra, rounding, unicode/case/length/indexing, regex, dates/timezones,
  arrays/maps/JSON, aggregates, boundary inputs).
- `data/behavior-matrix/<engine>-results.tsv` — each engine's RAW result per vector, run
  once (StarRocks 4.1.4 / Doris 4.1.3 all-in-one Docker). Pairwise SAME/DIFF verdicts are
  DERIVED from these, avoiding O(n²) hand-maintained probes.
- Raw pairwise evidence: `docs/research/probe-runs/<ts>-starrocks-doris.tsv`.
- Verdicts feed `brikk-sql/testResources/semantics/doris-starrocks-hazards.json` (source
  of truth for `GeneratedDorisStarrocksHazards.kt`). This is an explicitly **PARTIAL**
  scope: 92 vectors yielded 82 named concepts against an 820-function StarRocks catalog;
  unlisted functions remain unprobed and certification refuses them. Never infers
  identity from names/lineage/docs — only from the live results.

Refresh: bring both engines up (`docker run … starrocks/allin1-ubuntu:4.1.4` and
`apache/doris:all-in-one-4.1.3`), then
`python3 tools/probe_behavior_matrix.py all && python3 tools/generate_hazards_registry.py
brikk-sql/testResources/semantics/doris-starrocks-hazards.json && python3
tools/generate_semantic_coverage.py`.

## `data/trino-functions-481.tsv`

See the provenance section added with the Trino catalog (SHOW FUNCTIONS dump, Trino 481,
Apache-2.0) — regenerable via the Method-B harness documented in
tools/generate_trino_functions.py.

## Doris function registry (extracted data, not a binary)

The Doris function catalog (`brikk-sql-metadata/.../GeneratedDorisFunctionCatalog.kt`) is
generated by `tools/generate_doris_functions.py` from Doris's runtime function registry
(`fe/fe-core/.../catalog/Builtin*Functions.java`, vendored from the Doris 4.1.5 release) —
the same source of truth Doris registers at startup, more authoritative than docs. It lists
exactly what 4.1.5 registers: 713 definitions, 808 names including aliases, 1418 overloads.
Regenerate against a newer checkout to refresh. Signatures come from
`data/doris-signatures.json` (below), joined by class name.

A sparse checkout of the release is enough for both tools:

```bash
git init doris-4.1.5 && cd doris-4.1.5
git remote add origin https://github.com/apache/doris.git
git sparse-checkout init --no-cone
git sparse-checkout set '/fe/fe-core/src/main/java/org/apache/doris/catalog/Builtin*Functions.java' \
  '/fe/fe-core/src/main/java/org/apache/doris/nereids/trees/expressions/'
git fetch --depth 1 origin tag 4.1.5-release && git checkout --detach 4.1.5-release
cd - && python3 tools/extract_doris_signatures.py doris-4.1.5
cp doris-4.1.5/fe/fe-core/src/main/java/org/apache/doris/catalog/Builtin*Functions.java vendor/data/doris-registry/
python3 tools/generate_doris_functions.py
```

`DorisTableFunctions.kt` separately curates property arguments and output schemas
from the same release source. `DorisProperties.kt` supplements the older pinned
website capture with source-backed 4.1.5 compute-group, index/analyzer, and AI-resource
properties. Its source pin is recorded in the file; regenerating the older docs
capture must not erase those additions.

## `data/doris-signatures.json` — Doris function signatures + nullable modes

Per-function-class signatures (arg types / return type / variadic), statically extracted
from each function class's `SIGNATURES` field
(`fe/fe-core/.../nereids/trees/expressions/functions/**/*.java`) by
`tools/extract_doris_signatures.py` — no `fe-core` build needed; the declarations are
mechanical `FunctionSignature.ret(..).args(..)` builder chains. Anything the extractor
cannot confidently parse is preserved verbatim in per-class `"unparsed"` lists (never
guessed): at the current pin that is 6 signatures across 4 classes (`Lag`, `Lead`,
`Ipv4CIDRToRange`, `Ipv6CIDRToRange`), built in static blocks or from a local return type —
99.6% signature parse coverage. Type-string conventions (uppercase
SQL-ish, `ANY_<n>`/`ARG_<n>` type-variable placeholders, `ARRAY<..>`/`MAP<..>`) are
documented in the extractor's header.

Each class also carries `"nullable_mode"`: its Nereids `ComputeNullable` marker interface
(`PropagateNullable` / `AlwaysNullable` / `AlwaysNotNullable`, walked over the implements
clauses + extends chain), `"custom"` when a concrete `nullable()` override wins (Java's
"class wins" rule — Coalesce, Lag, NullableAggregateFunction subclasses, ...), or `null`
when nothing is found (table-valued/-generating classes always: row-set producers, scalar
nullability not applicable). `tools/generate_doris_functions.py` maps these onto
`FunctionDef.profile` (`SemanticProfile.nullPropagation`): PropagateNullable→STRICT,
AlwaysNullable→ALWAYS_NULLABLE, AlwaysNotNullable→NEVER_NULL, custom/unmapped
markers→UNKNOWN with provenance in `notes`, null→no profile. At the pin: 332
PropagateNullable, 141 AlwaysNullable, 110 AlwaysNotNullable, 68 custom, 55 none (over
706 classes).

| | |
|---|---|
| Source | https://github.com/apache/doris — `fe/fe-core/.../nereids/trees/expressions/functions/` |
| Pin | tag `4.1.5-release` (recorded as `doris_version` inside the JSON) |
| License | Apache License 2.0 (extracted registry data; see ATTRIBUTIONS.md) |
| Refresh | `python3 tools/extract_doris_signatures.py <doris-checkout>` then `python3 tools/generate_doris_functions.py` (keep the pin in sync with `data/doris-registry/`) |

### `data/doris-since-versions.json` — Doris function "since" versions

`FunctionDef.firstDocumentedIn` (serialized `"first_documented_in"`; `sinceVersion` is
reserved for proven introduction facts and stays null) is populated from
`vendor/data/doris-since-versions.json`, produced by
`tools/extract_doris_since_versions.py` against a local `git clone --depth 1
https://github.com/apache/doris-website` (gitignored under `reference/`, not vendored —
the JSON extracted from it is what's committed). **What was actually investigated**: the
Doris engine repo carries no per-function version metadata at all (confirmed: no
`@since` annotations anywhere in the function sources). doris-website's per-function docs
(`docs/sql-manual/sql-functions/**/*.md`, sampled broadly across ~700 files) also carry
**no explicit version field** — frontmatter is only `{title, language, description}`
(occasionally `+ sidebar_label`); a handful of doc bodies mention a version in prose but
only for *removal*/behavior-change notes (e.g. `struct-element.md`: "removed since
version 4.1.3"), not a systematic introduction fact.

**Method used instead: `first-documented-in`** (honestly labeled — this is NOT
"introduced in"). doris-website keeps historical per-release doc snapshots under
`versioned_docs/version-<X>/sql-manual/sql-functions/`; for each registry function
(primary name or any alias), the extractor takes the **oldest** version tier whose docs
contain a matching frontmatter title. At the pinned clone this yields tiers `1.2 < 2.0 <
2.1 < 3.x < 4.x` (whatever `versioned_docs/version-*` exists in the clone — not
hardcoded). Honesty caveats (also in the script's header and the JSON's `method_notes`):
doc lag/reorganization can push the reported version **later** than a function actually
shipped (never earlier); only the canonical English trees (`docs/`, `versioned_docs/`)
are consulted, not `i18n/zh-CN` or `ja-source` (a function documented only in a
translated tree reads as unmatched, e.g. `NTH_VALUE` at the pinned SHA).

| | |
|---|---|
| Source | apache/doris-website — `docs/sql-manual/sql-functions/`, `versioned_docs/version-*/sql-manual/sql-functions/` |
| Clone SHA | recorded as `clone_sha` inside the JSON |
| Method | `first-documented-in` (see above); recorded as `method` inside the JSON |
| License | Apache License 2.0 (extracted registry data; see ATTRIBUTIONS.md) |
| Coverage | 650 of 728 defs matched; 1 documented only in the live/unreleased tree (`undocumented_in_release`, no shipped version yet); 77 unmatched anywhere (mostly internal/legacy/undocumented functions — MySQL interval-literal helpers, internal aggregate-rewrite targets, debug/admin functions) |
| Refresh | `git clone --depth 1 https://github.com/apache/doris-website <path>` then `python3 tools/extract_doris_since_versions.py <path>` then `python3 tools/generate_doris_functions.py` |

## `data/duckdb-*-<version>.tsv` — DuckDB engine captures

DuckDB describes itself through system table functions. The JDBC capture tool
(`tools/metadata-capture`, run with `./kotlin run -m metadata-capture -- duckdb` from the
project root) runs the capture queries against the in-process `org.duckdb:duckdb_jdbc`
engine on its classpath and writes one file per capture, named by the engine's
`library_version`:

| File | Query |
|---|---|
| `duckdb-functions-<version>.tsv` | `duckdb_functions()`: every row, including pragma and operator rows, with return and parameter types, parameter names, varargs, database, schema, internal flag, alias, stability, description and examples |
| `duckdb-keywords-<version>.tsv` | `duckdb_keywords()`: name and category (reserved, unreserved, type_function, column_name) |
| `duckdb-settings-<version>.tsv` | `duckdb_settings()`: name, input type, scope, aliases and description; not the current value, which depends on the connection |
| `duckdb-extensions-<version>.tsv` | `duckdb_extensions()`: name, whether statically linked, aliases and description; not the installation-dependent columns |
| `duckdb-extension-functions-<version>.tsv` | for each extension in `CAPTURED_EXTENSIONS` (`tools/metadata-capture/src/Duckdb.kt`; today the DuckDB plugin's seven: fts, httpfs, icu, inet, postgres_scanner, spatial, sqlite_scanner), the `duckdb_functions()` rows `INSTALL`+`LOAD` adds on a fresh engine, prefixed by `extension` and the loaded `extension_version`. Written by `./kotlin run -m metadata-capture -- duckdb-extension-functions`, which needs the DuckDB extension repository (network); generation never does. Statically linked `icu` and function-less `httpfs` add no rows |

The queries, the row types and the row → metadata mapping are defined once, in Kotlin
(`brikk-sql-metadata/.../DuckdbCapture.kt`, `DuckdbDump.kt`); a live harvest in the IDE
plugins uses the same definitions. `tools/generate_duckdb_functions.py` only renders the
rows of every vendored version into `GeneratedDuckdb_<version>.kt`, so a new release is a
data drop. Format: a header of column names, then tab-separated rows; `\N` is NULL and
`\\`, `\t`, `\n`, `\r` escape values; list columns are JSON arrays.

| | |
|---|---|
| Versions | **1.5.6**, source ID `069cc9f9b5`; **2.0.0**, source ID `d54cbbe3df` (`org.duckdb:duckdb_jdbc:2.0.0.0`, the same engine as the native verifier) |
| Latest capture | 2026-10-21 |
| License | MIT (extracted registry data; see ATTRIBUTIONS.md) |
| Refresh | bump the `duckdb_jdbc` coordinate in `tools/metadata-capture/module.yaml`, run the `duckdb` and `duckdb-extension-functions` captures, then `python3 tools/generate_duckdb_functions.py` |

`duckdb_functions()` has **no null-handling column** (`has_side_effects` and `stability`
are the only behavioral flags), so `FunctionDef.profile` stays null for every DuckDB def.
The JDBC capture has no Python-client functions (`pandas_scan`, `python_map_function`),
which the earlier Python-module generator picked up. DuckDB 2.0.0 adds the JSON mutation
and `VARIANT` families, trigger/external-resource catalog functions, exact decimal helpers,
and the native ICU replacement's complete locale collation set. Historical cross-engine
hazard evidence retains the engine versions actually probed.

## `data/<dialect>-keywords-<version>.tsv` — Trino, Doris and ClickHouse keywords

Columns `word`, `category` (a `KeywordCategory` name) and `token` (the grammar token
symbol, `\N` when not from a grammar), in the dump format above.
`tools/generate_keywords.py` renders them into `GeneratedKeywords.kt`.

| File | Source | Refresh |
|---|---|---|
| `trino-keywords-484.tsv` | trino-grammar 484: lexer tokens with a one-word literal; UNRESERVED when the parser's `nonReserved` rule can start with the token, else RESERVED. The capture checks the reserved set equals `io.trino.sql.ReservedIdentifiers` (316 keywords, 84 reserved). The historical 483 dump is retained. | bump `io.trino:trino-parser` in `tools/metadata-capture/module.yaml`, `./kotlin run -m metadata-capture -- trino-keywords` |
| `doris-keywords-4.1.5.tsv` | the same reading of `vendor/lib/doris-fe-sql-parser-4.1.5-g917b20be8387.jar` (565 keywords) | `./kotlin run -m metadata-capture -- doris-keywords` after refreshing the jar |
| `clickhouse-keywords-26.5.1.1.tsv` | ClickHouse 26.5.1.1 `system.keywords` via `chdb` 4.2.1 with `chdb-core` 26.5.0 (`SELECT version()` = `26.5.1.1`), captured 2026-10-11. The table has no reservation column, so every category is UNKNOWN; many entries are phrases (`ADD COLUMN`) | see below |

```bash
python3 -c "import chdb; open('vendor/data/clickhouse-keywords-<ver>.tsv','w').write(str(chdb.query(
  \"SELECT keyword AS word, 'UNKNOWN' AS category, NULL AS token FROM system.keywords ORDER BY keyword FORMAT TSVWithNames\")))"
```

Pin `chdb-core` explicitly: `chdb` 4.2.1 alone resolves a newer engine.

## `data/trino-functions-484.tsv`

Trino **484**'s complete built-in function registry (`SHOW FUNCTIONS`), one row per
overload, 6 tab-separated columns: name, return type, argument types, function type,
deterministic, description. 915 rows / 442 distinct names (via generator: 443 defs /
825 overloads, with 373 scalar, 57 aggregate, 11 window, and 2 table definitions).

| | |
|---|---|
| Source | Trino — https://github.com/trinodb/trino |
| Version | 484 (official `trinodb/trino:484` container image) |
| Image digest | `sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f` |
| Captured | 2026-10-28, `SELECT version()` = `484` |
| License | Apache License 2.0 (extracted registry data; see ATTRIBUTIONS.md) |

### Generation method

**Method A (container dump)** — the official `trinodb/trino:484` server image bundles the
geospatial *and* ML plugins, so `SHOW FUNCTIONS` on a stock container lists their functions
(`ST_*`, `bing_tile*`, `geometry_*`, `great_circle_distance`, `convex_hull_agg`,
`learn_*`, `regress`, `classify`, …). This is why the 483 dump has ~100 more names than the
old 481 dump, which used the in-JVM harness (Method B) that never loaded those plugins.

```bash
docker run -d --name trino-dump trinodb/trino:484
# wait until `docker exec trino-dump trino --execute "SELECT 1"` succeeds, then:
docker exec trino-dump trino --execute "SHOW FUNCTIONS" --output-format=TSV \
    > vendor/data/trino-functions-484.tsv
docker rm -f trino-dump
```

Refresh on a version bump: dump from the new `trinodb/trino:<version>` image, save as
`data/trino-functions-<version>.tsv`, then re-run `tools/generate_trino_functions.py`
(TSV -> `GeneratedTrinoFunctionCatalog.kt`). The output is version-pinned and reproducible.

The 483 function capture is retained as historical evidence. The previous 481 capture
(Method B, 746 rows, no geospatial/ML) was superseded and removed.

Known caveats (carried into the generated Kotlin header): `SHOW FUNCTIONS` does not flag
variadics (arity is a lower bound), parametric types / type variables appear literally
(`varchar(x)`, `decimal(p,s)`, `E`), and operators are grammar-level and absent. It also
exposes **neither parameter names nor null-handling metadata**, so
`FunctionOverload.argNames` and `FunctionDef.profile` stay null for every Trino def.
Populating Trino profiles would require scraping the engine sources (`@SqlNullable` /
`RETURNS NULL ON NULL INPUT` on the java implementations) — future work.

## `data/trino-484-doris-4.1.5-json/`

Hand-owned JSON probe inputs and raw paired execution evidence captured on
2026-10-28 against Trino 484 and Doris FE/BE 4.1.5-release-917b20be838.
`vectors.json` contains 47 paired expressions; `results.json` includes exact
queries, stdout/stderr, exit codes, SQL NULL/value distinctions, engine versions
and image digests. Query IDs and capture timestamps are retained, not normalized
to make recaptures look byte-identical. See
[the scoped report](../docs/research/trino-484-doris-4.1.5-json.md) for exact image
pins, runtime limits, counterexamples and reproduction commands.

Refresh with `tools/probe_trino_doris_json.py` against disposable containers, then
review semantic changes before updating the golden results and hazard registry.
Never infer universal equivalence from matching samples or replace historical
non-JSON evidence with these versions. The metadata generator consumes the reviewed
`brikk-sql/testResources/semantics/trino-doris-hazards.json`, not raw probe counts.

## `data/clickhouse-2026-audit/`

Targeted 2026 SQL audit and STREAM/Tuple-combinator regression evidence. `engines.json`
pins official 26.8.34.13 LTS and 26.10.2.103 stable images by digest; `cases.json` records
stable case IDs and native acceptance/execution expectations. Native JSON captures include
raw parser output and exact native/generated finite-query results. Brikk before/after captures
record ASTs, output and errors, not semantic certification. Reproduce with
`tools/probe_clickhouse_2026.py` and `tools/clickhouse_2026_audit.kts`; see
`docs/research/clickhouse-2026-catchup.md` for commands, provenance and limitations.
These probes do not replace or relabel the historical 26.5.1.1 catalogs or hazard evidence.
ClickHouse-derived parser output is Apache-2.0 licensed.

## `data/clickhouse-functions-26.5.1.1.tsv`

ClickHouse **26.5.1.1**'s complete function registry (`system.functions`), one row per
function, 4 tab-separated columns: `name`, `is_aggregate`, `case_insensitive`, `alias_to`.
1787 functions / 230 aggregate / 255 case-insensitive / 218 aliases. This is the
prerequisite catalog for the `duckdb↔clickhouse` / `trino↔clickhouse` semantic-hazard probe
program (see `docs/research/REPORT-clickhouse-differential-probe-2026-07-13.md`).
`tools/generate_clickhouse_functions.py` generates
`GeneratedClickhouseFunctionCatalog.kt` from it and `clickhouse-table-functions-26.5.1.1.tsv`:
1561 definitions with 200 distinct cross-name aliases after case-only self-alias rows are folded
and the 9 `__`-prefixed engine internals (`__getScalar`, ...) are left out, plus the 87 table
functions as `TABLE_VALUED` (1648 in all). `file`, `format` and `fuzzQuery` are defined under
both kinds. The dumps expose no signatures, so overload lists remain honestly empty.

Two ClickHouse-specific columns beyond the other engines' catalogs:
- `is_aggregate` — 1 for aggregate functions.
- `case_insensitive` — 1 when the name resolves case-insensitively (ClickHouse names are
  case-**sensitive** by default; the 255 flagged as insensitive are the MySQL/SQL-standard
  compatibility aliases such as `COUNT`, `SUM`, `LOWER`, `SUBSTRING`). Case-sensitivity is
  itself a hazard axis when a transpiler normalizes identifier case.
- `alias_to` — non-empty when the name is an alias for another function (218 aliases, e.g.
  `LOWER` → `lower`, `BIT_AND` → `groupBitAnd`).

| | |
|---|---|
| Source | ClickHouse — https://github.com/ClickHouse/ClickHouse |
| Version | 26.5.1.1 (dumped via `chdb` 4.2.1, the embedded `clickhouse-local` engine for Python — `chdb-core` 26.5.0; `SELECT version()` = `26.5.1.1`) |
| License | Apache License 2.0 (extracted registry data; see ATTRIBUTIONS.md) |

### Generation method

No ClickHouse server required — `clickhouse-local` mode (here via the `chdb` Python module,
which embeds the same engine) is enough:

```bash
python3 -c "import chdb; open('vendor/data/clickhouse-functions-<ver>.tsv','w').write(str(chdb.query(
  'SELECT name, is_aggregate, case_insensitive, alias_to FROM system.functions ORDER BY name FORMAT TSVWithNames')))"
# or, with the standalone binary:
clickhouse local --query "SELECT name, is_aggregate, case_insensitive, alias_to \
  FROM system.functions ORDER BY name FORMAT TSVWithNames" > vendor/data/clickhouse-functions-<ver>.tsv
```

Refresh on a version bump: re-dump against the new pin, save as
`data/clickhouse-functions-<version>.tsv`, update this pin, and run
`python3 tools/generate_clickhouse_functions.py`.

## `data/clickhouse-table-functions-26.5.1.1.tsv`

ClickHouse 26.5.1.1's `system.table_functions`: `name` and `allow_readonly`, 87 rows,
captured 2026-10-12 with `chdb` 4.2.1 pinned to `chdb-core` 26.5.0 (`SELECT version()` =
`26.5.1.1`), the same engine as the function dump. The description column is left out.

```bash
python3 -c "import chdb; open('vendor/data/clickhouse-table-functions-<ver>.tsv','w').write(str(chdb.query(
  'SELECT name, allow_readonly FROM system.table_functions ORDER BY name FORMAT TSVWithNames')))"
```
