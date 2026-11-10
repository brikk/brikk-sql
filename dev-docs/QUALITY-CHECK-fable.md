# Quality check (Fable), 2026-11-09

A second review of the tree on `main`, by reading the code, not the history. It agrees
with [the Grok quality check](QUALITY-CHECK-grok.md): the Kotlin code is respectable, and
the SQLGlot port should keep its shape. This note covers what that one does not: engine
logic in shared code, IDE blocking, the verifier contract, stale comments beyond the
generator headers, and repository clutter.

Section F and parts of section C roll in the [Kimi quality check](QUALITY-CHECK-kimi.md),
which found correctness problems this review missed. Each adopted item names its `KQ` id
and says where this note agrees, refines or differs.

Nothing was built or run for this review, because another session was running tests in the
checkout. The Kimi check did run the suites and records the results: core 1,880 of 1,880
passing, the other modules green with optional live-engine tests aborted, IntelliJ platform
tests not run. Every item below was checked against the file it names unless it says
"reported", which means one of the four area reviews found it and it was not re-read.

## How to read this

Each item has a risk label, because the tree has just been through a destabilising round:

- **Docs**: comments or documentation only.
- **Move**: code moves or is renamed with no intended behaviour change. The existing
  corpora and tests should pass unchanged, and generated files should not differ.
- **Behaviour**: a user, a caller or a gate sees something different. Needs its own test.

Do not mix a Move and a Behaviour change in one commit.

The standing constraint in the Grok note applies here too. Nothing below splits
`Parser.kt` or `Generator.kt` or retypes the `Expression` args. Two items change
behaviour that upstream SQLGlot shares, FQ-27 and FQ-29. They are small and deliberate,
and each should be recorded in `docs/brikk-extensions.md` when made.

Paths in sections A, D and F that start with `ast/`, `dialects/`, `generator/`, `optimizer/`,
`parser/`, `scope/` or `shape/` are under `brikk-sql/src/dev.brikk.house.sql/`. Paths in
section B are under `brikk-sql-intellij-support/src/`. All other paths are
repository-relative.

## Summary

| ID | Area | Change | Risk |
| --- | --- | --- | --- |
| FQ-01 | Release | Check that the released commit is on `main` | Behaviour (CI only) |
| FQ-02 | Core | One home for the DuckDB analysis refusals | Move |
| FQ-03 | Core | Drop the gate-only dialect flags | Move |
| FQ-04 | Core | Move DuckDB renderers out of the base generator | Move |
| FQ-05 | Core | Meta keys as constants | Move |
| FQ-06 | IntelliJ | Profile hooks in place of DataFusion downcasts | Move |
| FQ-07 | IntelliJ | Catalog lock held through disk IO on the completion path | Behaviour |
| FQ-08 | IntelliJ | Harvest deadline on the console engine thread | Behaviour |
| FQ-09 | IntelliJ | Unbounded waits in the streaming cancel | Behaviour |
| FQ-10 | IntelliJ | Completion widens the saved introspection scope | Behaviour |
| FQ-11 | IntelliJ | Log the seven silent recoveries | Move (logging) |
| FQ-12 | Verifiers | Doris and ShardingSphere against the documented contract (KQ-03) | Behaviour or Docs |
| FQ-13 | Verifiers | Verifier lifecycle | Behaviour |
| FQ-14 | Verifiers | Deadline, bounded workers and size preflight for the ClickHouse verifier (KQ-07) | Behaviour |
| FQ-15 | Verifiers | Shared connection plumbing | Move |
| FQ-16 | Comments | "Not ported" comments in `Parser.kt` | Docs |
| FQ-17 | Comments | SQLGlot pin in documents and test headers | Docs |
| FQ-18 | Comments | Oracle and verifier comments | Docs |
| FQ-19 | Comments | Build, CI and README facts | Docs |
| FQ-20 | Cleanup | Hand-format the unspaced lines, add `.editorconfig` | Move |
| FQ-21 | Cleanup | Index `tools/`, share its helpers | Docs, then Move |
| FQ-22 | Cleanup | Test files named after batches | Move |
| FQ-23 | Cleanup | Archive finished trackers and dated notes | Docs |
| FQ-24 | Cleanup | Index the evidence folders in `vendor/` | Docs |
| FQ-25 | Cleanup | Small code leftovers | Move |
| FQ-26 | Correctness | `IdentityKey` hashes by identity (KQ-01) | Behaviour |
| FQ-27 | Correctness | Type cache keyed by dialect as well as type text (KQ-02) | Behaviour |
| FQ-28 | Correctness | Narrow the two broad catches in `SqlFragment` (KQ-04) | Behaviour |
| FQ-29 | Correctness | Reset the synthetic-alias counter per generation (KQ-06) | Behaviour |
| FQ-30 | Correctness | PostgreSQL oracle cleanup failures (KQ-08) | Behaviour |
| FQ-31 | Correctness | Decide who owns `SqlFragment.ast` (KQ-05) | API decision |

## Progress

The work is being done on the branch `quality-check-fixes`, one round per group in the
suggested order. Each round is implemented in one session, reviewed in another, gated by
the manager and committed on its own.

| Round | Items | State |
| --- | --- | --- |
| 1 | FQ-16, FQ-17, FQ-18, FQ-19, FQ-23, FQ-24, FQ-21 step 1 | Done |
| 2 | FQ-01 | Done |
| 3 | FQ-26, FQ-27, FQ-28 step 1 | Done |
| 4 | FQ-11; facts for FQ-07 to FQ-10 | FQ-11 done. FQ-07 deferred with a design. FQ-08, FQ-09 and FQ-10 need the owner's decision; see each item. |
| 5 | FQ-02, FQ-03, FQ-20 | FQ-02 and FQ-20 done. FQ-03 not done: no flag met its condition; see the item. |

Left open by the rounds so far, for the owner:

- FQ-19: `.github/workflows/test.yml` still ignores the branch
  `publish/intellij-support-0.1.0`. It is configuration, so the comments round left it.
- FQ-19: the Toolchain 0.11 and 0.12 comments were left as they are. Nothing in the
  repository shows that their statements stopped holding on 0.13.0.
- FQ-01: the guard checks `HEAD` only. A launcher run from a checkout with uncommitted
  edits to tracked files would still publish them. CI checkouts are clean, so this
  affects local releases only. A clean-tree check would be a separate change.
- FQ-01: the workflow steps have only been exercised locally against throwaway
  repositories, including a shallow clone like the one GitHub makes. The first real
  release run is their first run on GitHub.

## Release

### FQ-01. Check that the released commit is on `main`

