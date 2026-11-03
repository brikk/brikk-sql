# Structured DataFusion utilities

Resolves `DFS-012-S1`, the ten opaque DataFusion controls recorded during DFS-012.
The original `round12/qualification.json` and captures remain historical evidence.

## Qualified syntax

- `DatafusionShow` represents SHOW FUNCTIONS, TABLES, COLUMNS/FIELDS and settings/ALL.
  Captured modifiers include EXTENDED/FULL for tables and columns, FROM/IN targets and
  LIKE/ILIKE/WHERE filters. Settings retain ordered identifier components. Bare SHOW is
  accepted by the pinned parser and is represented without inventing an execution result.
- `DatafusionExplain` retains its child statement and ordered options. Legacy syntax uses
  ANALYZE, then VERBOSE, then FORMAT. The option-list form supports ANALYZE, VERBOSE, FORMAT,
  METRICS, LEVEL, TIMING, SUMMARY and COSTS with the pinned value constraints. Repeated
  options remain ordered, and ANALYZE classification uses the final value. Parenthesized
  queries remain queries rather than option lists. Changing an implicit ANALYZE flag to
  false in the AST must render the explicit false value.
- `DatafusionPrepare` retains the statement name, declared parameter types and structured
  body. Empty type lists are rejected. The body can contain traversable query/DML/DDL or
  supported utilities, but never an opaque command hidden beneath a structured parent.
- `DatafusionExecute` represents named SQL EXECUTE with empty, parenthesized or unwrapped
  argument lists. Qualified names and quoted dotted names remain distinct. Its effect is
  unknown because the prepared body may modify state; parsing does not run that body.
- CREATE TABLE uses `Create` and `ClusterProperty`. GenericDialect treats `(a, b)` as one
  tuple expression, while `a, b` are two keys. The AST and generator preserve that distinction,
  including nested arithmetic/functions. Empty keys, trailing commas and malformed tails
  are rejected instead of becoming an opaque CREATE command.

DataFusion's GenericDialect tokenizer now accepts backtick identifiers. The shared family
configuration keeps them out of Spice's PostgreSQL profile. Spice retains its own utility
nodes, option policy and cluster-key grammar. Native-only DataFusion utilities and cluster
statements refuse cross-dialect generation, including generation to Spice.

SHOW CREATE and other unqualified SHOW variants, EXECUTE IMMEDIATE and the USING/INTO/OUTPUT/
DEFAULT execution modifiers are explicitly refused. The pinned upstream parser accepts
some permissive SHOW spellings outside this set; they are not advertised by this qualification.
These bounds do not change the separate Flight SQL execution limitations T1 through T4.

## Evidence and checks

`cases.json`, `brikk.json` and `native.json` contain parser-only positive/negative controls.
There are 48 accepted statement/script cases and 29 rejected inputs.
Every accepted case has a structured tree, Serde and core round trips, and native acceptance
of both original and regenerated SQL. The native AST comparison removes source spans and
normalizes only equivalent identifier quote delimiters and the ARRAY constructor's optional
name. It preserves options, argument order, identifier values, cluster tuples and child nodes.
No utility, prepared body, model or search call is executed by this capture.

`DatafusionUtilityTest` also reparses all ten original DFS-012 exclusions with current code.
`DatafusionIdeTest` runs the utility corpus through the real IDE parser, retaining malformed
input diagnostics, whole-statement boundaries and server/client parameter ownership.

```sh
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/utilities/cases.json build/review/utilities-brikk.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference \
  --cases vendor/data/datafusion-spice/utilities/cases.json --brikk build/review/utilities-brikk.json \
  --output build/review/utilities-native.json
./kotlin test -m brikk-sql
BRIKK_INTELLIJ_SDK=/path/to/idea-261 ./kotlin check intellijPlatform -m brikk-sql-intellij-support
python3 -m unittest discover -s tools -p 'test_*datafusion*.py'
```

Use new output paths for later checks. No public data-class signature changed, and the existing
ABI and API-containment gates still apply. Publication remains on hold.

## Validation

- All 1,369 core tests passed, including existing dialect corpora, ABI/Java 17 checks and six
  utility contract tests. The 48 accepted native round trips and 29 rejected inputs pass.
- The full shared IDE suite passed 816 tests on 261.25134.95. The focused DataFusion consumer,
  parser, editor, catalog, diagnostics, execution and API-containment suite passed 61 tests on
  each of 261.25134.95, 262.10968.63 and 263.5701.42.
- All 58 DataFusion Python evidence/tracking checks passed.
- Local Maven packaging and Java 17/IntelliJ artifact audits passed. No internal or experimental
  IntelliJ API allowance was added. Flight SQL execution exclusions are unchanged.
