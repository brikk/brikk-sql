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

`RecordingConsole(project, file, dataSource)` attaches to an existing data source instead, which stays
when the fixture closes, so several consoles can share one data source.

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
and current-namespace changes during prompting, missing clients and refusals submit nothing; nested-only pipelines
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

## Plugin isolation

`PluginIsolationContract` holds the isolation checks every dialect plugin repeated: the plugin and its
siblings are installed and enabled, each with its own `PluginClassLoader`; the packages it bundles load
from its own jars and are defined by its loader, while a sibling bundling the same class has its own copy;
its languages are its own and parse; every Execute action chains each wrapping plugin's
`CooperativeExecuteAction` and ends in `RunQueryAction`; registering the installed Execute customizers in
every order reaches each action exactly once, also after the first registered is removed; and nothing
logs an error on any thread, from before the light project opens (its startup) to the end of each test.
The plugin supplies only its id, its siblings' ids, its language ids and its owned packages; it knows
nothing of any one plugin. Run it in the isolation test run (the plugin and its siblings installed as
distributions, the Brikk jars off the test classpath):

```kotlin
class MyPluginIsolationTest : BasePlatformTestCase() {
    private val contract = PluginIsolationContract(
        pluginId = "dev.sort.my-intellij-plugin",
        siblingPluginIds = System.getProperty("test.siblingPlugins", "").split(',').filter(String::isNotBlank)
            .map { "dev.sort.$it-intellij-plugin" },
        languageIds = setOf("MySQL Dialect"),
        ownedPackages = listOf("dev.brikk.house.sql", "dev.brikk.house.sql.intellij"),
        wrapsExecuteActions = true,              // false for a plugin without CooperativeExecuteConfiguration
        kotlinPluginEnabled = System.getProperty("test.kotlin")?.let { it == "present" },
    )

    override fun setUp() {
        contract.collectErrors()                 // before the light project opens
        super.setUp()
    }

    override fun tearDown() = RunAll({ super.tearDown() }, { contract.assertNoErrors() }).run()

    fun testErrorsOnPooledThreadsAreSeen() = contract.anErrorLoggedOnAPooledThreadIsSeen()
    fun testInstalledApart() = contract.thePluginAndItsSiblingsAreInstalledApart()
    fun testOwnedPackages() = contract.ownedPackagesLoadFromThePlugin()
    fun testLanguages() = contract.theLanguagesAreThePlugins(project)
    fun testExecuteChains() = contract.everyExecuteChainKeepsEachPluginsWrapper()
    fun testRegistrationOrders() = contract.everyRegistrationOrderReachesEachActionOnce(project)
}
```

Each check is also a `PluginIsolation` function taking what it reads (`assertInstalledApart`,
`assertOwnsPackages`, `assertLanguages`, `assertExecuteChains`, `installedCustomizers`,
`assertEveryRegistrationOrder`), for plugin-specific checks beside the contract, such as a sibling's
own wrapper class. Checks fail with an `AssertionError`.


