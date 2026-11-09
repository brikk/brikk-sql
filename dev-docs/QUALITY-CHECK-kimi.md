# Quality check (Kimi), 2026-11-09

A review of the current working tree, by reading the code and running the available offline
suites, not by comparing revisions. It agrees with the [Grok](QUALITY-CHECK-grok.md) and
[Fable](QUALITY-CHECK-fable.md) checks: this is a respectable, substantially well-engineered
codebase, and the SQLGlot port should keep its shape.

This note is a focused correctness follow-up, not another cleanup or rewrite plan. Its main
concerns are unstable identity hashing, dialect-insensitive type caching, failure fallbacks
that can look like authoritative answers, and a few state/resource ownership problems.
Nothing here requires splitting `Parser.kt` or `Generator.kt`, retyping `Expression.args`,
or reformatting the port.

The review did not change production code. Every location below was checked against the
current source. Line numbers are a navigation aid, not a stable contract.

## How to read this

- **Behaviour**: a caller, editor, or corpus gate can observe a different result. Add a
  focused regression test and review any corpus-ledger difference.
- **API decision**: the safest behaviour needs a small explicit decision about an existing
  public contract. Follow the binary-compatibility rule in [AGENTS.md](../AGENTS.md).
- **Resource**: primarily lifetime, bounding, or cleanup behaviour; still add a regression
  test with a controlled fake where practical.

Paths beginning `ast/`, `generator/`, `optimizer/`, `parser/`, or `shape/` are under
`brikk-sql/src/dev.brikk.house.sql/`. Other paths are repository-relative.

## Summary

| ID | Area | Suggested change | Risk |
| --- | --- | --- | --- |
| KQ-01 | Optimizer | Make `IdentityKey` hash by identity | Behaviour |
| KQ-02 | Schema | Include the effective dialect in the type-cache key | Behaviour |
| KQ-03 | Verifiers | Do not turn unexpected parser failures into `Rejected` | Behaviour |
| KQ-04 | Shape | Narrow silent analysis fallbacks | Behaviour |
| KQ-05 | Shape | Separate the mutable public AST from cached derived facts | Behaviour / API decision |
| KQ-06 | Generator | Reset synthetic-alias state for each top-level generation | Behaviour |
| KQ-07 | ClickHouse | Bound worker growth and reject oversized input before hex expansion | Resource |
| KQ-08 | PostgreSQL | Do not ignore statement/transaction cleanup failures | Resource / Behaviour |

## 1. Correctness fixes

### KQ-01. Make `IdentityKey` hash by identity

`optimizer/Scope.kt:65-69` defines an identity key whose equality is reference equality:

```kotlin
override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
override fun hashCode(): Int = value.hashCode()
```

For an `Expression`, the second line is a structural hash over mutable arguments
(`ast/Expression.kt:57-64, 496-517`). If a node changes after insertion, the same instance
can move to a different logical hash bucket and become unreachable. The wrapper is used in
lineage caches and in the expression-keyed subquery map at
`optimizer/Lineage.kt:485-493`; `Scope.refCount()` also returns these keys to callers.

**Suggested change**

Use `System.identityHashCode(value)`. That matches `===` for every type accepted by the
wrapper, including `Scope` and lineage `Node`, rather than only expressions. The existing
`Expression.objectId` is useful provenance, but is not a general replacement for identity
hashing of an `Any` wrapper.

**Regression tests**

1. Put `IdentityKey(expression)` in a `HashMap`, mutate the expression through `set`, and
   assert that a fresh `IdentityKey` around the same instance still finds the value.
2. Assert that two structurally equal but distinct expressions remain separate entries.
3. Add a lineage case whose selected subquery is transformed between scope-map construction
   and lookup if that ordering is reachable in the optimizer; otherwise the small map test
   is sufficient to pin the contract.

This is a one-line implementation change with a high-value invariant test. It does not
require changing the AST's structural `equals`/`hashCode`, which other code relies on.

### KQ-02. Include the effective dialect in the type-cache key

`optimizer/Schema.kt:224` declares:

```kotlin
private val typeMappingCache = HashMap<String, DataType>()
```

