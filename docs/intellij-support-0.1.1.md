# SQL IntelliJ support 0.1.1

Published from `befa4ea`, adding `SqlColumnCompletion` for consistent column
lookup presentation across the SQL dialect plugins.

Coordinate: `dev.brikk.house:brikk-sql-intellij-support:0.1.1`.

Central JAR SHA-256:
`7ffdbb93990507db3f7ad83bc8e2571a7ef011f6debe0dd6b03d72d1b1ebd64e`.

[Successful release workflow](https://github.com/brikk/brikk-sql/actions/runs/38113629918).
Core, metadata and verifier versions are independent of this helper-only release.

## Presentation contract

- Column name remains the insertion text.
- Type text includes known nullability, for example `String?`.
- Unknown types and nullability remain explicit.
- Source aliases appear in the tail, for example `brikk column (src)`.
- Consumers choose the origin label, including `relation contract` for generated
  navigation fallbacks and `PIPE column` for unproven computed columns.
- Same-named columns from different aliases retain distinct lookup identities.
- Default priority is 120. No type inference or scope resolution is performed.

The formatter accepts strings and a nullable Boolean, adding no dependency on
Kotlin IDE, the Kotlin helper or the SQL engine. Consumers decorating native
lookups can reuse its rendered type/tail while preserving native insertion,
navigation, icons and matching behavior.

## Verification

The original 27 helper classes are byte-identical to 0.1.0. Two new classes are
added. All 29 published classes match the locally tested candidate. Central
checksums, source/notices and POM/Gradle metadata audits pass; Kotlin stdlib remains
the sole Maven dependency.

Trino's real completion pipeline verifies nullable source columns and two
same-named join columns with different aliases/types. Its candidate passes the
five pinned IDE verifier targets. Trino 0.3.4 embeds the actual Central JAR and
passes package and completion checks against that release.

The Doris and DuckDB agents have separate migration notes in the Trino checkout.
SQL Transpiler currently has no column-completion provider to migrate.
