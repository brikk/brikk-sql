# DuckDB 2.0 tracker

## Baseline

- DuckDB 2.0.0 LTS, source `d54cbbe3df17e8e5ce68312f483b4155836112aa`.
- JDBC `org.duckdb:duckdb_jdbc:2.0.0.0`. The comparison engine is 1.5.6, source `069cc9f9b5`.
- SQLGlot parity pin `a00dc2e5c489214bea53bacbbeba8713f21ebce1`, described as `v30.22.0-5-ga00dc2e5c`.
- The 2.0 catalog contains 1,093 definitions and 2,963 overloads. Both engine snapshots remain bundled.
- Published catch-up artifacts are SQL 0.23.0 and IntelliJ support 0.4.0. See [the changelog](../docs/sql-0.23.0.md).

IDs are stable. Never renumber, reuse or remove one. Each ID has one row.
75 of 78 deliverables are DONE. Nested IDE schemas, general matching-session
helpers and a new executor remain deferred. DONE applies only to the stated
deliverable, not complete feature semantics or downstream plugin qualification.

| State | Meaning |
|---|---|
| DONE | The stated deliverable exists in the linked tests or source. |
| AUDIT | Existing support needs focused proof before a code change. |
| ADD | Structured support is missing or opaque. |
| MONITOR | Engine or client behavior needs observation, not necessarily parser work. |
| DEFER | Separate integration or executor work, with a reason in the notes. |

Paths below are repository-relative. Tests prove their assertions, not every
possible engine behavior. Deferred rows link retained safeguards or boundary
tests, not an implementation of the deferred feature.

