# Frozen-target Engine candidate verification

Integration follow-up: the validated catch-up through `09d5539` was merged into
current main on user request, preserving `0.20.0-SNAPSHOT` and main's newer IntelliJ
completion work. The existing main CI must pass its build/tests before publishing
that snapshot. The evidence below records the original private candidate, not a
claim that a stable release or a snapshot publication had already completed.

On 2026-10-11 the private core/metadata JARs from catch-up commit
`dd1ea09eb82844e677a4165d96410a575c1d8540` passed the actual brikk-house compiler and
runtime gates. The peer was snapshotted at
`3cc5b5a833f76284bcae5496539402ef8873489f`; its original checkout remained clean on
`main`. Nothing was published to Maven Local or a remote repository, and neither
repository's `main` was changed.

## Exact artifacts and results

| Artifact | SHA-256 |
|---|---|
| Core candidate | `f5e4c1618bd80feb731e7001a96ae75ab65bf2ee85fdc6309c906a9fe97acb3b` |
| Metadata candidate | `6f4a506a68d16c79bb7d7de42122aa3d72eb50d3ffcc427f8804287467e29541` |
| CLI relocated plugin (Kotlin 2.4.10) | `7d97f9db920257c8dc3c8bdbe8b1736aee2ff9a7002afce87f12e26501772787` |
| IDE relocated plugin (Kotlin 2.4.20-ij262-34) | `831b39506fb1d62c2bfb41430f1f6b282085160384422dff388e99869e2bb188` |

- **163 tests pass:** 40 runtime, 100 compiler-plugin, nine schema-input and fourteen
  tooling tests; no tests aborted or failed.
- CLI dependency-relocated plugin assembly passes.
- The real Toolchain compiler-plugin smoke module and its test sources build.
- Ten exact IDE-compiler fixtures pass: diagnostics, draft, duckdb-parameters,
  escaping, generation, literal-ranges, nullable, registration, snapshot and
  source-context. The expected IDE stdlib version override is a warning, not a skip.
- Both assembled plugins contain `v30.22.0-5-ga00dc2e5c` in their relocated
  `BrikkSql.class`, rather than the peer's published 0.18.0 implementation.

The final successful run is retained at `/tmp/opencode/engine-candidate-sdg1412e`:
`manifest.json` records candidate bytes and source-input hashes; `results.json`
records commands' exit statuses and log locations. The compiler/runtime, assembly,
smoke and IDE logs and scratch artifacts remain available there. The earlier
successful run at `/tmp/opencode/engine-candidate-qg1a7kkg` produced the same hashes.

## Reproduction

From the SQL checkout, after building the exact candidate:

```sh
./kotlin build
python3 tools/verify_engine_candidate.py \
  --client /home/jayson/DEV/brikk/brikk-house \
  --core-jar build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar \
  --metadata-jar build/tasks/_brikk-sql-metadata_jarJvm/brikk-sql-metadata-jvm.jar \
  --scratch-dir /tmp/opencode \
  --dependency-model-dir /home/jayson/.cache/JetBrains/Kotlin/.m2.cache/dev/brikk/house \
  --candidate-sha dd1ea09eb82844e677a4165d96410a575c1d8540
```

The SHA labels provenance supplied by the caller; it is not proof that arbitrary
JAR arguments were built from that commit. For this run the build and recorded
hashes identify the exact tested bytes. The final validation round changes only
tests, tooling and documentation, not those core/metadata sources.

The script copies tracked peer inputs with `git archive`, changes only five
scratch module dependency declarations, and serves copied candidate JARs through
an ephemeral **loopback-only HTTPS** repository. Its certificate is trusted only
by the child Toolchain process using a copied trust store, never globally. The
private `0.19.0-candidate-dd1ea09eb828` coordinates avoid replacing released 0.18.0
or Maven Local. Normal dependency resolution may cache those private coordinates.
Scratch inputs, outputs, manifests and logs are retained, including on failure.
The server is stopped at completion; PASS is written only after source and byte
invariants are checked.

The dependency models come from the cached 0.18.0 POMs with only the two SQL
artifact versions changed; their Kotlin/serialization dependencies match this
candidate. This gate validates explicit JAR consumption, **not** new publication
metadata, Maven Central, PluginVerifier, IDE UI behavior or a production deployment.
The existing [Doris PIPE consumer gate](consumer-verification.md) is separate and
does not substitute for these compiler/runtime tests.

## SQL-side final gates

Build, 1,283 Kotlin tests, 38 compatibility/editor contract methods, two generator
tests, three candidate-gate safety tests and sixteen offline release tests pass.
DuckDB JDBC, PostgreSQL 18.4, MySQL 8.4.10, Trino 483, chDB 26.5.1.1 calendar and
Doris shared-behavior probes run. Four optional tests skip: Java chDB and three
StarRocks tests already executed in prior rounds. The cached Doris engine actually
reports `doris-4.1.3-rc02-7126cf65d96`, not 4.1.4.1. See extension #50 for scope.
The full native-run log is `/tmp/opencode/sqlglot-catchup-final-native-all-2.log`.
The four isolated Doris/PostgreSQL/MySQL/Trino containers created for this task
were removed after verification; scratch candidate evidence remains retained.

## Safety assessment and coverage limits

This is a well-tested release candidate for the scoped catch-up, not proof of
arbitrary SQL equivalence. Confidence is strongest for the hand-owned behaviors:
partial/empty catalogs, all three legacy inference policies and the new disabled
overloads, strict two-step Engine preparation, scope/alias/slot ownership, ordered
star fields, conservative types/nullability, source ranges and AST nonmutation.
JVM descriptors, Kotlin default bridges and tokenizer constructors have explicit
ABI assertions; actual peer compiler/runtime and relocated-plugin tests provide
an independent consumer check.

Native results, headers/types, NULLs, boundary values and specific error states
back the affected dialect ports. Coherent fixture regeneration was byte-identical;
ledger discrepancies were reviewed by exact assertion/signature, not approved by
failure counts. The newer [Doris native gate](doris-native-catchup-verification.md)
adds current-engine and hotfix-specific execution evidence.

The highest residual risk is interaction among shared tokenizer/parser, scope,
qualification and typing changes beyond the tested cases. No measured line/branch
coverage percentage, exhaustive cross-dialect cross-product, broad fuzz campaign
or performance benchmark is claimed. The existing corpus inventory explicitly
defers 2,478 named identity assertions, 43 base identities and 1,482 base transpile
directions from direct consumers; overlapping tests are not full parity proof.
Optional native coverage and historical engine-probe versions also retain their
stated limits. A canary/replay of representative consumer queries remains sensible
before a production rollout; these results alone do not authorize publication or merge.
