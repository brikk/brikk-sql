# Quality check, 2026-11-09

Code review of the tree on `main`, not of the history. The library is a respectable
published codebase. The Brikk-owned layers are designed. The SQLGlot port was cleaned
in place and should stay that way until there is an explicit decision to diverge from
SQLGlot.

This note is the suggested follow-up. It is not a rewrite plan.

## Standing constraint

Until that divergence, keep the parser pile. Do not split
`brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt` (11,095 lines) or
`brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt` (6,195 lines). Do not replace
the string-keyed `Expression` (`Args` is `Map<String, Any?>`) with data classes. Do not
rename `this_`, and do not retype `identify` / `normalizeFunctions` off `Any`.

Those are the compatibility layer. A mismatch can still be traced to
`reference/sqlglot/` because the Kotlin file still has the Python shape and a
`sqlglot:` comment. A "cleaner" split would be a second dialect of the same port, and
it would not match the corpora any better.

`DuckdbGenerator.kt` (3,463 lines) and `AnnotateTypes.kt` (about 2,000) are the same
kind of file. Leave them unless a specific unported pipeline is being ported, in which
case the new method goes where SQLGlot put it.

## Already fine — do not reopen

Hygiene in hand-written source is not the problem. There is no `FIXME`, no `println`,
seven `TODO(` calls, eighteen `@Suppress` annotations (almost all unchecked casts at the
SQLGlot boundary), and one empty catch (`FileAlreadyExistsException` on the schema-cache
snapshot write). Commented-out code is effectively gone.

Do not "clean up" any of the following. They are the cleanup:

- `brikk-sql-verify-api`: `SqlVerifier` and the four-state `VerifyResult`. Invalid SQL is
  a result. Cancellation is never a verdict.
- `brikk-sql-metadata`: the stable accessor contract at the top of `FunctionCatalog.kt`,
  versioned snapshots, and handwritten tables that name their source and date
  (`DorisTableFunctions.kt`).
- `brikk-sql/src/dev.brikk.house.sql/scope/RelationFacts.kt`: full / partial / unknown
  knowledge, provenance, nullability. This is the catalog-aware analysis.
- `brikk-sql-intellij-support`: packages by job. Largest file is
  `LiveCatalogService.kt` at 737 lines. Hidden binary-compatibility constructors are in
  the types that grew fields.
- DuckDB contracts already extracted to one-job internal files (`DuckdbParquet.kt`,
  `DuckdbGeoJson.kt`, `DuckdbDivision.kt`, `DuckdbCopy.kt`, `DuckdbExplain.kt`,
  `DuckdbExecutableBoundaries.kt`). New engine contracts should follow that, not be
  folded back into the generator.
- ClickHouse typing files (`ClickhouseIntegerAddSubTyping.kt`,
  `ClickhouseDivisionTyping.kt`, and the rest). The split follows tickets. Do not merge
  them into a type-system abstraction unless [SQL-08](TODO-clickhouse-semantic.md)
  resumes and that abstraction is the work.

## Suggested changes

### QC-01. Narrow generator headers that claim `annotate_types` is missing

`TypeAnnotator` is ported. It lives in
`brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateTypes.kt`. Three class docs still
say there is no Kotlin equivalent:

- `brikk-sql/src/dev.brikk.house.sql/dialects/DuckdbGenerator.kt`
- `brikk-sql/src/dev.brikk.house.sql/dialects/PostgresGenerator.kt`
- `brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt`

`TrinoGenerator.kt` says "same infrastructure gaps as PrestoGenerator", which inherits
the overclaim.

The unported part is real and should stay in the comment. It is the `exp.Select`
preprocess pipelines and the other named gaps (`connect_by_to_recursive_cte`,
`eliminate_qualify`, `inherit_struct_field_names`, the `unnest_sql` branch that needs a
proved array-of-json type, and so on). `generator/Transforms.kt` already says this
accurately: only the transforms needed so far are ported. `HiveGenerator.kt` and
`Spark2Generator.kt` already name the missing pipelines instead of a missing subsystem.
Match that.