`PluginRegistrationContract(pluginId, servicePackage).check(project)` reads the installed plugin's `plugin.xml`, the
descriptors it includes and the optional descriptors of enabled dependencies from its jars, as IntelliJ does, and
instantiates every extension, declared service (`applicationService`, `projectService`) and light service: a
registration under a wrong namespace, a missing adapter, an adapter that does not instantiate the class the XML names,
a declared service that does not load or instantiate, and an open or failing light service all fail. A plugin adopts it
with one light test calling it. `DocumentationProviderContract` (see the support README's language layer) checks the
engine documentation provider the same way.

## Editor popup

`EditorPopupContract(myFixture, itemId, childIds).check(openValid)` expands the registered `EditorPopupMenu`
(or another `parent`) as the IDE renders a popup: the action or group registered as `itemId`, and each child, is
rendered, enabled and visible in the editor `openValid` opens, and absent (not disabled) with that file but no
editor, with that editor but no file, in an empty context, with that file and another file's editor, and in each
unrelated file (a text file by default), with and without a selection. The recipe it accepts is the platform's
`com.intellij.ide.actions.NonEmptyActionGroup` with children setting `isEnabledAndVisible` from their own
applicability; a plain group stays behind as an empty submenu. A plugin adopts it with one light test passing its
IDs and a lambda opening its valid SQL or PIPE context; plugin-specific contexts (a selection rule, an injection)
stay in its own tests, using `context(editor, file)`, `assertShown` and `assertAbsent`.

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

`assertIntrospectorRoutesToGeneric(dbms)` fails unless an introspector is registered for the dbms
itself (not inherited through a fallback), the dbms resolves to a `GenericJdbcIntrospectorGate`, and
the platform refuses native introspection at server majors around PostgreSQL's threshold and Trino's
(0 to 10000), through the data-source overload too where the IDE has it (262 on).

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

## Background work

Tests of anything that runs on a pooled thread or in a coroutine must fail on what that work logs
or throws, which never reaches the test thread on its own.

- `BackgroundErrors(until)`: from construction until `until` ends, collects errors logged on any
  thread (the platform logger still logs and rethrows them as usual) and throwables escaping the
  coroutines of its scopes. `newScope(until)` is a supervisor scope on `Dispatchers.Default`,
  cancelled when `until` ends, for example for a project service the test builds; `handler` collects
  for scopes the test builds itself. `awaitScopes()` waits, dispatching events, until no coroutine of
  its scopes is active; `assertNone()` fails with everything collected as suppressed causes.
- `HeldIntrospections(until, answerTimeoutSeconds = 30)`: replaces only the database boundary of the
  real `DataSourceSyncManager` (through `DataSourceSyncManager.withDefaultExecutor`, a Database Tools
  implementation seam). Every introspection waits for the test: `next()` returns the oldest one
  started, waiting while dispatching events, with its `LoaderContext`; `finish(effect)` lets it end
  after playing the introspection's effect on the model (idempotent: a refresh may reach the
  database more than once). `assertNone(millis = 200)` fails if one starts; `finishAll()` and the end
  of `until` finish every started one without an effect. A refresh left waiting longer than the
  timeout fails inside the sync, which `BackgroundErrors` collects.

An auto-introspection test with the plugin's own service subclass:

```kotlin
class MyAutoIntrospectTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var introspections: HeldIntrospections
    private lateinit var service: MyAutoIntrospectService

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        introspections = HeldIntrospections(testRootDisposable)
        service = MyAutoIntrospectService(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(MyAutoIntrospectService::class.java, service, testRootDisposable)
        // Add a data source whose model lists the namespace without children.
    }

    override fun tearDown() = RunAll(
        { introspections.finishAll() },
        { errors.awaitScopes() },
        { errors.assertNone() },
        // Remove the data source.
        { super.tearDown() },
    ).run()

    fun testATypedCatalogIsIntrospected() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        introspections.next().finish { hive.schemas.createOrGet("web") }
        PlatformTestUtil.waitWithEventsDispatching("done", { service.pending(myFixture.file.virtualFile) == null }, 10)
    }
}
```

The kit compiles against the 261 test framework (`test-framework`, `-core`, `-common`) without
declaring it; the consumer's test runtime supplies its IDE's copy.

## Live catalog

`JdbcCatalogRows(connection, queryTimeoutSeconds)` runs a dialect's `LiveCatalogHarvester` over a plain
JDBC connection, such as an embedded engine.

## Platform tests

Contracts for this module and `brikk-sql-intellij-support` live in `platform-test` and run
in a real IDE: `./kotlin check intellijPlatform -m brikk-sql-intellij-support`.
`BRIKK_INTELLIJ_TESTS=SomeTest,OtherTest` runs only those classes; `BRIKK_INTELLIJ_RUNTIME_SDK`
runs them on another installation.
