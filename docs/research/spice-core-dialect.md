# Spice core dialect

The canonical core dialect name is `spice`. `SpiceDialect` extends the shared DataFusion
implementation and selects the captured v2.4.0+models catalog. DataFusion and its
`arrow-datafusion` alias keep their own Generic parser profile and metadata.

## Implemented

- Case-preserving SQL identifiers, matching the measured runtime setting.
- PostgreSQL `#` XOR and `^` exponentiation, with distinct ASTs and nested power calls
  retaining their grouping.
- JSON `->`, `->>`, and `?` on Spice's string-backed JSON. Keys and indexes remain native
  expressions rather than being rewritten to an unsupported JSON function family.
- Complete native argument lists for search, RRF, reranking, AI/decision/embedding calls,
  JSON shredding, Spark-compatible functions, and partition transforms. Two-argument
  vector_search no longer picks up the incompatible BigQuery AST signature.
- Structured EXPLAIN options, SHOW, PREPARE, EXECUTE, and RESET. Native CREATE/ALTER/DROP,
  INSERT/UPDATE/DELETE/MERGE, external-table and COPY forms reuse common nodes. Cayenne
  CLUSTER BY remains a structured property. STORED AS rendering is shared with DataFusion.
- AST copy/serialization and explicit cross-engine refusal for unproved opaque Spice calls,
  JSON operators, and utility nodes. Existing typed common-expression translations remain
  available; this is not paired-engine semantic certification.

`SpiceExplain.executesQuery` distinguishes ANALYZE from planning, including explicit OFF.
PREPARE and RESET modify session state; EXECUTE has unknown effects without its prepared
body. EXPLAIN without ANALYZE is not a promise of side-effect-free planning, and no native
planning or execution was added to background IDE validation.

## Profile boundaries

The captured PostgreSQL profile rejects wildcard EXCEPT/EXCLUDE/REPLACE/ILIKE/RENAME and
backtick identifiers. Current unversioned docs describe some forms that this release's
default profile does not accept. A server configured with a different parser dialect needs
a separate profile rather than silently changing these defaults.

`?` remains available as client bind syntax, separate from JSON containment. The raw pinned
PostgreSQL parser rejects bare question placeholders; Flight SQL/JDBC parameter handling
is a later transport qualification task. Dollar parameters have their own nodes.

Function registration does not establish configured model availability. Search/AI cases are
parser-only in the fixtures, with no external model calls. DFS-006 adds
[Arrow types, call-specific search schemas, argument roles, parameters and lineage](datafusion-spice-analysis.md).
Connection harvesting remains later work. Provider-specific DDL/DML and external file
I/O have parser evidence only. PREPARE/EXECUTE session lifetime has not been driver-qualified.

This is development-branch core/metadata support with
[shared IDE identities and parser profiles](datafusion-spice-ide-parsing.md).
Consumer plugin registration, completion/inspection integration, Flight SQL JDBC qualification,
and publication have their own remaining plan steps.

## Reproduce the native checks

Inputs and observations are in `vendor/data/datafusion-spice/round4/`. They include the
previous round's scalar/query cases plus Spice operators, JSON table functions, utility
statements, nested search/AI calls, and negative controls. The parser tool and released
runtime are pinned and checksum-verified as described in
[the reference-tool guide](../../tools/datafusion-reference/README.md).

Of 120 inputs, all 104 accepted by the pinned native parser regenerate into SQL it accepts,
with structured Brikk nodes rather than Command fallback. The only intentional acceptance
difference is client `?` binding syntax. Of 61 bounded execution inputs, all 51 accepted
originals retain their captured values and types after regeneration. Rejected originals
remain visible. Search/AI, writes, and EXPLAIN execution are not run by this capture.

```sh
./kotlin run -m dialect-audit -- spice vendor/data/datafusion-spice/round4/cases.json vendor/data/datafusion-spice/round4/spice-brikk.json
python3 tools/probe_datafusion_spice.py --spice-parser build/spice-parser-reference/debug/brikk-spice-parser-reference --cases vendor/data/datafusion-spice/round4/cases.json --brikk vendor/data/datafusion-spice/round4/spice-brikk.json --output vendor/data/datafusion-spice/round4/parser-native.json
python3 tools/probe_datafusion_spice.py --spiced /tmp/opencode/spice-2.4.0-runtime/spiced --cases vendor/data/datafusion-spice/round4/cases.json --brikk vendor/data/datafusion-spice/round4/spice-brikk.json --output vendor/data/datafusion-spice/round4/runtime-native.json
```

The full core suite passes 1,338 tests, including 12 Spice tests, the existing DataFusion
gates, public ABI/Java 17 contracts, and other-dialect regression suites. Native acceptance
and runtime result comparisons are separate evidence, and preserve rejected originals.
