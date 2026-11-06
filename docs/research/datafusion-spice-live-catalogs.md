# DataFusion and Spice connection catalogs

DFS-009 adds `DatafusionCatalogHarvester`, `SpiceCatalogHarvester`, the corresponding
catalog profiles, and `ConnectedDatafusionEditorProfile`. They use the existing live
catalog service, execution observer, resolver, cancellation and refresh action.

## Consumer wiring

Pass the consumer's registered `DATAFUSION_BRIKK` or `SPICE_BRIKK` DBMS to
`DatafusionCatalogProfile` or `SpiceCatalogProfile`. Keep its `DialectDataSourceResolver`
instance. Register an application `LiveCatalogService` subclass using that profile,
plus `HarvestConnectionInterceptor`, `LiveCatalogCleanup` and `RefreshLiveCatalogAction`
subclasses naming the service. Register an `ExecutionObserver` whose profile names the
same service and uses `catalogProfile.statementDetector`, with its startup activity.
The library registers none of these extensions itself.

Use `ConnectedDatafusionEditorProfile(language, { catalogService }, additionalProviders)`
as the dialect's stable editor profile. Its completion, documentation, diagnostics and
relation facts read copied inventories. They run no catalog queries. Consumers with
navigation targets can supply their existing fact providers first. The native catalog
retains fully quoted provenance paths for resolving targets against a consumer's model.

DataFusion is an embedding API, not one universal server. This profile requires an
embedding exposing the documented information-schema contract and `SHOW FUNCTIONS`.
Override `embeddingProfile(dataSource)` with a deployment/build/configuration revision
when those distinguish endpoints behind the same URL. An embedding with different
metadata needs its own harvester, not a PostgreSQL transport assumption.

## Transport qualification

The selected driver is `org.apache.arrow:flight-sql-jdbc-driver:19.0.0`, class
`org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. Its SHA-256 is
`d3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f`.
The download was checked against Maven Central's published SHA-1 as well.

The disposable fixtures use `jdbc:arrow-flight-sql://127.0.0.1:PORT/?useEncryption=false`.
That setting is for isolated loopback tests. Credentials and TLS settings for a consumer
connection belong to its driver configuration.

The driver needs `--add-opens=java.base/java.nio=ALL-UNNAMED`. Catalog queries passed on
OpenJDK 17 and 21. Java 25.0.2 fails before connecting with the driver's default shaded
Netty Unsafe policy. Adding `--sun-misc-unsafe-memory-access=allow` to that driver JVM
made the same queries pass. No global JVM options were changed. The driver-only probe
can run on Java 17; the production IntelliJ support classes require Java 21 or later.

`vendor/data/datafusion-spice/round9/` records JDBC metadata, exact query rows, failures,
runtime versions, driver provenance and production Kotlin harvester results for:

- Spice v2.4.0+models, checksum-verified, using the existing disposable Spicepod.
- DataFusion 55.2.0 with Arrow Flight 59.2.0 in a bounded per-handshake fixture. Its separate
  Cargo lockfile preserves the earlier parser oracle's lockfile and provenance. The fixture
  adapts the pinned upstream Flight SQL example, retains schemas for empty results, and
  checks context ownership of statement/result handles. It is not a production server.

`FlightHarvesterProbe.java` runs the actual Kotlin harvesters against the JDBC connection.
Platform tests also replay the exact JDBC string cells through `CatalogRowSource`, then
exercise service selection, persistence, cancellation, identity changes and reconnects.

## Observed metadata gaps

Spice's JDBC product/version are `Spice.ai` and `2.4.0`, while `SELECT version()` reports
`Apache DataFusion 55.2.0, ...`. These are stored separately. The DataFusion profile refuses
a JDBC product identifying Spice, and the Spice profile requires that product identity.

Spice JDBC lists catalogs, schemas, tables and columns, but maps native types to JDBC
names such as BIGINT and VARCHAR. `information_schema.columns` retains Arrow names,
nested types, decimal precision/scale, nullability and defaults. View definitions come
from `information_schema.views`. An unquoted Spicepod name `MixedRows` registers as
`mixedrows` in this fixture, while its quoted column `MixedColumn` retains case. The
catalog stores exactly what the runtime reports.

The bounded DataFusion fixture implements query transport and product information, but
does not implement JDBC catalog/table metadata RPCs. Its information-schema queries work.
That difference belongs to the embedding, not to every DataFusion server.

The driver returns an empty function list for both products. `SHOW FUNCTIONS` and
`information_schema.routines` together provide names and kinds, including table functions
and Spice scalar search stubs. They do not prove exhaustive signatures. The harvest sets
`signatures=false`; `REPLACE_NAMES` never borrows bundled overloads for these live entries.
Scalar/table entries remain distinct. Removed names disappear even when the bundled
definition has no overloads. Grammar forms remain available.

