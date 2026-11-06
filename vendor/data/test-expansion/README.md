# Regression harvest sources

This is a small, source-pinned harvest, not an import of Polyglot's entire suite.
`manifest.json` records original repository, release, commit, path and SHA-256 for
every vendored file. Files below the three project directories are byte-for-byte
upstream downloads, including licenses. Tests run offline and do not fetch sources.

## Polyglot

Source: `tobilg/polyglot` v0.14.0, commit
`a62f5b8f5c64f2d30e1072237f5586c98f799066`, MIT, copyright 2026 TobiLG.

Adapted tests, all under `brikk-sql/test/dev.brikk.house.sql/`:

- `PolyglotNumericHarvestTest.kt`: `numeric_exponent_boundaries.rs`, adjacent END
  and complete/incomplete scientific notation. Adds Brikk copy/serde assertions.
  Leading-dot token counts are deliberately not copied. Native DuckDB 2.0 accepts
  incomplete exponent spelling at parse time and rejects DOUBLE conversion later;
  tests preserve that spelling when accepted, while permitting earlier rejection
  of the invalid value. They do not require Polyglot's token split. All seven compact CASE inputs were independently executed on
  DuckDB 2.0.0 JDBC and return the same values as their spaced counterparts.
- `PolyglotQuotedNamesHarvestTest.kt`: `quoted_relation_columns.rs`, relation and
  column aliases, escaped identifiers, ordinality and constraint-name suffixes.
  Uses Brikk's serde rather than Polyglot's serialized schema.
- `PolyglotJoinScopeHarvestTest.kt`: selected parenthesized-join cases from
  `query_analysis_regression.rs`, asserted through `SqlFragment.sourceTables`.
  Does not assert Polyglot-specific scope paths or transform-function metadata.
- `PolyglotRobustnessHarvestTest.kt`: `deep_nesting_regression.rs` and
  `unicode_token_ranges.rs`, adapted to Brikk's nesting budgets and UTF-16 offsets.
  Rust/PyO3/WASI guard APIs and unsupported dialects are not imported.

Existing coverage already exercises Unicode positions, source maps, generic
derived joins, division/regex policies and native DuckDB arrow precedence. These
tests add combinations and boundary inputs rather than replacing those gates.
The underway `sqlglot-30-29-batch-a` catch-up remains separate; its IS/set/operator
precedence fixes are not copied into this branch.

## Engine sources

DuckDB v2.0.0 and ClickHouse v26.10.2.103-stable files are obtained directly from
their repositories. Attribution and licenses are retained here and in root
`ATTRIBUTIONS.md`.

- `EngineSourceHarvestTest` checks every vendored hash and parses/renders/copies/
  serializes the unchanged ClickHouse lambda and JSON files. This is structural
  coverage, not ClickHouse execution. Original `.reference` outputs are retained
  unchanged for independent engine replay, not used as a SQLGlot oracle.
- `EngineSourceHarvestResultTest` in `brikk-sql-verify-duckdb` reads the two unchanged
  DuckDB SQLLogicTest files. It executes their setup and positive queries only in a
  fresh in-memory DuckDB with external access disabled. Original expected rows must
  match original SQL and Brikk-generated SQL through copy/serde. Negative statements
  are checked through DuckDB's parse-only serialization API, never executed.
- The SQLLogicTest adapter accepts only the directives present in these pinned
  files, checks record counts and fails on unknown directives. It does not silently
  skip setup, expected-error or query records. It is not a full SQLLogicTest runner.

No test weakens an existing executable refusal, changes the SQLGlot oracle pin,
updates a global failure ledger or adds a production fix.

## Findings retained as strict regressions

This test-only branch is not a claim that all harvested cases pass. The initial
runs exposed the following existing gaps; the assertions are not disabled or
added to a failure allowance:

- Numeric boundary: DuckDB accepts `SELECT CASE WHEN TRUE THEN 13END AS value`
  and the other six harvested number spellings. Brikk tokenizes `13END` as a
  number followed by a variable and fails to find CASE's END. The token-boundary
  and public parse/copy/serde tests both expose this same defect.