## SQL grammar, names and statement behavior

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| DDB20-001 | DONE | Preserve nested schema paths in core qualification, scope and lineage. | `brikk-sql/test/dev.brikk.house.sql/DuckdbNestedSchemaTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbNestedSchemaResultTest.kt` |
| DDB20-002 | DEFER | Represent recursive schemas in IDE introspection, browsing and editing. | `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.catalog/CatalogSearchPathTest.kt`; `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.relation/DasRelationFactsTest.kt` |
| DDB20-003 | DONE | Resolve self-update aliases and correlated targets without invented statement lineage. | `brikk-sql/test/dev.brikk.house.sql/DuckdbUpdateTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbUpdateResultTest.kt` |
| DDB20-004 | DONE | Resolve UPDATE FROM joins and target-only assignments. | `brikk-sql/test/dev.brikk.house.sql/DuckdbUpdateTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbUpdateResultTest.kt` |
| DDB20-005 | DONE | Pin UPDATE FROM multiple-match outcomes separately from scalar-subquery errors. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbUpdateResultTest.kt` |
| DDB20-006 | DONE | Preserve MERGE actions, INSERT BY NAME and RETURNING scopes. | `brikk-sql/test/dev.brikk.house.sql/DuckdbMergeTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbMergeResultTest.kt` |
| DDB20-007 | DONE | Pin MERGE duplicate-target errors and successful deduplicated controls. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbMergeResultTest.kt` |
| DDB20-008 | DONE | Model NEAREST joins, ranking scope and bounded native top-k behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbNearestJoinTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbNearestJoinResultTest.kt` |
| DDB20-009 | DONE | Model data-modifying CTE outputs and modification-once behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSideEffectCteTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbDmlCteResultTest.kt` |
| DDB20-010 | DONE | Keep COPY CTE bodies structured and expose Count, Files and statistics contracts. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSideEffectCteTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbCopyCteResultTest.kt` |
| DDB20-011 | DONE | Type scalar GROUP BY UNNEST and retain native grouping semantics. | `brikk-sql/test/dev.brikk.house.sql/DuckdbGroupUnnestTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGroupFetchResultTest.kt` |
| DDB20-012 | DONE | Convert supported FETCH forms without changing pagination or bind order. | `brikk-sql/test/dev.brikk.house.sql/DuckdbFetchTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGroupFetchResultTest.kt` |
| DDB20-013 | DONE | Preserve JSON mutation arguments and prove bounded overload and NULL contracts. | `brikk-sql/test/dev.brikk.house.sql/DuckdbJsonMutationTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbJsonMutationResultTest.kt` |
| DDB20-014 | DONE | Distinguish merge-patch, deep-merge, normalization and null-stripping behavior. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbJsonMutationResultTest.kt` |
| DDB20-015 | DONE | Retain VARIANT field case, extraction types and bounded storage round trips. | `brikk-sql/test/dev.brikk.house.sql/DuckdbVariantTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVariantResultTest.kt` |
| DDB20-016 | DONE | Distinguish dollar parameters with session defaults from client substitutions. | `brikk-sql/test/dev.brikk.house.sql/DuckdbVariablesTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVariablesResultTest.kt` |
| DDB20-017 | DONE | Preserve prepared bodies and native EXECUTE default precedence. | `brikk-sql/test/dev.brikk.house.sql/DuckdbVariablesTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVariablesResultTest.kt` |
| DDB20-018 | DONE | Model USING KEY aggregate payloads and raw versus finalized recursive types. | `brikk-sql/test/dev.brikk.house.sql/DuckdbKeyedRecursiveTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbKeyedRecursiveResultTest.kt` |
| DDB20-019 | DONE | Preserve CONNECT/DISCONNECT routing syntax with redacted diagnostics and explicit refusals. | `brikk-sql/test/dev.brikk.house.sql/DuckdbRoutingTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbQuackRoutingResultTest.kt` |
| DDB20-020 | DONE | Model inline trigger actions, transition scopes and supported native behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbTriggerTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbTriggerResultTest.kt` |
| DDB20-021 | DONE | Preserve SECURE VIEW modifiers and refuse unproved cross-target security mappings. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSecureViewTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSecureViewResultTest.kt` |
| DDB20-022 | DONE | Model extension repositories, INSTALL and LOAD without editor-side management. | `brikk-sql/test/dev.brikk.house.sql/DuckdbExtensionRepositoryTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSignedRepositoryResultTest.kt` |
| DDB20-023 | DONE | Model external-resource statements and fixed SHOW output without invoking callbacks. | `brikk-sql/test/dev.brikk.house.sql/DuckdbExternalResourceTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbExternalResourceResultTest.kt` |
| DDB20-024 | DONE | Preserve structured EXPLAIN options and keep editor verification parse-only. | `brikk-sql/test/dev.brikk.house.sql/DuckdbExplainTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbExplainResultTest.kt` |
| DDB20-025 | DONE | Preserve OVERLAY forms and prove VARCHAR, NULL and code-point behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbOverlayTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbOverlayResultTest.kt` |
| DDB20-026 | DONE | Preserve COPY PARTITION_BY and ORDER_BY expressions and ordering flags. | `brikk-sql/test/dev.brikk.house.sql/DuckdbCopyLayoutTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbCopyLayoutResultTest.kt` |
| DDB20-027 | DONE | Preserve PARTITION_PATH expressions and bounded path-helper facts. | `brikk-sql/test/dev.brikk.house.sql/DuckdbPartitionPathTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbPartitionPathResultTest.kt` |
| DDB20-028 | DONE | Distinguish SHOW settings from deprecated table-description behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbShowSettingsTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbShowSettingsResultTest.kt` |
| DDB20-029 | DONE | Type setval and pin sequence effects without editor execution. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSetvalTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSetvalResultTest.kt` |
| DDB20-030 | DONE | Preserve regex overloads and infer bounded scalar, list and named-STRUCT results. | `brikk-sql/test/dev.brikk.house.sql/DuckdbRegexAdditionsTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbRegexAdditionsResultTest.kt` |
| DDB20-031 | DONE | Capture real extension window functions and retain distinct completion identities. | `tools/build_duckdb_window_fixture.py`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbWindowCatalogResultTest.kt`; `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.completion/DuckdbWindowCompletionTest.kt` |
| DDB20-032 | DONE | Model declared Parquet schemas and hidden file_row_group_number columns. | `brikk-sql/test/dev.brikk.house.sql/DuckdbParquetSchemaTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbParquetSchemaResultTest.kt` |
| DDB20-033 | DONE | Separate Parquet writer options from reader metadata and test local output. | `brikk-sql/test/dev.brikk.house.sql/DuckdbParquetWriteOptionsTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbParquetWriteResultTest.kt` |
| DDB20-034 | DONE | Preserve GeoJSON import/export options and native GEOMETRY facts. | `brikk-sql/test/dev.brikk.house.sql/DuckdbGeoJsonTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGeoJsonResultTest.kt` |
| DDB20-035 | DONE | Classify DuckLake, Iceberg and Delta syntax while refusing lossy storage-DDL execution. | `brikk-sql/test/dev.brikk.house.sql/DuckdbCoreExtensionTriageTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbCoreExtensionTriageResultTest.kt` |

