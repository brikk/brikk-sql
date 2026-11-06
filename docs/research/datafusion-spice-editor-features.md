# DataFusion and Spice editor features

DFS-008 connects the two DFS-007 languages to metadata, core scopes and the shared IDE
features. Consumers still register their own extensions; the library registers none.

## Profile and registration

`DatafusionFamilyDialect.editorProfile` supplies a stable `DatafusionEditorProfile`. Override
it to provide copied `ExternalRelationFacts` and a cached `serverVersion(file)`. The profile
implements both `SqlCompletionProfile` and `RelationScopeProfile`. Its hooks perform no I/O.
An unknown or unmatched server version uses the bundled snapshot and labels that fallback.
`EngineBuiltins` is wired to this profile by default.

Register consumer subclasses of these shared components for the product's exact language:

- `DatafusionArgumentCompletion`, `SqlBuiltinPrototypeFilter`, `SqlCompletionContributor`
  and `ExternalRelationCompletionContributor`, ahead of stock completion contributors.
- `PgFamilyResolveExtension` and `ExternalRelationResolveExtension` as SQL resolve extensions.
- `DatafusionDocumentationProvider(profile)` as the language's documentation provider.
- `ExternalRelationGotoDeclarationHandler` for core-backed navigation.
- `ExternalRelationAnnotator` and `DatafusionFunctionAnnotator` for local diagnostics.
- `ExternalRelationInspectionSuppressor` for the references core analysis decides. The existing
  `PgFamilyInspectionSuppressor` is available for INSERT width checks over unknown table rows.

There is no new function-inspection suppressor. The dialect's builtin map exposes PostgreSQL
grammar special forms, including CAST, but excludes ordinary PostgreSQL function signatures.
Its grammar token inventory remains available. Native ordinary functions resolve through
`EngineBuiltins`; connection-owned routines keep their own platform targets. The completion
filter also removes inherited builtin-catalog function items while retaining user routines.

## Completion and documentation

Function entries keep their scalar, aggregate, window and table kinds. Obvious WHERE/ON/GROUP
positions withhold aggregates and windows. Nested RRF/rerank inputs offer search table calls;
their `SCALAR_STUB` registrations are never ordinary scalar suggestions. Dataset and column
arguments use their own relation scope. Keyword and type completion use the selected snapshot.

Named arguments insert one `=>`, reuse an existing arrow/value, and omit already-used labels.
The option names are a source-backed sidecar for Spice 2.4.0, not invented overload signatures.
Model/search entries show configuration-dependent availability. Unknown signatures stay
unknown; fixed source-backed scalar returns can still be shown independently of arity.

Quick documentation, lookup documentation and external links include captured descriptions,
syntax examples, alias identity where known, finite overloads where captured exhaustively,
and the selected inventory version. Introspection examples are not presented as exhaustive
signatures. Source evidence is not borrowed across snapshot versions. Links lead to current
upstream pages, explicitly distinguished from the pinned inventory. Column names, qualified
UDF calls and connection-owned routines keep their own documentation.

Completion parses call context once per request rather than once per catalog entry. It uses
decoded injected SQL and maps offsets through `PipeText`.

## References, schemas and rename

The IDE fact merge now retains `RelationFacts.callSites`, with the first provider of an offset
winning. Native table-type queries remap that call's absolute offset into their synthetic
query, and column provenance keeps the supplying provider for navigation. Pending facts do
not reuse an old schema for changed text.

Known struct fields, including literal-question `ai_decide` results, participate in completion.
Unknown inputs and fields stay open. Spice relation names retain case, and inserted column
names use the core dialect's quoting rules. Duplicate/ambiguous columns and unknown nullability
are retained rather than replaced with a guessed column or a non-null promise.

Search dataset arguments have table-reference PSI. Bracket arrays now have array-expression
PSI, preserving aliases and references in ordinary query structure. Numbered `$n` tokens are
parameters rather than PostgreSQL's default column references. Existing local-symbol rename
works through the platform's PSI and leaves matching string literals and named labels alone.
Opaque statement fallbacks continue to use core navigation; rename requires a real PSI
declaration and is never implemented by replacing matching text throughout a statement.

## Diagnostics and limits

`datafusionFunctionDiagnostics` returns local semantic diagnostics as core data. It checks
arity only against exhaustive registered overloads, leaves unknown signatures and qualified
UDFs alone, checks the measured numeric-versus-container SQRT case, and distinguishes Spice
scalar search stubs from valid relation/nested-search contexts. It does not evaluate functions.

`DatafusionFunctionAnnotator` maps those ranges into the file, skips connection-owned routines,
and presents unmatched-version results as bundled-profile warnings. An exact selected version
can produce errors. These are local semantic checks, not a native parser/server verdict.
Consumers can use the core diagnostics with their existing diagnostic profile instead of
registering a second annotator. Syntax and relation diagnostics keep their existing paths.

DFS-009 adds [connection catalogs and Flight SQL qualification](datafusion-spice-live-catalogs.md).
Publication remains a later plan step.
The tests use copied facts and a real cached IDE catalog with a recording console; this does
not qualify PostgreSQL wire transport for either engine.

## Evidence and commands

`vendor/data/datafusion-spice/round8/` contains separately labeled pinned source excerpts for
named arguments/AI result types and bounded native scalar controls for the diagnostics.
Rejected originals stay in the capture, including calls whose local signature remains unknown.
No search/model call executes in this capture.

```sh
python3 tools/probe_datafusion_editor.py
python3 tools/capture_spice_editor_sources.py
./kotlin test -m brikk-sql
./kotlin test -m brikk-sql-metadata
BRIKK_INTELLIJ_SDK=/path/to/idea-261 ./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

For the focused runtime matrix, set `BRIKK_INTELLIJ_TESTS` to
`DatafusionEditorFeaturesTest,DatafusionUserRoutineTest,DatafusionIdeTest,InternalApiContainmentTest`
and set `BRIKK_INTELLIJ_RUNTIME_SDK` to each supported installation. The API containment gates
include these new components and resolve inherited member annotations.

Validated on this round:

- Full shared IDE suite on 261.25134.95: 779 tests passed.
- Final focused suite: 28 tests passed on each of 261.25134.95, 262.10968.63 and 263.5701.42,
  including the final named-label deduplication and comment-preserving insertion controls.
- Full core suite: 1,356 tests passed, including ABI/Java 17 and the new context/diagnostic contracts.
- Metadata: 129 tests passed. DataFusion/Spice Python checks: 24 passed.
- No internal API references or experimental references in the new DataFusion components.
  The historical uncommitted binary inventory records 14 newly referenced IntelliJ
  members and 69 increased class/member-reference counts; these are constant-pool counts,
  not execution counts. No API-risk allowlist or suppression was added.
  The retained guard is
  `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij/InternalApiContainmentTest.kt`.
