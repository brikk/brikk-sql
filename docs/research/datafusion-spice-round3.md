# DataFusion registry-wide audit

DFS-004 is complete for the pinned DataFusion 55.2.0 profile. The audit enumerates all
315 registered spellings, including aliases, with seven positional arities and named
argument probes. Intentionally invalid arities are parser-only: they must not be silently
shortened into different calls. There were 610 argument-loss/reordering observations
before the fix and none afterward among accepted calls.

Native argument lists now use conservative typed mappings only where the shared AST has
the correct contract. Other calls preserve their full arguments and spelling. Catalog
aggregate kinds become AnonymousAggFunc rather than scalar Anonymous nodes. Quoted and
qualified names retain their identity; unknown native UDF names no longer receive a
different engine's translation. Cross-dialect SQUARE-to-power translation from the base
dialect still works. DISTINCT, ORDER BY, FILTER, and window modifiers remain structured.
Malformed empty arguments and comma-before-ORDER-BY are rejected instead of repaired.

This fixes additional collisions such as isnan/is_nan, signum/sign, to_hex/hex,
position argument order, and variance aliases. Native one-based arrays now have the
correct canonical index offset for cross-dialect generation. E-strings decode numeric
escapes and reject invalid Unicode, NUL, and non-ASCII byte escapes as the pinned sqlparser
does. The encoded-string policy is opt-in and copy-safe; existing tokenizer constructors
and other dialects retain their behavior.

## Evidence and scope

`vendor/data/datafusion-spice/round3/` contains the complete name inventory, before/after
argument audit, 132 typed-NULL execution probes, and 35 curated grammar/non-null controls.
All 123 accepted typed-NULL probes and all 30 accepted grammar execution controls match
their regenerated SQL. Rejected originals and parser-only probes remain distinct from
execution evidence. Samples are not universal semantic equivalence proofs, particularly
for dynamic and nested types with no primitive sample. The inventory records why those
names have parser-only coverage.

The grammar controls cover CTEs, joins, subqueries, set operations, grouping sets, aggregate
modifiers, windows, wildcard modifiers, date formats, numeric/string operators, array
indexing/slicing, and escaped literals. Statement/provider execution limits from the initial
inventory remain unchanged. Arrow-specific type inference and argument-dependent relation
schemas remain DFS-006 work, including improvements for generic native function nodes.

The full core suite passes 1,326 tests. Existing identity/parse ledgers remain unchanged.
Spice-specific semantics and utility statements are implemented separately in DFS-005.

## Reproduce

```sh
python3 tools/audit_datafusion_functions.py --output vendor/data/datafusion-spice/round3/function-cases.json
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/round3/function-cases.json build/datafusion-functions-after.json
python3 tools/audit_datafusion_functions.py --observations build/datafusion-functions-after.json --output vendor/data/datafusion-spice/round3/function-audit-after.json
python3 tools/audit_datafusion_functions.py --execution-only --output vendor/data/datafusion-spice/round3/function-execution-cases.json
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/round3/function-execution-cases.json vendor/data/datafusion-spice/round3/function-execution-brikk.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference --cases vendor/data/datafusion-spice/round3/function-execution-cases.json --brikk vendor/data/datafusion-spice/round3/function-execution-brikk.json --output vendor/data/datafusion-spice/round3/function-native.json
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/round3/grammar-cases.json vendor/data/datafusion-spice/round3/grammar-brikk.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference --cases vendor/data/datafusion-spice/round3/grammar-cases.json --brikk vendor/data/datafusion-spice/round3/grammar-brikk.json --output vendor/data/datafusion-spice/round3/grammar-native.json
```