`BigqueryGenerator.kt` is closer to right ("annotate_types-dependent branches of
`bracket_sql`"). Leave it unless a pass over the file shows the branch is no longer
dependent. `ClickhouseGenerator.kt` names three flags whose base-generator paths are
unported. That comment is fine.

Do not port those pipelines as part of this fix. Mismatches stay ledgered.

### QC-02. Narrow the UDF comments in `Schema.kt`

`brikk-sql/src/dev.brikk.house.sql/optimizer/Schema.kt` says UDF lookup waits until
`annotate_types` lands. The pass has landed. UDF typing inside it has not.
`getUdfType` is still stubbed to `UNKNOWN`, the UDF mapping is stored raw, and nothing
consumes `udfTrie`.

Rewrite the three `TODO(udf)` comments so they say that. Do not implement UDF typing
under a quality pass. It is a product gap, same as the parked certify policy below.

### QC-03. Disambiguate the two scopes in KDoc only

Two types answer different questions and share a word:

- `brikk-sql/src/dev.brikk.house.sql/optimizer/Scope.kt` is the SQLGlot selection scope.
  Sources are `MutableMap<String, Any>`. Qualification and the optimizer walk this.
- `brikk-sql/src/dev.brikk.house.sql/scope/` (`RelationFacts`, `ScopeAnalysis`) is the
  catalog-aware analysis. Plugins compile against these types.

Do not rename either. `optimizer.Scope` is the SQLGlot name, and `scope.RelationFacts`
is a published type (`AGENTS.md`). A package rename is a binary break.

Add a short KDoc on `optimizer.Scope` and on `RelationFacts` (or `ScopeAnalysis`) that
names the other type and says which question each one answers. That is the whole change.

### QC-04. Point `BrikkSql` at the real entry points

`brikk-sql/src/dev.brikk.house.sql/BrikkSql.kt` is a name and the SQLGlot pin.
`parseOne` is the last function of `Parser.kt`. `transpile` / `transpileAll` live in
`Dialect.kt`. Callers import from `parser`, `dialects`, `optimizer`, `shape`, and
`scope`. The module README is the API, and that is acceptable.

Do not add a facade, and do not move `parseOne`. A wrapper would be a second surface
over the same port.

Do add a KDoc on `BrikkSql` that points at `parseOne`, `transpile`, `transpileAll`, and
`Dialects.forName`, and says an unknown dialect name throws `UnknownDialectException`
rather than falling back to the base dialect.

### QC-05. Write the placement rule next to the dialect flags

`Dialect.kt` is a flag wall: SQLGlot class attributes as `open val`s, then Brikk-native
flags (`statefulCtes`, `dmlScopes`, `dottedFieldAccess`, and the rest). The Brikk flags
are already `internal` and already commented with the engine that reads them. Do not
collapse the wall. Collapsing it is a divergence.

Add a short comment above the Brikk-native block, and treat this as the rule for new
code:

- A SQLGlot dialect flag, or a parser/generator method that exists upstream, stays in
  the port, with a `sqlglot:` comment, in the file SQLGlot would have put it in.
- A Brikk-native engine contract goes in a small `internal` file next to that dialect,
  as the DuckDB contract files already do. It does not become another thousand lines of
  `Parser.kt`.
- Analysis that is not a port of an optimizer pass goes in `scope/`, `shape/`, or
  `diagnostics/`, not in `optimizer/`.
- Do not add a `Dialect` flag whose only reader would be that one helper file. Put the
  predicate on the dialect subclass or in the helper.

## Parked, not quality work

Leave these TODOs. Closing them is a product decision, and a hygiene pass should not
pick a policy in passing.

- `TODO(certify-policy)` in `brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt`. Policy
  #2 is the current middle ground: `DIVERGENT` / `UNCLEAR` is a warning when the target
  generator has a dedicated renderer, and a refusal when it does not. Option #1
  (always refuse) and option #3 (target-name refinement) are written at the decision
  site. Do not change the severity while "cleaning up" the comment.
- `TODO(udf)` in `Schema.kt`, after QC-02 has made the comment true. Implementing
  `getUdfType` is separate work.
- The open items in [TODO.md](TODO.md) (SQL-01 through SQL-06, SQL-08) are unfinished
  product work. They are not leftovers from this cleanup. SQL-07 is already done.

## Non-goals

- Rewriting the AST, the parser, or the generator into idiomatic Kotlin.
- A public facade module.
- Merging the ClickHouse typing files.
- Generating the handwritten metadata tables. Their headers already name the source and
  the date. A refresh updates the table and the header. It does not invent a generator
  for data no engine table exposes.
- Deleting `sqlglot:` comments. They are the index into the reference clone.
