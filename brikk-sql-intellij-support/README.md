# Brikk SQL IntelliJ support

An embedded Apache-2.0 JVM library for SQL dialect plugins. The first consumer is
SQL Dialect for Trino. Each consumer owns its copy through its plugin classloader.
It registers no extensions itself and has no SQL engine or Kotlin IDE dependency.

## API

- `CooperativeExecuteConfiguration`: capture and wrap the four stock Execute actions
  with the public dynamic customizer. Register a consumer subclass in plugin.xml.
- `CooperativeExecuteAction` and `ExecuteInterceptor`: preserve live options,
  presentation and captured-predecessor delegation. Only `DECLINED` delegates.
- `CooperativeExecutePromoter`: promote the underlying database action via the
  public registered promoter, then map back to the installed wrapper.
- `EditorSnapshot`: refuse stale document identity/revisions, including edit/revert,
  with `SqlExecutionRefusal`. Cancellation propagates unchanged.
- `TranslatingScriptModel`: preserve statement anchors and parameter prompting,
  replace only claimed statements, then finalize all translations before submission.
- `SqlExecutionAdapter`: product-owned claims, translation, parameter ranges and
  validation. Refuse expected failures with `SqlExecutionRefusal`; propagate cancellation.
- `RenderedSql`: exact SQL/input pair and optional source-map callback. An authored
  map is available only if parameter expansion did not change the original input.
- `SqlColumnCompletion.create`: consistent column lookup presentation with type,
  three-state nullability, source alias and origin label. Same-named columns from
  different aliases retain distinct lookup identities. Consumers provide copied
  facts, so the helper acquires no Kotlin IDE or engine dependency.

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
installation. SDK bytes are compile inputs only. The published POM exposes only
Kotlin stdlib, which IntelliJ consumers should exclude in favor of the platform copy.

```sh
./kotlin publish mavenLocal -m brikk-sql-intellij-support -m brikk-sql-intellij-test-support
python3 tools/publish_intellij_support.py 0.1.2 --local
python3 tools/publish_intellij_support.py 0.1.2
```

The last command requires the existing Central/signing environment. It publishes
only this module and its test kit, `brikk-sql-intellij-test-support`, at the same
version. `--core-version 0.19.0` explicitly adds core and metadata, never
verify/oracle. The module has independent `0.1.x` versioning. Temporary release
overrides restore on success or failure.

Shared platform contracts run in a real IDE against the test kit:

```sh
./kotlin check intellijPlatform -m brikk-sql-intellij-support
```

They compile against 261; `BRIKK_INTELLIJ_RUNTIME_SDK` runs them on another installation.

Consumer runtime, classloader, second-dialect and console tests live in Trino's
test suite. They are not shipped in the library. The test names and release results
are recorded in the consumer verification report and handoff documents.