Neither fixture exposes table constraints through information schema. Spice JDBC primary
keys and type info are empty; its table remarks are null. Missing comments/constraints
remain null, not a claim that none exist. `NativeCatalogRelation` and `NativeCatalogColumn`
can retain these facts when an embedding-specific harvester supplies them. Generic JDBC
introspection alone is therefore insufficient for the qualified native catalog.

The DataFusion session probe creates a view visible only to its connection. A SET command
changes that connection's setting while another keeps its default, but the JDBC update
reports failure after retries. This failure is retained in the capture. DFS-009 qualifies
catalog reads; [DFS-010](datafusion-spice-execution.md) records the execution fixture's
result-schema correction and the remaining product/driver limitations.

## Cache and refresh contract

Connection identity hashes the product, DBMS, URL, user, embedding profile, reported
database version, connection properties and driver properties. Credentials are not written
as identity text. The copied native catalog also has an engine/build profile, relevant
catalog/parser settings, a format version and a content revision. Arbitrary extension
settings are not persisted because they can contain credentials.

Every console connection harvests its own inventory. For these profiles the entire result
is session-local and memory-only. A helper refresh stores an offline shared inventory,
but never a console's namespace, temporary functions or prepared statements. Session
selections override the shared inventory. A helper refresh cannot overwrite them, and
reconnect clears the previous connection's session inventory before trying a new harvest.
An older in-flight harvest cannot overwrite the replacement connection's inventory.
Disconnect alone does not delete useful cached inventories. A failed reconnect can still
use the shared cache or bundle, but not the previous connection's temporary objects.

This conservative split means opening only consoles does not create a persistent shared
snapshot. A manual data-source refresh supplies one. A manual console refresh uses that
console's connection. Prepared statement text is not harvested or persisted.

The execution observer refreshes after CREATE/DROP/ALTER object changes, SET/RESET/USE,
PREPARE and DEALLOCATE. It uses the session's connection and retains the existing transaction
guard. Out-of-band UDF hot reload requires manual refresh or reconnect. There are no queries
on keystrokes. Helper refreshes use the existing debounce/deadline machinery.

Required query failures, permission denials, malformed rows and empty function inventories
reject the harvest. Tables, columns, views and function inventories are read again to detect
changes during harvesting; settings and engine version must also remain stable. This detects
observed races without claiming a server-side snapshot transaction. Failed/partial harvests
leave the last complete inventory intact. A table whose schema is unavailable remains open
with unknown columns. The overall catalog remains open for permissions and provider gaps.

Namespace components remain separate through storage. Core fact keys support SQL-quoted
components, so `"cat.one"."public"."a.b"` cannot become five identifiers. Namespace defaults
enter core search paths only when read from the asking console. Completion and documentation
label live, cached and bundled inventory sources. Live signatures remain unknown in docs
and inspections, including functions whose bundled names have exhaustive signatures.

The extended `LiveInventory` and `SessionFacts` retain their earlier constructors, synthetic
default constructors, `copy` and `copy$default` signatures. Platform ABI tests check them.

## Reproduction

Set `CARGO_TARGET_DIR` to a private compilation directory.

```sh
cargo build --locked --manifest-path tools/datafusion-reference/flight/Cargo.toml \
  --bin brikk-datafusion-flight --target-dir "$CARGO_TARGET_DIR" -j 2
python3 tools/probe_flight_catalog.py --java /path/to/jdk21/bin/java
python3 tools/probe_flight_catalog.py --java /path/to/jdk21/bin/java \
  --datafusion-flight "$CARGO_TARGET_DIR/debug/brikk-datafusion-flight" \
  --output vendor/data/datafusion-spice/round9/datafusion-jdbc.json
```

After compiling the platform tests, add `--harvester-classpath-file` pointing to their
`run-BUILD/test.log.classpath` to run the production Kotlin harvester as well. The probe
extracts only the Brikk and Kotlin runtime jars from that classpath. The captures committed
for this round include this check.

No publication or consumer dependency update is part of this round.

## Validation

- Full shared IDE suite on 261.25134.95: 788 tests passed.
- Final focused catalog/service/editor/ABI suite, including the reconnect race and metadata
  revision controls: 76 tests passed on each of 261.25134.95, 262.10968.63 and 263.5701.42.
- Full core suite: 1,357 tests passed, including quoted/dotted catalog components, overlapping
  provider keys, and the existing Java 17 and binary-compatibility checks.
- Python evidence checks: 26 passed. Both production Kotlin harvesters passed over the pinned
  JDBC driver. The stable captured inventories contain 320 DataFusion and 447 Spice name/kind
  entries, each with 11 relations including information-schema relations.
- The API containment tests found no new internal or experimental API use.
  `brikk-sql-intellij-test-support/platform-test/dev.brikk.house.sql.intellij/InternalApiContainmentTest.kt`
  retains the guard. The historical uncommitted binary audit counted five new
  IntelliJ member references and 12 increased class/member-reference counts.
  No API-risk allowance or suppression was added.