## Migration and changed SQL semantics

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| DDB20-036 | DONE | Emit Python-style LAMBDA syntax while retaining legacy arrow input. | `brikk-sql/test/dev.brikk.house.sql/Duckdb20SyntaxTest.kt` |
| DDB20-037 | DONE | Preserve native regex operators and their session-dependent partial/full policy. | `brikk-sql/test/dev.brikk.house.sql/DuckdbRegexOperatorsTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbRegexOperatorsResultTest.kt` |
| DDB20-038 | DONE | Bind factorial before multiplication and preserve operand types and errors. | `brikk-sql/test/dev.brikk.house.sql/DuckdbFactorialPrecedenceTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbFactorialPrecedenceResultTest.kt` |
| DDB20-039 | DONE | Preserve X/B literal payloads as BLOB and BIT rather than VARCHAR. | `brikk-sql/test/dev.brikk.house.sql/DuckdbBinaryLiteralTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbBinaryLiteralResultTest.kt` |
| DDB20-040 | DONE | Retain native NULL column types without changing historical annotations. | `brikk-sql/test/dev.brikk.house.sql/DuckdbNullTypeTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbNullTypeResultTest.kt` |
| DDB20-041 | DONE | Decode identifier-case modes and apply only explicitly supplied policies. | `brikk-sql/test/dev.brikk.house.sql/DuckdbIdentifierCasePolicyTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbIdentifierCaseResultTest.kt` |
| DDB20-042 | DONE | Separate native division/modulo policy from legacy rendering and unsafe rewrites. | `brikk-sql/test/dev.brikk.house.sql/DuckdbDivisionPolicyTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbDivisionPolicyResultTest.kt` |
| DDB20-043 | DONE | Pin selected timezone, calendar, collation and nanosecond type behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbNativeIcuTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbNativeIcuResultTest.kt` |
| DDB20-044 | DONE | Pin observed CONCAT_WS, escape and comprehension result changes. | `brikk-sql-verify/test/dev.brikk.house.sql.verify/DuckdbCatchupResultTest.kt`; `brikk-sql-verify/test/dev.brikk.house.sql.verify/StringEscapeResultTest.kt`; `brikk-sql-verify/test/dev.brikk.house.sql.verify/ComprehensionQualificationResultTest.kt` |
| DDB20-045 | DONE | Verify removed settings and replacement profiling/logging metadata. | `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/DuckdbSettingsMigrationTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSettingsMigrationResultTest.kt` |
| DDB20-046 | DONE | Preserve S3 query text and pin literal-key versus deprecated URL-parameter policy. | `brikk-sql/test/dev.brikk.house.sql/DuckdbS3UrlPolicyTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbS3UrlPolicyResultTest.kt` |
| DDB20-047 | DONE | Pin storage compatibility with isolated old/new engines and scratch databases. | `brikk-sql/test/dev.brikk.house.sql/DuckdbStorageCompatibilityTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbStorageCompatibilityResultTest.kt` |
| DDB20-048 | DONE | Keep CLI calculator and dot commands outside the core JDBC grammar contract. | `brikk-sql/src/dev.brikk.house.sql/dialects/DuckdbNativeParser.kt` |

## Parser and cross-dialect acceptance

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| DDB20-049 | DONE | Compare raw corpus acceptance on isolated 1.5.6 and 2.0 engines. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbAcceptanceComparisonTest.kt` |
| DDB20-050 | DONE | Inventory every applicable corpus emission through a restricted native parser. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbNativeCorpusCoverageTest.kt` |
| DDB20-051 | DONE | Pin PEG ambiguities and native JSON-arrow precedence without changing parity parsing. | `brikk-sql/test/dev.brikk.house.sql/DuckdbPegPrecedenceTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbPegDifferentialTest.kt` |
| DDB20-052 | DONE | Bound malformed input, response decoding, helper capacity and cancellation. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbMalformedInputRobustnessTest.kt` |
| DDB20-053 | DONE | Distinguish registered functions, special grammar forms and binding restrictions. | `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/DuckdbGrammarFormsTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGrammarBuiltinsResultTest.kt` |
| DDB20-054 | DONE | Reconcile upstream SQLGlot changes and preserve SORT BY NAME token boundaries. | `tools/duckdb_sqlglot_audit.py`; `vendor/data/duckdb-sqlglot-2.0-audit.json`; `brikk-sql/test/dev.brikk.house.sql/DuckdbUpstreamSortIdentifierTest.kt` |
| DDB20-055 | DONE | Require explicit refusals for unproved executable mappings across 13 targets. | `brikk-sql/test/dev.brikk.house.sql/DuckdbExecutableBoundaryMatrixTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbExecutableBoundaryResultTest.kt` |
| DDB20-056 | DONE | Assign all 837 final release-note PRs one reproducible disposition and stable owner. | `vendor/data/duckdb-2.0-release-triage.json`; `tools/duckdb_release_audit.py`; `tools/test_duckdb_release_audit.py` |

