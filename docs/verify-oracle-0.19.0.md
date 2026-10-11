# Verify and oracle 0.19.0

Published on 2026-10-11 UTC from `6579b0c`, completing the SQL 0.19.0 release.
The existing core/metadata 0.19.0 and SQL IntelliJ support 0.1.0 artifacts were
not republished. Their versions and checksums remain intact.

| Artifact | Version | Central JAR SHA-256 |
| --- | --- | --- |
| `dev.brikk.house:brikk-sql-verify` | 0.19.0 | `e4fc3a943942091b75d0cd37e1d28f137d6b80cbe1db0c47d9b4f7e98a996c09` |
| `dev.brikk.house:brikk-sql-oracle` | 0.19.0 | `ea19f90eb02c36a7004ac8e11cc99f1d877eac898fef2f57017fdceab0f47f81` |

[Successful release workflow](https://github.com/brikk/brikk-sql/actions/runs/38107790876).
The full repository build, test and local-publication checks passed before upload.
Local targeted checks passed 180 verifier tests and 25 oracle tests. One optional
chDB integration case was unavailable. All 16 release configuration tests passed.

Direct Central downloads include both JARs, sources, javadocs, POMs, Gradle metadata
and signing files. Downloaded artifacts match Central's checksum files. Both
metadata formats declare 0.19.0 without snapshot dependencies. Oracle exports
`brikk-sql-verify:0.19.0` and retains `brikk-chdb:0.16.0`.

The release launcher now supports validated module selection:

```sh
./publish-release.sh 0.19.0 brikk-sql-verify brikk-sql-oracle
```

The manual release workflow accepts the same modules as the comma-separated
`modules` input. Omitting selection retains the existing four-module release.
The independently versioned IntelliJ helper still uses its own release command.
