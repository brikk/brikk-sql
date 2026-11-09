# Development tools

Run these tools from the repository root unless their own instructions say otherwise.
This index records existing references, not a request to rerun a capture or refresh evidence.
Live families have workflow, document or generated-file references. Provenance tools
reproduce earlier evidence; that does not make their observations current engine proof.

- **Generated Kotlin — live.** `gen_ast_nodes.py`, `gen_function_registry.py` and
  `gen_typing_metadata.py` produce core `Generated*.kt`; `generate_*functions.py`,
  `generate_datafusion_metadata.py`, `generate_doris_properties.py`, `generate_keywords.py`,
  `generate_hazards_registry.py` and `generate_semantic_coverage.py` produce metadata `Generated*.kt`.
  Their generated headers name the scripts. `gen_tokenizer_tables.py` produces the companion token
  tables and is referenced in [the extensions registry](../docs/brikk-extensions.md). Use the exact
  source pins and reviewed inputs; see [vendor provenance](../vendor/README.md).
- **Release and publication checks — live.** `release_config.py`, `publish_intellij_support.py`,
  `verify_release_candidates.py` and `verify_*publication.py` validate versions, candidate artifacts
  and public coordinates. They are called by `test.yml`, `release.yml` or
  `intellij-support-release.yml` under [`.github/workflows`](../.github/workflows/). Consumer checks
  (`verify_engine_candidate.py`, `verify_doris_consumer.sh`) have
  [Engine](../docs/engine-candidate-verification.md) and [Doris](../docs/doris-pipe-handoff.md) guides.
  `doris-consumer.init.gradle` is the Gradle verification helper used by `verify_doris_consumer.sh`.
  `publish_maven_local.sh` is referenced by the
  [archived evaluation tracker](../dev-docs/archive/TODO-rectify-from-eval.md).
- **Packaged consumer smoke tests — live, documented release checks.** `release/` holds
  `ClickhouseSemanticSmoke.java`, compiled and run by `verify_clickhouse_semantic_candidate.py`.
  [The 0.23.0 release record](../docs/sql-0.23.0.md) documents that packaged Java 17 API check;
  [the ClickHouse tracker](../dev-docs/TODO-clickhouse-semantic.md) also cites the smoke source.
- **Engine probes, captures and audits — live, with historical evidence tools alongside them.**
  `probe_behavior_matrix.py` and `gen_behavior_vectors.py`, `probe_trino_doris_json.py`,
  `probe_clickhouse_2026.py`, `clickhouse_2026_audit.kts`, registry captures and Doris extractors have
  refresh instructions in [vendor provenance](../vendor/README.md). DataFusion/Spice capture, probe
  and audit tools are documented in [the reference guide](datafusion-reference/README.md) and its
  linked research reports; `datafusion_upstream.py` and `run_datafusion_upgrade_ci.py` are also called
  by `datafusion-upstream.yml`. DuckDB's three `duckdb_*audit.py` tools are referenced by
  [its tracker](../dev-docs/TODO-duckdb-2.0.md). `build_duckdb_window_fixture.py` is called by
  `test.yml`; `build_doris_parser.sh` and `clickhouse-verify-server.sh` have vendor and verifier
  README instructions.
- **Bounded native probes and source captures — evidence provenance.** The ClickHouse semantic
  guides have commands for `probe_clickhouse_division_types.py`,
  `probe_clickhouse_decimal_division.py`, `probe_clickhouse_integer_add_sub.py`,
  `probe_clickhouse_floating_add_sub.py`, `probe_clickhouse_literal_types.py`,
  `probe_clickhouse_projection_types.py`, `probe_clickhouse_result_matrix.py`,
  `probe_clickhouse_lowering.py`, `probe_clickhouse_script_settings.py` and
  `capture_clickhouse_semantic_contract_sources.py`; see
  [the evidence README](../vendor/data/clickhouse-semantic/README.md) and its companion guides.
  `probe_clickhouse_multiplication.py` is referenced by `test_clickhouse_multiplication.py`, not
  those READMEs. The MySQL statistics, ClickHouse JSON, Doris full-text, Spark/Hive and StarRocks
  probes and `StarrocksNativeParserProbe.java` are referenced by the
  [SQLGlot task evidence guides](../vendor/README.md#sqlglot-3029-task-evidence).
  `probe_sqlglot_precedence.py` has reproduction commands in the
  [batch-B guide](../vendor/data/sqlglot-30.29-batch-b/README.md). Several `probe_*.py` scripts use
  `SqlglotPrecedenceProbe.java` as their shared Java bridge; the batch-B and SG29-008 READMEs also
  cite it. Their finite controls do not establish general cross-engine equivalence.
- **Corpus generators and importers — live.** `gen_*corpus.py`, `extract_dialect_tests.py`,
  `extract_datafusion_slt_corpus.py` and `import_polyglot_datafusion_fixtures.py` build parser,
  tokenizer, AST/serde, scope, qualification, lineage and native-source fixtures. References are in
  [the integration evidence](../vendor/data/sqlglot-sg29-020/README.md),
  [corpus coverage](../docs/corpus-coverage.md), [attributions](../ATTRIBUTIONS.md) and the corpus
  READMEs under `brikk-sql/testResources/`. They need their recorded source revisions, not whichever
  upstream package is installed.
- **Frozen-target and ABI review — evidence provenance.** `sqlglot_target_dispositions.py` and
  `review_sqlglot_refresh.py` check explicit commit/ledger decisions, as documented in the
  SG29-019/020 evidence guides. `review_sqlglot_native_refresh.py`, `sg29_020_ledger_decisions.py` and
  `verify_jvm_abi.py` are recorded in `vendor/data/sqlglot-sg29-020/source-manifest.json`. Do not
  regenerate historical manifests to make a new tree match them.
- **Research inventories — documented tools.** `function_gap_report.py`, `generate_probe_worklist.py`
  and `verify_starrocks_engine.py` are referenced by
  [the research inventory](../dev-docs/research-inventory.md) and its linked reports. They inventory
  or verify bounded research inputs, not production engine support.
- **Tests — live.** `test_*.py` exercises tooling policies, generators and checked-in evidence.
  [CONTRIBUTING](../dev-docs/CONTRIBUTING.md) defines the full offline gate:
  `python3 -m unittest discover -s tools -p 'test_*.py'`. Workflows run selected release, DataFusion,
  Spice and ClickHouse patterns, not the full discovery set. `test_expansion_sources.py` is a
  source-harvest verifier despite its name; [vendor provenance](../vendor/README.md) documents its CLI.

No workflow, document or generated-header references were found for
`probe_temporal_arithmetic.py` or `import_spice_documentation_audit.py`, apart
from the cleanup review naming them. Their headers describe an SG29-009 temporal
probe and a historical Spice documentation import. They remain here unchanged;
this index does not establish that either is live.

The Kotlin `metadata-capture` and `dialect-audit` modules and the Rust
`datafusion-reference` tools support these captures. Their module files, the
reference guide and vendor provenance describe their engine dependencies.