## Metadata and reproducibility

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| DDB20-057 | DONE | Pin the final native engine and JDBC dependency. | `brikk-sql-verify-duckdb/module.yaml`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVerifierTest.kt` |
| DDB20-058 | DONE | Bundle generated 2.0 function, keyword, setting, type and extension catalogs. | `brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdb_2_0_0.kt`; `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/DuckdbDumpTest.kt` |
| DDB20-059 | DONE | Make captures hermetic and require explicit hashed local extension fixtures. | `tools/metadata-capture/test/DuckdbHermeticCaptureTest.kt`; `vendor/data/duckdb-extension-capture-audit-2.0.0.json` |
| DDB20-060 | DONE | Capture overload properties separately without inventing defaults or NULL rules. | `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/DuckdbFunctionPropertiesTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbRegistryPropertiesResultTest.kt` |
| DDB20-061 | DONE | Assert catalog kinds and signatures against bundled and native rows. | `brikk-sql-metadata/test/dev.brikk.house.sql.metadata/FunctionCatalogTest.kt`; `tools/metadata-capture/test/DuckdbCaptureTest.kt` |
| DDB20-062 | DONE | Add reviewed native function typing while keeping unsupported signatures unknown. | `brikk-sql/test/dev.brikk.house.sql/DuckdbReviewedFunctionTypingTest.kt`; `vendor/data/duckdb-reviewed-function-typing-2.0.0.json` |
| DDB20-063 | DONE | Record a bounded four-engine semantic supplement without rewriting historical hazards. | `vendor/data/duckdb-2.0-bounded-semantic-refresh.json`; `tools/duckdb_semantic_audit.py` |
| DDB20-064 | DONE | Document formal library coordinates and keep metadata and driver pins aligned. | `README.md`; `brikk-sql-verify-duckdb/module.yaml`; `tools/verify_release_candidates.py` |

## Editor boundaries and client work

| ID | State | Deliverable | Tests or source |
|---|---|---|---|
| DDB20-065 | DONE | Use parse-only JSON verification with tested UTF-8 and code-point error ranges. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/ParseOnlyDuckdbVerifierTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/ConnectionDuckdbVerifierTest.kt` |
| DDB20-066 | DONE | Retain the runtime-extension acceptance and static-grammar boundary. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGrammarIsolationResultTest.kt` |
| DDB20-067 | DEFER | Match helpers to follow-console, memory and remote sessions without replay or reconnection. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/ConnectionDuckdbVerifierTest.kt` |
| DDB20-068 | DONE | Keep extension keywords and activation separate from static DuckSQL. | `brikk-sql/test/dev.brikk.house.sql/DuckdbGrammarIsolationTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbGrammarIsolationResultTest.kt` |
| DDB20-069 | DONE | Preserve Java UDF syntax and registration-owned, nonpersistent live catalog facts. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbJavaUdfResultTest.kt`; `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.catalog/DuckdbJavaUdfCatalogTest.kt` |
| DDB20-070 | DEFER | Evaluate chunked results and native client ownership for a future executor. | `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbDmlCteResultTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbVariablesResultTest.kt` |
| DDB20-071 | DONE | Preserve SET SCHEMA strings and refuse unsupported nested namespace switching. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSetSchemaTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSetSchemaResultTest.kt` |
| DDB20-072 | DONE | Canonicalize native ANALYSE while retaining statistics and raw VACUUM boundaries. | `brikk-sql/test/dev.brikk.house.sql/DuckdbAnalyseTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbAnalyseResultTest.kt` |
| DDB20-073 | DONE | Model reviewed SUM promotions, empty STRUCT and typed macro-default syntax. | `brikk-sql/test/dev.brikk.house.sql/DuckdbCompoundNumericTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbCompoundNumericResultTest.kt` |
| DDB20-074 | DONE | Keep reader identifiers distinct from path strings across implicit-string modes. | `brikk-sql/test/dev.brikk.house.sql/DuckdbReaderIdentifierTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbReaderIdentifierResultTest.kt` |
| DDB20-075 | DONE | Classify secret storage visibility and lifetime without leaking credentials. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSecretStorageTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSecretStorageResultTest.kt` |
| DDB20-076 | DONE | Preserve colon subquery aliases and reject simultaneous prefix/postfix aliases. | `brikk-sql/test/dev.brikk.house.sql/DuckdbSubqueryColonAliasTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbSubqueryColonAliasResultTest.kt` |
| DDB20-077 | DONE | Infer native temporal cast precision without replacing caller-owned type facts. | `brikk-sql/test/dev.brikk.house.sql/DuckdbTemporalPrecisionTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbTemporalPrecisionResultTest.kt` |
| DDB20-078 | DONE | Separate ordinary static facts from bounded Spark-compatibility session behavior. | `brikk-sql/test/dev.brikk.house.sql/DuckdbCompatibilityModeTest.kt`; `brikk-sql-verify-duckdb/test/dev.brikk.house.sql.verify/DuckdbCompatibilityModeResultTest.kt` |

## Notes and remaining boundaries

### DDB20-001

Nested schemas cannot be the native current namespace in 2.0. USE and SET schema
reject them. Provider suffix matching is not proof that shortened SQL paths work.
Quoted dots remain part of one identifier. Shape catalogs require uniform depth.

### DDB20-002

Deferred because recursive IDE models and parent-ID-aware introspection need a separate implementation.
Native schema metadata exposes `oid` and `parent_schema_oid`, and tables expose
`schema_oid`. JDBC returns leaf schema names and can produce identical
catalog/schema/table triples for different nested tables. A PgSchema has a
PgDatabase parent, not a child-schema family. Keep ordinary and literally dotted
schemas distinct. Retained shared safeguards do not implement a recursive tree.

### DDB20-005

UPDATE FROM chooses an unspecified matching source row once per target. A scalar
no-match produces NULL instead of leaving the target unchanged. MERGE has a
different multiple-match contract.

### DDB20-008

Only INNER and LEFT NEAREST are supported by the pinned engine. Ties do not name
a stable winner. APPROX is informational in this engine, not a future recall guarantee.

### DDB20-009

Side-effecting CTEs execute once with any materialization hint in the tested
engine. New INSERT/UPDATE images are computed outputs, not old-row provenance.
MERGE is not a valid CTE body. Table rollback does not prove file rollback.

### DDB20-013

Mutations accept one scalar path and JSON-document values, not variadic path/value
pairs or automatic SQL-string quoting. Same-name functions on another engine are
not qualified translations.

### DDB20-014

RFC 7396 cannot represent every desired diff. `diff({}, {"a":null})` produces
`{"a":null}`, whose merge patch yields `{}`. Normalization returns VARCHAR,
sorts object keys but not arrays, preserves duplicate members and may reserialize numbers.

### DDB20-015

VARIANT brackets and integer variant_extract indexes are one-based. Field strings
are not JSONPath. JDBC display of nested values is not a new reader API.

### DDB20-016

Dollar names are named parameters with session defaults, not getvariable calls.
Unset dollar names error; getvariable returns SQL NULL. Numeric parameter names
never fall back to variables. Client colon substitutions remain separate.

### DDB20-017

SQL EXECUTE supports defaults, but unbound JDBC named PreparedStatements report
a parameter-count mismatch. Positional setters do not override named parameters.
Duplicates copy initial variables but later changes remain connection-local.

### DDB20-019

Bare CONNECT and CONNECT LOCAL parse but are not implemented in native 2.0.
Quack loopback tests do not certify live PostgreSQL/MySQL backends or remote data
availability. AST, serde, emitted SQL and raw native exceptions remain sensitive.
The optional Quack fixture is supplied through `BRIKK_DUCKDB_QUACK_EXTENSION`.

### DDB20-021

SECURE VIEW is not definition secrecy. Native definition catalogs still expose
its SQL, and visible-row statistics may remain available. Explicit PERSISTENT
is rejected for views despite shared grammar. Persistent secure views require
v2.0.0+ storage; temporary ones can work on older formats. No general timing-side-channel guarantee is claimed.

### DDB20-022

Quoted FROM in INSTALL AND LOAD can install successfully and then fail to load.
No rollback or trust emulation is provided. `BRIKK_DUCKDB_REPOSITORY_EXTENSION`
supplies a trusted matching fixture. Revocation is not proof that resident code unloads.

### DDB20-023

SHOW ALL may invoke discovery callbacks and is not a local-only catalog read.
Native prepare can evaluate handles/options. Mock local recipes do not qualify
cloud lifecycle, billing, polling deadlines or general remote teardown.

### DDB20-024

ANALYZE option presence enables execution even with false or zero. SQL is an
argument-less option incompatible with ANALYZE or FORMAT. Ordinary EXPLAIN may
evaluate table-function arguments at binding, so it is not an editor fallback.

### DDB20-026

ORDER_BY writes a directory even for a filename-like destination and sorts within
each file, not globally. Native partitioned sorting can evaluate a volatile order
key twice per row. Omitted sort direction and NULL order remain session-resolved.

### DDB20-027

PARTITION_PATH failures can leave earlier output. Empty sources evaluate no path
expression. Pinned JDBC can expose an empty COPY CTE plus outer SELECT as update
count zero. Never retry a write to recover its result channel.

### DDB20-028

SHOW table/view/query descriptions are deprecated in 2.0. Prepared SHOW snapshots
its setting at bind time. The generic combined `?::Type` token gap is not fixed here.

### DDB20-029

ROLLBACK does not undo setval. Duplicate connections share sequence and currval
catalog state, unlike PostgreSQL's session-local currval.

### DDB20-031

SUM OVER remains an aggregate catalog entry. Same-name scalar/window entries in
the real fixture use different system schemas, not a shared namespace.
The signed fixture and its capture qualify shared completion, not automatic
loading or a consumer's connection-refresh lifecycle.

### DDB20-034

GeoJSON ARRAY true writes collections, false writes lines. The old writing-docs
parenthetical was reversed. COPY FROM FORMAT GEOJSON with an arbitrary .json/.tmp
suffix may insert NULL target fields unless reader options enable FeatureCollection handling.

### DDB20-035

Triage is complete, not lossless storage-DDL support. Plain parity `.sql()` can
drop CREATE partition/property clauses. Source-aware previews retain authored SQL
with a finding; executable transformations refuse. Snapshot clauses cannot borrow
current catalog facts. DuckLake 1.1 and the reviewed Iceberg/Delta docs do not
qualify their storage engines or recursive IDE namespaces.

### DDB20-036

Single-arrow compatibility removal belongs to DuckDB 2.1, not the 2.0 input contract.

### DDB20-037

`SqlFragment`, `parseDuckdbNativeOne` and `nativeDuckdbParser` preserve native
operators. Ordinary parseOne, `.sql()` and top-level transpile keep historical
SQLGlot full-match lowering. Views capture the resolved function at creation;
changing the session setting later does not rewrite persisted views.

### DDB20-039

Native `B''` is one-bit zero, not an empty BIT value. Literal continuation through
comments is not a static-parser guarantee.

### DDB20-040

Native facts model 2.0 defaults, not an inferred legacy session. Enabling
legacy_disable_null_type does not retype an existing physical NULL table.

### DDB20-041

preserve_case/lowercase/uppercase affect spelling, not ASCII-insensitive lookup.
Unicode distinctions remain intact. Missing modes stay unknown; no connection is read implicitly.

### DDB20-042

Floating `/` retains Infinity/NaN under either zero policy. Integer `//` and
integer/Decimal modulo become NULL only under explicit deprecated false policy.
FLOAT/DOUBLE modulo zero remains NaN. FLOAT `//` can be fractional; Decimal `/`
and `//` return DOUBLE. Mixed or approximate inputs remain conservative.

