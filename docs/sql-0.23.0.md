# SQL 0.23.0 and IntelliJ support 0.4.0 releases

Published and verified on Maven Central on 2026-11-03 UTC from merged main commit
`b406a1bd894963f3a09f63f02f6b04e606f083f2`, frozen at `release/0.23.0`.
The user explicitly authorized final SQL `0.23.0` and IntelliJ `0.4.0` publication.
Both move forward from the preceding SQL `0.22.0` and IntelliJ `0.3.0` releases.
The historical RC labels below were local candidates, not published stable versions.

## Published coordinates

All artifacts use group `dev.brikk.house` and resolve from `mavenCentral()`.

SQL `0.23.0` includes all 12 aligned coordinates:

- `brikk-sql`, `brikk-sql-jvm`
- `brikk-sql-metadata`, `brikk-sql-metadata-jvm`
- `brikk-sql-verify-api`
- `brikk-sql-verify-trino`, `brikk-sql-verify-duckdb`, `brikk-sql-verify-doris`
- `brikk-sql-verify-shardingsphere`, `brikk-sql-verify-clickhouse`
- `brikk-sql-verify`, `brikk-sql-oracle`

IntelliJ `0.4.0` includes `brikk-sql-intellij-support`,
`brikk-sql-intellij-jdbc-support` and `brikk-sql-intellij-test-support`.
Its SQL dependencies are aligned at `0.23.0`. Publication used
`publish_core=false`, so it did not republish the immutable SQL prerequisites.

## Final merged release qualification

