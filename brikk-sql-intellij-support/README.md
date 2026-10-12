# Brikk SQL IntelliJ support

An embedded Apache-2.0 JVM library for SQL dialect plugins. The first consumer is
SQL Dialect for Trino. Each consumer owns its copy through its plugin classloader.
It registers no extensions itself. It depends on Brikk SQL core and metadata, has no
Kotlin IDE dependency, uses no Brikk (Kotlin-embedded SQL) types, and uses internal
Database Tools APIs only in the catalog kit, `dev.brikk.house.sql.intellij.catalog` (see there;
a platform test fails on any other use). SQL PSI hooks live in
`dev.brikk.house.sql.intellij.psi`; `SelectAliasResolveExtension` implements the
`com.intellij.sql.resolveExtension` interface from `com.intellij.sql.psi.impl`, which carries
no internal-API annotation.

## API

- `CooperativeExecuteConfiguration`: capture and wrap the four stock Execute actions
  with the public dynamic customizer. Register a consumer subclass in plugin.xml.
- `CooperativeExecuteAction` and `ExecuteInterceptor`: preserve live options,
  presentation and captured-predecessor delegation. Only `DECLINED` delegates.
- `CooperativeExecutePromoter`: promote the underlying database action via the
  public registered promoter, then map back to the installed wrapper.
- `EditorSnapshot`: refuse stale document identity/revisions, including edit/revert,
  with `SqlExecutionRefusal`. Cancellation propagates unchanged. `captureAfterSave`
  pins the revision after a save that resets the stamp, refusing edits made before it.
- `TranslatingScriptModel`: preserve statement anchors and parameter prompting,
  replace only claimed statements, then finalize all translations before submission.
- `SqlExecutionAdapter`: product-owned claims, translation, parameter ranges and
  validation. Refuse expected failures with `SqlExecutionRefusal`; propagate cancellation.
- `RenderedSql`: exact SQL/input pair and optional source-map callback. An authored
  map is available only if parameter expansion did not change the original input.
- `SqlColumnCompletion.create`: consistent column lookup presentation with type,
  three-state nullability, source alias and origin label. Same-named columns from
  different aliases retain distinct lookup identities. Consumers provide copied
  facts, so the helper acquires no Kotlin IDE or engine dependency. Experimental additions
  take `SqlColumnFacts`, whose name and opaque `source` are a column's identity (the alias is
  only shown): `create(column, style)` with the style's icon and priority,
  `decorate(candidate, column)` keeping the candidate's icon and insertion, `pipeInput` for
  PIPE stage input columns, and `createOrDecorate`, which decorates the first of other
  contributors' items matched to each visible column and creates the columns no item matched.
- `ToggleSettings` and `ToggleConfigurable`: a persisted project switch that reparses
  its language's files, and its settings page with a `-D<property>=false` veto.
- `notifyEscaped`: plain-text notifications. `FileBanner` and `FileBannerProvider`:
  per-file editor banners scoped to their project.

## PIPE (`dev.brikk.house.sql.intellij.pipe`, experimental)

A plugin implements one `PipeDialect` profile (core dialect, label, veto property,
language, project setting, session ownership; optional validator, statement ranges,
parameter spans, first-stage rule, server column convention, submit listener) and
registers no-arg subclasses:

- `PipeExecuteInterceptor` as its `CooperativeExecuteConfiguration.interceptor`. It claims
  PIPE input in owned consoles, pins the document after the stock save, captures the
  destination before preparation and rechecks it, with the setting, before submission.
  Refusals are notified in the profile's group; claimed input never falls back to raw SQL.
- `PipePreviewIntention`, `PipeRunToStageIntention`, `PipePreviewAction`,
  `PipeRunToStageAction`. Run-to-stage cuts at top-level stages and submits through
  the same checks; injected and inspection-only files cannot run.
- `PipeStageCompletionContributor` and `PipeStageKeywordAnnotator`: core stage phrases
  and offline stage input columns, also in injected SQL (`PipeText`).

`lower` turns one statement into `PipeTranslation` over core `lowerForExecution`,
also refusing what the profile's statement ranges refuse, and applies the optional
`NativeSqlValidator` at lowering (parameters masked) or at submission. `Unavailable`
does not block. `PipeText.errorRange` gives annotators a clamped, non-empty range.

Rich column examples, using a nullable Kotlin Boolean for nullability:

