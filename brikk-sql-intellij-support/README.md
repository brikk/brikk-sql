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
  destination before preparation and rechecks it, with the setting, before submission. The destination
  includes the console's current namespace, which the dialect's search path (`CatalogSearchPathStrategy`)
  switches and reads back; a switch refuses, and a namespace unknown before or after never blocks. It is
  compared as the platform keeps it, so the check costs no round trip and runs no probe.
  Refusals are notified in the profile's group; claimed input never falls back to raw SQL.
- `PipePreviewIntention`, `PipeRunToStageIntention`, `PipePreviewAction`,
  `PipeRunToStageAction`. Run-to-stage cuts at top-level stages and submits through
  the same checks; injected and inspection-only files cannot run.
- `PipeStageCompletionContributor` and `PipeStageKeywordAnnotator`: core stage phrases
  and offline stage input columns, also in injected SQL (`PipeText`). Right after `|>` only
  stage phrases are offered; once a stage has begun, other contributors complete as usual.
  Items take the `PIPE_STAGE` (UPPER, bold, "PIPE stage") and `PIPE_INPUT_COLUMN` rows of the
  `SqlLookupStyles` the contributor is given (`DEFAULT` unless the plugin passes its own).
  Both read the profile's `stagePhrases`, by default `pipeStagePhrases(dialect)`: the phrases core
  parses for the dialect that core lowers and the engine runs as lowered (recorded for Trino, DuckDB,
  Doris and ClickHouse; other dialects get every parsed phrase).

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

## Native errors (`dev.brikk.house.sql.intellij.diagnostics`, experimental)

A plugin implements one `NativeErrorProfile` (language, core dialect, label, `validator(file)`) and
registers a no-arg `NativeErrorAnnotator` subclass as the language's `externalAnnotator`. Each
statement of core's statement ranges (or, with `VerificationUnit.File`, the whole text) goes to the
validator's `verify`; the diagnostics themselves are core's plain data
(`dev.brikk.house.sql.diagnostics`), so an LSP server can reuse them.

- The engine decides where it gives a verdict. A rejection is marked at the engine's UTF-16
  position (across the token, or one code point); an acceptance clears the local parsers' errors in
  that statement; `Unverified`, `Unavailable`, a failed validator or no validator give no verdict,
  never "clean", and leave the local errors. An advisory validator's rejections are warnings.
- Local parsers, off by default: `localParser` reports brikk-sql's parser errors;
  `reportsPsiErrors` reports the IDE parser's PSI errors through the same rule (register a
  `NativeErrorPsiFilter` so the platform does not also show them; it hides only PSI error elements
  of that language, never other inspections or annotators).
- A result is applied only at the document revision and text it was computed for, and later passes
  at that revision reuse it. Cancellation propagates unchanged; `debounce` waits for typing to pause
  (an edit cancels the wait), `sizeCap` skips large statements, `budget` caps one pass. All off by
  default.
- Only files of exactly the profile's language are annotated, so each consumer acts on its own
  dialect. SQL injected into `injectionHosts` languages (register the annotator for each host too;
  external annotators do not run on fragments) is read unescaped, every fragment of a file, and
  marked at its host text, cut to editable parts; one range is marked once.
- Hooks: `statementDiagnostics` replaces a statement's check (PIPE lowering, Brikk templates),
  `fileDiagnostics` adds diagnostics read with the PSI (a last server error), `verificationText`
  masks binds at equal length, `message` formats.