The rule is that everything released is on `main`, and the release branch is cut from
there. Publishing from `release/<version>` is the intended flow. What is missing is the
check. Nothing in `.github/workflows/release.yml`,
`.github/workflows/intellij-support-release.yml`, `publish-release.sh` or
`tools/publish_intellij_support.py` asks whether the commit being released is an ancestor
of `main`. These all publish today:

- a `release/<version>` branch cut from a feature branch, which is what happened with 1.0.0;
- a release branch that started on `main` and then gained commits that never went back;
- a manual dispatch of either release workflow from any ref.

Add one step before publishing that fetches `main` and fails unless
`git merge-base --is-ancestor HEAD origin/main` succeeds. The checkout step needs full
history for that. The owner's per-release exception in `AGENTS.md` needs an explicit
workflow input, so that using it is visible in the run.

Separately, the `preverified` input on both workflows skips the whole test gate. Keep it
if it is used, but it should not also be able to skip the ancestry check.

## A. Engine-specific logic in shared code

`dialects/Dialect.kt` has 54 Brikk-native `internal open` members. 29 of them are
overridden only by `DuckdbDialect`. Most of those are fine: a shared pass asks a behaviour
question and one engine answers differently (`integerDivisionByZeroYieldsNull`,
`usingColumnTakesJoinSide`, `lambdaNamesKeepCase`, `dmlScopes`). Leave those, as the Grok
note says under QC-05.

The problem is narrower, and it is the part that spreads:

- DuckDB refusals are listed by hand in seven places (FQ-02).
- Three flags are not behaviours. They only gate a DuckDB-named predicate (FQ-03).
- The base generator renders DuckDB-only nodes and asks nine single-reader flags whether
  it may (FQ-04).

### FQ-02. One home for the DuckDB analysis refusals (Move)

Each analysis entry point opens with its own list of DuckDB constructs it refuses:

| Entry point | Lines | Error thrown |
| --- | --- | --- |
| `optimizer/Qualify.kt` | 92-114 | `OptimizeError` |
| `optimizer/QualifyColumns.kt` | 107-112 | `OptimizeError` |
| `optimizer/AnnotateTypes.kt` | 381-387 | `OptimizeError` |
| `optimizer/Lineage.kt` | 213-229, 251 | `SqlglotError` |
| `scope/ScopeEvaluator.kt` | 174-187 | `UnsupportedError`, or a silent `return true` |
| `shape/SqlFragment.kt` | 180-183, 392, 516-519 | `UnsupportedError`, `ShapeError` |
| `shape/SqlParameters.kt` | 23-34 | `UnsupportedError` |

The lists overlap but differ, and nothing says which differences are meant. `Qualify.kt`
refuses COPY layout options and recursive view definitions; the `Lineage.kt` block
refuses neither. `Lineage.kt` refuses secure view definitions; `Qualify.kt` does not.
Some checks are gated on a dialect flag (`nativeFunctionContracts && hasDuckdbGeoJsonContract()`)
and others on the same lines are not (`hasDuckdbRegexSelector()`, `isDuckdbNameShow()`).
A new DuckDB construct has to be added to as many as seven lists by hand.

`dialects/DuckdbExecutableGuards.kt` already solved this for the generator: one walk
collects the facts, and two functions hold every executable boundary. Do the same here.

- Add one internal file next to it, for example `dialects/DuckdbAnalysisGuards.kt`, with
  one function per pass that returns the refusal message or null.
- Each pass calls its function once and throws its own error type, as now.
- Move the checks verbatim: same conditions, same order, same messages, same gating.

After the move the seven lists sit side by side and the differences can be reviewed as a
separate, deliberate change. Do not unify messages, error types or gating in the move.

Done in round 5. `dialects/DuckdbAnalysisGuards.kt` holds nine functions with the 69
conditions of the seven places, each list moved whole and unchanged. Three parts stayed
in their callers because they depend on work done there: the side-effecting-CTE check in
`optimizer/Lineage.kt`, which runs after source expansion; the dependency checks in
`SqlFragment.sourceTables`, which inspect the normalised tree; and the name check and
one-statement check in `shape/SqlParameters.kt`, which run before and between parsing
steps. A differential run of the old tree against the new one over about 440,000 calls
gave identical output, with every guard message firing.

For the later unification, which is a Behaviour change and not part of this move:

- The file is DuckDB-named, but some checks have no dialect gate and apply to every
  dialect: trigger DDL, secure views, and the `UPDATE` and `MERGE` lineage refusal.
- No test asserts any of the 54 guard messages by text. The differential run is the only
  evidence that they are unchanged.

### FQ-03. Drop the gate-only dialect flags (Move)

Three flags do not describe behaviour. Every read is `flag && <DuckDB predicate>`:

- `nativeStorageContracts`: 7 reads, each paired with `duckdbStorageDdlIssue`,
  `hasDuckdbSnapshot` or `isDuckdbCheckpointCommand`.
- `nativeFunctionContracts`: 14 reads, paired with the GeoJSON, Parquet and SETVAL predicates.
- `copyLayoutOptions`: 1 read, in `optimizer/Qualify.kt`, paired with `hasDuckdbCopyLayout`.

After FQ-02 most of these reads are in the one guards file, where the flag and the
predicate can become a single predicate. This is the last rule of QC-05 applied to
existing code: no `Dialect` flag whose only job is to switch on one engine's helper.

One question to settle while doing it, as a separate Behaviour change if the answer is
"change it": whether a DuckDB predicate should apply when a DuckDB-parsed tree is analysed
under another dialect. Today the gated ones do not and the ungated ones do.

`shape/SqlParameters.kt:23` still compares the dialect name with `"duckdb"` directly,
seven lines above a read of `nativeStorageContracts` for the same purpose. Fold it in.

Not done in round 5, because no flag met the condition. After the move,
`nativeStorageContracts` still has one reader in `scope/ScopeEvaluator.kt` and
`nativeFunctionContracts` has four. `copyLayoutOptions` has a single production reader,
in the guards file, but `DialectCapabilitiesTest` asserts all three flags and a move may
not edit tests. Removing them needs the remaining `ScopeEvaluator` reads moved first and
that test updated, as its own change. The name comparison in `shape/SqlParameters.kt`
also stays: it runs before the dialect is looked up, so it is not equivalent to a flag
on the resolved dialect for every input string.

### FQ-04. Move the DuckDB renderers out of the base generator (Move)

`generator/Generator.kt` holds four functions that render DuckDB-only nodes:
`duckdbSessionStatementSql` (line 2788), `duckdbExtensionSql` (4890),
`duckdbExternalResourceSql` (4913) and `duckdbExplainSql` (4942). They are registered in
`generator/GeneratorTables.kt` (lines 555-559 and 711-714). Each begins by asking a
flag, for example `if (!dialect.executesExtensionManagement) unexecutable(...)`.

