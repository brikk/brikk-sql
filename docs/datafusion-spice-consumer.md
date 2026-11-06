# DataFusion and Spice consumer handoff

One plugin owns both products. Keep two language selections, DBMS identities, catalog
services and capability labels inside that plugin. The title and plugin ID belong to the
production repository. The fixture ID `brikk.datafusion.spice.fixture` is test-only.

## Tested consumer fixture

The shared platform suite compiles these sources against IDE 261 and runs them in the IDE:

- [DualDialectConsumerFixture.kt](../brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.dialect/DualDialectConsumerFixture.kt)
  installs both editor integrations under one plugin descriptor. It also supplies compiled
  catalog-service, interceptor, cleanup, manual-refresh and execution-observer subclasses.
- [DatafusionConsumerTest.kt](../brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.dialect/DatafusionConsumerTest.kt)
  checks simultaneous language registration, exact DBMS ownership, separate catalog fallbacks,
  product-specific function availability, observer wiring and native-mode defaults.
- [DatafusionEditorFeaturesTest.kt](../brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij.dialect/DatafusionEditorFeaturesTest.kt)
  uses that same installer for completion, documentation, references, rename and inspections.
- `DatafusionCatalogTest`, `DatafusionNativeErrorsTest` and `DatafusionExecutionTest` cover
  connected inventories, diagnostic freshness and console preparation. The recording console
  tests use stock PostgreSQL console machinery as a test double. Real Flight transport has
  separate JDBC captures; the test double does not certify PostgreSQL wire support.

The installer uses disposable extension registrations instead of loading a plugin ZIP.
Its language objects use injectable test facts. In the production consumer, use a stable
`ConnectedDatafusionEditorProfile(language, { applicationCatalogService })` on each dialect.
Keep the additional navigation providers ahead of the native-catalog provider. Do not copy
the fixture's `persistenceDirectory() = null` override into a persistent production service.

## Registration guide

### Product identity and association

| Product | Core name and marker | Language | DBMS |
| --- | --- | --- | --- |
| DataFusion | `datafusion` | `DataFusionSQL` | `DATAFUSION_BRIKK` |
| Spice | `spice` | `SpiceSQL` | `SPICE_BRIKK` |

Create one `DatafusionFamilyDialect` singleton per product using `DatafusionIdeProfile`.
Override `getDbms()` with the matching consumer-owned DBMS. Register each through
`com.intellij.sql.dialect` for that DBMS. The plugin requires `com.intellij.database`.
Bind files through explicit SQL dialect selection and consoles through the selected DBMS.
Do not claim every `.sql` file or infer Spice from a generic Flight URL, PostgreSQL syntax,
or `SELECT version()` reporting DataFusion. Spice product metadata and embedded engine
versions are distinct.

### Editor extensions

Register no-argument consumer subclasses for each exact language. The fixture's `Df*` and
`Sp*` classes demonstrate the constructor arguments and ordering.

| Extension point | Shared implementation |
| --- | --- |
| `com.intellij.lang.parserDefinition` | `DatafusionFamilyParserDefinition(language)` |
| `com.intellij.lang.syntaxHighlighterFactory` | `SqlSyntaxHighlighterFactory.Base(language)` |
| `com.intellij.completion.contributor` | `DatafusionArgumentCompletion`, `SqlBuiltinPrototypeFilter`, `ExternalRelationCompletionContributor`, `SqlCompletionContributor`, in that order before stock contributors |
| `com.intellij.lang.documentationProvider` | `DatafusionDocumentationProvider(editorProfile)` |
| `com.intellij.sql.resolveExtension` | One shared `PgFamilyResolveExtension`, plus `ExternalRelationResolveExtension` per profile |
| `com.intellij.gotoDeclarationHandler` | `ExternalRelationGotoDeclarationHandler(editorProfile)` |
| `com.intellij.annotator` | `ExternalRelationAnnotator` and `DatafusionFunctionAnnotator` per profile |
| `com.intellij.sql.inspectionSuppressorDelegate` | `ExternalRelationInspectionSuppressor(editorProfile)` |
| `com.intellij.externalAnnotator` | `NativeErrorAnnotator(nativeProfile)` |
| `com.intellij.highlightErrorFilter` | `NativeErrorPsiFilter(nativeProfile)` |

Use one stable `DatafusionNativeErrorProfile(language, catalog, pipe)` per language for both
diagnostic adapters. Register it only for that language and any deliberately supported injection
hosts. Native verification is unavailable. Local parse errors remain visible. Do not add a
second blanket PSI filter or use Flight preparation/EXPLAIN for background validation.

### Catalog lifecycle

Construct `DatafusionCatalogProfile(dfDbms)` and `SpiceCatalogProfile(spDbms)`. Keep their
resolver instances stable and separate. A custom DataFusion deployment should override
`embeddingProfile` with its build/configuration revision and qualify the required queries.

For each product, register:

1. A `LiveCatalogService(CoroutineScope)` subclass as an application service.
2. A `HarvestConnectionInterceptor(serviceClass)` under `com.intellij.database.connectionInterceptor`.
3. A `LiveCatalogCleanup(serviceClass)` listener for
   `com.intellij.database.dataSource.DataSourceStorage$Listener` at application and project scope.
