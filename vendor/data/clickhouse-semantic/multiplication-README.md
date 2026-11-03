# CHSEM-025 standalone primitive multiplication

Native-only typing now visits standalone primitive `*` and resolved unqualified
`multiply` calls, rather than relying on a surrounding lambda, addition or division
to trigger the proof pass. Nested operations, supported array/map callbacks,
schema-backed inputs and supported derived-relation propagation reuse the same
conditional type facts. Generic `annotateTypes` is unchanged.

The tagged `MultiplyImpl` uses the same `ResultOfAdditionMultiplication` trait as
addition. Integer signedness is the union of the input signs; storage doubles below
64 bits and stays at the maximum input width at 64/128/256 bits. Admitted Float32/64
pairs produce Float64. Float combined with Int128/UInt128/Int256/UInt256 remains
unproved, matching the native refusal boundary. Ordinary nullable inputs propagate
nullability; constant NULL dispatch remains outside this contract.

The finite G01/G02 witnesses now have native-conditional proofs:

- `SELECT 1*2`: `UInt16`, previously generic `Nullable(Int32)`.
- `SELECT 1e0*(2e0+3.0)`: `Float64`, previously generic `Nullable(Float64)`.

`multiplication-refinements.json` names the stronger results. Original closeout
fixtures and captures stay unchanged, and regression tests replay them with those
explicit refinements. The new native/typed evidence is separate from that history.

Unsupported primitive overloads cannot inherit a guessed generic result. Name
shadowing, qualified/member calls, caller-owned annotator rules, constant NULL,
Boolean, String and TryCast inputs stay outside native certification. Existing
contextual Decimal contracts remain available where they were previously proved;
standalone Decimal multiplication and overflow are not newly qualified here.
No new semantic rejection rules or general evaluator are introduced. Wraparound
values and conditional metadata are separate from execution success.

Evidence:

- `multiplication-sources.json`: tagged/hashed NumberTraits, MultiplyImpl,
  BinaryOperationTraits and default nullable-dispatch implementation excerpts.
- `multiplication-types/`: 1,440 ordered-pair types per pin over operator/builtin,
  integer widths through 256 bits, Float32/64 and four nullable profiles. Expected
  types are captured from the engine, not computed by the frontend.
- `multiplication-cases.json`: 19 finite success/boundary cases, including six
  intentionally unknown cases. Original/generated value/error outcomes run on
  both pins, retaining version-dependent TryCast behavior.
- `multiplication-script-cases.json`: six integrated settings/lambda/map/derived
  relation witnesses. Shared whole-session capture records original and generated
  outcomes with fixture/frontend hashes, including 26.8's refusal of the new
  integer-division setting. Only the exact enabled 26.10 pin certifies frontend
  script types; expected control-version errors are not successful type matches.

The CHSEM-024 provenance APIs expose the new operation proofs. Unproved wrappers or
other generic results are still conservatively labelled `GENERIC_FALLBACK`, and
mutations still require fresh analysis. This does not make every populated node
type native-exact or turn empty diagnostics into acceptance.

Validation: the full JVM/ABI gate passes 1,926 tests with 61 optional integration
assumption aborts and zero failures. The three new frontend reports regenerate
byte-identically. Full Python discovery passes 143 tests with one opt-in skip on
the unchanged SQLGlot pin. No historical capture, generic parity ledger, public
constructor or toolchain floor was changed.
The sequential IntelliJ gate passes 757 tests on 261.25134.95; other IDE versions
were not run. Release-candidate qualification follows after main reconciliation.
