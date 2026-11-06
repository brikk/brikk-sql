# Research inventory

The repository contains 124 files under `docs/research/`: 46 top-level Markdown
notes, six nested Flight issue-draft files, and 72 probe files. The probe files
are 20 `.batch`, 51 `.tsv` and one `.json`. No file was deleted. Raw probe bytes
are unchanged, including captures that are not valid UTF-8.

File names in the tables are relative to `docs/research/`. References with no
directory prefix are also in `docs/research/`. Other paths are repository-relative.
This inventory excludes its own links and the index links added with it.

## How these files are used

No executable reader of these Markdown notes or raw probe dumps was found in
tracked repository code. Kotlin references are comments or provenance strings,
not file reads. Hazard generators read the versioned semantic JSON files and
carry their provenance into generated Kotlin. A provenance link does not rerun
a raw probe or prove that its historical engine still matches current behavior.

Three tools write research reports rather than read them:

- `tools/generate_probe_worklist.py` writes `doris-probe-worklist.md`.
- `tools/function_gap_report.py` writes `function-gap-report.md`.
- `tools/verify_starrocks_engine.py` writes `starrocks-engine-verification.md`.

The search covers tracked text, full paths, unique basenames and probe-family
wildcards. "No named reference" means no specific incoming reference was found,
not that the file is safe to delete. Historical reports also refer generally to
the probe directory. External repositories and generated references assembled
entirely at runtime are outside this inventory.

## Notes and reports

