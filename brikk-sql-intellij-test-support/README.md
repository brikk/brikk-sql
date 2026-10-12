# Brikk SQL IntelliJ test support

Test fixtures for IntelliJ database dialect plugins. Use it in test scope only; never
bundle it in a plugin. It is versioned and released with `brikk-sql-intellij-support`.

## RecordingConsole

A real `JdbcConsole` whose session records submitted requests instead of executing them.
Each request also becomes finished session work, which the IDE 263 gutter renderer needs.

```kotlin
RecordingConsole(project, file, "my-driver-id").use { recording ->
    executeThroughThePlugin(recording.console)
    val sql = (recording.requests.single() as DataRequest.QueryRequest).query
}
```

- `requests`: every request submitted through any of the fixture's sessions, in order.
  An empty list means nothing was sent.
- `onSubmit`: runs after a request is recorded; throw from it to fail or cancel a submission.
- `onAuditorAdded`: runs when an auditor registers on a session bus, for example to edit or
  cancel at tracking time.
- `beforeSessionCall`: runs with the method name before every session call, including
  `equals`/`hashCode`; use it to prove a guard refuses before session access, or to refuse access.
- `replaceSession()` / `replaceDataSource()`: dispose the console and attach a new one to
  the same file, as a session or data source switch does. Change the URL through
  `console.dataSource.url`; detach clients through `console.session.detach(console)`.
- `close()` disposes the console, removes its data sources, and fails if the platform
  called a session method the recording does not model.

Set the file's SQL dialect before constructing the fixture and clear it after closing.

## PipeExecutionContract

The PIPE execution contracts for a `PipeDialect`: only PIPE in owned consoles is claimed;
statements are chosen as stock Execute chooses them; stale input, cancellation, destination
changes during prompting, missing clients and refusals submit nothing; nested-only pipelines
lower; run-to-stage runs only top-level stages to the caret. Call each check from a light
platform test with PIPE enabled:

```kotlin
private val contract = PipeExecutionContract(MyPipeDialect, "my-driver-id", "a-foreign-driver-id",
    { project.service<MyPipeSettings>().enabled = it }) { sql ->
    myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", sql)
    // Map the file to the dialect here and undo it when the test ends.
    myFixture.editor
}
fun testRefusesStaleInput() = contract.refusesStaleInput()
```

## Lexer restarts

`assertRestartsMatchFullLex(::MyLexer, text)` lexes `text` in full and again from every token
at which the lexer reports its initial state, where the editor highlighter restarts it, and
fails unless every restarted pass yields the same tokens and states. It also fails when no
restart point follows a `;`, when a state is negative (the console's parameter preprocessing
reserves those), or when starting at a token in the state reported there does not lex. Run it over the dialect's
corpus. `lexAll` returns one pass as `LexedToken`s.

## Dialect mappings

`setSqlDialectMapping(project, file, dialect, until)` maps a file, or the project with a null
file, on every supported IDE (the workspace setter on 263) and reparses it; with `until`, the
mapping is cleared when that disposable ends.

## Catalog contracts

`assertIntrospectorRoutesToGeneric(dbms)` fails unless the dbms's introspector factory is a
`GenericJdbcIntrospectorGate` registered for that exact dbms and the platform refuses native
introspection at server majors around PostgreSQL's threshold and Trino's (0 to 10000).

`assertFallbackExtensionsAudited(dbms, allowed)` fails unless every dbms-keyed Database Tools
extension the dbms inherits through its `extensionFallback` chain is registered for the dbms itself
or listed in `allowed` (extension point name to reason). Blank reasons and listed points that are no
longer inherited fail too. A dbms takes its fallback's extensions for any point where it has none of
its own, so a new platform extension point would otherwise be inherited silently. The chain is the
platform's: each dbms's first `extensionFallback`, then `UNKNOWN` (the generic dbms), which every
dbms falls back to last, so a dbms without a declared fallback is audited too. Points whose bean
class cannot load are skipped.
`inheritedDbmsExtensions(dbms)` lists the inherited points and the dbms each comes from, to start
the list:

```kotlin
fun testFallbackExtensionsAreAudited() = assertFallbackExtensionsAudited(MyDbms.DBMS, mapOf(
    "com.intellij.database.typeSystem" to "PostgreSQL types are the engine's types",
))
```

Finding the extension points reads `ExtensionsArea.nameToPointMap`, internal platform API; the
test kit is never bundled, so the support jar's containment rule does not apply to it.

## Platform tests

Contracts for this module and `brikk-sql-intellij-support` live in `platform-test` and run
in a real IDE: `./kotlin check intellijPlatform -m brikk-sql-intellij-support`.
`BRIKK_INTELLIJ_TESTS=SomeTest,OtherTest` runs only those classes; `BRIKK_INTELLIJ_RUNTIME_SDK`
runs them on another installation.