`NativeSqlValidator.verify` returns the engine's positioned `VerifyResult` (by default `validate`
without positions); `NativeSqlValidator.of(verifier)` adapts a `brikk-sql-verify-<engine>`
verifier, and `VerifyResult.toNativeVerdict` is the execution mapping.

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
  contributor already offers. Keywords from later contributors (bold plain-text items whose words are
  all in the catalog) are spelled in the same case, keeping their insertion; an explicit keyword case in
  the user's SQL code style wins over the profile's, for both. Inside the call of a table
  function with metadata `TableFunctionInfo`, its property names, and their known values
  right of `=`; outside a string the item is inserted in the profile's `propertyQuote`.
  Function kinds follow the position, judged from the core tokens of the statement before the typed
  name (`tablePositions`, by default `TablePositions.of(dialect)`). `STANDARD` places tables after FROM,
  any join and PIPE `|> CALL`, after a comma continuing their list, and inside `TABLE(`; `IS DISTINCT
  FROM`, ClickHouse's `ARRAY JOIN` and a FROM inside a call holding no query (`EXTRACT(YEAR FROM x)`)
  are not table positions. A table position offers only table functions, a known expression position
  hides them, so a name of both kinds (DuckDB `range`) shows its fitting form; where the text does not
  tell, every function is offered. Aggregates and window functions are not filtered by clause.
  Per dialect: DuckDB's `unnest` is also offered in expressions; Doris's generators are too, and
  follow `LATERAL VIEW`; Trino's table functions other than UNNEST and JSON_TABLE are inserted as
  `TABLE(name(...))` where they stand bare. Right after `|>`, where only one of the profile's
  `stagePhrases` stands, no functions or metadata keywords are offered.
- `SqlCompletionConfidence` (`order="first"`): keeps the autopopup inside a table function's
  property strings, and skips it after a digit-led prefix when the digit gate is on.
- `SqlBuiltinPrototypeFilter` (`order="first"`): drops the base dialect's builtin-function
  items (`BuiltinFunction.Prototype` only) that the profile's `builtinFunctionFilter` does not
  keep; null (the default) keeps them all.