### DDB20-045

profiling_mode choices are equivalent, but SET still enables profiling and unknown
names error. enable_logging accepts BOOLEAN but its current getter is INTEGER.
Live getter types are not setting-input types or universal static defaults.

### DDB20-046

The default S3 policy treats `?` and its tail as object-key text. An unset getter
can be SQL NULL while actual requests still follow that default. HTTPFS is an
explicit signed fixture, not a core static setting or automatic installation.

### DDB20-047

New files use format 69, beyond 1.5.6's 64 to 68 range. Existing files retain
their format. Explicit v1.5.0 targets qualify selected INTEGER fixtures only.
A runtime write-target tag does not prove a forced physical-format rewrite.

### DDB20-048

The 2.0 Gems calculator uses shell grammar customization. It does not establish
a JDBC expression-statement entrypoint.

### DDB20-049

JDBC metadata reports driver version 1.0 for both audited JARs. It is not the
Maven version. Unverified transformer/non-SELECT outcomes are not acceptance.

### DDB20-050

Coverage fingerprints protect syntax inventories, not executable equivalence.
Trino authorization nodes can replace nine formerly rejected opaque emissions;
that does not lose accepted input. The test selects the reviewed baseline.

### DDB20-052

Input limits are 1,048,576 UTF-16 units and 256 structural levels. Responses are
limited to 8,388,608 units and 256 JSON levels. There are two helper workers and
two cancellation workers per classloader, without a queued backlog. Limits and
transport failures are unavailable/unverified. Embedded JNI parse-only calls are
synchronous, not crash-isolated or deadline-qualified.

