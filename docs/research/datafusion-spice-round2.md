# DataFusion and Spice metadata and first correctness fixes

Date: 2026-10-30. Branch: `spice-support`.

This round supplies DFS-003's versioned metadata and the first native-proven corrections
in DFS-004. DFS-004 remains open for the wider function-collision and grammar audit.
The production Spice dialect and its IDE integration remain subsequent steps.

## Metadata

- DataFusion 55.2.0: 234 canonical definitions representing 320 registered name/kind pairs.
  Alias identity, native signature constraints, and volatility come from the pinned Rust
  registry exporter. Only fully matched finite signatures become catalog overloads.
- Spice v2.4.0: 447 registered name/kind pairs. Names remain separate because SQL introspection
  does not establish canonical alias identity. Scalar/table dual registrations remain separate.
  Source-backed overrides mark vector_search, text_search, rrf, and rerank scalar registrations
  as `SCALAR_STUB`, rather than ordinary executable scalar implementations.

`DATAFUSION_METADATA`, `SPICE_METADATA`, and both function catalogs expose version selection,
documentation sources, keyword inventories, settings, and successfully probed SQL type aliases.
`bundledMetadata` recognizes both products. Core DataFusion exposes its matching catalog;
the future Spice dialect will wire its catalog in DFS-005.

`DatafusionProfileEvidence` and `DatafusionFunctionEvidence` retain descriptions, syntax
examples, representative signatures, captured native constraints/volatility, setting defaults,
capture hashes, and build/profile limitations. SQL signature examples are deliberately separate
from `FunctionDef.overloads`. Missing samples do not mean zero arity. Table routines have open
schemas until their argument-aware shape work is implemented. These are additive types; no
existing public data-class constructor or copy shape changed.

Raw captures retain parameter modes, ordinals, variadic markers, registry constraints, and
source status. Keyword reservation is UNKNOWN because sqlparser's inventory does not define
reservation in every dialect/context. Parameterized/nested type probes remain raw evidence
rather than being advertised as fixed named types. Snapshot selection matches a numeric
version, not the embedding's exact build features, models, or user functions.

## Shared correctness changes

The DataFusion parser/generator now preserves regex flags, to_char formats, variadic map
arguments, array_sort direction/null placement, and date_bin origin expressions. Ordered
string_agg places its delimiter correctly, including DISTINCT, without mutating the AST
or moving an ORDER BY out of a scalar subquery argument.
Tagged and untagged dollar strings stay intact across statement boundaries. Dollar parameters
become Parameter nodes instead of columns. E-string escapes, including literal backslashes
and quotes, hex literals, and array containment operators retain native behavior. Default
NULL ordering now models `nulls_max`, including NULLs first for descending order.

Map and array_sort use lossless generic calls where the shared typed AST has a different
argument contract. Arrow-specific inference remains DFS-006 work. Spice-only operator and
function semantics are not forced into the upstream profile.

## Native evidence

The original baseline artifacts are unchanged. Round-two evidence is in
`vendor/data/datafusion-spice/round2/`. Its 85 inputs include the original 76 and nine extra
controls for escapes, dollar tags, nested/DISTINCT aggregation, map forms, and sort/date options.
Forty-nine cases permit bounded execution; the other 36 are parser-only.

All 31 executions accepted by the upstream reference have matching original/generated
value, type, name, and nullability observations. None produces a changed result or rejected
generated query. The other 18 cases include absent Spice functions/namespaces, native-invalid
inputs, and the unsupported upstream `#` spelling. Matching observations use the reference
tool's documented codec and do not certify arbitrary values.

Spice has 34 matching observations, two changed results, two rejected generated queries,
one missing generated query, and ten rejected originals. Remaining actionable cases are
LIKE argument ordering, date_add arity, JSON arrow generation, truncate semantics, and `#`.
These belong to its specialization. Baseline docs/runtime discrepancies still apply.

## Reproduction

```sh
cargo build --locked --manifest-path tools/datafusion-reference/upstream/Cargo.toml --target-dir build/datafusion-reference -j 4
python3 tools/capture_datafusion_spice_metadata.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference --spiced /tmp/opencode/spice-2.4.0-runtime/spiced
python3 tools/generate_datafusion_metadata.py
python3 -m unittest discover -s tools -p 'test_generate_datafusion_metadata.py'
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/round2/cases.json vendor/data/datafusion-spice/round2/brikk-observations.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference --cases vendor/data/datafusion-spice/round2/cases.json --brikk vendor/data/datafusion-spice/round2/brikk-observations.json --output vendor/data/datafusion-spice/round2/datafusion-observations.json
python3 tools/probe_datafusion_spice.py --spice-parser build/spice-parser-reference/debug/brikk-spice-parser-reference --cases vendor/data/datafusion-spice/round2/cases.json --brikk vendor/data/datafusion-spice/round2/brikk-observations.json --output vendor/data/datafusion-spice/round2/spice-parser-observations.json
python3 tools/probe_datafusion_spice.py --spiced /tmp/opencode/spice-2.4.0-runtime/spiced --cases vendor/data/datafusion-spice/round2/cases.json --brikk vendor/data/datafusion-spice/round2/brikk-observations.json --output vendor/data/datafusion-spice/round2/spice-runtime-observations.json
```

The Spice executable must match the checksums in the
[reference-tool instructions](../../tools/datafusion-reference/README.md). Captures start
their own temporary runtime and use no user connections or configured models.

## Validation and next work

The full core suite passes 1,321 tests, including Java 17/ABI, source-range, shape, lineage,
and other-dialect corpus gates. Metadata passes 128 tests. Existing DataFusion gates retain
275 identity cases, two transpile directions, and 1,208 parse cases without weakening ledgers.
New tests cover native options, AST roles, boundaries, mutation-free rendering, conservative
metadata, and deterministic generation.

Next: finish DFS-004's collision/grammar audit, then implement the Spice child under DFS-005.
Dynamic search schemas, full parameter ranges, provider-specific DML, Flight SQL JDBC, and
IDE qualification remain attached to their later plan steps. No IDE API usage or published
artifacts changed in this round.