```kotlin
SqlColumnCompletion.create("payload", "String", true, "src")
// payload    brikk column (src)    String?
SqlColumnCompletion.create("code", "String", false, "r", "relation contract")
// code       relation contract (r)    String
```

A null type name displays `unknown type`; unknown nullability displays
`(nullability unknown)`. Presentation does not infer native SQL types or replace
the consumer's scope analysis. The default lookup priority is 120.

Example consumer registration:

```kotlin
class Actions : CooperativeExecuteConfiguration() {
    override val interceptor = ExecuteInterceptor { event, options ->
        // Check host eligibility and DBMS/project participation before preparation.
        // Return HANDLED for rejected/failed owned PIPE, DECLINED only for unowned input.
        handler(event, options)
    }
}
class Promoter : CooperativeExecutePromoter()
```

The console flow is `beforeExecuteQueries(model)`,
`model.finalizeTranslations(console.pStorage)`, then `executeQueries(editor, model, options)`.
The product must also verify destination identity after prompting. The helper does
not discover drivers, select connections, enable PIPE, interpret warnings as safe,
or execute inspection templates. Stock user parameters remain a platform concern.

Foreign wrappers are traversed through platform `ActionWithDelegate`, never a cast
to another plugin's private helper copy. Unloading beneath a peer detaches the
wrapper but may retain its classloader until restart.

## Completion (`dev.brikk.house.sql.intellij.completion`, experimental)

A plugin implements one `SqlCompletionProfile` (language and `DialectMetadata`; optional
snapshot choice, function list, server-only functions, keyword catalog, styles and gates) and
registers no-arg subclasses. The contributor calls each per-file hook at most once per completion:

- `SqlCompletionContributor` (`order="first"`): metadata functions, one item per name and kind
  (`completableFunctions`). Whether parentheses are inserted comes from the invocation
  syntax, not from the overloads: a call inserts `()`, while a grammar bare form
  (`CURRENT_TIMESTAMP`, also when a call is accepted) and a special form (`CAST`) insert only
  the name. A name written with parentheses reuses an existing `(`, with the caret inside,
  and gets `()` when accepted with `(`. Only the caret inside new parentheses follows the
  overloads: it goes after `()` when the only overload takes no arguments. Keywords from the profile's
  `keywordCatalog` (the snapshot's by default), all or only reserved ones (`KeywordScope`), except those a later
  contributor already offers. Inside the call of a table
  function with metadata `TableFunctionInfo`, its property names, and their known values
  right of `=`; outside a string the item is inserted in the profile's `propertyQuote`.
- `SqlCompletionConfidence` (`order="first"`): keeps the autopopup inside a table function's
  property strings, and skips it after a digit-led prefix when the digit gate is on.
- `SqlBuiltinPrototypeFilter` (`order="first"`): drops the base dialect's builtin-function
  items (`BuiltinFunction.Prototype` only) that the profile's `builtinFunctionFilter` does not
  keep; null (the default) keeps them all.

`SqlLookupStyles` holds one `SqlLookupStyle` (icon, type text, bold, priority, case,
signature tail, return type) per `SqlLookupKind`; `DEFAULT` is the proposed shared table and
a dialect replaces rows with `with`. `nativeKinds` maps engine-native function kinds
(`FunctionPresentation.nativeKind`, such as DuckDB macros) to their own rows. `SqlFunctionGates` are off by default: the qualifier
rule (`Unrestricted`, `Unqualified`, or `Namespaced` functions), the digit-prefix gate, and
the autopopup gate (`ExpressionPositions`; explicit completion always offers functions).

`SelectAliasResolveExtension(dbms, clauses)` (`dev.brikk.house.sql.intellij.psi`, experimental):
unqualified references in the given clauses (default HAVING, GROUP BY, ORDER BY) of a query in
that DBMS see the SELECT-list aliases, as the platform does only for its own families. Register a
no-arg subclass as `com.intellij.sql.resolveExtension`.

## Language layer (experimental)

A dialect plugin keeps its rule tables and small subclasses; the machinery is here.

- `MaskingSqlLexer(base, castMask)` (`lexer`): a `LookAheadLexer` over the substrate lexer. The
  subclass's `rule(base, upper)` collapses spans into one string or identifier token or masks
  droppable modifiers as a comment, with raw-buffer probes (`nextIs`, `wordsFollow`, ...),
  consumers (`consumeBalanced`, `consumeWords`) and span helpers (`maskWords`, `maskWithGroup`,
  `collapseWithGroup`, `maskToCloseParen`, `collapseRoutineBody`, `span`). Statement context
  (`parenDepth`, `lastMeaningful`, `statementHead`, the subclass's own in `resetStatement`)
  starts fresh after every `;`; whitespace and comments never reach it. `CastTargetMask` opts
  into masking cast targets such as `TRY_CAST(x AS t)`. `getState` reports the base state only
  at statement starts, so the editor highlighter restarts and resynchronizes only there and
  never sees a lexer that lost its context: an edit relexes its own statement, nothing before.
  States stay non-negative, as the console's parameter preprocessing requires; the base lexer's
  state must fit 15 bits.
  Restoring a saved position relexes from its statement's start. A routine body still unbalanced
  at the end of the text ends before its first `;`, so later statements keep their tokens.
- `LenientSqlParsing` (`parser`): `PsiBuilder` extensions that never consume, scanning the
  statement to its `;` within `MAX_LOOKAHEAD` tokens: `wordAt`, `containsAny`, `firstOf`,
  `wordThenWord` (next word), `wordThenToken` (exact next token) and the general
  `scanStatement`; `advanceLenient`, `consumeLenient` and `parseLenientStatement` keep platform
  user parameters; `parseColonParameter` parses `:name`.
- `PipeStatementGate(pipeDialect)` (`pipe`): in a parser's `parseSqlStatement`, makes a PIPE
  statement (or a statement the caller claims, such as FROM-first) one node bounded by the
  dialect's statement range, before its final `;` unless the range has a boundary error. Text
  without `|>` costs nothing; ranges are cached per parsed text.
- `MaskedSpanRecolorAnnotator(language, coreDialect, keywords)` (`editor`): recolors masked and
  collapsed spans token by token with the core tokenizer, in the colors the file's highlighter
  gives the same platform token kinds. Genuine comments, quoted strings (`E'..'`, `$$..$$`) and
  identifiers are never touched; comments inside a span keep comment colors.
- `SubstrateErrorFilter(language)`: hides the substrate grammar's parse errors in the dialect.
- `SqlDialectQuoteHandler(EnterInString)`: Enter in a quoted string inserts a newline or splits
  it with `||`; collapsed spans are never split.
- `KeywordOverlayHighlighterFactory(dialect, keywords)`: the dialect's SQL highlighter factory
  wrapped in a `KeywordOverlayHighlighter`, which colors listed plain identifiers as keywords with
  one overlay token type per language. Register a no-arg subclass as `lang.syntaxHighlighterFactory`.
- `PgFamilyDialect(id)` (`dialect`): a PostgreSQL-grammar dialect with the platform PostgreSQL
  operators, system variables and builtin functions; the subclass supplies `getDbms`. Keep its
  `Dbms` outside the dialect object, whose base constructor already asks for it.
- Comments: register the platform commenter, which toggles `-- ` lines and block comments:
  `<lang.commenter language="<your dialect language id>" implementationClass="com.intellij.database.sql.common.core.psi.impl.support.SqlNestedCommenter"/>`.

`SqlDialectRegistry` (`dialect`): a static, versioned table (`VERSION`) of core dialect names,
labels, `-- dialect:` marker spellings, IDE language ids and `Dbms` names. Each plugin's copy is
identical data keyed only by platform identities, so plugins agree without sharing objects.
Stock platform identities are aliases next to the plugins' own (stock DuckDB: `DUCKDB` before
263, `DUCK` and the `DuckDB` language from 263; `TRINO`, `PRESTO`, `HIVE`, `SPARK`). `GenericSQL`
never maps: many engines share it.

`setDialectMapping(project, file, dialect)` (`dialect`): maps a file or the project, reparses, and
returns whether the mapping took effect.
On 263 it calls `SqlDialectMappingUtilsKt.setDialectMappingSync` reflectively: a public function
of the `intellij.database.sql.core.impl` module with no internal-API annotation, absent before
263, where the old setter only updates storage.

## Catalog kit (`dev.brikk.house.sql.intellij.catalog`, experimental)

Database Tools integration for a dialect's own dbms. Register no-arg subclasses (the model facade
takes the dbms the platform passes):