Nine flags exist only for this and have exactly one reader each:
`requiresConnectTarget`, `executesSessionStatements`, `supportsMergeInsertByName`,
`executesExtensionManagement`, `executesExternalResources`, `executesExplainOptions`,
`executesBinaryLiterals`, `executesRegexOperators` and `executesRecursiveViews`.

These are not ported SQLGlot methods, so moving them does not change the port's shape.

- The base registration keeps a handler that raises the same `unexecutable` message.
- `DuckdbGenerator`, or a contract file of extension functions on it, owns the rendering.
- The nine flags go.

I did not trace how a dialect's dispatch table overrides a base entry. Confirm that
first; if it is awkward, leave the registrations and move only the function bodies.

Related, and small: `Generator.duckdbNativeSemantics` is set to `true` by
`shape/SqlFragment.kt:188` for every source and target. It means "strict fragment
transpile", not anything about DuckDB. It is `internal`, so it can be renamed freely.

### FQ-05. Meta keys as constants (Move)

Parsers leave markers in `Expression.meta` under 44 distinct string keys
(`native_only_dialect`, `starrocks_map_literal`, `clickhouse_json_extract_raw`,
`datafusion_native_call`, `duckdb_pagination_position`, and so on). The generator and the
optimizer read them back as `metaOrNull?.get("...") == true`. A typo on either side
compiles and silently turns a refusal off.

Put the keys in one internal object and replace the literals. Keep every string value
exactly as it is: `ast/Serde.kt` writes meta into the serialised form.

The per-node checks at `generator/Generator.kt:662-675` also compare `dialect.name` with
`"starrocks"`, `"clickhouse"`, `"hive"` and `"spark2"`. Leave those. Folding them into the
existing `native_only_dialect` marker would change when they refuse (that marker refuses
always; these refuse only for execution).

### FQ-06. Profile hooks in place of DataFusion downcasts (Move)

Generic IntelliJ code reaches into one engine's profile. In `brikk-sql-intellij-support/src/`:

- `dev.brikk.house.sql.intellij.completion/SqlCompletionContributor.kt:59` and `:178`
  cast to `DatafusionEditorProfile` for `position`, `offerFilter` and `decorator`.
- `dev.brikk.house.sql.intellij.completion/SqlBuiltinPrototypeFilter.kt:24` tests
  `profile is DatafusionEditorProfile`.
- `dev.brikk.house.sql.intellij.dialect/EngineBuiltins.kt:159` casts for `tableCall`.
- `dev.brikk.house.sql.intellij.relation/RelationAnalysis.kt:78` tests
  `profile.dialect == "spice"`.

Give the profile base type open members with neutral defaults for these five questions
and let `DatafusionEditorProfile` override them. The next engine that needs the same
thing then overrides a member, not a cast in four files. The profile types are published,
so check the change against the binary-compatibility rule in `AGENTS.md`.

The literal `"SCALAR_STUB"` appears at five sites, and `"spice"` with `"2.4.0"` at
several more in `DatafusionEditorProfile.kt`, `ConnectedDatafusionEditorProfile.kt` and
`DatafusionDocumentationProvider.kt`. Name them once.

## B. IDE blocking and side effects

None of these is on the EDT by construction as far as I read. They can stall a console
or a background pass; they are not known to freeze the IDE. Each one touches code that
is deliberate and documented, so confirm the facts marked "to check" before changing
anything. FQ-07 and FQ-09 touch the race handling that 0.4.1 fixed
([record](../docs/intellij-support-0.4.1.md)); change them only with
`LiveCatalogRefreshRaceTest` and `StreamingCancelTest` running.

### FQ-07. Catalog lock held through disk IO on the completion path (Behaviour)

In `dev.brikk.house.sql.intellij.catalog/LiveCatalogService.kt`:

- `selection()` (line 243) takes the per-source lock and calls `store.select`. On the
  first call for a key that reads, migrates and touches files (`LiveCatalogStore.kt:68`,
  `loadOnce` at 136).
- `record()` (lines 521-565) holds the same lock through `store.store`, which writes and
  moves a file. Its KDoc says this is on purpose.
- `forget()` (lines 472-482) holds it through the file deletes.
- `completion(file)` (line 253) calls `selection()`, and the editor profile calls
  `completion(file)` from six members: `facts`, `serverVersion`, `parserSettings`,
  `snapshot`, `functions` and `catalogProvenance`
  (`dev.brikk.house.sql.intellij.dialect/ConnectedDatafusionEditorProfile.kt`).

So a completion or highlighting thread can wait for a file write or delete of the same
data source, and the first selection for a source does file IO on the calling thread.
The wait is bounded by local disk, not by the network: the lock is never held while
connecting or harvesting.

To check: which threads the consuming plugins call `completion(file)` from, and why
`selection()` needs the lock at all (probably so it cannot observe half of a `forget`).

Suggested direction: answer `selection()` from the in-memory map without the lock, and do
the one disk probe per key off the caller, serving the bundled catalog until it lands.
Keep the lock for the ordering of `record` and `forget`.

Smaller, same file family: `ConnectedDatafusionEditorProfile` keeps two single-slot
caches, each behind its own `@Synchronized` monitor (lines 17-29 and 33-45). Two editors
on different data sources rebuild them on every switch.

**Findings (round 4, checked by the reviewer).** This is not a lockup, and the lock is
needed.

- The lock in `selection()` is there for correctness. Without it, a cold disk read that
  finishes after a concurrent `forget` puts the forgotten inventory back through
  `entries.putIfAbsent`, and the legacy migration can rewrite the deleted file.
  `docs/live-catalog-refresh-ordering.md` gives the same reason.
- File IO from `selection()` happens only on the first probe of a key, and again after a
  `forget` of that source. Later selections are memory only.
- Stored inventories measured 360 KB for an embedded DuckDB 2.0.0 harvest and 24 to 32 KB
  for the DataFusion and Spice captures. DataFusion and Spice files include every table,
  column and view definition, so they grow with the user's schema and have no cap.
- The editor entry points have no off-EDT restriction, so a call on the EDT is possible.
  No consuming plugin was available to check which threads really call
  `completion(file)`.
- The two caches in `ConnectedDatafusionEditorProfile` rebuild when callers alternate
  between inventories. No wrong result was shown; widen them only if profiling shows
  the rebuild matters.

No change was made. The exposure is a local read or write of a small file, and the fix
reworks the race handling that 0.4.1 put in place. The design to use when it is done:
answer completion from memory, run the first disk probe on an IO thread and publish its
result under the existing source ordering, serve the bundled catalog until it lands and
then invalidate highlighting. It needs a new test that stalls a cold probe and proves
that completion does not wait, that `forget` still wins, and that a newer live result
beats the probe.

