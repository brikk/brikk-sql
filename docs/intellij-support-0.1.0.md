# SQL IntelliJ support 0.1.0 and core 0.19.0

Released on Maven Central on 2026-10-11 UTC from `a7af8fd`.

## What changed

The shared helper provides cooperative SQL actions and script modeling for
dialect plugins. Core 0.19.0 adds:

- `SqlScriptSnapshot.parse(text, dialect)` with half-open UTF-16 statement ranges,
  trivia/terminator ownership, real PIPE markers and explicit boundary errors.
  Tested Trino routines are supported; uncertain procedural/client-delimiter
  remainders are not executable prefixes.
- `Tokenizer.tokenizeForEditor` with lexical recovery and visible command tokens.
  Ordinary parser command folding stays unchanged.
- `parseParameterOccurrences(sql, dialect)` with parser-proved name/parameter
  spans without rendering or lowering. IDE textual parameters remain separate.
- Table-position `rows()` slots without changing window-frame ROWS grammar.

## Coordinates and upgrade

Use `mavenCentral()`:

- `dev.brikk.house:brikk-sql-intellij-support:0.1.0`
- `dev.brikk.house:brikk-sql-jvm:0.19.0`
- `dev.brikk.house:brikk-sql-metadata-jvm:0.19.0`

Start with cooperative actions independently of engine upgrades. Use platform
`ActionWithDelegate` across plugin classloaders. Product policies still own DBMS
participation, settings, native validation, notifications and execution eligibility.
Kotlin recognition stays in the separate Brikk Kotlin helper.

Verify and oracle also have aligned 0.19.0 releases. See
[their changelog](verify-oracle-0.19.0.md).

## Compatibility

The helper uses Java 21 bytecode and depends only on Kotlin stdlib. It does not
bundle or transitively publish SDK classes, Kotlin IDE APIs, native engines,
verifiers or fixtures. Trino supplied consumer checks across SDK 261/262/263 and
live Trino 483 PIPE/cancellation checks. A DuckDB-shaped adapter exercises shared
ScriptModel behavior, not actual DuckDB plugin migration.

The complete-source-map path retains one exact input/rendering pair. Neither
source-preserving rendering nor arbitrary parameter substitution automatically
creates a proved authored-source map. See the module README for lifecycle rules.

| Artifact | Central JAR SHA-256 |
|---|---|
| brikk-sql-intellij-support | `21798b0c699dda59786d939ca51369c4417d1b724a524d823018fda9eb5ec833` |
| brikk-sql-jvm | `fa614ee7ac29dd8cbd44777956c162c527d171dfaf88c9ae0cf9e640bc0397d2` |
| brikk-sql-metadata-jvm | `6f4a506a68d16c79bb7d7de42122aa3d72eb50d3ffcc427f8804287467e29541` |

[Release checks](https://github.com/brikk/brikk-sql/actions/runs/38101272963).