### DDB20-053

POSITION comma syntax is rejected unqualified despite scalar registration.
Qualified main.position uses a different argument order. Bare-only time forms
can parse as calls yet fail catalog lookup. GROUPING outside groups fails binding.

### DDB20-054

The upstream audit compares the parity pin with observed upstream
`6de2df31b0ae8b21155828f575a14dc8903c1b9b`. Its 37 commit dispositions and 18
probes do not claim every upstream hunk is implemented or qualified locally.

### DDB20-055

559 refusal cells cover 43 feature families across 13 targets, not native runs
of thirteen foreign engines. Fractional interval splitting and multi-path JSON
extraction also refuse same-target execution when rendering would lose meaning.

### DDB20-056

The committed snapshot is title-level triage, not a review of every PR body.
126 result/type follow-ups and 13 opaque-title follow-ups remain unqualified.
Owner IDs do not convert those dispositions into implemented features.

### DDB20-059

Historical capture `3111faa` has no binary-hash attestation. Matching versions
alone do not prove freshness. HTTPFS can add zero rows, and statically linked
ICU has no independent extension version or binary.

### DDB20-060

Registry rows expose no argument defaults, null-handling policy, positional split
or table-result columns. colN labels do not prove argument roles. typed_value is
a changing VARIANT getter, not a default. Old 13-column capture and public JVM
constructor/copy shapes remain supported.

