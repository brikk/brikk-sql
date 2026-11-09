# Building and publishing

Use the pinned Kotlin Toolchain 0.13.0 wrapper and Kotlin 2.4.10.
All publishable SQL and IntelliJ support modules use `1.0.1-SNAPSHOT`, following
the verified stable release `1.0.0`. Keep the snapshot suffix in development; the
release launchers apply the stable version temporarily.
Published versions are listed in the [root README](../README.md).

## Build and test

The project model requires a root `.env` for the publish-only repository configuration.
On a fresh checkout, create a placeholder file. Do not overwrite an existing credentials file:

```sh
printf 'brikk.mavencentral.user=x\nbrikk.mavencentral.pass=x\n' > .env
./kotlin build
./kotlin test -m brikk-sql --jvm-args="-Xmx1g -XX:ActiveProcessorCount=2"
./kotlin test --exclude-module=brikk-sql
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

Run the core suite separately because it includes wall-clock scaling checks. Optional native
controls need their documented fixtures or engine URLs. The live DuckDB window-completion
controls require a genuine signed-fixture capture through `BRIKK_DUCKDB_WINDOW_CAPTURE`.
Feature boundaries are tracked in [the DuckDB tracker](TODO-duckdb-2.0.md).

### Native window fixture

The shared IntelliJ suite needs a real native window-function capture. CI builds it from
DuckDB revision `d54cbbe3df17e8e5ce68312f483b4155836112aa` and checksum-pinned static libraries.
For local runs, supply a clean checkout at that exact revision. Set `WINDOW_DIR`
to a new private output directory. This generated fixture is not checked-in evidence.

```sh
python3 tools/build_duckdb_window_fixture.py --source /path/to/duckdb --output "$WINDOW_DIR"
export BRIKK_DUCKDB_WINDOW_EXTENSION="$WINDOW_DIR/brikk_window.duckdb_extension"
export BRIKK_DUCKDB_WINDOW_CAPTURE="$WINDOW_DIR/catalog.json"
./kotlin test -m brikk-sql-verify-duckdb --include-classes='*DuckdbWindowCatalogResultTest*'
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

The native test signs a scratch copy with test-only keys, loads it in a private instance,
executes the window controls and writes the capture. The IDE gate validates that capture.
Do not substitute hand-written rows or install this fixture into a user's database.

No sibling Engine checkout or native chDB installation is needed for the standard tests.
The oracle resolves `dev.brikk.house:brikk-chdb:0.16.0` from Maven Central.
Corpus failures write review files as configured in
`brikk-sql/test@jvm/dev.brikk.house.sql/TestResources.kt`; do not blindly copy
them over curated failure ledgers. See [corpus coverage](../docs/corpus-coverage.md).

The IntelliJ build provisions a checksum-pinned IDEA 261 SDK on Linux x64, or accepts
`BRIKK_INTELLIJ_SDK` on other hosts. Core-only builds do not provision the SDK.
Generators live in `tools/`; some need the gitignored checkouts under `reference/`.
See [vendor provenance](../vendor/README.md) before refreshing fixtures.

The offline Python gate is `python3 -m unittest discover -s tools -p 'test_*.py'`.
Generator tests need the SQLGlot checkout at `reference/sqlglot`, exactly
`a00dc2e5c489214bea53bacbbeba8713f21ebce1`. An existing clean checkout at that
commit can instead be supplied through `PYTHONPATH`; do not substitute a newer
installed package or regenerate fixtures for a documentation change.

## Publishing

SQL engine, metadata, verification and oracle artifacts share the publishing template.
The three IntelliJ support modules use their own aligned version. Build tooling is not published.

- Push to `main`: CI builds/tests and checks Maven Local publication before publishing snapshots.
- Push `release/<version>` or dispatch the release workflow: the same gates precede signed
  Maven Central publication.
- Local publication gate: `./kotlin publish mavenLocal`, followed by the verifier, Java 17
  and IntelliJ publication audits in `tools/verify_*publication.py`.
- Local snapshot: `./kotlin publish centralSnapshots`.
- SQL release: `./publish-release.sh <version>`. Optional module arguments select a subset;
  its in-repo dependencies must already be released at that version.
- IntelliJ support release: `python3 tools/publish_intellij_support.py <support-version> --core-version <sql-version> --skip-core-publication`.
  Reference already-published SQL prerequisites without republishing them. Omit
  `--skip-core-publication` only when explicitly releasing new core prerequisites too.

After a stable release, advance the SQL template and all three IDE module descriptors.
Release launchers restore their input templates; they do not advance development versions.
Existing published coordinates are immutable. The offline policy tests check that development
versions sort after the latest stable versions:

```sh
python3 -m unittest discover -s tools -p 'test_*release*.py'
```

GitHub publishing uses `KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME`,
`KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD`, `KOTLIN_TOOLCHAIN_SIGNING_KEY` and
`KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE`. The workflows also accept
`KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME/PASSWORD`.
Local snapshots read `brikk.mavencentral.user/pass` from `.env`; local releases use
the corresponding environment variables and Python 3.
Release launchers validate SemVer, temporarily compose the Portal/signing configuration,
and restore the original files on exit. Release versions for SQL and chDB are independent.
Snapshots need the snapshots repository; stable artifacts resolve from Maven Central.
