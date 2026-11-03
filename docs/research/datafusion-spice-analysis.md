# DataFusion and Spice analysis

DFS-006 adds analysis rules for the pinned DataFusion 55.2.0 and Spice v2.4.0 profiles.
The implementation uses the existing type annotator, relation scopes, qualification,
Engine shapes, parameter discovery and lineage APIs.

## Types

`dataTypeFromStr` accepts Arrow type text for these dialects, including unsigned integers,
decimal width/precision/scale, timestamp units and zones, intervals, binary/string variants,
lists, fixed-size lists, structs and maps. The AST exposes the logical `DType` and retains
the native spelling in type metadata. Analysis output preserves that spelling, including
through `arrow_cast`, AST copy and serialization. Arrow names are analysis data, not new
SQL cast syntax. Shape comparison does not equate different list widths or timestamp
units merely because their logical SQL types match.

Spice JSON union values retain their captured Arrow union descriptor. Their logical type
stays unknown: a union is neither a guaranteed string nor a struct with accessible fields.
The typed JSON getters and `->>` have their measured logical types.

Integer and floating literals use BIGINT and DOUBLE. Signed-integer SUM widens to BIGINT;
floating SUM and integer AVG return DOUBLE; integer division remains integer. SQRT/FLOOR
preserve Float32 inputs. LENGTH returns INT for Utf8 and BIGINT for upstream LargeUtf8.
Spice's captured LargeUtf8 literal case differs, so that return type stays unknown there.
Decimal arithmetic and mixed
unsigned coercions stay unknown rather than borrowing another engine's precision rules.
Round5 records the native default decimal results separately. These observations do not
qualify another decimal setting or authorize a cross-engine mapping.

Literal `ai_decide` question objects refine the returned struct, including nested choice
and score probabilities. Question IDs follow the native sorted-map order. Dynamic,
malformed, empty and duplicate-key question objects remain unknown. Nested field access
uses known struct fields without treating their names as table qualifiers. Analysis never
calls a model or folds a volatile/model-backed function. Unknown function signatures and
representative introspection samples are not return-type proofs.

## Relations and output shapes

Search calls resolve their dataset arguments as relations and their column arguments in
that dataset's scope. Named option labels do not become unresolved columns. String-valued
document/join-key options are not identifier rename sites.

- `vector_search` and `text_search` preserve known input columns and honor `include_score`.
  Their fallback output remains partial because index-backed providers can change it.
  List fields can disappear on the Elasticsearch path, so only exact call-site facts can
  promise those fields. Fallback nullability remains unknown.
- RRF puts `_fused_score` first, keeps columns shared by all inputs, and excludes `rank`,
  `_score` and names ending in `_embedding`. Its coalesced columns are derived; incompatible
  input types remain unknown.
- Rerank removes `_score` and `_fused_score`, then appends non-null FLOAT `rerank_score`.
  An existing `rerank_score` is not silently overwritten: duplicate output names remain
  ambiguous. Its score lineage reads the document input rather than claiming to reuse the
  earlier search score.
- `flatten_json`, `flatten_json_properties` and `json_tree` have fixed metadata schemas and
  source-backed field nullability. Table-function column aliases apply in output order.

`RelationFacts.callSites` supplies exact output facts for a particular call. Keys are the
function name's absolute UTF-16 start offset in the analyzed script. They must come from
the same document revision and engine/connection profile as the query. Facts for one call
never replace a same-named call elsewhere. This adds no background catalog query. The old
four- and five-field constructors, default bridges and copy methods remain available.
Completion remaps call offsets through its recovery edits and drops facts for replaced calls.

Unknown input width prevents ordinal guesses for aliases on partial search results. Engine
shapes expose known columns plus an unresolved `*` for the open remainder. CTEs, correlated
queries, join ambiguity, USING/NATURAL keys, grouping/windows, DataFusion wildcard modifiers,
UNNEST and nested fields retain their existing scoped analysis contracts. USING keys keep
their left-input position; qualified stars keep the original input keys.

Qualification preserves dataset/column arguments inside native calls. Lineage follows
known passthrough columns to physical tables or CTEs and represents scores as computed
function nodes. Unknown inputs remain unresolved. A search score can depend on an
engine-selected embedding/index, so dataset-level dependencies do not claim a specific
physical scoring column.

## Parameters

Strict occurrence discovery and editor script scanning distinguish numbered `$n` binds
from anonymous `?` binds and Spice JSON containment `?`. `ParamStyle.NUMBERED_DOLLAR` retains
the number as its name; repeated numbers share a binding but retain separate authored
occurrences. UTF-16 ranges survive supplementary characters, comments, strings and script
boundaries. An unterminated string stops editor scanning before its contents. Flight SQL
binding and PREPARE session continuity remain transport qualification work.

## Evidence and checks

`vendor/data/datafusion-spice/round5/` contains bounded native type/query observations,
including rejected controls, and separately labeled source excerpts for model/search
schemas. Source URLs include the exact Spice commit and full-file SHA-256 hashes. The
native probes do not run search/model calls or connect to a user's runtime.

```sh
python3 tools/probe_datafusion_analysis.py
python3 tools/capture_datafusion_analysis_sources.py
./kotlin test -m brikk-sql
./kotlin test -m brikk-sql-metadata
python3 -m unittest discover -s tools -p 'test_*datafusion*.py'
python3 -m unittest discover -s tools -p 'test_spice_native_evidence.py'
```

The JVM evidence gate compares static field claims with captured native names, logical
types and nullability. Contract tests cover the argument roles, open/closed call facts,
collisions, lineage, nested fields, parameter ranges and prior JVM signatures.

The capture contains 59 upstream inputs and 64 Spice inputs, with 54 and 59 successful
executions respectively. Both retain five rejected controls. The core suite passes 1,351
tests, the metadata suite 129, and the DataFusion/Spice Python checks 22.
The final focused analysis/ABI run passes 22 tests after the completion-offset adjustment.