### FQ-08. Harvest deadline on the console engine thread (Behaviour)

`LiveCatalogService.inventoryChanged` (lines 435-451) harvests on the console's engine
thread through `runHarvestBlocking` (`LiveHarvest.kt:81-100`). That is deliberate: the
session's facts can only be read over the session's connection. The wait is the profile
deadline plus a 2 second cleanup. The default deadline is 5 seconds
(`LiveCatalogService.kt:100`), and DataFusion sets 30 (`DatafusionCatalogProfile.kt:24`).

So after a statement that changes the function inventory, the next statement in that
console can wait 7 seconds on a slow server, or 32 for DataFusion and Spice.

No structural change is suggested. Decide whether the blocking path should use a shorter
deadline than the background refresh, and whether 30 seconds was meant to apply to it.

**Findings (round 4, checked by the reviewer).** This is the item closest to a real
stall, and it needs the owner's decision.

- For DataFusion and Spice the rules mark `SET`, `RESET`, `USE`, `PREPARE`, `DEALLOCATE`
  and the listed `CREATE`, `DROP` and `ALTER` forms as changing session facts
  (`DatafusionCatalogProfile.kt:43-47`). So the blocking harvest follows ordinary
  statements, once per request. For DuckDB it follows `LOAD`, temporary `CREATE`, and
  `DROP MACRO` or `DROP FUNCTION`.
- The wait is the deadline plus up to 2 seconds of cleanup: 7 seconds by default, 32 for
  DataFusion and Spice. It is not a hard bound. The `finally` in `runHarvestBlocking`
  calls `running.stop()`, which cancels and closes the remote statement synchronously
  with no local timeout (`LiveHarvest.kt:93-99`, `113-119`, `203-208`).
- After the cleanup wait the engine thread goes on even if the harvest still uses the
  console's connection.
- Nothing in the code or the documents says 30 seconds was chosen with this path in mind.

The decision: keep the profile deadline for every harvest, or cap the automatic harvest
after a statement (for example at 5 seconds) and keep the full deadline for a refresh the
user asks for. The cap trades catalog freshness for console responsiveness. It can be an
internal constant, with no API change. It does not bound the synchronous cancel.

### FQ-09. Unbounded waits in the streaming cancel (Behaviour)

In `dev.brikk.house.sql.intellij/StreamingCancel.kt`:

- line 108: the engine thread parks in `requestFinished` until the cancel releases it;
- line 158: `finishes(running, wait = true)` waits on the driver's `cancelAll` with no
  timeout when the engine is still on that connection;
- lines 186-195: `awaitHandled` waits until the engine handles the request or is
  disposed, logging once per deadline.

The comments explain why the engine must not start its next request during a cancel. The
case not covered is a driver whose `cancelAll` never returns on a connection the
disconnect does not retire. The pooled worker and the console's engine thread then stay
parked, and that console cannot run another statement.

Decide the last resort and write it down: release the engine after N deadlines with an
error in the log, or keep waiting knowingly. Add a test with a cancel that never returns.

Line 232 finds the engine's state by reflecting a private `JdbcEngine` field by shape. It
degrades to the stock cancel and logs when the field is missing, which is the right
fallback. No change.

**Findings (round 4, checked by the reviewer).** This needs the owner's decision.

- A console can stay parked in two ways, not one. If `cancelAll` never returns, the
  code disconnects after the deadline, and the console is stuck only if that disconnect
  is not handled or does not retire the connection. If `cancelAll` does return, the code
  then waits without a bound for the stock cancel to be handled, and that path also
  calls into the driver.
- The platform runs both the disconnect and the stock cancel outside the engine's
  sequential queue, so in the ordinary case they complete and the console is released.
- Releasing the engine after a timeout while it is still on the same connection is not
  safe. The outstanding `cancelAll` covers every statement on the connection and would
  cancel the next request. This is the hazard the current design avoids.
- `StreamingCancelTest` covers the timeout disconnect, a refused disconnect and a failed
  ownership check. It has no test of a wait that never ends.

