# Doris native catch-up validation

The current native validation target is **Doris 4.1.4.1**, using the official,
digest-pinned FE and BE images rather than the older 4.1.3 all-in-one image:

| Component | Official image digest |
|---|---|
| FE | `apache/doris@sha256:a9da001c8afa6d07b9b3de532a0b65566c3bc134f0beefbedfce7fad006eb843` |
| BE | `apache/doris@sha256:0b2827e2fcc0fc9b007502bda2a89e00d97f5696ca47c89f95cdff8e488da249` |

Both live components report **`doris-4.1.4.1-rc02-5dbec55b399`**, matching the
release-tag commit `5dbec55b399c2c4323f565bb2c6745cacb574416`. Version output is
retained at `/tmp/opencode/sqlglot-catchup-doris4141-versions.tsv`.

## Why upgrade

[4.1.4](https://doris.apache.org/releases/v4.1/release-4.1.4/) has meaningful
TIMESTAMPTZ, COALESCE, nullability, execution and function fixes, as well as SQL
additions already supported by this repository. The cached 4.1.3 image was a
convenient shared-behavior baseline, not evidence that the newer release was
insignificant.

[4.1.4.1](https://doris.apache.org/releases/v4.1/release-4.1.4.1/) fixes a BE crash
when nullable Boolean OR feeds expressions such as multi-branch CASE. It also
fixes materialized-view partition refresh, VARIANT WAL reads and aarch64 startup.
The [six-commit tag comparison](https://github.com/apache/doris/compare/4.1.4...4.1.4.1)
changes no grammar or parser-facade file. Our vendored **4.1.4 grammar remains
correct**; native execution should prefer the hotfix.

## Gates

```sh
BRIKK_DORIS_TEST_CONTAINER=<4.1.4.1-FE-container> TMPDIR=/tmp/opencode \
  ./kotlin test -m brikk-sql-verify --include-classes='*Doris*'
```

`DorisCatchupResultTest` checks:

- Actual FE and BE versions through SHOW FRONTENDS/BACKENDS. VERSION() reports
  only MySQL wire compatibility, so it is not a Doris version check.
- Native/generated escapes, comment boundaries, IF/CASE and explicit DATE_ADD/SUB.
- Generic table properties, SHOW CREATE and inserted rows; MySQL INSERT VALUE
  normalizes to Doris VALUES rather than claiming native singular VALUE support.
- All nine nullable Boolean OR combinations with independently fixed row results.
- The JSON-derived nullable-Boolean payload regression from
  [apache/doris#68401](https://github.com/apache/doris/pull/68401): 100,000 actual
  table rows, OR, AND/OR and both CASE paths, with short-circuit execution enabled
  and disabled. Exact expected group counts protect against incorrect results as
  well as crashes; literal NULL-only tests do not exercise the same payload path.
- VARIANT parsing/NULL inputs and timezone-aware COALESCE preservation.

Other Doris tests still use the exact native 4.1.4 grammar, DuckDB reference
execution and hand-owned semantic assertions as documented in their own suites.
Not every `*Doris*` method executes a Doris server; the native catch-up methods do.

All **81 Doris-selected methods pass**, including all six live catch-up methods,
with no failures or skips. The log is
`/tmp/opencode/sqlglot-catchup-doris4141-native.log`. Build, 38 compatibility/editor
contracts, sixteen offline release tests, two generator tests and three candidate
gate safety tests also pass. Core/metadata JAR SHA-256 values are unchanged from
the exact candidate that passed the peer compiler/runtime and CLI/IDE gates.

The subsequent full sweep passes **1,267 methods with 24 optional native skips**
and no failures (`/tmp/opencode/sqlglot-catchup-doris4141-full.log`). This sweep
configures Doris and Python chDB only; the other live-engine containers from the
previous round were already removed. Their earlier native results are not
reclassified as failures or as newly executed tests: the previous broader native
sweep passed 1,283 methods with four optional skips against these exact unchanged
core/metadata JAR bytes.

### Historical behavior-matrix recheck

The existing 92 vectors spanning 82 function names were rerun against 4.1.4.1.
All raw outputs match the committed 4.1.3 results exactly: **88 value results and
four expected errors** (two ARRAY_LENGTH cases and two ARRAY_TO_STRING cases).
No error was accepted by count alone; each is the same specific function-lookup
error from the baseline. Raw old/new SQL and results are retained in
`/tmp/opencode/sqlglot-catchup-doris4141-matrix.json`; the summary is in
`/tmp/opencode/sqlglot-catchup-doris4141-matrix.log`. This rechecks these exact
vectors, not the entirety of Doris or every older pairwise hazard dataset.

## Isolated runtime setup

The FE runs on a task-only internal Docker network; the BE shares its network
namespace. No host ports, bind mounts, peer checkouts or production databases are
used. FE/BE limits are 3/6 GiB and 4/6 CPUs; Java heaps are 1536/512 MiB.

This host requires two test-only startup accommodations: explicit JVM heaps and
`-XX:-UseContainerSupport` avoid the bundled JDK's cgroup-v2 discovery exception;
`SKIP_CHECK_ULIMIT=true` bypasses the BE launcher's production kernel preflight.
Docker still enforces the container limits. No host kernel setting, global Java
configuration or vendored SQL grammar is changed. This small isolated runtime
does not establish production sizing, performance or operational readiness.

The exact image digests, native version output and test logs—not tags alone—are
the execution evidence. Historical 4.1.3 probe datasets retain their original
provenance; this upgrade must not relabel unrerun historical evidence as 4.1.4.1.
The two task containers and their internal network were removed after verification;
all recorded native/matrix logs remain retained.
