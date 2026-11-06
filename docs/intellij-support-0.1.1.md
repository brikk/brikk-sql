# SQL IntelliJ support 0.1.1

Released on Maven Central from `befa4ea`.

## What changed

SqlColumnCompletion provides consistent column-lookup presentation across SQL
dialect plugins. Column names remain insertion text. Type text includes known
nullability, such as `String?`, while unknown types/nullability stay explicit.
Source aliases appear in the tail, such as `brikk column (src)`. Consumers choose
origin labels such as `relation contract` or `PIPE column`.

Same-named columns from different aliases retain distinct lookup identities.
Default priority is 120. The formatter performs no inference or scope resolution.

## Coordinates and upgrade

Use `dev.brikk.house:brikk-sql-intellij-support:0.1.1` from `mavenCentral()`.
Core, metadata and verifier versions are independent of this helper-only release.
Reuse the formatter's type/tail text while preserving native insertion,
navigation, icons and matching behavior on decorated lookups.

## Compatibility

The formatter accepts strings and a nullable Boolean. Kotlin stdlib remains the
only Maven dependency, with no Kotlin IDE, Kotlin helper or SQL-engine dependency.
The original 27 helper classes are byte-identical to 0.1.0; two classes are new.
Trino 0.3.4 embeds this Central JAR and checks nullable source columns and
same-named join columns. Other plugins need their own adoption checks.

Central JAR SHA-256 is
`7ffdbb93990507db3f7ad83bc8e2571a7ef011f6debe0dd6b03d72d1b1ebd64e`.
[Release checks](https://github.com/brikk/brikk-sql/actions/runs/38113629918).