### DDB20-062

Reviewed binom, decimal_average, vertex_extract and LTTB facts are signature-bound.
INVOKE, assert_true, variant_to_parquet_variant and aggregate-state export remain
unmodeled where registry types cannot prove their result.

### DDB20-063

The supplement records 11 candidates on DuckDB 2.0.0, Trino 484, Doris
4.1.5-release-917b20be838 and ClickHouse 26.10.2.103. Doris protocol types do not
prove logical-type equality. These samples do not replace the global hazard registry.

### DDB20-064

The original publication milestone was SQL 0.20.1 and support 0.2.4. Later
changelogs list subsequent versions. A library publication is not plugin adoption.

### DDB20-065

Editor verification never prepares or binds SQL. Trusted corpus fallback has a
different contract and still excludes side-effecting management wrappers.

### DDB20-066

The original signed-psql plugin smoke was downstream. The linked repository test
retains native activation/no-side-effect controls, not installed-plugin qualification.
Native autocomplete requires explicit loading and is separate from grammar activation.

### DDB20-067

Deferred because no approved same-instance/routing mechanism proves general session parity.
Safe embedded-file helpers do not qualify memory, remote or follow-console
contexts. Configuration/module changes must invalidate helper and diagnostic
identity. Plugin-only cache and public-method compatibility fixes do not supply
session cloning. Do not replay settings, reconnect, or mutate console transactions.