`SqlLookupStyles` holds one `SqlLookupStyle` (icon, type text, bold, priority, case,
signature tail, return type) per `SqlLookupKind`; `DEFAULT` is the proposed shared table and
a dialect replaces rows with `with`. `SqlLookupIcons` holds the two icons the platform lacks, each with a
dark variant: `WindowFunction` ("W" in the function circle, the WINDOW row) and `BrikkColumn` (bricks
laid 2-1-2, the BRIKK_COLUMN row). `nativeKinds` maps engine-native function kinds
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
- `StatementBoundedSqlLexer(baseFactory, coreDialect)` (`lexer`): lexes each statement of core's
  script ranges on its own with a fresh start of the base lexer, so no token runs past its
  statement (ClickHouse inline `INSERT ... FORMAT` data ends where core frames it: at a blank line,
  a line holding only `;`, or the end of the text, never at another `;` or a quote in the data,
  which is stored as written, also across quoted CSV fields; framing reads only settings written in
  the INSERT itself, never session-level ones; a token covering the terminating `;` is
  cut before it, and the base restarts there, so it reads the `;` as a separator rather than data). States are 0 at statement starts and 1 elsewhere:
  non-negative, whatever the base reports, and the highlighter restarts and resynchronizes only at
  statement starts. Started in state 1 (the console's parameter preprocessing restarts mid-token),
  it continues the statement holding the offset, within its bounds. Core ranges are read a window at a time, so relexing an edit costs about its
  statement, and a pass from the start of a text shares them with `StatementBoundaryGate`'s parse of
  the same text. `CHouseFamilyParserDefinition` lexes this way; a `MaskingSqlLexer` may be its base.
- `LenientSqlParsing` (`parser`): `PsiBuilder` extensions that never consume, scanning the
  statement to its `;` within `MAX_LOOKAHEAD` tokens: `wordAt`, `containsAny`, `firstOf`,
  `wordThenWord` (next word), `wordThenToken` (exact next token) and the general
  `scanStatement`; `advanceLenient`, `consumeLenient` and `parseLenientStatement` keep platform
  user parameters; `parseColonParameter` parses `:name`.
- `StatementBoundaryGate(coreDialect)` (`parser`): in a parser's `parseSqlStatement`, runs the
  grammar on a top-level statement and parses its core range again as one lenient `SQL_STATEMENT`
  where the grammar's boundary is clearly wrong: it runs past the range's end (before its `;`), or
  it stops before it after reporting an error in what it parsed (a clause it lacks), after parsing
  nothing, or where core reads one construct on (inside brackets, a token or a comment). Errors are
  those the platform builder recorded since the statement began (`PsiBuilderImpl.hasErrorsAfter`,
  public, under any `PsiBuilderAdapter`); a builder without one counts every early stop as wrong. Statements the
  grammar reads cleanly without a `;` between them stay apart, as in the platform dialects, and the
  statement after such a split is gated as one starting the range; an
  erroneous one absorbs the rest of its core range. Statements the grammar parses keep its PSI;
  nested statements and ranges with a boundary error (an unclosed bracket being typed) keep the
  grammar's result.
- `CHouseFamilyParser(coreDialect)` (`parser`): the platform ClickHouse parser behind such a gate.
  The platform grammar lacks `PARTITION BY` in `INSERT INTO [TABLE] FUNCTION`, so it splits
  `INSERT INTO FUNCTION s3(...) PARTITION BY ...` before a `SELECT` on a new line (and mid-statement
  after a comment); the family keeps it whole. Subclasses add statements in `parseStatement`.
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
- `CHouseFamilyDialect(id)` (`dialect`): a ClickHouse-grammar dialect that behaves as the platform
  ClickHouse dialect (tokens, builtin functions with special forms and combinator suffixes, types,
  operators, system variables, separators, resolution); a raw data leaf is never reparsed alone, as
  an edit in data can move statement boundaries. The subclass supplies
  `getDbms`, best with `<extensionFallback fallbackDbms="CLICKHOUSE">`. The platform dialect is
  final, so its overrides are delegated to it, and a test fails when an IDE adds one.
  `isServerResolvedParameter` (263, a parameterized view's `{name:Type}`) is called reflectively,
  being absent from the 261 API.
- `CHouseFamilyParserDefinition(dialect)` (`parser`): parsing for such a dialect, with one file node
  type per dialect and the platform ClickHouse element factory, reached through a platform
  definition's public `getElementFactory`. It creates the grammar's ClickHouse PSI and the platform
  PSI of shared nodes such as `parseLenientStatement`'s `SQL_STATEMENT` (a composite node, created
  by `createComposite`, never by `createElement`). Its lexer is a `StatementBoundedSqlLexer` over
  `createBaseLexer`. Register a no-arg subclass as
  `lang.parserDefinition`; dialect rules go in `createBaseLexer` and in a `CHouseFamilyParser`
  subclass from `createParser`. A `MaskingSqlLexer` over `CHouseLexer` from `createBaseLexer` (to
  mask what the grammar lacks, such as `PARTITION BY <expr>` after `INSERT INTO FUNCTION`) starts
  afresh at each statement, so its context and the platform lexer's high state bits never cross one.
- Comments: register the platform commenter, which toggles `-- ` lines and block comments:
  `<lang.commenter language="<your dialect language id>" implementationClass="com.intellij.database.sql.common.core.psi.impl.support.SqlNestedCommenter"/>`.

`SqlDialectRegistry` (`dialect`): a static, versioned table (`VERSION`) of core dialect names,
labels, `-- dialect:` marker spellings, IDE language ids and `Dbms` names. Each plugin's copy is
identical data keyed only by platform identities, so plugins agree without sharing objects.
Stock platform identities are aliases next to the plugins' own (`ClickHouseSQL` and `CLICKHOUSE_BRIKK`
beside the stock `ClickHouse` and `CLICKHOUSE`; stock DuckDB: `DUCKDB` before
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
  outermost first; `consoleSession(file)` its session.

- `NamespacePathResolver(levels, usesCurrentNamespace, maxDepth = levels.size, childrenOf)`:
  `resolve(roots, path, currentNamespace)` finds the model object a typed dotted path addresses and
  returns its absolute path (`NamespaceTarget`). `levels` are the namespace kinds, top first:
  `[DATABASE, SCHEMA]` for catalogs as databases, `[SCHEMA]` for one level. Paths are tried as
  absolute first, then (when enabled) under the current namespace's first one, two, ... names; up
  to `maxDepth` names, so a dialect may address tables below its namespaces. Names match exactly,
  else ignoring case; an unresolved path gives null. `isChildless(node)`: listed but not introspected.

- `CatalogProfile`: a dialect's catalog facts. Minimum: `dbms`, `productLabel`, `quoteChars`,
  `levels`. Defaults: `usesCurrentNamespace = false`, `maxDeepenDepth = levels.size`,
  `autoIntrospect = false` (a dialect turns it on), `outOfScopeReferences = false` (see below), `dataSources` (a `DialectDataSourceResolver`;
  override with a stored instance for its options), `childrenOf`. `pathResolver` builds the
  `NamespacePathResolver`. `CatalogScopes.select(levels, path)` selects exactly one namespace (its
  direct children load, not its subtree); `widen` adds it to a scope.
- `AutoIntrospectService(project, scope)`: register one project service subclass per plugin with
  its `profile`. `requestPath(file, path)` resolves a typed path (absolute or relative to the
  console's namespace) on the file's data source and, when the model lists it without children,
  adds exactly that namespace to the introspection scope, saves the data source and refreshes only
  that element; `request(file, dataSource, target)` does the same for a resolved target. Requests are
  keyed by data source and absolute path in an `AutoIntrospectEngine`: claimed while in flight and
  after success, claimable again a minute (`createEngine`) after a failure. Refreshes queue rather
  than stop one another. When one ends, its banner goes (a newer request's banner stays) and after
  success the file is highlighted again. `refreshChanged(dataSource, path)` reloads a loaded namespace
  (empty path: the top-level list) after statements changed it, with no banner, no scope change and no
  claim, so it may run again. Loaded means it has children, the scope selects it, or a refresh of the
  service loaded it, so a namespace a statement emptied still reloads.
  `introspect(file, dataSource, target)` is the user-initiated request: it runs also when `autoIntrospect`
  is off and right after a failure, but not while in flight or once loaded.
- `IntrospectBannerProvider(serviceClass)`: register a no-arg subclass as
  `editorNotificationProvider`; it shows "<product>: introspecting '<namespace>'…" with Dismiss while
  the file's newest request runs.

- `QualifiedPathIntrospectContributor(serviceClass)`: completion right after a qualified path
  (`hive.` or `hive.web.wh`, names quoted with the profile's `quoteChars`, a doubled quote standing
  for itself) passes the path to the service's `requestPath`; it offers nothing itself and works
  while the IDE indexes. Only a console or data source the profile's resolver accepts is used, so a
  file attached to another dbms's console is never introspected. Register a no-arg subclass as
  `<completion.contributor language="X" order="first" implementationClass="..."/>`.

### Out-of-scope references

A reference into a namespace the model lists but never introspected fails to resolve though the name
may well exist. `OutOfScopeClassifier(service).classify(reference)` tells it apart (`ReferenceScope`):
the nearest qualifier that resolves is a namespace of the profile's levels and dbms; without loaded
children it is `OutOfScope(dataSource, namespace)`, unless the service knows it introspected it (one of
its refreshes ran on it without errors, or the introspection scope selects it by name; a scope listing
every namespace but some selects none by name); with children or introspected it is `Nonexistent`;
nothing resolving above the name is `Unknown`. An unqualified table is in the console's current namespace (as far as the model
lists it) when the profile `usesCurrentNamespace`. A column is out of scope only with the relation it
reads: the one its qualifier names (an alias, or an unaliased table by its name, in its query or an
enclosing one), or, unqualified, every relation of its own query when all of them are out of scope. No
other reference takes another's status: a wrong catalog, alias or table name, a column of a loaded table,
of a CTE or of a derived table stays reported. A namespace introspected some other way but empty still
counts as out of scope (the model cannot tell them apart), so a wrong name under it goes unreported. With
`outOfScopeReferences` on (off by default):

- `OutOfScopeReferenceFilter(serviceClass)`, registered as `daemon.highlightInfoFilter`, hides the
  platform's "Unable to resolve" reports (matched by its own messages, in the IDE's language) on exactly
  the name of an out-of-scope reference in SQL files. Annotator, server and other inspection reports stay.
  It cannot lower their severity, only hide them.
- `IntrospectNamespaceIntention(serviceClass, productLabel)`, registered as `intentionAction` for the
  dialect's language, offers "<product>: introspect '<namespace>'" on such a reference and calls the
  service's `introspect`. It changes no text and has no preview; its family is "<product>: introspect
  namespace".

### Console namespace

`CatalogSearchPathStrategy` is how a console switches and reads back its current namespace where
catalogs (`DATABASE`) hold schemas (`SCHEMA`). A plugin's `<dialect>` subclass delegates
`sqlSetSearchPath(path)` to `switchSql(path)` and `tryToLoadSearchPath(connection)` to
`readBack(connection)`. `CatalogSchemaSearchPath(quote, useSchema, useBareSchema, useCatalog,
catalogProbes, schemaProbes, defaultCatalog, schemaRequired)` builds one from statement templates
(`{catalog}` and `{schema}` are replaced by quoted names, a doubled quote standing for itself; a null
template cannot switch to that shape) and read-back probes tried in order (`NamespaceProbe.Value`, the
first column; `Column`, a labelled column; `FlaggedRow`, the name of the row a flag column marks).
Probes sharing SQL run it once; a probe that fails falls through. Without a catalog (and no default)
the path is unknown; without a schema it is the catalog alone unless `schemaRequired`.

```kotlin
// Doris
CatalogSchemaSearchPath('`', "use {catalog}.{schema}", "use {schema}", "SWITCH {catalog}",
    listOf(NamespaceProbe.Value("select current_catalog()"), NamespaceProbe.FlaggedRow("SHOW CATALOGS", "CatalogName", "IsCurrent")),
    listOf(NamespaceProbe.Value("select database()")), defaultCatalog = "internal")