The decision: keep waiting, which is safe but can strand one console, or escalate after a
bound to something that proves the connection is retired before the engine is released
(a forced disconnect or disposal of the console's engine). The second loses the
session's state and its teardown can hang too. A timeout that only releases the engine
is not an option.

### FQ-10. Completion widens the saved introspection scope (Behaviour)

`dev.brikk.house.sql.intellij.catalog/AutoIntrospect.kt:462-463` assigns
`dataSource.introspectionScope` and calls `updateDataSource` from the background refresh
coroutine. Typing a qualified path in completion starts this
(`QualifiedPathIntrospectContributor`). The class KDoc says the widening is intended, so
that what loads stays loaded.

Two things to check:

- whether the platform expects `updateDataSource` on the EDT or under a write action;
- what happens when the load then fails. The `finally` block updates only the service's
  own state, so the saved scope keeps a namespace that never loaded. Reverting the
  widening on failure looks right, but confirm against the manual-refresh behaviour the
  comment at lines 456-458 describes.

**Findings (round 4, checked by the reviewer).** This needs the owner's decision, and
there is more at stake than tidiness.

- The assignment and `updateDataSource` run in the service's coroutine scope, with no
  EDT hop and no write action.
- The platform does not require one. In the provisioned SDK (261.25134.95) neither
  `LocalDataSource.setIntrospectionScope` nor `DataSourceStorage.updateDataSource` has
  a threading annotation or assertion, and the platform's own callers differ: its
  introspection session sets the scope from a background thread, while one refresh path
  sends the storage update to the EDT. Not checked: whether any `dataSourceChanged`
  listener assumes the EDT.
- After a failed or cancelled load the saved scope stays widened, and no test asserts
  the scope after a failure.
- The consequence, read from the code and not tested: after an IDE restart the
  in-memory state is gone, and the widened scope alone makes `introspected()` and
  `completelyLoaded()` treat a namespace that never loaded as introspected. `OutOfScope`
  then reports names missing under it as nonexistent, so their "Unable to resolve"
  errors are no longer hidden.

The decision: keep the scope as requested, so a manual refresh retries what the user
typed, or roll back on failure. A rollback must remove only the request's own addition.
It must keep selections the user or another request made meanwhile, and it must treat
an error, a cancellation and a successful empty result as different cases.

### FQ-11. Log the seven silent recoveries (Move, logging only)

`recoverEditorOperation { ... }` turns an exception into a `Result`. Seven sites drop the
failure with `getOrDefault` or `getOrNull` and log nothing, all under
`dev.brikk.house.sql.intellij.catalog/`:

- `DialectDataSourceResolver.kt:88`, `:97`, `:102`
- `DasRelationFacts.kt:59`, `:68`
- `LiveCatalogService.kt:692`, `:725`

The other sites log (`AutoIntrospect.kt:197`, `OutOfScope.kt:160`). The resolver is the
one that matters: if a platform API changes shape, `resolve()` returns `NotFound`, every
connected feature falls back to the bundled catalog, and `idea.log` says nothing.

Log at warn with the exception. These run in highlighting passes, so log once per site
and exception class, not on every call.

Done in round 4. One limit: a platform method that was removed outright raises
`NoSuchMethodError`, which is an `Error`. `recoverEditorOperation` does not catch errors,
so those still reach the IDE's error reporting and are not logged by this helper.

## C. Verifier contract

### FQ-12. Doris and ShardingSphere against the documented contract (Behaviour or Docs)

`brikk-sql-verify-api/src/dev.brikk.house.sql.verify/SqlVerifier.kt:14-17` says an
implementation lets every exception that is not an engine verdict propagate.

- `brikk-sql-verify-doris/src/dev.brikk.house.sql.verify/DorisVerifier.kt:44-53` turns
  any `Exception` from the parser into `Rejected`, prefixing the class name when it is not
  a `ParseException`. A parser NPE on valid SQL reads as "your SQL is wrong". Its own
  `verifyScript` rethrows.
- `brikk-sql-verify-shardingsphere/src/dev.brikk.house.sql.verify/ShardingSphereVerifier.kt:44-51`
  catches `Exception` and returns `Rejected`. The comment says ShardingSphere reports
  rejections through several exception types.

For Doris the distinguishing test already exists (`isParseException`), and `verifyScript`
in the same file already applies the right policy (lines 118-123): a parse exception is a
rejection, anything else is rethrown. Make `verify` and `verifyExpression` do the same.
This is the KQ-03 recommendation and it replaces my first suggestion of returning
`Unverified`: rethrowing is what the contract says, and it needs no new policy. It can
move entries in the corpus ledgers, so run the gate and review the difference.

For ShardingSphere, first try to name the exception types the pinned parser throws for a
syntax error and catch only those (KQ-03). If they cannot be told apart from internal
failures, keep the broad mapping and say so in the KDoc as an advisory-tier exception to
the contract.

### FQ-13. Verifier lifecycle (Behaviour)

- `SqlVerifier` is not `AutoCloseable`, but `SqlVerifiers.forEngine("duckdb")` returns a
  new `DuckdbVerifier` holding an embedded database, typed as `SqlVerifier`. Ten test
  sites in seven files call `SqlVerifiers.forEngine("duckdb")!!.verify(...)` inline and
  never close it.
- `DuckdbVerifier.close()` (`brikk-sql-verify-duckdb/src/.../DuckdbVerifier.kt:97-100`)
  is not synchronised although `verify` is, and `verify` after `close` silently opens a
  new database. `ParseOnlyDuckdbVerifier` after `close` returns `Unavailable`.
- The oracle's `ClickhouseVerifier.close()`
  (`brikk-sql-oracle/src/.../ClickhouseVerifier.kt:46`) is unsynchronised against a
  synchronised `verify` on a native chDB session.
- `DorisVerifier.JAR_CLASSES` (line 154) caches the parser class per jar, which keeps its
  `URLClassLoader` for the life of the JVM. That matters when a plugin unloads.

Suggested: make `SqlVerifier` extend `AutoCloseable` with a default no-op `close()`,
synchronise the two `close()` methods, and give both DuckDB verifiers the same
after-close result (`Unavailable`). Close the verifier in the ten test sites. The
interface is published; check the supertype change against `AGENTS.md`. For the Doris
loader, either document it or tie it to the verifier's lifetime.

### FQ-14. Deadline, bounded workers and size preflight for the ClickHouse verifier (Behaviour)

`brikk-sql-verify-clickhouse/src/dev.brikk.house.sql.verify/ParseOnlyClickhouseVerifier.kt`
has three gaps. The first is mine; the other two are KQ-07. Do them together, since a
bounded pool without a deadline still leaves a caller waiting on a hung `connect()`.

- **No deadline.** It polls its worker in `while (true)` (lines 122-128). The default
  `checkCanceled` does nothing. The statement has a JDBC timeout and
  `max_execution_time`, so this only bites when `connect()` hangs or a driver ignores the
  timeout. Then a caller without cancellation waits forever.
- **Unbounded workers.** `WORKERS` is a cached thread pool (line 200), and a cancel adds
  another task to it (lines 97-104). Calls that ignore interruption keep their threads.
- **Size check after the allocation.** `verify` (lines 52-54) encodes the whole input to
  UTF-8 and then to hex before comparing with `MAX_HEX_LENGTH` (200,000). A large editor
  buffer pays for the allocation the check is there to refuse.

`ConnectionDuckdbVerifier` already has the first two answers (lines 64-73 and 123-127):
a deadline that returns `Unavailable("timed out")`, and a bounded pool with a stated
saturation result. Do the same here, with cancellation work bounded as well. The
annotator's own budget is optional (`NativeErrorAnnotator.kt:104` defaults to none), so
the IDE does not bound this for the verifier.

For the size check, an exact preflight is available without encoding. Every UTF-16 code
unit becomes at least one UTF-8 byte, and every byte becomes two hex characters, so
`sql.length > MAX_HEX_LENGTH / 2` already proves the input is too long. Keep the exact
check after it for input that passes.

Test with fake connections and latches, as KQ-07 lists: oversized input never calls
`connect`, non-interruptible calls cannot grow threads without bound, saturation returns
promptly, and a connection that opens after cancellation is still closed
(`ClickhouseAvailabilityTest.kt:62-91`, reported).

On the DuckDB side the opposite limit is worth a decision. Its pool is two threads with
no queue (`ConnectionDuckdbVerifier.kt:123-127`), so a third concurrent verification
returns "helper capacity is exhausted". Decide whether that is the wanted result when
several editors highlight at once.

### FQ-15. Shared connection plumbing (Move, optional)

`DuckdbQueryConnection` with `JdbcDuckdbQueryConnection`
(`ConnectionDuckdbVerifier.kt:132-164`) and `ClickhouseQueryConnection` with
`JdbcClickhouseQueryConnection` (`ParseOnlyClickhouseVerifier.kt:207-243`) are the same
shape, as are the two `Call` classes and the polling loops. `SQLException.reason()` is
defined twice inside `brikk-sql-verify-duckdb`.

All four types are public in published artifacts, so they cannot simply merge. FQ-14
removes the difference that matters. Sharing the rest is optional; if done, put a neutral
connection interface in `brikk-sql-verify-api` and keep the existing names as subtypes.

## D. Stale comments and facts

The Grok note covers the generator headers (QC-01) and the UDF comments in `Schema.kt`
(QC-02). These are the others. All are Docs.

### FQ-16. "Not ported" comments in `Parser.kt`

`parser/Parser.kt` marks unported upstream pieces with "not ported". Several are no
longer true, which makes a search for real gaps unreliable.

Stale: the code below the comment builds the node.

| Line | Comment says | Actual |
| --- | --- | --- |
| 346-347 | class KDoc lists COPY, simplified PIVOT/UNPIVOT and triggers as raise-gates | `parseCopy` (6015), `parseSimplifiedPivot` (3683) and trigger parsing (5070-5159) exist |
| 3047 | `exp.Lateral` not ported | the function builds `Lateral` |
| 3438 | `exp.HistoricalData` not ported | the function builds `HistoricalData` |
| 3932 | `exp.LimitOptions` not ported | the function builds `LimitOptions` |
| 9809 | `exp.TryCast` not ported | line 9832 builds `TryCast` |
| 2676 | `exp.Unnest` not ported | the class exists; upstream's Unnest type test is missing, and only its following CROSS/ARRAY token test is ported |
| 6086 | `exp.MultitableInserts` not ported | built at line 2023; only this `INSERT FIRST/ALL` branch still raises |

For line 2676, fix the comment only. Adding upstream's missing Unnest type test is a
parity question for the corpus, not for this pass.

Imprecise: the node class is generated and only the parser branch raises. Reword these to
"parser branch not ported": lines 2998 (`AtIndex`), 3033 (`Stream`), 3430 (`Changes`),
5276 (`Block`), 3407 (`WithTableHint`).

### FQ-17. SQLGlot pin in documents and test headers

`BrikkSql.SQLGLOT_PIN` and the fixture ledgers say `v30.29.0`.

- `ATTRIBUTIONS.md:12` and `docs/corpus-coverage.md:3` say the port is pinned to
  `v30.18.0-43-g3ca82489`. Update both.
- 17 test files and `parser/Tokenizer.kt` say their expectations come from the pinned
  SQLGlot at `v30.12.0-44-g93d16591`. Either reword to "captured at" or drop the version,
  so the header does not read as the current pin.
- `docs/brikk-extensions.md:201`, `:229` and `:569` name older versions. I did not read
  them in context. Leave them if they record when a divergence was observed.

### FQ-18. Oracle and verifier comments

- `brikk-sql-oracle/module.yaml:23` says PostgreSQL 14.22; lines 33-35 pin the 18.4.0 binaries.
- `brikk-sql-oracle/src/.../PostgresVerifier.kt:34`, `:46` and `:178` describe
  `accepted=true/false`. The result is a `VerifyResult` state now.
- `brikk-sql-oracle/src/.../ClickhouseVerifier.kt:13` says `EXPLAIN AST` does not execute
  the statement. Reported: the `brikk-sql-verify-clickhouse` README explains when it does,
  which is why the editor verifier uses `formatQuery`. Make the two agree.

### FQ-19. Build, CI and README facts

- `vendor/README.md:208` documents `data/trino-functions-481.tsv`. The tracked files are 483 and 484.
- `.github/workflows/test.yml:17` still ignores the branch `publish/intellij-support-0.1.0`.
- `publish.module-template.yaml:17` gives `0.1.0-SNAPSHOT` as the example coordinate.
- `publish-targets.module-template.yaml:9` lists modules that are not in this repository
  (compiler plugin, smoke, runtime, toolchain plugin).
- Comments in `brikk-sql/module.yaml:25`, `brikk-sql-metadata/module.yaml:21`,
  `.github/workflows/test.yml:47` and both publish templates cite Toolchain 0.11 or 0.12.
  The wrapper is 0.13.0. Check whether each statement still holds.
- `brikk-sql-intellij-jdbc-support/README.md:3-6` names versions 0.2.3 and 0.2.1.
- `dev-docs/README.md` links `../docs/sql-1.0.0.md` twice under different labels.
- `brikk-sql-intellij-support/src/dev.brikk.house.sql.intellij.pipe/PipeExecution.kt:47-51`
  has the KDoc of `PipeExecuteInterceptor` stranded above another declaration's KDoc.
- Reported: the root README module table omits the published
  `brikk-sql-intellij-jdbc-support` and `brikk-sql-intellij-test-support`.
- Reported: `DatafusionSltParseTest.kt:22,25` calls the dialect "thin" and cites ledgered
  gaps while the ledger is empty; `AnnotateTypesCorpusTest.kt:24` and
  `AstCorpusDifferentialTest.kt:83` say the parser does not populate positions.

## E. Cleanup

### FQ-20. Hand-format the unspaced lines, add `.editorconfig` (Move)

There is no formatter or linter configuration. The tree is consistent anyway, with one
visible exception: a few dozen lines written with no spaces, such as
`if(expression.hasDuckdbRegexSelector())throw OptimizeError(...)`.

- Core: 26 lines in 9 files, mainly `optimizer/Qualify.kt:92-95`,
  `optimizer/Lineage.kt:213-216`, `shape/SqlFragment.kt:180-183, 392, 516-519` and
  `shape/SqlParameters.kt:23, 30`. FQ-02 moves most of these, so format them there.
- Metadata: `DuckdbCoreExtensionOptions.kt`, `DuckdbParquetWriteOptions.kt`,
  `DuckdbFunctionProperties.kt` and `DuckdbGeoJsonOptions.kt` under `brikk-sql-metadata/src/`.
- `brikk-sql-verify-duckdb/src/.../ConnectionDuckdbVerifier.kt:44, 123`.

Format these by hand and add an `.editorconfig` that states the conventions the tree
already follows. Do not run a formatter over the whole tree. It would rewrite the port's
import blocks and long tables for no gain and bury every later diff.

### FQ-21. Index `tools/`, share its helpers (Docs, then Move)

`tools/` has 141 Python files in one flat directory with no README: 55 `test_*`,
27 `probe_*`, 12 `gen_*`, 11 `generate_*`, 8 `verify_*`, 7 `capture_*` and others.
Live generators sit beside one-off captures for closed tasks.

Moving files into subdirectories would break paths in CI, in the documents and in every
generated file header, so start smaller:

1. Add `tools/README.md` with one line per family: what is live (used by CI, a document
   or a generator), and what is kept only as provenance for vendored evidence.
2. Delete the two scripts nothing refers to: `tools/probe_temporal_arithmetic.py` and
   `tools/import_spice_documentation_audit.py`.
3. Rename `tools/test_expansion_sources.py`. It is a tool, and its name matches the test
   discovery pattern.
4. Put `sqlglot_version()` (defined in 10 scripts) and the Kotlin string escaper (10
   scripts, as `kstr`, `kt` or `kesc`, reported to differ in what they escape) in one
   shared module. Regenerate and require a zero diff in the generated Kotlin.

`tools/probe_spark_hive_catchup.py:50` hardcodes a path under `/var/tmp/jayson/`.

### FQ-22. Test files named after batches (Move)

57 test files are named for a work batch, not a behaviour (`*Catchup*`,
`MainIntegration*`, `FrozenTarget*`, `*Closeout*`, `*Harvest*`, `*ReleaseIntegration*`):
46 in core, 10 in the verify modules, 1 in the platform tests. There is no way to find
the tests for a source file by name.

Do not rename them in bulk now. Adopt the rule for new tests, and when a batch-named file
is touched, move its cases to a file named for the behaviour.

Reported, same area: `private fun roundTrip(` is defined in 23 test files and
`loadCorpus(` in 8. A shared test utility would remove them.

### FQ-23. Archive finished trackers and dated notes (Docs)

- `dev-docs/TODO-sqlglot-30.29.md` stays in place: vendored dispositions and
  `tools/sqlglot_target_dispositions.py` cite its path, and a source manifest records its hash.
- The dated record `main-integration-20261106.md` is now in `dev-docs/archive/`.
- `docs/` holds eight per-release records beside the user guides. A `docs/releases/`
  folder would separate them.
- Paths under `/home/jayson/` or `/var/tmp/jayson/` appear in `docs/sql-1.0.0.md`,
  `docs/doris-pipe-handoff.md`, `docs/engine-candidate-verification.md`,
  `dev-docs/archive/main-integration-20261106.md`, `dev-docs/TODO-sqlglot-30.29.md` and seven
  `docs/research/HANDOFF-*` files. Where a document says evidence is "retained" at such a
  path, say where it really is or that it is gone.

### FQ-24. Index the evidence folders in `vendor/` (Docs)

`vendor/` holds 480 tracked files, 36 MB. `vendor/README.md` describes it as pinned
artifacts that cannot be resolved publicly, but most of it is now task evidence. Fifteen
`sqlglot-sg29-*` and `batch-b` folders under `vendor/data/` each have their own README
and are not mentioned in the top one.

Add one line per folder to `vendor/README.md`. Do not move them: tests read
`vendor/data` by path.

### FQ-25. Small code leftovers (Move)

- `brikk-sql-intellij-support/src/dev.brikk.house.sql.intellij.completion/SqlCompletionContributor.kt:207`
  has `PROPERTY_COMPLETION = false`, which keeps the property-completion code unreachable.
  Ship it or delete it.
- `# TODO(review)` sits above the `description` in ten `module.yaml` files. Review them once.
- `-Xexplicit-api=strict` is set in nine published modules and absent from
  `brikk-sql-verify`, `brikk-sql-oracle` and `brikk-sql-metadata`. The first two are small.
- Reported: `Inventory.model` in `AutoIntrospect.kt:157` is written and never read;
  `TextExplain.kt:71-72` keeps a deprecated unused member on a public interface.

## F. Correctness and failure boundaries

These come from the [Kimi check](QUALITY-CHECK-kimi.md). I verified each against the
source and agree with all of them as problems. Where my suggested fix differs from the
Kimi one, the item says so. That note has the regression tests to write for each; they
are not repeated here.

### FQ-26. `IdentityKey` hashes by identity (KQ-01, Behaviour)

`optimizer/Scope.kt:66-69` wraps a value so that maps compare it by reference, but it
hashes with `value.hashCode()`. For an `Expression` that is the structural hash over its
mutable args (`ast/Expression.kt:517`). Two consequences:

- If a node changes after it is inserted, the same instance hashes differently and the
  entry cannot be found again.
- Every insert and lookup walks the node's whole subtree to compute the hash.

Only expression keys are affected. `Scope` and the lineage `Node` are plain classes and
already hash by identity. The expression-keyed uses are the subquery map at
`optimizer/Lineage.kt:485-493` and `Scope.refCount()`. I did not find a path that mutates
a key between insert and lookup, and did not search for one exhaustively, so today this
is a latent hazard and a cost, not a known wrong answer.

Use `System.identityHashCode(value)`, as KQ-01 suggests. It is a one-line change, and
`java.util.IdentityHashMap` is already used in three core files. If the multiplatform
target list in `brikk-sql/module.yaml` is restored later, `Expression.objectId` is the
common-Kotlin equivalent for expression keys; it exists for exactly this purpose.

### FQ-27. Type cache keyed by dialect as well as type text (KQ-02, Behaviour)

`MappingSchema.toDataType` (`optimizer/Schema.kt:511-523`) caches by the type string
alone, although `getColumnType` passes a dialect override that changes both parsing and
identifier normalisation. The first dialect to ask for a type string decides the answer
for every later dialect on that schema.

This is a faithful port: upstream does the same
(`reference/sqlglot/sqlglot/schema.py:673-685`). The fix is still right, because Brikk
analyses one schema under several dialects more readily than SQLGlot does. Key the cache
by the type string and the effective dialect instance, compute the effective dialect
before the lookup, and record the divergence in `docs/brikk-extensions.md`. Keep it a
per-schema cache.

### FQ-28. Narrow the two broad catches in `SqlFragment` (KQ-04, Behaviour)

- `unmappableFunctions` (`shape/SqlFragment.kt:272-276`) catches every `Exception` from
  transpiling and reparsing, then scans the source tree. Its only caller in the
  repository is `shape/Certify.kt:221`, which turns the result into refusal findings. A
  bug or a cancellation therefore produces a certification computed from the wrong tree,
  with no sign that it happened.
- `contract` (`shape/SqlFragment.kt:685-693`) catches every `Exception` from
  `columnDependencies` and returns an empty map. A failed analysis looks the same as a
  query with no dependencies.

I suggest two steps where KQ-04 has one.

1. Catch `BrikkSqlError` in both places. Every refusal the library raises on purpose
   derives from it (`ParseError`, `TokenError`, `UnsupportedError`, `OptimizeError`,
   `SqlglotError`, `ShapeError`, `SchemaError`), so expected refusals keep today's result
   while runtime bugs and cancellation propagate. This is the low-risk part.
2. Then narrow further as KQ-04 describes: only a target parse or token error falls back
   in `unmappableFunctions`, and only the known lineage refusals give an empty map in
   `contract`. Whether callers need "unknown" as data, distinct from "none", is an API
   decision under the `AGENTS.md` compatibility rule.

Step 1 is done. Doing it turned up facts that step 2 should start from:

- One deliberate refusal was not a `BrikkSqlError`: lineage's "Could not find <column>"
  for a set operation, a port of an upstream `ValueError`. It now has its own internal
  type, `LineageColumnNotFoundError`, which still extends `IllegalArgumentException`,
  and `contract` catches it.
- An unexpandable star never gave empty dependencies, although the old KDoc and KQ-04
  both use it as the example. `SELECT * FROM mystery` gives `{*=[mystery]}`.
- `SELECT * FROM mystery GROUP BY 1` throws a plain `IllegalStateException` from
  `optimizer/QualifyColumns.kt` (a port of upstream's `assert_is`), reachable from SQL
  text. It comes from `outputShape`, which `contract` does not guard, so it propagated
  before and still does.
- `parser/Tokenizer.kt` wraps any unexpected exception in `TokenError`, so a tokenizer
  bug still falls back under the `BrikkSqlError` catch.

### FQ-29. Reset the synthetic-alias counter per generation (KQ-06, Behaviour)

`generator/Generator.kt:122-123` keeps `nextNameCounter` on the instance, and
`generate()` (lines 397-415) does not reset it. A generator used twice on the same input
emits `_t0` the first time and `_t1` the second.

Upstream behaves the same way (`reference/sqlglot/sqlglot/generator.py:981` sets the
sequence in `__init__` only). Inside this repository it is not observable, because
`Dialect.generate` and `SqlFragment` build a new generator for each call. It shows only
for a caller that holds a `Generator` and reuses it, which the public
`Dialect.generator()` allows.

I agree with resetting the counter at the start of `generate()`. I found no place where a
generator calls `generate()` on itself, so a reset cannot hand out the same alias twice
within one statement. Value is modest and so is the risk; record it as an extension.

### FQ-30. PostgreSQL oracle cleanup failures (KQ-08, Behaviour)

`brikk-sql-oracle/src/dev.brikk.house.sql.verify/PostgresVerifier.kt` discards the result
of `DEALLOCATE` (line 151) and of the rollback and auto-commit restore in the
rolled-back execution path (lines 171-172), each inside `runCatching`.

The rollback case is sharper than it looks. JDBC commits the open transaction when
auto-commit is switched on during one. So if `rollback()` fails and the next line
restores auto-commit, the statement the method promises to roll back can be committed
instead.

Follow KQ-08: make a rollback failure explicit and do not restore auto-commit after one,
discard the connection or mark the verifier unavailable after any cleanup failure, and
keep the original SQL exception with cleanup failures attached as suppressed. This is
oracle code used by the corpus gate, so it ranks below the editor-facing items.

### FQ-31. Decide who owns `SqlFragment.ast` (KQ-05, API decision)

`SqlFragment.ast` (`shape/SqlFragment.kt:111`) is public and returns the fragment's own
mutable tree. `stages`, `scalarParams`, `tableSlots` and `sourceTables` are lazy values
computed from it once. A caller that reads `sourceTables`, mutates `ast` and reads
`sourceTables` again gets the first answer.

The problem is real. I differ on the default fix. KQ-05 prefers that `ast` return a deep
copy on every read. That makes each read cost a full copy, changes node identity between
reads, and is a behaviour change for any plugin that walks `fragment.ast` often. No
other module in this repository reads `fragment.ast`, and the library's own paths copy
before lowering or generating (`prepareTree` and `transpile` in `shape/SqlFragment.kt`).
The Kimi note adds that the tests already prove analysis leaves the tree unchanged. So
the only exposure is a caller outside this repository mutating the tree.

My preference is to keep `ast` as the fragment's tree and state the rule in its KDoc:
it is read-only for callers, and `ast.copy()` is the way to get a tree to change. That
matches how the rest of the port treats trees, where rewriting functions take a `copy`
flag. If the owner wants enforcement, the KQ-05 copy-on-read design is the way to do it;
it should then be a deliberate, announced change. Either way, do not fix a single lazy
property in isolation.

## Parked: gates and lenient verdicts

Each of these needs a decision about what the correct result is, so they are not
suggested now. None looks critical: none changes what a published artifact does for a
user today. They are listed so they are not lost.

- `brikk-sql-oracle/test/.../VerifyCorpusGateTest.kt:206-210` prints "skipped" and passes
  when the engine gives no verdict for `SELECT 1`. Making it fail could turn CI red where
  an engine is missing, which is the reason to park it.
- `brikk-sql/test@jvm/.../ConcurrencyTest.kt:31, 53` submits work and never reads the
  futures, so a worker exception is dropped. This is the cheapest one to fix when ready.
- `brikk-sql-verify-duckdb/src/.../SerializedSql.kt:103` treats any error column other
  than `"true"` as parsed. With the response shape DuckDB documents this branch is not
  reachable, so it is a strictness gap, not a live wrong verdict.
- `brikk-sql-oracle/src/.../ClickhouseVerifier.kt:71` matches `"Code: 62"` as a
  substring, which also matches codes 620 to 629.
- Reported: `PostgresVerifier.kt:185-196` maps any unlisted SQLSTATE to `Rejected`.
- `brikk-sql/testResources/corpus-policy.json` defers 2,657 named-dialect identity
  assertions and 1,498 base transpile directions. Disclosed, with reasons, but not run.
- `.github/workflows/test.yml` runs four name patterns of the Python tests. Eleven test
  files match none of them, while `CONTRIBUTING.md:57` calls the full discover the gate.
- 32 untyped `assertFails` in 18 core test files accept any exception.

## Suggested order

1. Docs first, since they cannot break anything: FQ-16 to FQ-19, FQ-23, FQ-24, and the
   `tools/README.md` step of FQ-21.
2. FQ-01, which only affects release runs.
3. FQ-26, FQ-27 and step 1 of FQ-28: small, isolated correctness fixes, each with a
   direct regression test.
4. The logging in FQ-11, then the facts "to check" in FQ-07 to FQ-10. Those answers
   decide whether section B needs code changes at all.
5. FQ-02, then FQ-03 and FQ-20 in its wake. This is the change that stops the spread.
6. FQ-05 and FQ-06.
7. FQ-12 to FQ-14, each with its own test, reviewing any corpus-ledger movement.
8. FQ-29 and FQ-30.
9. FQ-04, after confirming how dispatch overrides work.
10. FQ-31 and step 2 of FQ-28, once the API decisions are made.
11. The rest of section E as files are touched.

After each group, run the core suite on its own and then the other modules, with the
commands in [CONTRIBUTING.md](CONTRIBUTING.md). Do not refresh a known-failure ledger
unless the reviewed change explains the new signatures.