`getColumnType` accepts a dialect override and passes it to `toDataType`
(`optimizer/Schema.kt:353-376`), but `toDataType` caches only by `schemaType`
(`optimizer/Schema.kt:510-523`). The selected dialect affects both parsing and identifier
normalization. The first lookup therefore determines the `DataType` returned for the same
text under every later dialect override.

**Suggested change**

Compute `val d = dialect ?: this.dialect` before the cache lookup and key the cache by at
least `(schemaType, d)`. Using the effective dialect object also covers its
`supportsUserDefinedTypes` setting; no separate boolean key is needed. Keep the existing
schema-dialect default and normalization behaviour unchanged.

**Regression tests**

- On one `MappingSchema`, request the same column type under two dialects whose
  `dataTypeFromStr` results differ, and assert each result against an independent direct
  `dataTypeFromStr` call.
- Repeat in the opposite order on a fresh schema. Both orders must produce the same two
  answers.
- Keep the existing default-dialect and unknown-column tests in `SchemaTest.kt` unchanged.

Do not replace this with a global cache: schema instances have their own dialect defaults,
and a process-wide mutable AST cache would add ownership and eviction problems.

### KQ-03. Do not turn unexpected parser failures into `Rejected`

The verifier contract says that exceptions which are not engine verdicts propagate
(`brikk-sql-verify-api/src/dev.brikk.house.sql.verify/SqlVerifier.kt:14-17`). Two adapters
currently hide unexpected failures as SQL rejections.

#### Doris

`brikk-sql-verify-doris/src/dev.brikk.house.sql.verify/DorisVerifier.kt:44-53` catches an
`InvocationTargetException` and returns `Rejected` for every target `Exception`.
`isParseException` currently changes only the message, not whether the exception becomes a
verdict. A parser `IllegalStateException`, linkage-related runtime failure, or cancellation
can therefore be reported as invalid SQL.

Make `verify` and `verifyExpression` follow the policy already used by `verifyScript` at
`DorisVerifier.kt:118-123`:

- return `Rejected` only when `isParseException(cause)` is true;
- otherwise throw the original cause;
- preserve the existing positioned `VerifyError` conversion for real parse exceptions.

Add a small internal test seam if necessary to invoke the reflected parse path with a
controlled parser. Do not add a public test-only constructor. Assert that the exact
unexpected exception instance propagates and that a Doris `ParseException` still becomes a
positioned rejection.

#### ShardingSphere

`brikk-sql-verify-shardingsphere/src/dev.brikk.house.sql.verify/ShardingSphereVerifier.kt:43-52`
catches every non-`CancellationException` `Exception` and returns `Rejected`.

Identify the syntax-exception type or closed set of types thrown by the pinned
ShardingSphere parser and catch only those. Unexpected runtime failures should propagate,
as the shared contract requires. If the pinned library genuinely makes syntax rejection
indistinguishable from internal failure, retain the broad mapping only as a documented
advisory-tier exception and narrow the KDoc rather than claiming the general contract is
met. That documentation fallback is preferable to inventing another SQL verdict.

Add tests for each deliberately caught exception category and one unexpected runtime
exception. Run the oracle corpus gate afterward and review, rather than blindly refresh,
any ledger movement.

This overlaps [FQ-12](QUALITY-CHECK-fable.md); the important invariant is that an
implementation failure must not look like evidence that the user's SQL is invalid.

### KQ-04. Narrow silent analysis fallbacks in `SqlFragment`

Two broad catches in `shape/SqlFragment.kt` discard too much information:

- `unmappableFunctions` (`:265-276`) catches every `Exception` from transpilation and target
  reparsing, then scans the source AST. Its KDoc describes a fallback for target parse
  failure, not for cancellation, unsupported execution boundaries, or implementation bugs.
- `contract` (`:685-693`) catches every `Exception` from `columnDependencies` and returns
  `emptyMap()`. A genuine analysis failure is then indistinguishable from a query with no
  column dependencies.

**Suggested change**

- In `unmappableFunctions`, fall back only for the expected target `ParseError`/`TokenError`
  path. Let generation failures, cancellation, and unexpected runtime exceptions propagate.