| File | What it is | Current incoming references |
|---|---|---|
| `BUGS-certify-newly-caught-2026-07-13.md` | Certification divergence cases. | `REPORT-certify-hazard-hole-closed-2026-07-13.md` |
| `BUGS-clickhouse-generator-mappings-2026-07-13.md` | ClickHouse mapping bug cases. | `brikk-sql/test/dev.brikk.house.sql/ClickhouseGeneratorMappingBugsTest.kt`; `NOTE-for-brikk-sql-clickhouse-followups.md`; `REPORT-clickhouse-differential-probe-2026-07-13.md`; `SPIKE-source-aware-generator-transforms-2026-07-13.md` |
| `BUGS-doris-generator-mappings-2026-07-13.md` | Doris mapping bug cases. | `brikk-sql-verify/test/dev.brikk.house.sql.verify/SqlVerifierTest.kt`; `brikk-sql/test/dev.brikk.house.sql/DorisGeneratorMappingBugsTest.kt`; `NOTE-for-brikk-sql-doris-followups.md`; `REPORT-certify-hazard-hole-closed-2026-07-13.md`; `REPORT-doris-differential-probe-2026-07-13.md` |
| `CLICKHOUSE-rename-map.md` | Cross-dialect function rename evidence. | `brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseGenerator.kt`; `brikk-sql/src/dev.brikk.house.sql/dialects/DuckdbGenerator.kt`; `brikk-sql/test/dev.brikk.house.sql/ClickhouseRenameFixesTest.kt`; `HANDOFF-generator-rename-fixes.md` |
| `HANDOFF-aggregates-probe.md` | Aggregate probe instructions and input/output links. | No named reference. |
| `HANDOFF-clickhouse-probe-program.md` | ClickHouse differential-probe scope and method. | `REPORT-clickhouse-differential-probe-2026-07-13.md` |
| `HANDOFF-doris-clickhouse-mass.md` | Doris side of the mass scalar probe. | No named reference. |
| `HANDOFF-doris-clickhouse-probe.md` | Doris/ClickHouse paired probe instructions. | No named reference. |
| `HANDOFF-doris-probe-continuation.md` | Archived Doris worklist, sample harness and capture rules. | `HANDOFF-clickhouse-probe-program.md`; `HANDOFF-doris-clickhouse-probe.md`; `NOTE-for-brikk-sql-doris-followups.md` |
| `HANDOFF-doris-xxhash-week-reprobe.md` | Direct hash and week-number recheck instructions. | No named reference. |
| `HANDOFF-generator-rename-fixes.md` | Cross-name generator repair instructions. | `HANDOFF-reverse-doris-trino.md` |
| `HANDOFF-reverse-doris-trino.md` | ClickHouse-source reverse mapping probes. | No named reference. |
| `HANDOFF-trino-clickhouse-mass.md` | Trino side of the mass scalar probe. | No named reference. |
| `HANDOFF-trino-doris-probe.md` | Trino/Doris deeper differential probes. | No named reference. |
| `NOTE-for-brikk-sql-clickhouse-followups.md` | ClickHouse probe findings and proposed fixes. | No named reference. |
| `NOTE-for-brikk-sql-doris-followups.md` | Doris probe findings and proposed fixes. | No named reference. |
| `REPORT-certify-hazard-hole-closed-2026-07-13.md` | Hazard certification and warning/refusal policy findings. | `brikk-sql/test/dev.brikk.house.sql/shape/CertifyTest.kt`; `BUGS-certify-newly-caught-2026-07-13.md`; `SPIKE-source-aware-generator-transforms-2026-07-13.md` |
| `REPORT-clickhouse-differential-probe-2026-07-13.md` | Versioned ClickHouse/DuckDB results and composed Trino findings. | `brikk-sql/testResources/semantics/duckdb-clickhouse-hazards.json`; `brikk-sql/testResources/semantics/trino-clickhouse-hazards.json`; corresponding `GeneratedDuckdbClickhouseHazards.kt` and `GeneratedTrinoClickhouseHazards.kt` under `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/`; `BUGS-clickhouse-generator-mappings-2026-07-13.md`; `NOTE-for-brikk-sql-clickhouse-followups.md`; `vendor/README.md` |
| `REPORT-doris-differential-probe-2026-07-13.md` | Versioned Doris differential findings. | `brikk-sql/testResources/semantics/duckdb-doris-hazards.json`; `brikk-sql/testResources/semantics/trino-doris-hazards.json`; corresponding `GeneratedDuckdbDorisHazards.kt` and `GeneratedTrinoDorisHazards.kt` under `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/`; `brikk-sql/src/dev.brikk.house.sql/dialects/DorisGenerator.kt`; `BUGS-doris-generator-mappings-2026-07-13.md`; `HANDOFF-clickhouse-probe-program.md`; `HANDOFF-doris-probe-continuation.md`; `NOTE-for-brikk-sql-doris-followups.md`; `REPORT-clickhouse-differential-probe-2026-07-13.md` |
| `REPORT-duckdb-doris-cast-coverage-2026-10-10.md` | CAST hazard coverage and the 0.18.1 fix. | `brikk-sql/testResources/semantics/duckdb-doris-hazards.json`; `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdbDorisHazards.kt`; `docs/brikk-extensions.md` |
| `SPIKE-source-aware-generator-transforms-2026-07-13.md` | Design investigation for source-aware transforms. | `BUGS-clickhouse-generator-mappings-2026-07-13.md` |
| `clickhouse-2026-batch2.md` | Catalog and syntax catch-up results. | `docs/brikk-extensions.md`; `clickhouse-2026-batch3.md`; `clickhouse-2026-batch4.md`; `clickhouse-2026-catchup.md`; `vendor/README.md` |
| `clickhouse-2026-batch3.md` | Composition, lambda and utility catch-up results. | `docs/brikk-extensions.md`; `clickhouse-2026-batch4.md`; `vendor/README.md` |
| `clickhouse-2026-batch4.md` | Final catch-up scope, controls and handoff. | `docs/brikk-extensions.md`; `vendor/README.md` |
| `clickhouse-2026-catchup.md` | Initial engine-pinned catch-up evidence. | `docs/brikk-extensions.md`; `clickhouse-2026-batch2.md`; `clickhouse-2026-batch4.md`; `vendor/README.md` |
| `clickhouse-main-integration.md` | Historical integration and compatibility findings. | `clickhouse-2026-batch4.md` |
| `datafusion-spice-analysis.md` | Static typing, scope and analysis contracts. | `spice-core-dialect.md` |
| `datafusion-spice-editor-features.md` | Editor completion, documentation and inspections. | `brikk-sql-intellij-support/README.md`; `datafusion-spice-ide-parsing.md`; `spice-core-dialect.md` |
| `datafusion-spice-execution.md` | Validation/execution boundaries and driver evidence. | `brikk-sql-intellij-support/README.md`; `docs/datafusion-spice-consumer.md`; `datafusion-spice-live-catalogs.md` |
| `datafusion-spice-ide-parsing.md` | IDE identity, lexer/parser and API contracts. | `brikk-sql-intellij-support/README.md`; `spice-core-dialect.md` |
| `datafusion-spice-live-catalogs.md` | Connection catalog and cache contracts. | `brikk-sql-intellij-support/README.md`; `docs/datafusion-spice-consumer.md`; `datafusion-spice-editor-features.md`; `spice-core-dialect.md` |
| `datafusion-spice-reference-baseline.md` | Exact reference versions, profiles and evidence. | `tools/datafusion-reference/README.md`; `vendor/README.md` |
| `datafusion-spice-round2.md` | Metadata and first parser/generator corrections. | `tools/datafusion-reference/README.md` |
| `datafusion-spice-round3.md` | Registry-wide function audit and corrections. | No named reference. |
| `datafusion-spice-upgrades.md` | Immutable refresh/comparison tooling and promotion limits. | `docs/datafusion-spice-consumer.md`; `docs/datafusion-spice-support.md`; `tools/datafusion-reference/README.md` |
| `doris-4.1.4-sql-gaps.md` | Doris DDL/function gap classification. | `dev-docs/TODO-doris-ddl.md` |
| `doris-clickhouse-generator-gaps.md` | Doris-source ClickHouse mapping candidates. | No named reference. |
| `doris-probe-worklist.md` | Generated same-name Doris probe worklist. | Writer `tools/generate_probe_worklist.py`; `HANDOFF-clickhouse-probe-program.md`; `HANDOFF-doris-probe-continuation.md`; `REPORT-doris-differential-probe-2026-07-13.md` |
| `duckdb-clickhouse-generator-gaps.md` | DuckDB-source ClickHouse rename-recovery results. | `brikk-sql/testResources/semantics/duckdb-clickhouse-hazards.json`; `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdbClickhouseHazards.kt`. Those provenance strings incorrectly insert `probe-runs/` before this filename. |
| `flight-issue-drafts/01-spice-missing-column-error.md` | Missing-column error suppression issue text. | `flight-issue-drafts/README.md` |
| `flight-issue-drafts/02-spice-prepared-update.md` | Prepared update returning zero without execution. | `flight-issue-drafts/README.md` |
| `flight-issue-drafts/03-spice-execute-schema.md` | SQL EXECUTE schema mismatch issue text. | `flight-issue-drafts/README.md` |
| `flight-issue-drafts/04-spice-parameter-nullability.md` | Parameter nullability metadata issue text. | `flight-issue-drafts/README.md` |
| `flight-issue-drafts/05-arrow-binder-leak.md` | Arrow JDBC failed-binding allocation leak issue text. | `flight-issue-drafts/README.md` |
| `flight-issue-drafts/README.md` | Index of filed issues and reproduction links. | `spice-flight-failures.md` |
| `function-gap-report.md` | Generated function relationship report. | Writer `tools/function_gap_report.py`; `brikk-sql/test/dev.brikk.house.sql/FunctionSemanticsTest.kt`; `function-semantics-trino-duckdb.md` |
| `function-semantics-trino-duckdb.md` | Distilled Trino/DuckDB function semantics. | `brikk-sql-verify/test/dev.brikk.house.sql.verify/SqlVerifierTest.kt`; `brikk-sql/src/dev.brikk.house.sql/dialects/DuckdbGenerator.kt`; `HANDOFF-clickhouse-probe-program.md`; `doris-probe-worklist.md`; documentation links emitted by `tools/generate_probe_worklist.py` |
| `spice-core-dialect.md` | Spice syntax and core support boundaries. | No named reference. |
| `spice-flight-failures.md` | Flight SQL root-cause investigation with versioned captures. | `docs/datafusion-spice-support.md`; `datafusion-spice-execution.md`; `flight-issue-drafts/README.md`; `vendor/data/datafusion-spice/flight-investigation/README.md` |
| `starrocks-engine-verification.md` | Generated native parser/analyzer report. | Writer `tools/verify_starrocks_engine.py` |
| `trino-484-doris-4.1.5-json.md` | Paired JSON execution and hazard refresh. | `brikk-sql/testResources/semantics/trino-doris-hazards.json`; `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedTrinoDorisHazards.kt`; `dev-docs/archive/trino-483-parity-todo.md`; `docs/brikk-extensions.md`; `docs/doris-native-catchup-verification.md`; `docs/sql-0.21.0.md`; `vendor/README.md` |
| `trino-clickhouse-generator-gaps.md` | Trino-source ClickHouse mapping candidates. | No named reference. |