### DDB20-069

Java registrations are instance-local, not registering-connection-only. Native
system.main/internal=true fields do not prove persistence or ownership. Persist
only application-identified registration ownership; arbitrary external attribution remains unknown.

### DDB20-070

Deferred because chunking and client ownership belong to a future executor, not the verifier.
JDBC can classify a completed DML/COPY CTE plus outer SELECT as CHANGED_ROWS.
An executeQuery error may follow a write, so executing again is unsafe. C API v2
instability, C++17 packaging and macOS x86_64 phase-out are client concerns.

### DDB20-073

Reviewed SUM inputs promote to HUGEINT, DOUBLE or DECIMAL(38,s) as tested.
Bare approximate decimals/wide literals remain unknown. STRUCT() is a parser
error; fractional DECIMAL parameters fail binding. Internal TUPLE recognition
does not establish public tuple-construction or executor support.

### DDB20-075

Memory secrets are instance-shared; connection secrets remain owner-only and are
absent from duplicates. Internal TRANSACTION persistence is not SQL syntax.
CREATE TRANSACTION SECRET is rejected by the final parser.

### DDB20-077

TIMESTAMP precision 10 is accepted but still yields nine-digit nanoseconds.
Selected nanosecond-to-microsecond casts round rather than truncate. Native
VARCHAR projections, not JDBC client-zone formatting, determine zone strings.

### DDB20-078

The registry's GLOBAL label does not prove ordinary SET propagation. Existing
duplicates can retain NONE; later duplicates copy SPARK and then diverge.
Identical grammar lists can coexist with different indexed-lambda results.
Static facts model NONE and never infer live session semantics from grammar activation.

## Doris lessons for nested schemas

For DDB20-002, audit the fixed `levels` list and `maxDeepenDepth` in
`brikk-sql-intellij-support/src/dev.brikk.house.sql.intellij.catalog/CatalogProfile.kt`,
path truncation in `CatalogScopes.kt`, depth limits in `NamespacePathResolver.kt`,
and bounded discovery in `AutoIntrospect.kt` in the same directory.
`CatalogSearchPath.kt` currently constructs DATABASE to SCHEMA readback, not
recursive ancestry. Increasing a depth limit alone does not fix that model.

Doris uses a fixed Ms hierarchy; DuckDB uses PgFamilyModelFacade. Neither proves
recursive SCHEMA children. Quote components separately, preserve cached children
on failed refresh, distinguish empty successful inventories from failures, and
test picker paths, unloaded objects, current namespaces and quoted dots.
Do not flatten nested schemas into top-level dotted names or copy MySQL import
workarounds without proving they apply to the PostgreSQL-family model.

## Sources

- [Final announcement](https://duckdb.org/2026/10/21/announcing-duckdb-200)
- [Final release notes](https://github.com/duckdb/duckdb/releases/tag/v2.0.0)
- [Documentation additions](https://duckdb.org/docs/2.0/whats_new_in_the_docs)
- [Gems of 2.0](https://duckdb.org/2026/10/26/gems-of-duckdb-200)
- [Migration guide](https://duckdb.org/docs/2.0/operations_manual/migration)
