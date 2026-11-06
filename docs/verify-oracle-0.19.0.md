# Verify and oracle 0.19.0

Released on Maven Central on 2026-10-11 UTC from `6579b0c`.

## What changed

Verify and oracle complete the aligned SQL 0.19.0 module set. Oracle exports
`brikk-sql-verify:0.19.0` and retains `brikk-chdb:0.16.0`.
Core/metadata 0.19.0 and independently versioned IntelliJ support 0.1.0 remain
unchanged. See [the core/helper changelog](intellij-support-0.1.0.md).

## Coordinates and upgrade

Use `mavenCentral()` and keep the four SQL modules aligned at 0.19.0:

- `dev.brikk.house:brikk-sql-verify:0.19.0`
- `dev.brikk.house:brikk-sql-oracle:0.19.0`

Choose verify for validation and oracle when native engine oracles are needed.
Optional chDB integration still needs its native fixture; its unavailable case
is not a successful integration check. IntelliJ helpers use their independent version.

| Artifact | Central JAR SHA-256 |
|---|---|
| brikk-sql-verify | `e4fc3a943942091b75d0cd37e1d28f137d6b80cbe1db0c47d9b4f7e98a996c09` |
| brikk-sql-oracle | `ea19f90eb02c36a7004ac8e11cc99f1d877eac898fef2f57017fdceab0f47f81` |

[Release checks](https://github.com/brikk/brikk-sql/actions/runs/38107790876).
Module-selective publishing instructions live in
[the contributor guide](../dev-docs/CONTRIBUTING.md#publishing), not consumer upgrade steps.
