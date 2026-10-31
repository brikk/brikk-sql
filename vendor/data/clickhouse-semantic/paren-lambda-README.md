# CHSEM-011 parenthesized explicit lambdas

ClickHouse composition operands such as `(x -> toString(x)) | (y -> upper(y))`
now parse as parenthesized Lambda nodes, not JSONExtract expressions. The existing
composition and contextual analysis can therefore use their declared parameters,
captures and result facts. No new AST class or serialized node kind was introduced.

## Parser change

The existing parseLambda header lookahead is factored into a protected helper.
The ordinary function-argument path retains its fast atom check, lambda operators,
fallback and argument modifiers. A new parenthesized-item hook defaults to the
same parseExpression call used before; only ClickHouse overrides it to recognize
an arrow lambda header first. The parenthesis parser still handles tuples, queries,
comments and closing delimiters itself.

The ClickHouse hook recognizes single identifiers, quoted identifiers and grouped
parameter lists, including redundant enclosing parentheses. Literal strings,
numbers, NULL/Boolean literals and non-headers keep the ordinary expression path.
It does not reparse arbitrary expression bodies as lambdas or introduce argument
ORDER/HAVING modifiers into ordinary parentheses. Existing public parseParen,
parseLambda and its synthetic default entrypoint keep their JVM signatures.

Other dialects do not use the new hook. PostgreSQL, DuckDB, MySQL and Doris
parenthesized JSON `->`/`->>` regressions remain JSON operations. Ordinary ClickHouse
parentheses, tuples, scalar subqueries, literal-JSON arrows and `->>` retain their
existing parsing behavior. Parenthesized ClickHouse identifier-arrow headers are
now correctly treated as lambda syntax.

## Binding, types and diagnostics

The normal lambda replacement/binding logic handles references. Stage inputs use
the existing composition rules, nested scopes and captures, rather than sharing
initial array elements across every stage. Function-valued Lambda/Composition
nodes retain UNKNOWN slots; conditional scalar/array results are separate proofs.

Tests cover two-array first stages, one-array tuple field syntax, nested maps,
shadowing, repeated parameter spellings, numbered-looking explicit parameter names,
quoted identifiers, physical captures and lineage. Public rule errors now reach
the explicit operands, including right-stage arity and source-array arity failures.
Lambda syntax origins and diagnostics use authored UTF-16 positions, with CRLF,
surrogate prefixes and script offsets covered. SQL generation and annotated serde
retain the structured lambda nodes.

Parsing a lambda does not establish every body result. Named tuple field lookup
and an unresearched function stay unknown in the controls, without a false
PARSER_GAP label. Existing WITH function aliases remain unresolved, not promoted
to builtins merely because their definitions now have the correct Lambda shape.
Unsupported qualified operands and other parser/analyzer boundaries can still
have unavailable knowledge. This resolves the named CHSEM-011 gap, not all gaps.

## Native evidence and historical refinements

`paren-lambda-cases.json` has thirteen finite execution cases and two syntax negatives.
Both original and generated forms run on the two exact digest pins. The 26.10
cases cover values, real argument errors and unresearched bodies. The 26.8 engine
rejects composition syntax; those are explicit code-62 controls, not execution
skips. Native formatting is checked separately. Its generated-format batch has
zero eligible 26.8 statements and uses a typed empty tuple array; an untyped
arrayJoin([]) would fail the probe query itself, not the candidate SQL.

`paren-lambda-refinements.json` records the intentional current-assertion changes:

- CHSEM-003-E26 now binds UInt8 and then String and participates in current SQL/
  serde checks, rather than being skipped as a parser gap.
- CHSEM-004-E32 now has unavailable RESULT_FAMILY knowledge for toTypeName, not
  PARSER_GAP. E29/E46 now identify their WITH Lambda definitions as unsupported
  contexts while retaining the existing unresolved-name behavior and no false errors.
- CHSEM-005-E33 now knows Array(String) and emits generated SQL in the current
  report. The existing native toTypeName capture already witnessed that type.
  E32's WITH definition also generates correctly as a Lambda, but its result
  remains unknown and its unresearched definition context is recorded explicitly.

All historical fixture flags, raw annotation reports and native captures remain
intact. Layered current assertions name each refinement instead of relabeling old
evidence. Corpus fixtures, ledgers and comparison rules are unchanged.

## Refusal and reproduction

CHSEM-010's cross-dialect refusal remains in force, including these correctly
parsed operands. No production lowering, release, dependency update or new
IntelliJ API call is part of the parser fix.

```sh
./kotlin test -m brikk-sql \
  --include-classes='*ClickhouseParenthesizedLambda*Test' \
  --include-classes='*BinaryCompatibilityTest' \
  --jvm-args='-Dbrikk.clickhouseParenOut=/tmp/opencode/paren-lambdas.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/paren-lambda-cases.json --task CHSEM-011 \
  --brikk /tmp/opencode/paren-lambdas.json --output /tmp/opencode/paren-lambda-native
python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

Use fresh output paths for rechecks. Containers keep the existing isolated,
resource-limited, no-network/no-mount setup and query/host deadlines.

## Validation

Both pins passed fifteen original parser observations, thirteen original execution
assertions and thirteen generated execution assertions. The two syntax negatives
stay invalid, arity errors retain their native codes, and unresearched bodies stay
unknown rather than becoming generic acceptance. New Brikk reports regenerate
byte-identically; historical changes are limited to the named refinement layers.

Full JVM regression passed 1,846 tests with 61 optional integration assumption
aborts and zero failures, including binary compatibility and unchanged corpus gates.
Full IntelliJ regression passed 757 tests on 261.25134.95; other IDE versions were
not run. Complete Python discovery passed 96 tests with one opt-in Toolchain-model
skip using the unchanged SQLGlot pin; ClickHouse-only discovery passed 48.
No new IntelliJ API calls, old public JVM signature removals, toolchain/release
changes, publication or consumer dependency updates were introduced.
