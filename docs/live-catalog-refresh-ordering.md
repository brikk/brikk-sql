# Live catalog refresh ordering

Released IntelliJ support **0.4.1** fixes two `LiveCatalogService` races in
`0.4.0`: a harvest could recreate an inventory after `forget`, and
an older helper result could replace a newer complete result for the same source.
All three support artifacts are [publicly verified on Maven Central](intellij-support-0.4.1.md)
and retain released SQL `0.23.0` dependencies.

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

Read-only inspection of the current consumer sources confirms that all five database
plugins use this service: **DataFusion & Spice**, **DuckDB**, **Trino**, **ClickHouse**
and **Doris**. Trino explicitly enables its live catalog. These plugins should adopt
`0.4.1`; that is not a claim that every consumer has completed integration qualification.
The SQL Transpiler's current source has no direct `LiveCatalogService` use.

**DuckDB** is exercised by upstream tests using SDK-opened helper connections and
native collection. The separate DataFusion/Spice consumer matrix qualified the
accepted snapshot, whose binary/source JARs are identical to the public release.

No SQL core, metadata, verification API, engine parser, Flight protocol or native
harvester semantics change. Consumers not using `LiveCatalogService` are unaffected.

## Historical local candidate and verification boundary

Before the final release, the three IntelliJ artifacts were aligned at the uniquely named
`0.4.1-livecatalog-races-20261104-g2a46bd08efcd-SNAPSHOT` in Maven Local only:
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
It explicitly accepted that snapshot for local qualification/dogfood, not as a stable
release. The owner subsequently authorized public shared-library publication on
2026-11-05. Stable `0.4.1` was then released from that exact accepted source.

No sibling plugin was edited upstream. The DataFusion/Spice agent received the
verified stable handoff; the owner is coordinating the other database plugins.
Marketplace publication still needs separate authorization, and a candidate-only
qualification ZIP must not simply be relabelled as a final plugin release.