- MySQL key parts: `CREATE TABLE t (\`name(1)\` VARCHAR(64), PRIMARY KEY
  (\`name(1)\` ASC))` fails our parser. MySQL's official 8.4 CREATE TABLE grammar
  permits ordering and prefix lengths on key parts:
  https://dev.mysql.com/doc/refman/8.4/en/create-table.html . This is documentation
  corroboration, not native MySQL execution; Docker access was denied on this host.
- Native nested joins: DuckDB's original positive query in
  `nested_join_backtracking.test` returns `1\t1\t1`. Brikk emits an alias after
  the nested join condition (`... ON t2.b = t3.c AS t2(b) ...`), which native
  DuckDB rejects. The expected rows are taken directly from DuckDB's unchanged
  fixture. Negative native parser controls also run as their own test so this
  positive-case failure cannot prevent them executing.

The incomplete-exponent assertion was corrected after independent native testing:
`SELECT 1e` is parsed by DuckDB and then fails DOUBLE conversion. Requiring an
immediate ParseError would be a false regression. Leading-dot scientific notation
is asserted by numeric AST value rather than Polyglot's one-token representation.

## Validation on 2026-11-06

- Full core suite: 1,751 tests executed, 1,748 passed, three strict harvested
  failures. Two failures expose the same compact CASE/END token boundary, and
  one exposes MySQL constraint key-part parsing. No test is skipped or waived.
- Focused DuckDB native suite: three tests executed, two passed. JSON-arrow
  subquery result tests and the independent malformed-join controls pass; positive
  nested-join rendering fails against native DuckDB 2.0.0.
- Source tool: all 16 vendored files verify offline. Four Python tool tests pass,
  including modified bytes/identity and refused replacement on bad downloads.
- `git diff --check` passes. No production files, oracle pins or failure ledgers
  are changed. No full IDE, other verifier, live MySQL or native ClickHouse
  execution gate is claimed. This branch deliberately retains the new red tests
  for follow-up fixes; it is not merge-ready as a green test-only change.

Logs are `/var/tmp/jayson/opencode/test-expansion-core-final.log` and
`/var/tmp/jayson/opencode/test-expansion-duckdb-final.log`. Earlier failed test
adaptations remain in the first/focused logs rather than being presented as bugs.

## Reproduce

```sh
python3 tools/test_expansion_sources.py
./kotlin test -m brikk-sql --include-classes='*HarvestTest' --jvm-args='-Xmx1g -XX:ActiveProcessorCount=2'
./kotlin test -m brikk-sql-verify-duckdb --include-classes='*EngineSourceHarvestResultTest' --jvm-args='-Xmx1g -XX:ActiveProcessorCount=2'
```

`--fetch` on the source tool re-downloads and verifies locked bytes. `--record`
re-records hashes and is reserved for a reviewed source refresh. Adapted assertions
are strict; new failures must be investigated, not silently accepted or skipped.

## Main integration follow-up

The SG29 branch merged these tests with the v30.29.0 integration. The follow-up
fixes numeric END boundaries, MySQL primary-key ordering/prefixes and the placement
of aliases on nested VALUES joins. Native rows, malformed-join controls and all
vendored bytes remain unchanged.

The adjacent-END assertion also needed the documented leading-dot adaptation:
Brikk emits DOT + NUMBER for `.5`, rather than Polyglot's single NUMBER. The test
now checks those exact types/texts and the END offset. Compact CASE numeric
meaning and copy/serde checks are unchanged. It does not approve an arbitrary
token count or let END become part of a number.

The original red-test record above remains historical. The combined build,
2,633 JVM tests, all 841 IntelliJ tests and 247 tooling tests now pass. Existing
optional JVM assumptions remain. Validation and logs are recorded in
[the integration record](../../../dev-docs/main-integration-20261106.md).
