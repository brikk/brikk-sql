# CHSEM-030-A non-recursive CTE type propagation

This is the approved narrow slice of CHSEM-030, not completion of all catalog and
complex-query analysis. It carries the existing bounded native type rules through
ordinary non-recursive CTE outputs and supported derived relations. The original
counterexample now has a native conditional `UInt16` result:

```sql
WITH c AS (SELECT CAST(1 AS UInt8) AS x)
SELECT x+2 FROM c
```

`cte-context-refinements.json` records the change from RC1's `Nullable(UNKNOWN)`
frontend result. Generic `annotateTypes` retains that earlier result; only the
explicit native semantic APIs gain the CTE proof.

## Supported slice

- CTE chains using preceding definitions, including multiple references with
  different relation aliases, qualified columns and nested WITH shadowing.
- Named projections, identity/renamed aliases, supported computed projections and
  simple computed alias reuse when no physical-column ambiguity exists.
- Bare and qualified stars when the input column list is completely known and
  output names are unique. Stars with EXCEPT/REPLACE modifiers are not expanded.
- Supported nested derived SELECTs and caller-provided ClickHouse MappingSchema
  facts. Generic annotated type slots are not a native type source.
- Existing supported casts, numeric arithmetic, nullable inputs and array/map
  callbacks. Array-producing CTEs can feed later lambdas or later CTEs.
- Filters, ordinary GROUP BY/DISTINCT, ORDER BY and LIMIT around those projections.
  This does not add native aggregate-function contracts.
- Qualified inputs of simple INNER/CROSS joins, including joins inside a CTE.
  Unqualified ambiguity is not resolved by guessing server preference settings.
- The materialized CTE spelling for the same supported projection types, without
  claiming materialization/execution/correlation semantics.
- Existing explicit statement/script settings and query-local override rules.
  A CTE does not establish shared state between calls or statements.

## How the proof is bounded

The native pass uses the existing structural scope builder for lexical CTE/table
bindings. It evaluates demanded output types using native type rules and supplied
schema facts, memoizes outputs within one analysis and guards dependency recursion
at 128 active queries. It does not use generic type annotations as proof or rewrite
the AST. Cancellation checks cover traversal and output evaluation.

Output typing is conditional, not execution. Inferring an input type does not
diagnose or execute an unused WITH definition. Projection inference can establish
an input type for downstream diagnostics, but does not add a general evaluator or
CTE constant folding. For example, a column known to have an integer type is not
automatically treated as a known integer value for division-error diagnostics.

The provenance APIs report proved consumed columns/operations as
`NATIVE_CONDITIONAL`. Unknown outputs never get promoted merely because generic
annotation populated a slot. SQL, metadata/source ranges, AST ownership, serde,
generic annotation policy and public JVM signatures remain unchanged.

## Remaining boundaries

Recursive CTEs, forward/self references, set-operation outputs, outer/SEMI/ANTI/ASOF/
NATURAL/USING joins, modified stars, explicit relation/CTE column-name lists,
grouping sets/rollup/cube/totals and incomplete/ambiguous source schemas remain
outside this slice. Some native-valid examples deliberately remain unknown.
Unknown aggregate/UDF/other function outputs stay unknown; this round does not
expand their overload catalogs. Correlated/lateral resolution and complete alias
preference behavior are not certified. Full CHSEM-030 stays open for those needs.
Conflicting query-local division/Decimal modes between a CTE definition and a
consumer also stay unknown instead of reusing a proof under the wrong settings.
Database/catalog-qualified column or star paths are not rebound to CTE aliases.

## Evidence

`cte-context-cases.json` has 28 finite cases, with 20 qualified interactions and
eight deliberately deferred controls. `cte-context-script-cases.json` has six
whole-session settings cases, with four qualified and two intentionally unknown
after DEFAULT or under conflicting definition/consumer modes. Native
original/generated results/errors are captured separately
on both digest pins. Empty result sets are not counted as type observations.
The enabled semantic API pin remains exactly 26.10.2.103; control-version errors
are recorded as expected outcomes, not frontend type matches.

`cte-context-sources.json` retains tagged/hashed Apache-2.0 QueryAnalyzer and
IdentifierResolver excerpts for lexical CTE resolution, projection-column types
and ambiguity guards. Arithmetic and higher-order contracts reuse their earlier
source/native evidence. No old capture or parity ledger is replaced.

JVM tests also cover schema-backed sources, generic-slot contamination, unused
definitions, positioned downstream diagnostics, copy/serde, script isolation,
ownership and cancellation. The packaged Java 17 API smoke now includes ordinary
CTEs, chained arithmetic, arrays and star/pass-through results.

## Final qualification

All modules build. Core passes 1,545 tests and other modules pass 455, with 61
optional engine assumption aborts and zero failures. The sequential IntelliJ gate
passes 817 tests on 261.25134.95. Python discovery passes 214 tests with one opt-in
skip; a separate enabled release-model run passes all 38 release tests. Both CTE
frontend reports regenerate byte-identically, and source/test fingerprints remain
unchanged throughout final qualification.

New unsigned local SQL `0.23.0-rc.2` and IntelliJ `0.3.1-rc.2` candidates pass all
15-coordinate alignment, verifier, Java 17 and IDE audits. All 60 payloads/JAR
integrity and eight representative packaged sources are checked. The Java 17
packaged API smoke passes its new CTE cases. RC1 did not contain this change and
must not be used to claim CTE qualification. Logs and SHA-256 manifests are under
`build/chsem-030-final-gate/`. Nothing is published remotely or adopted in consumers.
