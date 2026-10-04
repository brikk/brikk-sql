# Evaluation findings — SQL cleanup complete

No unresolved SQL-owned findings remain from the 2026-09-02 quality and
correctness evaluation. **SQL-07 / EVAL-15 is complete** (2026-10-03).

`brikk-sql` is pinned to SQLGlot `v30.18.0-43-g3ca82489`; generated fixtures
and exact known-failure ledgers under `brikk-sql/testResources/` are
the behavioral oracle. Intentional divergences in `docs/brikk-extensions.md`
are out of scope.

The completed hygiene work is indexed as **SQL-07** in [TODO.md](TODO.md):
compiler warnings were removed without suppression, release versions use shared
SemVer 2.0.0 validation, publishing settings use Toolchain template inheritance
rather than indentation-sensitive edits, the Doris parser JAR checksum is recorded
in `vendor/README.md`, and hazard tests check structure and exact probe evidence
instead of hardcoded inventory counts. Offline release regression tests are part
of the GitHub test gate.

The standalone `mise.toml` compiler installation pin was removed. The Toolchain
wrapper still pins Toolchain 0.13.0, and module settings still pin Kotlin 2.4.10.
`project.yaml` modules are already alphabetical, and `tools/publish_maven_local.sh`
delegates to Toolchain rather than hardcoding a publication version.

The original finding mixed work from multiple repositories; native-artifact footprint belongs to
`CHDB-02` in [brikk-chdb](https://github.com/brikk/brikk-chdb/blob/main/TODO.md).
chDB artifacts are already published, and
[brikk-chdb's attributions](https://github.com/brikk/brikk-chdb/blob/main/ATTRIBUTIONS.md)
now credit their native binary.
