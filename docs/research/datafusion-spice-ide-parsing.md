# DataFusion and Spice IDE identities and parsing

DFS-007 uses these agreed consumer identities:

| Product | Core dialect / marker | IDE language | DBMS |
| --- | --- | --- | --- |
| DataFusion | `datafusion` | `DataFusionSQL` | `DATAFUSION_BRIKK` |
| Spice | `spice` | `SpiceSQL` | `SPICE_BRIKK` |

`SqlDialectRegistry.VERSION` is 4. PostgreSQL, GenericSQL and generic Flight SQL sources do
not become either product. Registry lookups use the product's own language or DBMS ID.

## Consumer wiring

The shared support jar registers no language, DBMS or extension. Each consumer owns one
`DatafusionFamilyDialect` instance with its profile and overrides `getDbms()` to return its
own DBMS. It registers a no-argument subclass of `DatafusionFamilyParserDefinition` for that
exact language, and a stock `SqlSyntaxHighlighterFactory.Base` subclass for the same language.
The library does not create a driver or infer product identity from a JDBC URL.

```kotlin
object SpiceSql : DatafusionFamilyDialect(DatafusionIdeProfile.SPICE) {
    override fun getDbms(): Dbms = SpiceDbms.DBMS
}
class SpiceParserDefinition : DatafusionFamilyParserDefinition(SpiceSql)
class SpiceHighlighterFactory : SqlSyntaxHighlighterFactory.Base(SpiceSql)
```

Use `DatafusionIdeProfile.DATAFUSION` for the other consumer. These are shared library
components and real platform fixtures, not a packaged or published consumer plugin.

## Grammar and offsets

The base is `PgFamilyDialect`, whose PostgreSQL function definitions retain special forms
such as CAST. `PgFamilyElementFactory` supplies the existing PSI implementation. Resolution
and completion through `PgFamilyResolveExtension`/`EngineBuiltins` remain DFS-008 work.

`DatafusionFamilyLexer` changes only keyword-named argument labels, QUALIFY and DataFusion's XOR
token, and joins complete dollar-quoted strings into single literal tokens so the formatter
cannot insert spaces inside their values. `StatementBoundedSqlLexer` keeps tokens inside the core dialect's script boundaries,
including strings or comments containing semicolons. Literal and comment text stays intact.

`DatafusionFamilyParser` uses PostgreSQL PSI for ordinary queries. Native array constructors,
TRY_CAST, native call-word collisions, wildcard modifiers, QUALIFY, GROUP/ORDER BY ALL,
USE, COPY/file formats, CLUSTER BY, Spice utilities and
Brikk pipes use an explicit statement fallback only after the core parser accepts their
structured AST. The fallback keeps the original tokens and parameter occurrences rather
than masking a statement as a comment or string. This is editor structure, not an engine
execution verdict or permission to execute pipes.

Core parse failures remain PSI errors at their authored token positions. There is no new
error filter, inspection suppressor or background native query. Unknown command text is
left to the existing parser; it is not promoted into proved native grammar support.
Cancellation propagates through tokenization, core parsing and fallback traversal. The
new cancellable core Parser overload retains the original two-argument JVM entry point.

The fixtures exercise separate languages, common function PSI, native and rejected forms,
multi-statement boundaries, lexer restarts, E/hex/dollar strings, named arguments, JSON/array
operators, numbered/question parameters, CRLF and supplementary characters. They also
check formatting by reparsing the result, injected SQL and host offsets, large scripts,
and incomplete input. PostgreSQL and sibling language ownership remain separate.

## Validation

Compile against IDE 261. A focused shared-platform run is:

```sh
BRIKK_INTELLIJ_SDK=/path/to/idea-261 \
BRIKK_INTELLIJ_TESTS=DatafusionIdeTest,SqlDialectRegistryTest,InternalApiContainmentTest \
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

Use `BRIKK_INTELLIJ_RUNTIME_SDK` to run the same compiled contracts on 262 or 263. The
unfiltered command runs the full shared platform suite. Consumer plugin registration and
isolated plugin distributions still need their own consumer tests.

Validation completed:

- Full shared platform suite on 261.25134.95: 766 tests passed.
- Final focused dialect/registry/API contracts: 23 tests passed on each of 261.25134.95,
  262.10968.63 and 263.5701.42, after the native USE/ALL and dollar-string fixes.
- The focused run includes all 104 inputs accepted by the pinned Spice parser in round4,
  without changing those fixtures or suppressing PSI errors.
- Full core suite, including cancellation, ABI and Java 17 checks: 1,353 tests passed.

IDE 262 exposed formatter-inserted spaces inside split dollar strings. Keeping each complete
dollar string as one literal token fixes that change of value; formatting tests compare the
core AST before and after formatting on all three IDE versions.

## API containment

The existing full support-bytecode internal-API gate remains unchanged in scope. An added
gate checks every new DataFusion/Spice component, including its generated classes, for
experimental API references. Both resolve inherited member declarations and enclosing
class/package annotations. They inspect bytecode, not just source imports.

The before/after binary inventory records seven newly referenced IntelliJ members: the PG
lexer/parser constructors, two parser entry points, the XOR token, and two PSI error-reporting
methods. There are 32 increased class/member-reference counts, including already-used public
builder/lexer operations. These counts are constant-pool references per class, not execution
counts. The inspection gates check their API status. The inventory and binary snapshots are
at `/tmp/opencode/dfs007/`; no Plugin Verifier suppressions or allowlists were added.