The [SQL release workflow](https://github.com/brikk/brikk-sql/actions/runs/41570918595)
and [IntelliJ release workflow](https://github.com/brikk/brikk-sql/actions/runs/41573875618)
both passed their full gates on the frozen source, without a preverified bypass:

- All modules built; 2,466 JVM tests passed, with 72 expected optional assumption
  aborts and zero failures. Core scaling tests ran separately with the CI budget.
- All 827 IntelliJ platform tests passed on `261.25134.95`. The native window
  fixture was built from exact DuckDB `d54cbbe3df17e8e5ce68312f483b4155836112aa`
  headers and checksum-pinned static libraries. Its genuine signed capture was
  generated and checked before the IDE gate.
- Each CI gate passed 43 enabled release-model tests, 64 DataFusion tests, three
  Spice evidence tests and 99 ClickHouse evidence tests. Separate local full Python
  discovery passed all 225 tests with the release model enabled, using the unchanged
  SQLGlot commit `a00dc2e5c489214bea53bacbbeba8713f21ebce1`.
- Formal release POM/module alignment, verifier packaging, Java 17 and IntelliJ
  publication audits passed. No native evidence, known-failure ledger or test
  expectation was relaxed. Historical ClickHouse CTE reports remain unchanged.

The public audit downloaded all 15 coordinates from
`https://repo.maven.apache.org/maven2/`, independently of Maven Local. It verified
all 60 POM/module/binary/source payloads, ZIP integrity, SHA-1 sidecars and detached
PGP signatures. The signer is `C8079FC776361E633B091E534E674B3D5F551356`, matching
the preceding release. Dependency metadata contains no SNAPSHOTs or stale Brikk
versions. Nine representative packaged sources match the exact frozen tree,
including DuckDB parser/generator/catalog code and ClickHouse semantic APIs.

The downloaded core and metadata JARs also pass the packaged ClickHouse semantic
API smoke on actual Java `17.0.21`, including CTE propagation, proof categories,
script diagnostics and the old result constructor shape. Core, metadata,
verification API and Doris verifier retain Java 17 compatibility; IDE support
retains Java 21 bytecode. Trino/aggregate/oracle runtime floors remain unchanged.

Audit manifests and the Java 17 smoke report are under
`build/release-023-040-public/`. `sql-verified.json` and `ide-verified.json` record
public URLs, SHA-256 hashes, source comparisons and all 60 signature results.

## DuckDB and Quack versions

DuckDB is pinned to engine **2.0.0 LTS**, with official
`org.duckdb:duckdb_jdbc:2.0.0.0`. The public DuckDB verifier POM confirms that pin.
The qualified baseline is not silently raised to an untested newer engine.
Nested-schema IDE support remains excluded from this release; core SQL/name/scope
support and the existing safeguards remain intact.

The requested Quack JDBC consumer-driver baseline is **0.12.1**. Quack JDBC is not
a dependency of this repository or these published POMs. This release does not
change consumer-plugin pins or qualify a Quack driver integration. Historical
Quack 0.12.0 notes below or in the work list describe earlier work, not a downgrade.

The merged release includes the bounded ClickHouse semantics/CTE scope described
below and DuckDB 2.0 catch-up. It preserves DataFusion/Spice and existing Java/API
compatibility guards. CHSEM-034 remains open for separately authorized consumer
delivery; publication alone is not adoption or full-server-analyzer certification.

Development resumes at SQL `0.24.0-SNAPSHOT` and IntelliJ `0.4.1-SNAPSHOT`, following
the policy that development bases sort after the latest stable versions.

## Historical candidate preparation

Before final publication, the last stable versions were SQL `0.22.0` and IntelliJ
`0.3.0`. The provisional local candidates were SQL `0.23.0-rc.2` and IntelliJ
`0.3.1-rc.2`. RC2 included the CTE follow-up and superseded RC1 for its source.
Remote publication, release tags and consumer updates were not authorized at that
stage. Development descriptors then used `0.23.0-SNAPSHOT` and `0.3.1-SNAPSHOT`.
The final release above supersedes those publication restrictions for the shared
libraries only. The following records retain their original scope and evidence.

## Later main synchronization

The branch subsequently merges main `95f4fcd4`, including DuckDB 2.0 work and
the developer-documentation reorganization. The shared annotator retains DuckDB,
ClickHouse and DataFusion rules. Its entry guard also preserves main's native
timestamp-precision handling without overwriting caller-owned type slots.
The ClickHouse tracker now lives under `dev-docs/` with repaired evidence/test links.

The RC2 artifacts below qualify the earlier `c770484` CTE source, not this later
merge. Final release checks must rebuild/audit artifacts from the final main
commit, including main's genuine signed DuckDB window capture before IDE gates.
No newer candidate artifacts are built by this synchronization alone.

The user authorized fast-forwarding and pushing main after the merged-tree gates
pass and this branch is committed/pushed. No force update, formal release or
consumer update is part of that authorization.

Merged-tree validation passes 1,734 core tests, 732 other module tests with 72
optional assumption aborts, 827 IntelliJ tests on 261.25134.95, and 224 Python
tests with one opt-in skip. The enabled release-model suite passes 43 tests.
The genuine signed DuckDB 2.0 window capture is generated from the exact pinned
headers/static libraries and validated before the IDE gate. Both ClickHouse CTE
reports regenerate byte-identically. Source/test fingerprints remain unchanged
through final validation. Logs are in `build/merge-main-final-gate/`.

## Candidate scope

The candidate combines current main's DataFusion/Spice release baseline
`0572f6d` with the ClickHouse semantic branch, including CHSEM-001 through
CHSEM-025 plus the narrow CHSEM-030-A CTE follow-up. The two P1 implementation
rounds are `f58dc3d` and `3ae9205`; main reconciliation is `085759b`.
Main is reconciled into the feature branch, not the other way round.

The merge retains DataFusion's strict encoded-string policy alongside ClickHouse's
scientific-number boundaries. Both policy flags survive tokenizer-config builders
without changing the published constructor shape. Shared type annotation retains
DataFusion inference and ClickHouse's contextual native proof walk.

ClickHouse additions include:

- Structured composition and contextual lambda contracts with bounded array/map
  result typing and positioned semantic diagnostics.
- Exact native literal projection types, bounded integer/floating arithmetic and
  setting-aware integer/Decimal division, with separately proved constant errors.
- Explicit sequential script analysis and fresh owned statement AST/report results.
  Existing statement APIs remain local and never infer external session state.
- Scientific-number token boundary corrections in native ClickHouse mode, without
  changing raw generic tokenizer parity.
- Additive statement/script `WithProvenance` APIs that distinguish conditional
  native proofs, generic fallbacks and unknowns for each walked node.
- Primitive standalone multiplication through 256-bit integer widths and admitted
  floating/nullable combinations. Named refinements close the old G01/G02 root
  deviations without rewriting historical captures.
- Bounded non-recursive CTE output propagation through chains, aliases, stars,
  derived relations, supported schema inputs and downstream arithmetic/lambdas.
  Qualified simple inner/cross joins are included; full complex-query parity is not.

## Consumer contract

This is opt-in type-hint/checking support for exactly ClickHouse `26.10.2.103`, not
a full server analyzer. The `26.8.34.13` native pin is a control only. Unsupported
overloads/context can remain unknown without an error. Empty diagnostics never
certify acceptance or execution. A conditional type can coexist with a proved
runtime error. The native `formatQuery` verifier remains parse-only.

Use `annotateClickhouseSemanticTypesWithProvenance` for a supplied AST, or explicitly
choose `annotateClickhouseScriptSemanticTypesWithProvenance` for preceding SET facts.
Trust a node as native-typed only when its fact is `NATIVE_CONDITIONAL`; populated
type slots alone are not proof. Provenance snapshots belong to one unchanged AST
analysis. Copy, serde, mutation and independent calls require fresh analysis.
Function values themselves remain unknown even when callable result facts exist.

No IDE/editor mode is selected automatically. Shared IntelliJ libraries do not
register a production plugin or adopt the APIs on behalf of consumers.
Existing generic typing and public result-class shapes remain compatible.
Core/metadata/verification API/Doris retain Java 17; IDE helpers retain Java 21
bytecode. Trino/aggregate/oracle runtime requirements are unchanged.

Guides and evidence:

- [Closeout and accepted scope](../vendor/data/clickhouse-semantic/closeout-README.md)
- [Per-node provenance](../vendor/data/clickhouse-semantic/type-provenance-README.md)
- [Primitive multiplication](../vendor/data/clickhouse-semantic/multiplication-README.md)
- [Non-recursive CTE slice](../vendor/data/clickhouse-semantic/cte-context-README.md)
- [Deferred CHSEM backlog](../dev-docs/TODO-clickhouse-semantic.md#deferred-improvement-backlog)

P2 families, general rejection coverage, broader session/catalog inference,
cross-dialect lowering and additional version qualification remain deferred.
CHSEM-034 stays open until separately approved publication and consumer delivery
have their own evidence; local candidate success is not delivery.

## Qualification record

The final local RC2 gate passed after the CTE follow-up:

- Every module builds. Core passes 1,545 tests; remaining modules pass 455 with
  61 optional-engine assumption aborts and zero failures.
- IntelliJ platform tests pass 817 cases on `261.25134.95`. Other IDE versions
  were not qualified.
- Full Python discovery passes 214 tests with one opt-in skip. A separate enabled
  release-model run passes all 38 release tests, including the normally opt-in check.
- Both CTE frontend reports regenerate byte-identically. Fresh native CTE replay
  checks 28 finite and six script original/generated outcomes per pin. Twenty
  finite cases and four script cases qualify native types; the other ten controls
  remain deliberately unknown. Control-version errors are not native type matches.
- Earlier main reconciliation refreshed the 1,440-type multiplication matrix and
  19 finite/six script outcomes on both pins. The full current suite replays that
  evidence; not every historical native matrix is freshly executed again here.
- All 15 local candidate coordinates pass alignment, verifier, Java 17 and IDE
  packaging audits. All 60 POM/module/binary/source payloads exist and every JAR
  passes ZIP integrity checks. Eight representative packaged sources match the
  combined checkout, including the merged annotator/tokenizer policies and both
  new semantic APIs/rules and the CTE resolver. Payload/source SHA-256 hashes are recorded locally.
- The packaged API smoke compiles and runs on actual Java `17.0.21`. It verifies
  corrected product types, all three proof categories, copy isolation, conditional
  types alongside semantic errors, exact-pin rejection, the old result constructor,
  and ordinary/chained/array/star CTE propagation against the packaged JARs.
- Local release helpers restore all publishing descriptors byte-for-byte. No
  signing credentials, remote upload, release tag or consumer checkout is needed.

Combined-tree gates are run sequentially using a task-private temporary directory:
all-module build, core tests with the CI resource budget, remaining module tests,
then IntelliJ platform tests on `261.25134.95`. Optional assumption aborts and
skips are reported separately. Python discovery retains the unchanged SQLGlot pin.

Local artifacts are built with the existing restoring release helpers. Audits
check all 15 coordinates, aligned POM/Gradle metadata without SNAPSHOT references,
binary/source JARs, bytecode floors, notices and verifier/IDE dependency contracts.
`tools/release/ClickhouseSemanticSmoke.java` compiles/runs on Java 17 against the
packaged candidate core/metadata JARs, not the build output.

Final CTE/RC2 logs, source fingerprints and audit records are retained under
`build/chsem-030-final-gate/`. The prior RC1 record remains under
`build/clickhouse-release-gate/`. Kotlin source/test fingerprints stay unchanged
throughout the final gates and packaging. No native evidence or parity ledger is
relaxed. Recursive/set-operation CTEs, outer joins, ambiguous or incomplete source
bindings, conflicting definition/consumer division modes and unproved functions
remain outside the narrow CTE contract.

Reproduction commands after the build/test gates, using unused local candidate
coordinates so existing local artifacts are not overwritten:

```sh
TEST_KOTLIN_RELEASE_MODEL=1 python3 -m unittest discover -s tools -p 'test_*release*.py'
./publish-release.sh --local 0.23.0-rc.2
python3 tools/publish_intellij_support.py 0.3.1-rc.2 \
  --core-version 0.23.0-rc.2 --skip-core-publication --local
python3 tools/verify_release_candidates.py 0.23.0-rc.2 --support-version 0.3.1-rc.2
python3 tools/verify_java17_publication.py 0.23.0-rc.2
python3 tools/verify_verifier_publication.py 0.23.0-rc.2
python3 tools/verify_intellij_publication.py 0.3.1-rc.2 --core-version 0.23.0-rc.2
python3 tools/verify_clickhouse_semantic_candidate.py 0.23.0-rc.2 --java-home /path/to/jdk17
```

The checked local coordinates already exist after this run. A later build should
use new unused candidate labels rather than overwrite this qualification sample.

## Original candidate approval checklist

This was the checklist before final release authorization. Items 1 through 3 are
now covered by the final source, user-selected versions, CI gates and public audits
above. Consumer adoption in item 4 remains separately authorized and qualified.

1. Select the exact final source commit and approve landing/releasing it.
2. Confirm final SQL and independently versioned IntelliJ release numbers.
3. Run the remote CI/release gates on that frozen source and approve publication.
   Audit the actual Central artifacts and signatures after publication.
4. Separately authorize and qualify each consumer's dependency/API adoption.

Do not run `publish-release.sh` without `--local` during preparation. Its Central
mode publishes automatically. Existing immutable stable coordinates must not be
overwritten, and an unsigned local candidate is not a signed Central release.