// Trino
val row = "SELECT current_catalog AS catalog, current_schema AS schema"
CatalogSchemaSearchPath('"', "USE {catalog}.{schema}", catalogProbes = listOf(NamespaceProbe.Column(row, "catalog")),
    schemaProbes = listOf(NamespaceProbe.Column(row, "schema")), schemaRequired = true)
// DuckDB: USE "c" switches to the catalog's default schema
val current = "SELECT current_database() AS catalog, current_schema() AS schema"
CatalogSchemaSearchPath('"', "USE {catalog}.{schema}", useCatalog = "USE {catalog}",
    catalogProbes = listOf(NamespaceProbe.Column(current, "catalog")), schemaProbes = listOf(NamespaceProbe.Column(current, "schema")))
```

`CatalogImports.allCatalogs(base, dataSourceNames)`: for a dialect whose own SQL editor imports reach
only schemas (the MySQL family) over a model whose top level is catalogs, a dialect overrides
`getBaseImports(dataSource, names)` with `allCatalogs(super.getBaseImports(dataSource, names), names)`:
every catalog of the attached data sources becomes a qualifier head, without importing its schemas into
unqualified scope; without data source names the base imports return unchanged. PostgreSQL-family
dialects already resolve catalog heads (`CatalogImportsTest` shows it for the platform PostgreSQL
dialect and a `PgFamilyDialect`), so Trino and DuckDB need none of this.

### Live function catalog

What the connected server reports about its functions, harvested per data source and served to
completion. A plugin implements one `LiveCatalogProfile` (minimum: `dbms`, `productLabel`, bundled
`metadata`, `harvester`) and registers no-arg subclasses:

- `LiveCatalogService(scope)` as an `applicationService` with its `profile`;
- `HarvestConnectionInterceptor(serviceClass)` as `<database.connectionInterceptor>`;
- `LiveCatalogCleanup(serviceClass)` as a `DataSourceStorage$Listener` in both `projectListeners` and
  `applicationListeners`;
- optionally `RefreshLiveCatalogAction(serviceClass)` as an action.

The dialect's `LiveCatalogHarvester` holds its SQL and parsing: over a `CatalogRowSource` it returns a
`Harvest` (the server's `LiveInventory` and the connection's `SessionFacts`), or null when what it read
is incomplete. `LiveInventory.signatures` says whether overloads are the engine's own; a names-only
inventory proves availability, not overloads. The test kit's `JdbcCatalogRows` runs a harvester over
plain JDBC.

Selection (`LiveCatalogStore`), one ordered rule per data source and connection identity (by default a
hash of dbms, URL and user; `profile.identity`): a complete harvest from the current identity taken this
IDE session (`CatalogSource.Live`), else a complete harvest stored on disk under that identity
(`Disk`), else the bundled snapshot for the server version (`Bundled`). Only complete harvests are
stored, so a failed, timed-out or partial one never replaces a complete one. A changed identity selects
nothing old, and storing deletes the data source's files of other identities. Session facts (temporary
macros, extensions the session loaded) are kept per console session in memory, never persisted, and
served only to that session under the identity they were read from. Only a harvest on the console's own
connection updates them; any other harvest updates only the shared inventory. A console's facts are
served once the session ran a request the `ExecutionObserver` saw: that links the session to its engine,
the connection requestor the platform reports (the session itself is not reachable from a connection). A
harvest is labelled with the identity taken when its connection opened, and discarded
(`RefreshOutcome.Superseded`) if the data source names another server by the time it ends. With `persistenceId`,
harvests are written under `<system>/brikk-live-catalog/<persistenceId>/`, one file per data source and
identity; files of deleted data sources are deleted (`LiveCatalogCleanup`), and files not written for
`maxAge` (90 days) are swept at startup; files selected or stored in this IDE session are kept (a
selected file is marked as used). `legacyFiles` reads an earlier data-source-keyed format, which
is rewritten under the current identity on first use and then deleted.

When harvests run (`HarvestTrigger`): `ONCE_PER_SESSION` (default: the first connection of an identity
in an IDE session), `EVERY_CONSOLE_CONNECT` (every console connection, other connections only while no
live harvest exists), `ON_REFRESH`. Connections of the profile's dbms or its registry aliases count.
The interceptor skips the helper connections refreshes open, so a refresh harvests once.
`refresh(project, dataSource)` harvests over a helper connection. `refresh(project, session)` harvests
over a console session's own connection, so it also reads what the session loaded implicitly (DuckDB
autoload): the harvest is queued on the session as a request and runs on its engine's thread, and both
the shared inventory and the session's facts are recorded and returned (`RefreshOutcome.Refreshed.session`,
which `onRefreshed` shows). A session in manual-commit mode is not harvested
(`RefreshOutcome.SessionInTransaction`): its harvest queries would open a transaction of their own. Nor is
one in a transaction a statement began in auto-commit mode, which the platform does not track: the
`ExecutionObserver` treats a session as in a transaction from a detected begin until a detected end, a
commit or rollback through the IDE, or a new connection (`ExecutionObserverProfile.transactionStatements`,
default `StatementRules.TRANSACTIONS`: `BEGIN`, `START TRANSACTION`; `COMMIT`, `ROLLBACK`, `ABORT`, `END`;
`ROLLBACK ... TO`, `COMMIT PREPARED` and `ROLLBACK PREPARED` keep it, PostgreSQL's two-phase
`PREPARE TRANSACTION 'gid'` ends it (not a statement prepared under the name `transaction`, as DuckDB's
`PREPARE transaction AS ...`), and an end `AND CHAIN` begins the next; Trino's profile uses
`StatementRules.TRINO_TRANSACTIONS`, without `BEGIN` and `END`, which only delimit its routine bodies).
Only statements that succeed count: a failed begin begins nothing, a failed end ends nothing, and a
failed script whose statements include a begin leaves the session in a transaction, since its begin may
have run and its end may not. From 263 the platform reads `COMMIT`
and `ROLLBACK` statements itself (`transactionControlLanguageHandler`, PostgreSQL and MySQL families, not
the stock `DUCK`), but only to end a manual-commit transaction, where this tracking does not apply; the
two agree, `AND CHAIN` included. Without such a handler, a `COMMIT` statement leaves the platform holding
the manual-commit transaction, and it refuses the harvest's request (`HarvestFailed`). A session that is not connected
falls back to the helper connection. `RefreshLiveCatalogAction` uses the editor's connected console
session when it has one. `inventoryChanged(project, session,
requestor, sessionFacts, notify)` follows executed statements (see `ExecutionObserver`): when the change
may belong to the session, the session's connection is known and reaches the current server, and the
session is in auto-commit mode, it harvests over that connection on the engine's thread; a session in a
transaction is not harvested (a failing harvest query could abort it). Otherwise, and never on the EDT, a
debounced refresh follows (2 s, `refreshDelay`; a change during a refresh brings another). Every harvest
runs on a pooled thread under `deadline` (5 s, also each query's timeout). Past it, or when the caller is
cancelled, the running statement is cancelled and closed and the thread interrupted, and the connection
is handed back once the harvest let go of it, after at most 2 s more: a driver that ignores cancellation
may keep the connection busy. Results go to `notificationGroupId` (`onRefreshed`): for manual refreshes,
and after statements whose rule `notifies` (`StatementRule.notifying()`).

Completion: `service.completion(file)` returns a `LiveCompletion` for the file's data source and console
session; a `SqlCompletionProfile` forwards its hooks to it (`snapshot`, `functions`,
`serverOnlyFunctions`, `keywords` for `keywordCatalog`, `builtinFunctionFilter`). The bundled snapshot
is the one for the harvested server version. `LiveMergePolicy`: `REPLACE` (live functions replace the
bundled registry; live signatures win, names-only ones keep the bundled def; grammar-only entries and
extension functions not loaded stay), `FILTER_AND_EXTEND` (bundled registry functions the server does
not list go, unknown listed names are server-only, platform builtin items neither knows are hidden),
`EXTEND` (bundled stay, unknown listed names are server-only). With live signatures, `FILTER_AND_EXTEND`
presents kept functions with them too. Session functions are always offered and win over a server or
bundled function of the same name and kind (a temporary macro shadowing a built-in).
`profile.baseline(snapshot)` is the list merged (default `completableFunctions()`); it must not call
back into the completion profile's hooks.

`TrinoFunctionHarvester` reads Trino's `SHOW FUNCTIONS` (and `version()`) with the bundled Trino
catalog's modeling, which is generated from the same command: the harvest of the vendored 483 dump
equals the bundled 483 catalog. `TrinoLiveCatalogProfile` (only `dbms` left) uses it once per session
with `REPLACE`, in memory, and is off (`enabled = false`) until the Trino plugin turns it on.

### Executed statements

`ExecutionObserver(project, scope)`: register a project service subclass with its
`ExecutionObserverProfile` (`dbms`, `statements`, and the `liveCatalog` and `autoIntrospect` service
classes it drives) and an `ExecutionObserverStartup(serviceClass)` subclass as `postStartupActivity`.
It watches every request that finishes in the project's consoles of the dbms (the project's root data
bus, after execution, on the session's engine thread) and passes what `statements` detects to
`onFunctionInventoryChange` (default: the live catalog harvests again over the session's own
connection, so a temporary macro reaches only that session) and `onNamespaceChange` (default:
after `namespaceRefreshDelay`, 2 s, coalescing statements on the same namespace, the auto-introspection
service's `refreshChanged(dataSource, path)` reloads it). Unqualified names complete from the console's
current namespace; a namespace that was never loaded is not reloaded, and the introspection scope is
left as it is. The observer never fails a request: what a handler throws is logged, apart from
control flow and cancellation; the interceptor never fails a connection the same way. Implicitly loaded extensions (DuckDB autoload) run no statement; a refresh picks them up.

`StatementHeadDetector(rules, dialect)` matches statement heads, not a parse: the core tokenizer of the
core dialect (`duckdb`, `trino`, `doris`, ...) reads the script, so quotes, escape strings and nested
comments are the dialect's; statements end at `;` tokens and heads are whole leading words
(`StatementRule` patterns such as `CREATE [OR REPLACE] TEMP|TEMPORARY MACRO|FUNCTION`); after the head,
`IF [NOT] EXISTS` is skipped and the qualified name read. `StatementEffect.FunctionInventory(sessionFacts)`
(true when the change may belong to the session alone, so its own connection is harvested), or
`Namespace(depth, refreshDepth)`: the name addresses a namespace at `depth` and the one at `refreshDepth`
reloads (creating a schema is `Namespace(2)`, reloading a catalog `Namespace(1, 1)`). `StatementRules`
holds DuckDB (`INSTALL`, `FORCE INSTALL` and persistent macro DDL change the server's functions; `LOAD`,
temporary macro DDL and `DROP MACRO` may change only the session's; `ATTACH`, `DETACH`), Trino
(`CREATE`/`DROP CATALOG` and `SCHEMA`; function DDL) and Doris (`CREATE`/`DROP`/`REFRESH CATALOG`); none
notifies.

Internal API used, by class (the platform offers no public alternative):

| API | Used by | Why | Marketplace |
| --- | --- | --- | --- |
| `DBIntrospector.Factory` (interface and its methods) | `GenericJdbcIntrospectorGate` | the only seam for introspector selection | ignored for the Doris and Trino plugins; confirm it holds for classes in a bundled jar |

`InternalApiContainmentTest` (in the test kit's platform tests) reads every class of this jar in
full, signatures, method bodies, lambdas and annotation values, and fails if a class outside the kit
references a class, enclosing class or package annotated `@ApiStatus.Internal` or
`@IntellijInternalApi`, or a member so annotated or declared by such a class (found through the
owner's supertypes). It also fails if the kit's internal API differs from the test's own list, which
this table mirrors.

Auto-introspection's sync seams carry no internal-API annotation on IDEA 261.25134.95, 262.8665.81
and 263.5701.42 (the containment test runs on each), but they are Database Tools implementation
classes that a release may change: `prepareOneElementRefreshTask(dataSourceId, element)` (a public
top-level function of `IntrospectionTasks.kt`, so no Java shim is needed), `LoaderContext.selectTask`
(the class itself is not internal; only `selectSkip` is) and the suspending
`DataSourceSyncManager.tryPerformSync`, the sync entry point that remains in 263.

The live catalog kit uses `DatabaseConnectionInterceptor`, which is `@ApiStatus.Experimental` (the only
seam that sees every new connection before it is handed over; the Kotlin compiler also emits delegating
stubs for its deprecated `intercept`, which the plugin verifier reports), and tells consoles from
other connections by their requestor: a console's engine implements `DataProducer`, and the requestor
of an observed request (`DataRequest.Context.producer`) is the same object.

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