- `PgFamilyModelFacade(dbms, databasesAsCatalogs = false)`: the PostgreSQL model and helper for a
  dbms with `<extensionFallback fallbackDbms="POSTGRES">`, so facade, dialect and introspector agree
  on one model family. With `databasesAsCatalogs`, the tree and schemas pane call the database level
  "catalog"; otherwise the helper is `PgModelHelper` itself.
  `<modelFacade dbms="X" implementationClass="my.XModelFacade"/>` with
  `class XModelFacade(dbms: Dbms) : PgFamilyModelFacade(dbms)`.
- `GenericJdbcIntrospectorGate`: registered for the exact dbms, it shadows the fallback dbms's
  introspector and supports no server version, so the platform always uses its generic JDBC
  introspector. Without it, a PostgreSQL fallback introspects any server reporting major 9 or later
  (Trino reports 483) with `pg_catalog` queries. The per-data-source "Use JDBC-based introspector"
  flag is no substitute: the platform resets it when the IDE build changes.
  `<introspector dbms="X" implementationClass="my.XIntrospectorGate"/>`. The test kit's
  `assertIntrospectorRoutesToGeneric(dbms)` checks the routing.
- `DialectDataSourceResolver(dbms, onlyDataSourceFallback = true)`: `resolve(file)` finds the data
  source serving a file. An explicit association decides: the file's valid console, else its SQL
  resolution scope (file, directory or project) when that names data sources. A console or scope
  bound only to another dbms, or a scope that excludes data sources ("all except ..."), gives
  `NotFound`, never another source. Only a file with neither falls back, when enabled, to the
  project's only data source of the dbms itself. Consoles and scopes also accept registry aliases
  (the stock `TRINO` for a plugin's Trino dbms); the fallback does not. The `DataSourceResolution`
  says which (`Console`, `FileMapping`, `OnlyDataSource`) or why none (`Ambiguous` with the
  candidates, `NotFound`). Injected files and completion copies resolve through their host; failed
  lookups give `NotFound`. `consoleNamespace(file)` is the valid console's current namespace,
  outermost first.

Internal API used, by class (the platform offers no public alternative):

| API | Used by | Why | Marketplace |
| --- | --- | --- | --- |
| `DBIntrospector.Factory` (interface and its methods) | `GenericJdbcIntrospectorGate` | the only seam for introspector selection | ignored for the Doris and Trino plugins; confirm it holds for classes in a bundled jar |

`InternalApiContainmentTest` reads this jar's bytecode and fails if a class outside the kit
references a class, enclosing class, package or member annotated `@ApiStatus.Internal` or
`@IntellijInternalApi`, or if the kit's set differs from this table.

## SQL-domain APIs

The accompanying core update adds `SqlScriptSnapshot.parse(text, dialect)` and
`parseParameterOccurrences(sql, dialect)`. Statement ranges are half-open UTF-16
and preserve trivia/terminators. Incomplete boundaries carry an explicit error.
Parameter discovery is parse-only and refuses unproved input. These APIs belong
to core; SQL-only tools need no IntelliJ dependency to use them.

Reuse core lowering, certification, shape analysis and source maps. The separate
source-preserving renderer has no source map and cannot borrow another rendering's map.

## Build and publication

Compile against IDEA 261, with Java 21 bytecode. Linux x64 builds provision a pinned,
SHA-256-verified IDEA 2026.1.3 SDK, or accept `BRIKK_INTELLIJ_SDK` pointing to a 261
installation. SDK bytes are compile inputs only. The published POM exposes core,
metadata and Kotlin stdlib; IntelliJ consumers should exclude stdlib in favor of the
platform copy.

```sh
./kotlin publish mavenLocal -m brikk-sql-intellij-support -m brikk-sql-intellij-test-support --transitive
python3 tools/publish_intellij_support.py 0.2.0 --local
python3 tools/publish_intellij_support.py 0.2.0 --core-version 0.20.0
```

The last command requires the existing Central/signing environment. It publishes
this module and its test kit, `brikk-sql-intellij-test-support`, at the same version,
plus core and metadata at `--core-version`, never verify/oracle. Without
`--core-version`, only a local publication is allowed; it references the core
snapshot already in Maven Local. The module has independent `0.x` versioning.
Temporary release overrides restore on success or failure.

Shared platform contracts run in a real IDE against the test kit:

```sh
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

They compile against 261; `BRIKK_INTELLIJ_RUNTIME_SDK` runs them on another installation.

`PipeExecutionContract` in the test kit runs the PIPE execution contracts against
a consumer's own profile. Classloader isolation and real-dialect console tests live
in the consumers' suites.
