# Building and publishing

Use the pinned Kotlin Toolchain 0.13.0 wrapper and Kotlin 2.4.10.
Current development versions are SQL `0.23.0-SNAPSHOT` and IntelliJ support `0.3.1-SNAPSHOT`.
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
Current release blockers and missing fixtures are tracked in [the DuckDB work list](TODO-duckdb-2.0.md).

No sibling Engine checkout or native chDB installation is needed for the standard tests.
The oracle resolves `dev.brikk.house:brikk-chdb:0.16.0` from Maven Central.
Corpus failures write review files under `build/ledger-actual/`; do not blindly copy those
over curated failure ledgers. See [corpus coverage](../docs/corpus-coverage.md).

The IntelliJ build provisions a checksum-pinned IDEA 261 SDK on Linux x64, or accepts
`BRIKK_INTELLIJ_SDK` on other hosts. Core-only builds do not provision the SDK.
Generators live in `tools/`; some need the gitignored checkouts under `reference/`.
See [vendor provenance](../vendor/README.md) before refreshing fixtures.

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
- IntelliJ support release: `python3 tools/publish_intellij_support.py <support-version>`.
  Add `--core-version <version>` only when also releasing core and metadata prerequisites.

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