- In `contract`, preserve the documented empty result only for known, expected lineage
  refusals such as an unexpandable star. Enumerate those exception types at the call site or
  in a small internal helper; propagate everything else.
- If callers truly need dependencies-or-unknown as data, model that explicitly rather than
  overloading an empty map. Adding a field to the public contract is an API decision and
  must retain the old JVM constructor and `copy` shapes required by `AGENTS.md`.

**Regression tests**

Cover a normal unexpandable-star contract, a deliberate target reparse failure, and an
injected unexpected failure/cancellation. The first keeps its current result; the latter
must not silently become a successful-looking partial answer.

## 2. State and resource ownership

### KQ-05. Separate the mutable public AST from cached derived facts

`shape/SqlFragment.kt` exposes a mutable AST through `val ast` (`:111`) while caching values
derived from it: `stages`, `scalarParams`, `tableSlots`, and `sourceTables`
(`:295-304, 371-391`). `Expression.args` is mutable, so this sequence can return stale
facts:

1. read `fragment.sourceTables`;
2. mutate `fragment.ast`;
3. read `fragment.sourceTables` again and receive the first result.

The same issue affects parameters and slots. Tests currently prove that analysis does not
mutate the author's AST, but not what happens when the caller does.

**Suggested change**

Prefer a private parsed root used by all internal derivations, with the public `ast` accessor
returning a defensive deep copy. This preserves source compatibility while preventing caller
mutation from invalidating fragment caches. Audit callers for accidental reliance on the
identity of repeated `ast` reads before making the change.

If stable AST identity is an intentional public feature, keep it and instead provide an
explicit mutation/replacement boundary that invalidates every derived cache together. Do
not fix only one lazy property. Merely documenting "do not mutate" is the weakest option
because the exposed type continues to invite mutation.

**Regression test**

Pin whichever contract is chosen: either mutating the returned AST leaves the fragment's
own facts unchanged, or an explicit mutation operation refreshes all derived facts. Include
parameters, table slots, and source tables in one test so a partial invalidation fix fails.

### KQ-06. Reset synthetic-alias state for each top-level generation

`generator/Generator.kt:121-123` stores `nextNameCounter` on the generator instance.
`tablealiasSql` consumes it for an unnamed alias (`:1531-1550`), but `generate()` resets
only diagnostics and source-map state (`:397-415`). Reusing a generator for identical input
can therefore emit `_t0` on one call and `_t1` on the next.

**Suggested change**

Reset `nextNameCounter` at the start of each top-level `generate()` call. If nested
`generate()` calls are discovered, use per-run state rather than resetting a shared counter
recursively. This is a deliberate determinism improvement for reusable Kotlin generators;
it does not require moving or restructuring any SQLGlot renderer.

**Regression tests**

- Generate one AST containing an unnamed table alias twice with the same generator and
  assert byte-identical SQL.
- Generate two unrelated statements sequentially and assert that the second does not depend
  on the first.
- Run the existing generator and transpile corpora. They normally create fresh generators,
  so their expected output should not change; investigate any difference rather than
  accepting a new alias ledger entry automatically.

### KQ-07. Bound ClickHouse verification work and preflight input size

`brikk-sql-verify-clickhouse/src/dev.brikk.house.sql.verify/ParseOnlyClickhouseVerifier.kt`
has two independent resource gaps:

- `verify` converts the entire input to UTF-8 and hex before checking `MAX_HEX_LENGTH`
  (`:52-55`). Oversized editor input therefore incurs the allocation it is meant to reject.
- `WORKERS` is a global `Executors.newCachedThreadPool` (`:200-202`). Connection or driver
  calls that ignore interruption can retain threads indefinitely, and cancellation can add
  another task through `Call.cancel` (`:97-104`).

**Suggested change**

1. Add a cheap conservative UTF-16-length rejection before encoding, then retain the exact
   UTF-8/hex-length check for inputs that pass the preflight. Do not replace the exact check
   with a character-count approximation for non-ASCII SQL.
2. Replace the cached pool with a bounded executor and a deliberate saturation result.
   Bound both verification and cancellation work; do not create an unbounded second pool
   for cancellation.
