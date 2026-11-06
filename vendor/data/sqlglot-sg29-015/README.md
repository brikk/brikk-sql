# SG29-015 Spark/Hive aggregation and ALTER forms

The owner authorized this round on 2026-11-06. SG29-016 onward, publication,
downstream adoption and PR creation remain outside the request. The global
oracle stays at `v30.22.0-5-ga00dc2e5c`; frozen target is SQLGlot v30.29.0.

## Bounded upstream changes

- `ab043477`/#8829 routes Spark LISTAGG through the string-aggregation parser.
  `2748f82e`/#8831 accepts explicit ALL. DISTINCT, separators and WITHIN GROUP
  ordering remain in the AST and serde. Spark rendering separates ordering into
  WITHIN GROUP without detaching children during copy=false generation. The
  native empty default separator stays empty, not SQLGlot's generic comma.
  Overflow and aggregate LIMIT have no Spark mapping and refuse strict execution.
- `0b001782`/#8611 covers singular/plural DROP COLUMN forms, optional wrapping,
  IF EXISTS, nested fields and keyword-spelled names. Plural drops preserve their
  column schema and IF EXISTS flag. Foreign plural execution is refused rather
  than rendering another engine's unsupported multi-column grammar.
- `03744a2a`/#8633 introduces ReplaceColumns with one required expressions list.
  It is an exact bounded upstream query node, not a brikk-native node. Registry
  and schema tests allow exactly this addition without refreshing the manifest.
  Definitions/comments and Hive CASCADE remain structured. Empty or unsupported
  definitions retain the complete opaque ALTER statement. Other-engine execution
  refuses unqualified catalog replacement, and Hive CASCADE is not mapped to
  Spark partition-metadata behavior.

The project has no Databricks dialect. Upstream Databricks fixtures do not
authorize a new dialect. Spark2 remains its existing separate dialect and does
not gain Spark 4 LISTAGG support in this round.

## Scope and catalog limits

ALTER is a state-modifying statement, not a SELECT. Column definitions and DROP
targets must not become query references or result columns. RelationScope
analysis does not apply migrations to provider-supplied catalog facts, and this
round does not add a catalog migration engine. Parsing a REPLACE action says
nothing about a connector's storage, partition metadata, analyzer acceptance or
whether a live table permits the operation. Refreshing catalog facts after a
real migration remains the caller's responsibility. No public data-class
constructor/copy shape changes.

`tools/probe_spark_hive_catchup.py` uses a task-private Spark 4.0.1 local session.
It executes only synthetic SELECT original/generated pairs for aggregation
values and exact Spark schema JSON. ALTER inputs go only to the shipped parser,
never SparkSession.sql, an analyzer or an executor. The empty task catalog must
remain unchanged. Hive examples use the shipped Hive ParseDriver, without a
Hive session or backend. Parser acceptance is not Hive backend qualification.
Docker is unavailable to this session due
to socket permissions; no permissions or host settings are changed.

Validation uses a new `build/sg29-015-build` root. Logs and the private PySpark
environment use `/var/tmp/jayson/opencode/sg29-015-*`. The prior native audit
snapshot is retained in `build/sg29-015/native-coverage-before.json`. Frozen
parity or native-audit differences must be reviewed individually; no broad
generated-table refresh or count allowance is permitted.

## Integration validation

The all-module build passes. The corrected candidate passes 1,822 core tests,
760 other JVM tests and all 827 shared IntelliJ tests on `261.25134.95`.
The 72 existing optional JVM assumption aborts are not native qualifications.
Python discovery passes 228 tests with one optional skip. The genuine signed
window fixture remains in use. Kotlin source hashes are retained in
`build/sg29-015/source-manifest.json`.

An initial cross-target nested-DROP refusal also blocked an existing qualified
Doris native contract. The guard now applies only to Spark-source translations;
the full corrected suite includes that Doris control. Failed logs are retained.

`native-coverage-review.json` confirms the entire before/after audit is identical,
including all 24,606 native outcomes, engine/settings, local classifications and
inventory rows. No audit baseline, parity ledger row or frozen corpus changed.
`reference-controls.json` contains ten controls from an isolated archive of
the exact v30.29.0 target commit. The shared upstream checkout had moved to
another commit; it was not reset or modified.

`native-results.json` retains 22 matching original/generated Spark 4.0.1 SELECT
value/type pairs on Java 17.0.21. They cover ALL/DISTINCT, omitted/empty/nonempty
separators, ordering, duplicates, NULL values, all-NULL and empty input, and a
NULL delimiter. Omitted separators produce `baa`, not `b,a,a`. The empty native
default is deliberately preserved instead of the generic comma emitted by the
frozen v30.29 target. Unordered pairs are same-control observations, not a claim
that LISTAGG promises deterministic order without WITHIN GROUP.

Eight Spark parser-plan pairs and two Hive 2.3.10 ParseDriver AST pairs match.
Hive CASCADE is checked only as parsed metadata, not partition/storage effects.
Hive 4 backend execution is not claimed. All ALTER validation is parse-only;
the private Spark catalog remains empty. The report records five native jar
hashes, the probe hash and the runtime versions. The Spark session is stopped
in a finally block, including failed iterations.

Spark 4.0.1 rejects the upstream-compatible unwrapped `REPLACE COLUMNS a INT,
b STRING` source. Its parenthesized generated form is checked separately as
normalization, never counted as a native original/generated equivalence pair.
The grammar port retains this upstream convenience without pretending the
original text executes on Spark. Wrapped replacements remain separate native
parser-plan controls. No ALTER is executed.

SG29-015 is complete and verified. The owner authorized commit/push, followed
by SG29-016 through SG29-018 in order with separate commits/pushes. No
publication or downstream adoption. SG29-019 onward remains out of scope.

`final-facade-replay.json` records the unchanged published Java consumer facade
running all 33 renderings on Java 17 and the final compiled-class hashes. All
core/metadata class files retain Java 17 bytecode. The exact ReplaceColumns
registry/schema allowance is separate from the frozen global manifest; existing
public constructor/copy signatures are unchanged. Final Kotlin and probe source
hashes match the tested candidate. `git diff --check` passes.