## Probe reference groups

The raw-file table uses these groups to avoid repeating long provenance paths.
They are references, not executable file readers.

- A means `aggregates-round.*` provenance in five files under
  `brikk-sql/testResources/semantics/`: `doris-clickhouse-hazards.json`,
  `duckdb-clickhouse-hazards.json`, `duckdb-doris-hazards.json`,
  `trino-clickhouse-hazards.json` and `trino-duckdb-hazards.json`. Their generated
  counterparts are `GeneratedDorisClickhouseHazards.kt`,
  `GeneratedDuckdbClickhouseHazards.kt`, `GeneratedDuckdbDorisHazards.kt`,
  `GeneratedTrinoClickhouseHazards.kt` and `GeneratedTrinoDuckdbHazards.kt` under
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/`.
- B means `doris-clickhouse-mass.*` provenance in
  `brikk-sql/testResources/semantics/doris-clickhouse-hazards.json` and
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDorisClickhouseHazards.kt`.
- C means `doris-clickhouse-round2.*` provenance in the same two files as B.
- D means the `doris-clickhouse.*` comment in
  `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/HazardRegistryTest.kt`.
- E means `duckdb-clickhouse-round2.*` provenance in
  `brikk-sql/testResources/semantics/duckdb-clickhouse-hazards.json` and
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdbClickhouseHazards.kt`.
- F means `trino-clickhouse-mass.*` provenance in
  `brikk-sql/testResources/semantics/trino-clickhouse-hazards.json` and
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedTrinoClickhouseHazards.kt`.
- G means `trino-doris-round2.*` provenance in
  `brikk-sql/testResources/semantics/trino-doris-hazards.json` and
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedTrinoDorisHazards.kt`.
- H means direct provenance in
  `brikk-sql/testResources/semantics/doris-starrocks-hazards.json` and
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDorisStarrocksHazards.kt`.
- I means direct provenance in
  `brikk-sql/testResources/semantics/doris-clickhouse-hazards.json`,
  `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDorisClickhouseHazards.kt`
  and `brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseGenerator.kt`.