3. Implement this together with the client-side deadline in
   [FQ-14](QUALITY-CHECK-fable.md), since a bounded pool alone does not release a caller
   waiting on a hung `connect()`.

**Regression tests**

Use fake connections and latches, not a real server, to prove that:

- oversized input returns `Unverified` without calling `connect`;
- many non-interruptible calls cannot grow threads without bound;
- saturation returns the chosen non-verdict result promptly;
- cancellation still closes a connection that opens after cancellation, preserving the
  existing test at `ClickhouseAvailabilityTest.kt:62-91`.

### KQ-08. Do not ignore PostgreSQL cleanup failures

`brikk-sql-oracle/src/dev.brikk.house.sql.verify/PostgresVerifier.kt` ignores every
`DEALLOCATE` failure after a successful prepare (`:150-152`) and ignores rollback and
auto-commit restoration failures in the direct-execution fallback (`:159-173`).

The SQL may already have received a valid parse verdict, but a broken connection should not
silently remain in the reusable verifier. In particular, failed rollback followed by
restoring auto-commit is not proof that the "always rolled back" safety property held.

**Suggested change**

- Preserve the original SQL exception when cleanup also fails, attaching cleanup failures as
  suppressed where appropriate.
- After cleanup failure on an otherwise successful call, close/discard the connection and
  return `Unavailable` with the cleanup failure, or mark the verifier unavailable for later
  calls. Do not return a clean lifecycle state while reusing questionable connection state.
- Make rollback failure explicit before attempting auto-commit restoration; do not claim
  that rollback succeeded merely because no exception escaped `runCatching`.

Add controlled JDBC tests for `DEALLOCATE`, rollback, and auto-commit restoration failures.
The existing real-engine tests prove ordinary DDL leaves no residue; they do not exercise
these failure paths.

For the related Doris class-loader lifetime and general verifier close semantics, follow
[FQ-13](QUALITY-CHECK-fable.md) rather than creating a second lifecycle design here.

## Test coverage and confidence

The following was actually run for this review:

- `python3 -m unittest discover -s tools -p 'test_*.py'`: **251 passed, 1 skipped**.
- `./kotlin test -m brikk-sql --jvm-args="-Xmx1g -XX:ActiveProcessorCount=2"`:
  **1,880/1,880 passed**, none skipped.
- `./kotlin test --exclude-module=brikk-sql`: green; its reported task summaries were
  **226 passed/46 aborted** and **294 passed/12 aborted**. The aborts were optional
  live-engine/native fixtures, not additional passing coverage.

The IntelliJ platform contracts were not run because the shared native window fixture was
not configured. Full live Trino, Doris, MySQL, PostgreSQL, StarRocks, chDB, and the optional
signed DuckDB fixture coverage was unavailable. Those limits should remain visible in any
later claim that this checklist is complete.

The corpus policy is commendably explicit about its deferred coverage
(`brikk-sql/testResources/corpus-policy.json`): 2,657 named identity assertions lack direct
consumers, along with the listed base-fixture identities and transpile directions. Those
are disclosed gaps, not executed parity. Closing them is separate coverage work; do not
inflate the test totals above by counting them as run.

## Suggested order

1. **KQ-01 and KQ-02**: small, isolated correctness fixes with direct regression tests.
2. **KQ-03 and KQ-04**: restore truthful failure boundaries; review any corpus-ledger
   differences case by case.
3. **KQ-06**: make generator reuse deterministic without restructuring the generator.
4. **KQ-07**, together with FQ-14: bound the editor-facing ClickHouse path using fakes.
5. **KQ-08** and the lifecycle items already in FQ-13.
6. **KQ-05** after the public AST identity/copy decision is made explicitly.

After each group, run the core suite separately and then the remaining modules with the
commands above. Do not update known-failure ledgers unless the reviewed behaviour change
explains the new signatures.

## Non-goals

- Splitting the 11,095-line parser or 6,195-line generator merely to reduce file size.
- Rewriting the SQLGlot AST into a second, more idiomatic Kotlin representation.
- Running a formatter across the port or generated sources.
- Adding a facade over the existing parser/dialect entry points.
- Treating disclosed deferred corpus assertions as though they were already green.
