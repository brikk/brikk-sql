# SQL IntelliJ support 0.1.0 and core 0.19.0

Published through the scoped workflow on 2026-10-11 UTC, from `a7af8fd` after
the initial implementation commit `d073725`. All three artifacts are available
directly from Maven Central. Verify/oracle were not part of this release.

| Artifact under dev.brikk.house | Version | SHA-256 of Central JAR |
| --- | --- | --- |
| brikk-sql-intellij-support | 0.1.0 | `21798b0c699dda59786d939ca51369c4417d1b724a524d823018fda9eb5ec833` |
| brikk-sql-jvm | 0.19.0 | `fa614ee7ac29dd8cbd44777956c162c527d171dfaf88c9ae0cf9e640bc0397d2` |
| brikk-sql-metadata-jvm | 0.19.0 | `6f4a506a68d16c79bb7d7de42122aa3d72eb50d3ffcc427f8804287467e29541` |

[Release workflow](https://github.com/brikk/brikk-sql/actions/runs/38101272963).
The full SQL build/test/publication gate passed before upload. Direct Central
downloads match repository checksums, and every class matches the locally tested
snapshot payload: 27 support, 2,401 core and 40 metadata classes.

The helper publication audit checks Java 21 bytecode, sources, notices, POM and
Gradle metadata. Kotlin stdlib is its only dependency. SDK classes, Kotlin IDE APIs,
native engines, verifiers and fixtures are not bundled or published transitively.

## Core additions

- `SqlScriptSnapshot.parse(text, dialect)`: source-dialect tokenization, half-open
  UTF-16 statement ranges, trivia/terminator ownership, real PIPE markers and
  explicit incomplete/unsupported boundary errors. Supports tested Trino routines;
  uncertain procedural/client-delimiter remainders are not executable prefixes.
- `Tokenizer.tokenizeForEditor`: lexical recovery and visible command tokens.
  Normal parser command folding remains unchanged.
- `parseParameterOccurrences(sql, dialect)`: parser-proved parameter/name spans
  without rendering or lowering. IDE-specific textual parameters remain separate.
- Table-position `rows()` slots work without changing window-frame ROWS grammar.

## Consumer evidence

Trino is the first consumer and supplied recording-console, action cooperation,
Kotlin injection, genuine generated-shape and real plugin-classloader checks on
the 261/262/263 SDK generations. A DuckDB-shaped adapter exercises the shared
ScriptModel without requiring the actual DuckDB dogfood plugin to migrate.
Live Trino 483 checks compare generated PIPE queries with native SQL and verify
client/server cancellation. The consumer's report holds its final artifact results.

Core's 852 tests pass. The repository-wide CI gate additionally exercises metadata,
native verification, engine oracles and publication configuration. Optional native
oracle exclusions remain explicit in CI output.

## Adoption

Start with cooperative actions independently of engine upgrades. Use platform
`ActionWithDelegate` across plugin classloaders. Product policies still own DBMS
participation, settings, native validation, notifications and execution eligibility.
Kotlin recognition stays in the separate Brikk Kotlin helper.

The complete-source-map path retains one exact input/rendering pair. Neither
source-preserving rendering nor arbitrary parameter substitution creates a proved
authored-source map automatically. See the module README for API and lifecycle rules.
