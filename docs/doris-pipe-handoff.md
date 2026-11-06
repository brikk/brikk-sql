# Doris PIPE handoff results

Verified locally on 2026-09-08 against uncommitted changes based on upstream
`c2a8007`, after published 0.11.0's `b58d024e33e1a13fffac8ef060ff5d471f063fca`.
No commit, publication, version change, or client edit was made for this handoff.
These results apply to the candidate hashes below, not to published 0.11.0.

## Fixes and results

The shared fixture is `t(id INTEGER, category VARCHAR)` with rows
`(1, 'A'), (2, 'A'), (3, 'B')`. Results below are duplicate-preserving row multisets;
an inner ORDER BY selects the input rows but does not promise outer presentation order.

| Issue | 0.11.0 behavior | Candidate behavior |
| --- | --- | --- |
| `ORDER BY id |> LIMIT 2 |> WHERE id > 1` | IDs 2 and 3 | ID 2 only. The filter consumes the limited relation. |
| Head `DISTINCT ON (category)` ordered by ID, then `WHERE id > 1` | IDs 2 and 3 | ID 3 only. Filtering cannot replace the selected A row. |
| `SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) < 3 |> SELECT DISTINCT *` | Exposes an internal `_w` column | IDs 1 and 2, exactly `id, category`. Doris retains native window-dependent QUALIFY. |
| `RENAME id AS renamed_id |> WHERE renamed_id > 1 |> LIMIT 2` | Warning-free SQL containing an invalid star RENAME | With complete schema, IDs 2 and 3 with columns `renamed_id, category`; without expansion, `UnsupportedError`. |
| Invalid pipe LIMIT/OFFSET values | Internal-state/number-format errors or string coercion | Typed `UnsupportedError`; no coercion, evaluation, truncation, or dropped parameters. |
| `ORDER BY id |> LIMIT 2 |> OFFSET 1` | Keeps the earlier limit of 2 after offsetting | ID 2 only, emitted as `LIMIT 1 OFFSET 1`. OFFSET consumes the earlier limited slice. |

WHERE reuses the scope-aware input-boundary checks, including lateral namespace
refusals. RENAME validates simultaneous mappings per star and creates an output
boundary. Pretty source-map matching now tolerates indentation added around a
CTE/subquery, without assigning repeated column tokens to a different clause.

The two corrected pipe-corpus expectations are explicit and checked against their
current inputs. The extracted SQLGlot fixtures remain unchanged. See
[the extensions registry](brikk-extensions.md) for the divergence and refusal rules.

## Public contracts

No public method signatures, serialized model fields, or dependencies changed.
Core and metadata still target Java 21. These are behavioral contract changes:

- `toExecutable`, `transpileTo`, and AST lowering/generation can now throw the existing
  `UnsupportedError` for unsafe WHERE boundaries, unexpanded Doris star RENAME,
  unprovable QUALIFY rewrites, and invalid pipe pagination. These paths no longer
  return warning-free incorrect SQL or leak generic numeric exceptions.
- Pipe LIMIT/OFFSET supports non-negative integer numeric literals through
  `Long.MAX_VALUE`. Strings, decimals/scientific notation, placeholders, expressions,
  unsupported row-count modifiers, and overflowing slice arithmetic are refused.
  The existing checked Doris standalone head-OFFSET behavior remains covered.
- Schema-aware RENAME uses the existing
  `fragment.toStandardSql("doris", inputs = catalog, expandStars = true)` API.
  `toExecutable` has no catalog parameter and does not expand RENAME automatically.
  Callers needing source maps can use tracked generation of an AST expanded with
  `expandStarModifiers`; `PipeRenameTest` covers that path.
- Schema expansion/qualification rejects invalid RENAME mappings with the existing
  `ShapeError`: unknown, ambiguous, or repeated sources; repeated/colliding targets;
  and duplicate schema column names. Doris column collisions are case-insensitive,
  including quoted names. Qualified rename arguments raise `UnsupportedError`.
- Syntax errors retain their parse/token error types. Do not catch all runtime
  failures, change cancellation handling, or identify refusals by message matching.
  Keep `unsupportedMessages` handling for results that actually return.

Ordinary native window-dependent QUALIFY is supported without hiding caller columns
named `_w` or `_row_number`. Cross-dialect window aliases need proven input bindings.
Scalar-only predicates can use an explicit-projection fallback; unsafe stars, nested
queries, alias collisions, and unprojected aggregates remain refusals. Generic
non-Doris QUALIFY star lowering still has its documented helper-leak limitation.

## Upstream verification

Run from the upstream root. Set `SCRATCH_DIR` to an existing private directory
with enough space for native extraction and test outputs.

```sh
env JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$SCRATCH_DIR" \
  ./kotlin do assemblePluginJar
env -u BRIKK_TRINO_CONTAINER \
  JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$SCRATCH_DIR" \
  ./kotlin test \
    -m brikk-sql -m brikk-sql-metadata -m brikk-sql-verify \
    -m brikk-engine-kotlin -m brikk-engine-kotlin-compiler-plugin \
    -m brikk-engine-kotlin-tooling -m brikk-engine-kotlin-smoke
```

