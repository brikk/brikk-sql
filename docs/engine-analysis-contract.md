# Engine analysis compatibility gate

This is a **consumer contract**, separate from generated SQLGlot parity fixtures.
Changing a reference pin must not silently change these expectations to match the
new oracle. The gate was checked against brikk-house commit
`3cc5b5a833f76284bcae5496539402ef8873489f` on 2026-10-10; its five SQL-consuming
modules use published `brikk-sql-jvm:0.18.0`. Tests live here and do not depend on a
sibling source checkout, Engine compiler internals, or Maven Local publication.

## What the peer actually calls

Peer paths are relative to `brikk-house/brikk-engine/`:

| Peer call | SQL-side protection |
|---|---|
| `brikk-engine-kotlin-compiler-plugin/.../analysis/SqlAnalysis.kt` `computeOutput()` | `EngineAnalysisContractTest`: exact ordered names, SQL types, nullability and quoting for empty/partial catalogs, known columns and unknown projections |
| The same analyzer's `applyTo()`, called by `fir/BrikkSqlCallRefinement.kt` | Recompute one generic pipe under trait-minimum and concrete inputs; retain extra concrete columns, repeat analysis without cache leakage, preserve the caller's source/AST |
| `analysis/ColumnValidation.kt` `validateColumns()` | Reproduce both `toStandardSql(inputs, expandStars=true)` and strict `qualify(..., inferSchema=false, validateQualifyColumns=true)`; cover slots, qualified catalog depth, foreign catalog quoting, aliases, CTE visibility and correlations |
| `analysis/TypeMap.kt` and trait checks | Keep known types, numeric widening and nullable outer-join columns; UNKNOWN is not evidence for a concrete trait; unknown nullability must not become an invented non-null proof |
| Already-compiled compiler-plugin calls | `EngineAnalysisAbiContractTest`: retain old `qualify`/`qualifyColumns` JVM descriptors and Kotlin `$default` bridges; exercise the old qualification bridge with `inferSchema=false` |

The tests additionally protect `stageShapes()`, schema-less lineage and slot
contracts, even though the audited Engine source does not call all those APIs
directly. Single-source lineage may identify a table without inventing a column
type; ambiguous ownership must remain unresolved.

SQL shapes do not themselves encode Engine's `Partial`/`Shape` marker distinction.
Engine determines whether a column set is closed and maps SQL types/nullability to
Kotlin getters. The peer's compiler tests must still run on candidate artifacts
before a migration is released; these tests protect the inputs to that compiler,
not Kotlin plugin execution or downstream diagnostic wording.

## Important two-step validation nuance

Engine does **not** call strict qualification directly on the authored SQL. It
first asks SQL for a disposable standard rendering with star expansion. With an
empty catalog, this preparation can infer the sole unknown source and attach its
qualifier. The subsequent `inferSchema=false` pass accepts that already-qualified
reference when no column evidence exists for the source. That is existing
best-effort behavior, **not proof that a database column exists**.

In contrast, strict qualification of a raw unqualified unknown column rejects it
when inference is disabled. A nonempty unrelated catalog also disables the
legacy automatic fallback. Tests cover these differences explicitly. Preserving
only `outputShape()` during migration is insufficient: `toStandardSql()` and its
`expandStarModifiers()` preparation path need an explicit compatible policy too.

The baseline quoted-slot case also carries known type/name/quoting but unknown
nullability after synthetic namespace binding. Its expectation remains null
(conservative nullable getter), not a claim that this path recovers every input
nullability proof.

## Policy and migration requirements

`InferencePolicyContractTest` exercises both `qualify()` and `qualifyColumns()`:

- `inferSchema=true`: keep the explicit legacy ownership fallback.
- `inferSchema=false`: do not infer missing ownership.
- `inferSchema=null`: preserve the existing empty-schema versus populated-schema
  automatic behavior.
- Complete schemas: enabled and disabled modes agree; nonexistent columns reject.
- Multiple unknown sources: none of the policies invents a unique owner.

The upstream-shaped overloads are now implemented and tested directly: omitting
the inference parameter selects no-inference behavior, while shape/lineage/preparation
facades explicitly choose the compatible legacy nullable entry point. The disabled
policy adopts upstream alias-expansion ordering; legacy true/null paths preserve
their old order. Brikk's optional-schema lineage contract remains intact instead
of imposing upstream's mandatory-schema API. The old JVM descriptors, default
bridges and argument-mask positions remain available for compiled consumers.

## Running the gate

```sh
./kotlin test -m brikk-sql --include-classes='*ContractTest'
./kotlin build
./kotlin test
```

The gate now has **38 test methods** after merging main's editor contracts, including the tokenizer constructor ABI check,
DuckDB comprehension and USING-star safeguards, and correlated UNNEST projection/
UNKNOWN evidence through two-step Engine preparation, and PostgreSQL JSON_VALUE
declared/default return evidence without fabricated nullability and optional-schema
UNNEST lineage compatibility. Most cases run under PostgreSQL,
DuckDB and Doris. These are hand-owned assertions, not current-output snapshots.

Mutation checks were performed and then reverted:

| Deliberate regression | Contract failures |
|---|---|
| Change automatic inference to always disabled | 3: nullable legacy policy, single-source lineage, and Engine's two-step preparation/validation |
| Force inference even when disabled | 5: old default-bridge behavior, explicit disabled policy, populated-schema default, empty-slot nullability safety, and unrelated-catalog validation |

Neither mutation is retained. Production entry points now use the explicit split
above; the shipped parity-oracle pin is unchanged pending the catch-up integration.