4. A `RefreshLiveCatalogAction(serviceClass)` with its own action ID and label.
5. An `ExecutionObserver(Project, CoroutineScope)` project service whose profile uses that
   catalog's DBMS, resolver and statement detector, and names that catalog service.
6. An `ExecutionObserverStartup(observerClass)` under `com.intellij.postStartupActivity`.

Register the notification groups used by each profile. IDE service scopes own background
work. Refreshes run off the EDT; editor hooks read copied facts only. Session inventories stay
in memory, and helper refreshes populate shared offline caches. See the
[catalog contract](research/datafusion-spice-live-catalogs.md) for reconnect and failure rules.

### Optional Brikk pipes

Persist a project opt-in, off by default, separately from native SQL mode. Pass it to
`DatafusionPipeDialect(language, catalog) { project -> settings.brikkPipeMode }`. Use the same
instance for diagnostics, stage completion, preview, run-to-stage and execution interception.
Register the consumer's `CooperativeExecuteConfiguration` and `CooperativeExecutePromoter`;
dispatch by owned language/session before invoking the matching `PipeExecuteInterceptor`.
Give both products distinct preview/run action IDs. Never replace stock Execute globally.

Only `SELECT`, `WHERE`, `EXTEND`, `AS`, `AGGREGATE`, `DISTINCT`, `ORDER BY`, `LIMIT`, `OFFSET`
and `WINDOW` have qualified lowering. Use checked `lowerForExecution`, not unrestricted
`toExecutable`. Stock prompting owns client parameters. Native SQL PREPARE parameters remain
server-owned. See the [execution contract](research/datafusion-spice-execution.md).

### Driver

Use `org.apache.arrow:flight-sql-jdbc-driver:19.0.0`, class
`org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`, with the recorded SHA-256
`d3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f`.
Driver-process options include `--add-opens=java.base/java.nio=ALL-UNNAMED`.
The measured Java 25 configuration also needs `--sun-misc-unsafe-memory-access=allow`.
Configure these on the driver JVM, not globally. TLS, credentials and connection properties
belong to the data source. The unencrypted loopback URLs in the probes are disposable fixtures.

The generic JDBC function list is empty on both measured endpoints. Use the native harvester.
The optional `brikk-sql-intellij-jdbc-support` gate only routes generic JDBC introspection;
it does not supply missing native schemas or qualify another transport.

## Released artifacts

These final coordinates are published and verified on Maven Central. All artifacts use group
`dev.brikk.house`. Use `mavenCentral()`; no Maven Local or snapshot repository is needed.
See [the SQL release record](sql-0.23.0.md) and the
[IntelliJ 0.4.1 catalog-race fix](intellij-support-0.4.1.md).

| Artifact | Release version | Consumer use |
| --- | --- | --- |
| `brikk-sql-jvm` | `0.23.0` | Core, Java 17 |
| `brikk-sql-metadata-jvm` | `0.23.0` | Metadata, Java 17 |
| `brikk-sql-verify-api` | `0.23.0` | Verification contracts, Java 17 |
| `brikk-sql-intellij-support` | `0.4.1` | Embedded support, Java 21 |
| `brikk-sql-intellij-jdbc-support` | `0.4.1` | Optional reviewed generic JDBC gate |
| `brikk-sql-intellij-test-support` | `0.4.1` | Consumer test dependency only |

Keep the SQL family at `0.23.0` and the three support artifacts at `0.4.1` together.
The support POM exports core, metadata and verification API.
Do not package the heavy verifier aggregate or Kotlin IDE plugin just
to use these profiles. Shared jars contain no plugin descriptor or consumer fixture classes.
`./kotlin publish mavenLocal` still exercises development packaging locally. The earlier
shared-library release hold was lifted for these releases; downstream dependency changes and
production plugin publication still require separate authorization.

## Downstream adoption checks

The production repository must supply and test its descriptor, driver definition, settings,
icons, licensing and Marketplace metadata. Before calling that plugin supported:

- Build its ZIP and inspect the dependency closure for duplicate or stale Brikk/Kotlin jars.
- Load the ZIP in clean IDE 261, 262 and 263 installations with Database Tools; test both
  dialect selections together and coexistence with PostgreSQL and other dialect plugins.
- Verify parser/highlighter/formatting, completion/docs/navigation, inspections, injected SQL,
  explicit file association, reconnect, refresh and disposal through the actual descriptor.
- Run real Flight SQL driver smoke tests for each advertised endpoint/build, including TLS
  configuration, result rows, errors, parameter types, reconnect and cancellation bounds.
- Keep the [support exclusions](datafusion-spice-support.md) visible. A returned zero update
  count is not proof of successful SQL execution. Unsupported operations need an explicit
  consumer policy before any broader execution claim.
- Check packaged API references, ABI and Plugin Verifier output without widening allowances.
- Obtain separate authorization for publication and released dependency changes.

Weekly monitoring remains deferred. Use the existing manual
[upstream workflow](research/datafusion-spice-upgrades.md) for reviewed pin changes.