| Module | Tests | Failures/errors/skips |
| --- | ---: | --- |
| brikk-sql | 746 | 0/0/0 |
| brikk-sql-metadata | 47 | 0/0/0 |
| brikk-sql-verify | 140 | 0/0/0 |
| brikk-engine-kotlin | 19 | 0/0/0 |
| brikk-engine-kotlin-compiler-plugin | 53 | 0/0/0 |
| brikk-engine-kotlin-tooling | 5 | 0/0/0 |
| brikk-engine-kotlin-smoke | 1 | 0/0/0 |
| Total | 1,011 | 0/0/0 |

New semantic suites are `DorisPipeStageOrderTest`, `DorisQualifySemanticsTest`,
`DorisRenameSemanticsTest`, and `PipePaginationSemanticsTest`. They use independent
staged references, exact output names/width and row multiplicities, native Doris
grammar, strict qualification with complete fixtures, and unchanged generated SQL
execution in DuckDB JDBC 1.5.5.0 for portable cases. Existing FULL JOIN, DISTINCT,
lateral, Unicode/source-map, quoting, function and DDL tests remain active.

## Consumer verification

The unchanged client is at `49caa9a8f187367393852e71cde4fb0de4696beb`. From
`/home/jayson/DEV/sortdev/doris-intellij-plugin`, use the candidate gate with
`CORE_JAR` and `METADATA_JAR` set to fresh candidate outputs:

```sh
mise exec -- bash /home/jayson/DEV/brikk/brikk-sql/tools/verify_doris_consumer.sh \
  --client /home/jayson/DEV/sortdev/doris-intellij-plugin \
  --core-jar "$CORE_JAR" \
  --metadata-jar "$METADATA_JAR" \
  --scratch-dir "$SCRATCH_DIR"
```

Result: **44 passed, 1 failed, 0 skipped**. All 9 action tests passed; 35 of 36
upgrade tests passed. The sole failure is
`B14 remains open because warning free pipe RENAME fails the native grammar`.
It expects `Transpile.Ok` containing invalid RENAME SQL. The adapter instead returns
the intended `Transpile.Err` from `UnsupportedError`. The gate correctly stays red.

Before adoption, replace B14's invalid-success characterization with an assertion
that no-schema RENAME returns `Transpile.Err`. Keep the dispatch regression requiring
handled refusals to report without submission or predecessor delegation. Do not
exclude B14, broaden the exception catch, or match its diagnostic message to turn
the gate green. If the client adopts schema-backed generation, add a separate
successful RENAME case with the complete catalog.

`tools/verify_doris_consumer.sh` records JUnit results, candidate/input hashes
and the final test classpath for each new run. Historical candidate hashes follow.

| Input | SHA-256 |
| --- | --- |
| Core candidate | `fb67dd1038186eba182b363a0e85c9a54e4596c927a4d8a55b30116e5461a58d` |
| Metadata candidate | `6c698e38d931c0bd2be0dd3c06c5e7c2d3556218339d4167a10a734fa86f398e` |
| Copied client input manifest | `0414474de4cede2abde6f0197387b03f8c4bfc5162353f4676a1f2c89c67175d` |

## IDE runtime libraries

The Gradle consumer test classpath includes Kotlin 2.4.10. Separate Java 21 probes
therefore checked the actual older IDE libraries without Maven runtime replacements:

| Library set | Kotlin | Serialization core | Result |
| --- | --- | --- | --- |
| DataGrip 2026.1.3 from the private consumer SDK | 2.3.20 | 1.9.0 | Passed |
| Installed DataGrip DB-262.10315.132 | 2.4.0 | 1.9.0 | Passed |

Each run initialized all 2,406 candidate classes and reflected 21,428 members.
Every class has header 65.0, including 2,366 core classes and 40 metadata classes.
No dependency classes are bundled in either JAR. The symbolic linkage scan checked
96 Kotlin/serialization types and 297 member references, with none missing in either
SDK. Runtime probes round-tripped all 4,441 catalog entries, Shape and captured
metadata, then exercised the new lowering/refusal paths, source-map identity,
schema-aware RENAME and DDL capture. No newly required runtime API was found.

The throwaway Java linkage probes were not committed. These are historical
measurements, not repeatable repository fixtures. Current public JVM-shape
checks are in `brikk-sql/test/dev.brikk.house.sql/BinaryCompatibilityTest.kt`;
they do not reproduce that old-IDE linkage scan.

These checks do not launch either IDE or prove plugin-classloader isolation,
companion coexistence, packaging, or published transitive dependencies. No live
Doris server, production database, PluginVerifier, or full downstream IDE matrix ran.
Native grammar acceptance and DuckDB results are not a Doris planner/runtime proof.
Plugin B3 and B10 remain separate work. After separately authorized publication,
verify the actual new artifacts and rerun the downstream matrix; never replace an
existing Maven Central version.