- J means direct provenance in the same hazard JSON/generated file as I, plus
  `brikk-sql/src/dev.brikk.house.sql/dialects/DorisGenerator.kt` and
  `brikk-sql/src/dev.brikk.house.sql/dialects/TrinoGenerator.kt`.

The ClickHouse differential report links `probe-runs/*.batch` and describes the
paired TSVs generally. The Doris continuation note links the directory and
filename templates. These broad archival references apply in addition to the
named references below. Timestamp-prefixed snapshots do not match the narrower
family globs. The `duck_ch_massdiff*` provenance in DuckDB/ClickHouse hazards has
no matching file in this directory; it does not explicitly name
`duckdb-clickhouse-massprobe.json`. No reference was invented to bridge that gap.

## Raw probes

| File | What it is | Named references |
|---|---|---|
| `probe-runs/20260713T084022Z-snapshot.batch` | Snapshot SQL inputs. | No named reference. |
| `probe-runs/20260713T084022Z-snapshot.tsv` | Snapshot paired outputs. | No named reference. |
| `probe-runs/20260713T091721Z-string-fixup.batch` | String correction inputs. | No named reference. |
| `probe-runs/20260713T091721Z-string-fixup.tsv` | String correction outputs. | No named reference. |
| `probe-runs/20260713T091818Z-string-unicode-null.batch` | Unicode and NULL string inputs. | No named reference. |
| `probe-runs/20260713T091818Z-string-unicode-null.tsv` | Unicode and NULL string outputs. | No named reference. |
| `probe-runs/20260713T091959Z-numeric.batch` | Numeric probe inputs. | No named reference. |
| `probe-runs/20260713T091959Z-numeric.tsv` | Numeric paired outputs. | No named reference. |
| `probe-runs/20260713T092047Z-datetime.batch` | Date/time inputs. | No named reference. |
| `probe-runs/20260713T092047Z-datetime.tsv` | Date/time paired outputs. | No named reference. |
| `probe-runs/20260713T092156Z-array.batch` | Array inputs. | No named reference. |
| `probe-runs/20260713T092156Z-array.tsv` | Array paired outputs. | No named reference. |
| `probe-runs/20260713T092244Z-aggregate.batch` | Aggregate inputs. | No named reference. |
| `probe-runs/20260713T092244Z-aggregate.tsv` | Aggregate paired outputs. | No named reference. |
| `probe-runs/20260713T092503Z-regex-clean.batch` | Regex correction inputs. | No named reference. |
| `probe-runs/20260713T092503Z-regex-clean.tsv` | Regex correction outputs. | No named reference. |
| `probe-runs/20260713T092527Z-regex-json-hash.batch` | Regex, JSON and hash inputs. | No named reference. |
| `probe-runs/20260713T092527Z-regex-json-hash.tsv` | Regex, JSON and hash outputs. | No named reference. |
| `probe-runs/20260713T092600Z-misc.batch` | Miscellaneous scalar inputs. | No named reference. |
| `probe-runs/20260713T092600Z-misc.tsv` | Miscellaneous scalar outputs. | No named reference. |
| `probe-runs/20260713T092731Z-baseline-math.batch` | Baseline math inputs. | No named reference. |
| `probe-runs/20260713T092731Z-baseline-math.tsv` | Baseline math outputs. | No named reference. |
| `probe-runs/20260713T092847Z-window-extra.batch` | Additional window inputs. | No named reference. |
| `probe-runs/20260713T092847Z-window-extra.tsv` | Additional window outputs. | No named reference. |
| `probe-runs/20260713T092934Z-window-rank-fix.batch` | Window-rank correction inputs. | No named reference. |
| `probe-runs/20260713T092934Z-window-rank-fix.tsv` | Window-rank correction outputs. | No named reference. |
| `probe-runs/20260713T093023Z-collisions.batch` | Function collision inputs. | No named reference. |
| `probe-runs/20260713T093023Z-collisions.tsv` | Function collision outputs. | No named reference. |
| `probe-runs/20260713T224434Z-doris-clickhouse.doris.raw.tsv` | Timestamped raw Doris outputs. | No named reference. |
| `probe-runs/20260713T231518Z-doris-clickhouse-round2.doris.raw.tsv` | Raw Doris round-two bytes. | No named reference. |
| `probe-runs/20260713T233401Z-trino-doris-round2.doris.raw.tsv` | Raw Doris side of Trino round two. | No named reference. |
| `probe-runs/20260713T233955Z-trino-doris-round2.trino.raw.tsv` | Raw Trino side of round two. | No named reference. |
| `probe-runs/20260713T235213Z-aggregates-round.doris.raw.tsv` | Raw Doris aggregate outputs. | No named reference. |
| `probe-runs/20260713T235213Z-aggregates-round.trino.raw.tsv` | Raw Trino aggregate outputs. | No named reference. |
| `probe-runs/20260714T003121Z-trino-clickhouse-mass.trino.raw.tsv` | Raw Trino mass-probe outputs. | No named reference. |
| `probe-runs/20260714T003640Z-doris-clickhouse-mass.doris.raw.tsv` | Raw Doris mass-probe outputs. | No named reference. |
| `probe-runs/20260714T013725Z-reverse-doris-trino.results.raw.tsv` | Raw reverse-mapping comparisons. | No named reference. |
| `probe-runs/20260830T073810Z-starrocks-doris.tsv` | Versioned StarRocks/Doris comparison matrix. | H. |
| `probe-runs/aggregates-round.batch` | Cross-engine aggregate expressions. | A; `HANDOFF-aggregates-probe.md`. |
| `probe-runs/aggregates-round.clickhouse.tsv` | ClickHouse aggregate outputs. | A. |
| `probe-runs/aggregates-round.doris-input.tsv` | Doris aggregate SQL inputs. | A; `HANDOFF-aggregates-probe.md`. |
| `probe-runs/aggregates-round.doris.tsv` | Doris aggregate outputs. | A; `HANDOFF-aggregates-probe.md`. |
| `probe-runs/aggregates-round.duckdb.tsv` | DuckDB aggregate outputs. | A. |
| `probe-runs/aggregates-round.trino-input.tsv` | Trino aggregate SQL inputs. | A; `HANDOFF-aggregates-probe.md`. |
| `probe-runs/aggregates-round.trino.tsv` | Trino aggregate outputs. | A; `HANDOFF-aggregates-probe.md`. |
| `probe-runs/doris-clickhouse-mass.batch` | Doris calls and transpiled ClickHouse expressions. | B; `HANDOFF-doris-clickhouse-mass.md`. |
| `probe-runs/doris-clickhouse-mass.clickhouse.tsv` | ClickHouse mass-probe outputs. | B; `HANDOFF-doris-clickhouse-mass.md`. |
| `probe-runs/doris-clickhouse-mass.doris-input.tsv` | Doris mass-probe inputs. | B; `HANDOFF-doris-clickhouse-mass.md`. |
| `probe-runs/doris-clickhouse-mass.doris.tsv` | Doris mass-probe outputs. | B; `HANDOFF-doris-clickhouse-mass.md`. |
| `probe-runs/doris-clickhouse-probe.batch` | Initial paired Doris/ClickHouse inputs. | `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse-round2.batch` | Paired round-two inputs. | C; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse-round2.clickhouse.tsv` | ClickHouse round-two outputs. | C; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse-round2.doris-input.tsv` | Doris round-two inputs. | C; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse-round2.doris.tsv` | Doris round-two outputs. | C; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse.clickhouse.tsv` | Initial ClickHouse paired outputs. | D; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse.doris-input.tsv` | Initial Doris paired inputs. | D; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-clickhouse.doris.tsv` | Initial Doris paired outputs. | D; `HANDOFF-doris-clickhouse-probe.md`. |
| `probe-runs/doris-xxhash-week-reprobe.input.tsv` | Hash and week-number comparison inputs. | `HANDOFF-doris-xxhash-week-reprobe.md`. |
| `probe-runs/doris-xxhash-week-reprobe.results.tsv` | Hash and week-number comparison outputs. | I; `HANDOFF-doris-xxhash-week-reprobe.md`. |
| `probe-runs/duckdb-clickhouse-massprobe.json` | DuckDB calls, generated ClickHouse SQL and result/error map. | No named reference. |
| `probe-runs/duckdb-clickhouse-round2.tsv` | Paired round-two verdict and output matrix. | E. |
| `probe-runs/reverse-doris-trino.input.tsv` | ClickHouse-source calls and proposed target mappings. | `HANDOFF-reverse-doris-trino.md`. |
| `probe-runs/reverse-doris-trino.results.tsv` | Reverse-mapping outputs and match flags. | J; `HANDOFF-generator-rename-fixes.md`; `HANDOFF-reverse-doris-trino.md`. |
| `probe-runs/trino-clickhouse-mass.batch` | Trino calls and transpiled ClickHouse expressions. | F; `HANDOFF-trino-clickhouse-mass.md`. |
| `probe-runs/trino-clickhouse-mass.clickhouse.tsv` | ClickHouse outputs for Trino mass probe. | F; `HANDOFF-trino-clickhouse-mass.md`. |
| `probe-runs/trino-clickhouse-mass.trino-input.tsv` | Trino mass-probe inputs. | F; `HANDOFF-trino-clickhouse-mass.md`. |
| `probe-runs/trino-clickhouse-mass.trino.tsv` | Trino mass-probe outputs. | F; `HANDOFF-trino-clickhouse-mass.md`. |
| `probe-runs/trino-doris-round2.batch` | Paired Trino/Doris round-two inputs. | G; `HANDOFF-trino-doris-probe.md`. |
| `probe-runs/trino-doris-round2.doris-input.tsv` | Doris side of round-two inputs. | G; `HANDOFF-trino-doris-probe.md`. |
| `probe-runs/trino-doris-round2.doris.tsv` | Doris side of round-two outputs. | G; `HANDOFF-trino-doris-probe.md`. |
| `probe-runs/trino-doris-round2.trino-input.tsv` | Trino side of round-two inputs. | G; `HANDOFF-trino-doris-probe.md`. |
| `probe-runs/trino-doris-round2.trino.tsv` | Trino side of round-two outputs. | G; `HANDOFF-trino-doris-probe.md`. |

## Unresolved provenance

The stale DuckDB/ClickHouse rename-recovery path and the missing
`duck_ch_massdiff*` family above remain unchanged in generated code and source
hazard JSON. Repairing their generators or evidence attribution is outside this
docs-only change. Historical downstream plugin checks and throwaway binary audits
also cannot be reconstructed from this repository alone. No current qualification
claim should depend on their deleted local logs.
