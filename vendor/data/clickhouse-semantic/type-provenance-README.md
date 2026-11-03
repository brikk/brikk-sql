# CHSEM-024 per-node type evidence

The additive `annotateClickhouseSemanticTypesWithProvenance` and
`annotateClickhouseScriptSemanticTypesWithProvenance` APIs expose external snapshots
of the bounded native proof pass. Existing statement/script APIs, constructors,
generic typing and serialized AST formats are unchanged.

Every walked node has a `ClickhouseNodeTypeFact` with its process-local object ID,
captured ClickHouse type spelling and one of these evidence categories:

- `NATIVE_CONDITIONAL`: the native proof pass recorded the exact final type. This
  assumes supplied schema/settings and successful dispatch/execution. It is not a
  successful-execution verdict, and can coexist with division-by-zero diagnostics.
- `GENERIC_FALLBACK`: a type exists but no separate matching native proof was
  recorded. This includes generic propagation and conservative false negatives
  such as unproved wrappers. Equal spelling alone never establishes a native proof.
- `UNKNOWN`: no usable type was established. Function values retain this category;
  their callable result facts are not silently treated as node value types.

```kotlin
val result = annotateClickhouseSemanticTypesWithProvenance(ast, "26.10.2.103")
val fact = result.provenance.factFor(node)
if (fact?.evidence == ClickhouseTypeEvidence.NATIVE_CONDITIONAL) {
    // fact.typeSql is a conditional native type, not acceptance or execution evidence.
}
```

Statement annotation mutates the supplied AST just like the existing API. Explicit
script mode returns independently owned ASTs, positioned diagnostic reports and
per-unit provenance; framing/parsing failures have no provenance. Preceding SET
facts are carried only in explicit script mode, and the exact native pin remains
mandatory. Parser acceptance never removes a semantic error.

Snapshots contain strings/IDs, not AST or mutable DataType references. Copy and
serde create new object identities and do not transfer the report. Do not persist
or rebind facts by SQL equality or tree position. Changing SQL, bindings, settings
or type slots invalidates the snapshot, even if a node retains its identity.
Re-run analysis after mutation. `factFor` is an identity lookup, not a freshness
validator. Independent calls and script units must not share reports.

Tests distinguish CHSEM-023 G01/G02 generic multiplication from native addition,
unknown UDFs and function values, and verify conditional proofs alongside errors,
explicit settings isolation, ownership/copy/serde, cancellation and exact-pin
behavior. This round introduces no new native typing rules or native observations.
The closeout captures remain the native reference, not a newly executed matrix.

Validation: focused provenance/constructor tests pass; the full JVM/ABI regression
passes 1,921 tests with 61 optional integration assumption aborts and zero failures.
Python discovery passes 139 tests with one opt-in skip on the unchanged SQLGlot pin.
The sequential IntelliJ gate passes 757 tests on 261.25134.95; other IDE versions
were not tested. No compatibility warnings, corpus ledgers or optional skips were
suppressed to qualify this change.
