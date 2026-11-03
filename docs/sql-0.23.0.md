# SQL 0.23.0 release preparation

This is a release-candidate record, not a publication announcement. The last
published versions remain SQL `0.22.0` and IntelliJ support `0.3.0`.
The provisional local candidates are SQL `0.23.0-rc.1` and IntelliJ support
`0.3.1-rc.1`. No remote publication, release tag or consumer update is authorized.
Development descriptors stay at `0.23.0-SNAPSHOT` and `0.3.1-SNAPSHOT`.

## Candidate scope

The candidate combines current main's DataFusion/Spice release baseline
`0572f6d` with the ClickHouse semantic branch, including CHSEM-001 through
CHSEM-025. The last two implementation rounds are `f58dc3d` and `3ae9205`.
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
- [Deferred CHSEM backlog](../TODO-clickhouse-semantic.md#deferred-improvement-backlog)

P2 families, general rejection coverage, broader session/catalog inference,
cross-dialect lowering and additional version qualification remain deferred.
CHSEM-034 stays open until separately approved publication and consumer delivery
have their own evidence; local candidate success is not delivery.

## Qualification record

The local combined-tree gate passed on 2026-11-02:

- Every module builds. Core passes 1,536 tests; remaining modules pass 455 with
  61 optional-engine assumption aborts and zero failures.
- IntelliJ platform tests pass 817 cases on `261.25134.95`. Other IDE versions
  were not qualified.
- Full Python discovery passes 210 tests with one opt-in skip. A separate enabled
  release-model run passes all 38 release tests, including the normally opt-in check.
- Multiplication reports regenerate byte-identically on the combined tree.
- Post-reconciliation native replays freshly repeat the 1,440-type matrix and
  19 finite/six script original/generated outcomes on both exact pins. Their
  reports match the committed CHSEM-025 evidence field-for-field.
- All 15 local candidate coordinates pass alignment, verifier, Java 17 and IDE
  packaging audits. All 60 POM/module/binary/source payloads exist and every JAR
  passes ZIP integrity checks. Seven representative packaged sources match the
  combined checkout, including the merged annotator/tokenizer policies and both
  new semantic APIs/rules. Payload/source SHA-256 hashes are recorded locally.
- The packaged API smoke compiles and runs on actual Java `17.0.21`. It verifies
  corrected product types, all three proof categories, copy isolation, conditional
  types alongside semantic errors, exact-pin rejection and the old result constructor.
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

Gate logs and local audit records are retained under
`build/clickhouse-release-gate/`. Native CHSEM-025 evidence freshly checks 1,440
independent types per pin and 19 finite/six integrated script original/generated
outcomes. Historical matrices are replayed against the combined library, not all
freshly executed in native containers again. No native evidence or parity ledger
is relaxed during reconciliation.

Reproduction commands after the build/test gates, using unused local candidate
coordinates so existing local artifacts are not overwritten:

```sh
TEST_KOTLIN_RELEASE_MODEL=1 python3 -m unittest discover -s tools -p 'test_*release*.py'
./publish-release.sh --local 0.23.0-rc.1
python3 tools/publish_intellij_support.py 0.3.1-rc.1 \
  --core-version 0.23.0-rc.1 --skip-core-publication --local
python3 tools/verify_release_candidates.py 0.23.0-rc.1 --support-version 0.3.1-rc.1
python3 tools/verify_java17_publication.py 0.23.0-rc.1
python3 tools/verify_verifier_publication.py 0.23.0-rc.1
python3 tools/verify_intellij_publication.py 0.3.1-rc.1 --core-version 0.23.0-rc.1
python3 tools/verify_clickhouse_semantic_candidate.py 0.23.0-rc.1 --java-home /path/to/jdk17
```

The checked local coordinates already exist after this run. A later build should
use new unused candidate labels rather than overwrite this qualification sample.

## Remaining release approvals

1. Select the exact final source commit and approve landing/releasing it.
2. Confirm final SQL and independently versioned IntelliJ release numbers.
3. Run the remote CI/release gates on that frozen source and approve publication.
   Audit the actual Central artifacts and signatures after publication.
4. Separately authorize and qualify each consumer's dependency/API adoption.

Do not run `publish-release.sh` without `--local` during preparation. Its Central
mode publishes automatically. Existing immutable stable coordinates must not be
overwritten, and an unsigned local candidate is not a signed Central release.
