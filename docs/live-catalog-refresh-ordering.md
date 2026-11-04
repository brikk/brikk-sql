# Live catalog refresh ordering

The local integration candidate fixes two `LiveCatalogService` races in released
IntelliJ support `0.4.0`: a harvest could recreate an inventory after `forget`, and
an older helper result could replace a newer complete result for the same source.

## Recording policy

- A request captures a per-data-source invalidation epoch and increasing request
  order before opening a helper or queuing a session/background refresh.
- `forget(dataSourceId)` changes that epoch and clears pending debounced work,
  memory, session facts and persisted inventory. Recording and forgetting share
  the same source lock through the disk operation, so there is no check/write gap.
  Selecting a lazy disk snapshot uses that lock too.
- A complete result is accepted only if no newer-started successful request has
  already updated its lane. Merely starting a newer request does not reject an
  older useful result. Failure, incomplete collection, timeout and cancellation
  leave the last good inventory and accepted order unchanged.
- The shared inventory and each console's session facts have independent ordering
  lanes. A newer helper or another console cannot suppress a session's private
  inventory. Existing identity and console-reconnect checks still apply.
- No source lock is held while connecting or harvesting. Unrelated sources do not
  share a recording lock, and shared disk writes do not hold the global console
  map lock. Weak session keys, helper startup-script suppression, identity-keyed
  persistence and the cache format remain unchanged.

The service retains a small fence state per encountered data-source ID so forgotten
work cannot become current again. It does not persist epochs or keep forgotten
inventories, connection objects or credentials in that state.

## Engine impact

The consumer's confirmed failing reproducers cover **DataFusion 55.2.0** and
**Spice 2.4.0**, using their native harvesters over actual Flight SQL helpers.
Those products share `DatafusionCatalogProfile` / `SpiceCatalogProfile` and are
directly affected by both fixes.

The fix is engine-independent. The built-in **Trino** live-function profile also
uses this service. **DuckDB** is exercised by upstream tests using SDK-opened
helper connections and native collection. Any ClickHouse, Doris or other consumer
that subclasses `LiveCatalogService` receives the same lifecycle fix, but this is
not a claim that every such plugin uses it or has passed integration tests.

No SQL core, metadata, verification API, engine parser, Flight protocol or native
harvester semantics change. Consumers not using `LiveCatalogService` are unaffected.

## Local candidate and verification boundary

The three IntelliJ artifacts are aligned at a uniquely named
`0.4.1-livecatalog-races-20261104-g<source-commit>-SNAPSHOT` in Maven Local only:
`brikk-sql-intellij-support`, `brikk-sql-intellij-jdbc-support` and
`brikk-sql-intellij-test-support`, all in group `dev.brikk.house`.
Their SQL dependencies remain the released `0.23.0` coordinates.

The restoring helper accepts a named snapshot only with the explicit local-only
opt-in and without core publication:

```sh
python3 tools/publish_intellij_support.py "$CANDIDATE_VERSION" --snapshot --local \
  --core-version 0.23.0 --skip-core-publication
```

Regression tests gate results after collection and check memory and disk. They
cover forget, out-of-order success, newer failure/partial/timeout/cancellation,
identity changes, unrelated sources, queued background work and console isolation.
The same tests can run against the immutable `0.4.0` JAR to retain red evidence.

The consumer owns Spice/DataFusion reproducers and IDE/DataGrip, Kotlin mode,
credentials, TLS/auth, console, cancellation and packaged-plugin verification.
This is an owner-authorized SNAPSHOT candidate, not a stable or public release.
No sibling plugin is edited. Public publication, release tags and Marketplace
publication are not authorized. Consumer-verification acknowledgement is required
before considering any further final-library release step.
