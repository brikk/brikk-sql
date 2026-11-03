# ClickHouse catch-up batch 3

CH26-005 and CH26-006 on `clickhouse-catchup`. This batch uses the same immutable
26.8.34.13 LTS and 26.10.2.103 stable images as [batch 2](clickhouse-2026-batch2.md).
It does not change the metadata default, historical snapshots or semantic-hazard evidence.

## Analysis corrections

`* LIKE 'a%'` and qualified LIKE/ILIKE stars now contain column-name filters,
not boolean comparisons. Schema-aware expansion, relation-scope output, completion
through a subquery, typing of selected physical columns and lineage use the filtered
names. LIKE is case-sensitive; ILIKE is case-insensitive. `%`, `_` and escaped
wildcards retain their native meaning. Missing schemas remain incomplete knowledge,
not an invented empty result with a complete schema.

Bare LOCALTIME, LOCALTIMESTAMP, SESSION_USER, NOW and CURRENT_TIMESTAMP are recognized
in statement expressions. CURRENT_DATE and CURRENT_USER keep their bare spelling.
Quoted and qualified identifiers are still columns, and ordinary parenthesized
function calls still use the established builders. A standalone identifier fragment
keeps the previous expression-parser interpretation. Native type checks establish
Time, DateTime and String for LOCALTIME, LOCALTIMESTAMP and SESSION_USER. CURRENT_TIME
is not added: the pinned stable engine reports UNKNOWN_IDENTIFIER for that spelling.

`date_part` and `datepart` keep their literal units separate from column references.
The PostgreSQL-style EXTRACT additions have dedicated native nodes. These include
DOW, DOY, ISODOW, ISOYEAR, CENTURY, DECADE, MILLENNIUM, EPOCH and timezone fields.
Their result types remain unknown rather than inheriting a guessed generic integer
type. Native result fixtures distinguish Sunday DOW 0 from ISODOW 7 and verify a
century result. This is not a cross-engine date/time equivalence claim.

Function composition and implicit lambda arguments have explicit AST nodes. The
higher-order binding contexts come from the captured 26.10 `system.functions.higher_order`
flags. Bare function designators are not table columns. `_`, `_1` and `_2` bind in a
known higher-order call's first argument, while real underscore-named columns outside
that argument remain references. Nested higher-order calls keep their own bindings.
Free physical columns still contribute scope references and lineage. Function-valued
composition and implicit-lambda types remain unknown pending argument-aware inference.

Native 26.10 treats **every `|` as composition**, including `1 | 2`. The latter fails
with BAD_ARGUMENTS and advice to use `bitOr`; it must not become integer BitwiseOr
in Brikk. Explicit `bitOr`, other existing bitwise forms, and integer `|` in other
dialects keep their established handling. The expected native error is a fixture.

## Structured DDL and EXPLAIN

- Refreshable views retain schedule, offset, randomization, dependencies, settings
  and target clauses with APPEND INCREMENTAL as a distinct refresh mode.
- CREATE HYPOTHETICAL INDEX retains its name, table, expression list, type,
  granularity and IF NOT EXISTS flag.
- EXPLAIN ANALYZE and WHATIF retain their settings and enclosed statement.
  `ClickhouseExplain.executesQuery` and `SqlFragment.statementEffect` explicitly
  distinguish ANALYZE from planning or inspection. These are descriptions of
  execution behavior, not permission checks or guarantees that a query is harmless.
  WHATIF may itself read data to estimate indexes.
- MODIFY COLUMN ADD ENUM VALUES keeps automatic assignments, explicit signed
  integers and the column's IF EXISTS flag.
- CREATE TOKEN keeps VALID UNTIL, VALID FOR, GRANTS and output FORMAT. Token
  clauses may occur in either order. Supported CREATE/ALTER USER authentication
  methods keep their credential, per-method validity and GRANTS. Authentication
  validity precedes GRANTS, as in the upstream parser.
- CREATE/ALTER/DROP HANDLER retain existence flags, cluster, protocol, URL match
  kind, HTTP methods, type and nested SQL. The examples are checked against
  `ParserCreateHandlerQuery.cpp`; the original simple URL example is in fact valid
  on both pinned engines. Only GET, POST, PUT and DELETE are accepted by this grammar.
- Projection indexes and projection-level WITH SETTINGS are structured in CREATE
  TABLE and ALTER ADD/MODIFY. ALTER DROP/CLEAR/MATERIALIZE PROJECTION, position
  clauses for ADD, MODIFY CONSTRAINT CHECK/ASSUME and mixed supported ALTER actions
  retain their clauses. Ordinary projections without new clauses keep their old AST.
- Unified SYSTEM START/STOP/PAUSE/CANCEL/REFRESH target-table and ALL BACKGROUND
  commands are structured. Unrelated SYSTEM commands keep their existing raw
  Command behavior, including the literal-valued AST and source anchors.

All newly native-only forms refuse unproved cross-dialect executable generation.
Unrelated unsupported statement families continue to use Command.

## Credentials and verifier safety

Authentication credentials have a distinct node. Debug formatting masks them even
when printing a parent AST. `redactedClickhouseSql(expression)` renders a diagnostic
copy with credential values replaced by `[REDACTED]`; it does not mutate the input.
Normal `.sql()`, AST arguments and Serde deliberately retain authored credentials.
They are not redacted logging formats. The redaction helper covers the structured
authentication nodes in this batch, not arbitrary secrets embedded in unsupported
Command text, URLs or third-party function arguments.
Parser errors can also contain source excerpts. The helper is not a general log scrubber.

No DDL or EXPLAIN ANALYZE fixture is executed. The native runner passes statement
text to `formatQueryOrNull` as hex data. The verifier's independent unit test captures
the actual submitted query for mutating EXPLAIN, token, authentication, handler and
SYSTEM inputs. It must be exactly `SELECT formatQuery(unhex('<hex>'))` with the
verifier's own read-only/timeout settings, never the enclosed statement. The small
execution fixture list contains only finite SELECT expressions and expected errors.

## Native observations that corrected tentative tests

The native parser accepts SELECT FROM as a projection of the keyword-shaped
identifier `FROM`. It also accepts EXPLAIN ANALYZE with no query as an implicit
SELECT of an identifier and alias. Neither is a valid negative parser fixture.
The negative EXPLAIN vector instead uses an unclosed parenthesis.

Token clauses can be reordered, but authentication-method validity after GRANTS is
rejected. Unified SYSTEM commands in the checked parser do not accept ON CLUSTER;
that option is not invented here. Bare CURRENT_DATE and CURRENT_USER are kept
without parentheses, because the native formatter distinguishes those spellings.
Numeric interval leaves in the new validity clauses retain their original literal
type instead of inheriting the generic interval parser's string canonicalization.

## Reproduction

Fixtures are in `vendor/data/clickhouse-2026-audit/batch3-cases.json`. The fixture's
source URLs identify the official tag's parser and analyzer files. Ordinary tests
check structure, serde, scope, shape, completion, lineage, unknown typing, redaction,
classification and refusal without a server. Native evidence is stored separately
in `vendor/data/clickhouse-2026-audit/batch3/`.

Run the audit test using the absolute batch3 fixture path in
`-Dbrikk.clickhouseAuditCases=...` and an output JSON path in
`-Dbrikk.clickhouseAuditOut=...`, passed with the wrapper's `--jvm-args` option.
Then run:

```sh
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-2026-audit/batch3-cases.json \
  --task CH26-005 --task CH26-006 \
  --brikk <observation-file> --output <output-directory>
```

The runner checks applicable generated statements against native canonical
formatting on each pin and compares the finite original/generated result or error
outcomes. Later-release execution cases are marked inapplicable on the LTS engine.

## Validation

The full `./kotlin test` run with the batch-3 audit properties discovered 1,799 tests:
1,738 passed, 61 optional integration assumption aborts, zero failures. This includes
core, metadata, source-preserving lowering, serde, binary compatibility and verifier
tests. Existing parser, annotation, generator and transpilation corpus gates pass
without changing their ledgers or comparison policy.

Both pinned native runs passed. Each has 48 original parser observations. Applicable
generated parser checks number 18 on 26.8 and 41 on 26.10. The LTS run has five
original and five generated execution outcomes; five later-release cases are
explicitly inapplicable. Stable has ten original and ten generated outcomes, including
the expected BAD_ARGUMENTS error for integer-looking composition. No DDL or
executing EXPLAIN appears in that execution list.

The standalone evidence contains 58 Brikk observations: 48 statement fixtures and
ten finite execution fixtures. Final audit captures agree. Ten Python tests pass
with `python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'`. They include
an exact comparison between the static higher-order binding contexts and the
captured native flags, and a guard keeping DDL out of execution fixtures.

The optional aborts remain the unconfigured live HTTP/JDBC engines and chDB
integrations listed in batch 2. The isolated pinned ClickHouse probes did run.
Custom IntelliJ platform tests were not run. No public data-class field or JVM
constructor signature was changed, and no toolchain/runtime requirement was raised.

During development the full gates caught a no-parenthesis parser intercepting NOW(),
the old standalone current_timestamp identifier contract, and a raw SYSTEM Command
AST becoming a string instead of a positioned Literal. These were fixed in the
implementation rather than ledgered. Native formatting also caught numeric validity
intervals becoming strings and bare built-ins gaining parentheses. The redaction
test caught recursive parent debug formatting bypassing the credential node's own
toString; the shared debug formatter now masks that node explicitly.

## Explicit limitations

- This is not the complete ClickHouse access-management grammar. User authentication
  support here is the IDENTIFIED WITH method, optional BY literal, validity and
  GRANTS sequence. HOST restrictions, access-storage clauses, salt/certificate-specific
  options and unrelated CREATE/ALTER USER options are not structured by this batch.
  Unsupported combinations fail or retain existing Command handling; no clause is
  silently discarded. Token substitution forms beyond the checked literal examples
  have not been certified.
- EXPLAIN modes other than ANALYZE and WHATIF remain Command. The parser does not
  implement every native statement or every native ambiguity of keyword-shaped
  identifier fragments. Existing query-only text-EXPLAIN safety restrictions are not
  widened to authorize ANALYZE.
- The handler's body uses the existing SQL parsers. A body from an unsupported
  statement family can still contain a Command. Inline INSERT payloads and protocol
  implementations are not added. Handler queries, grants, index settings and refresh
  schedules have parser evidence, not deployment or authorization validation.
- Projection expressions use the existing SELECT/expression grammar. This batch does
  not implement connector behavior, materialization, query planning or index estimates.
  The new unified SYSTEM subset is not a rewrite of the entire SYSTEM family.
- Higher-order argument/result typing and cross-engine composition conversion remain
  unknown. Native execution still decides invalid placeholder arities, mixed `_`/`_N`
  forms and function-specific argument rules. No overload or equivalence fact is
  inferred from these parser changes.
- Website documentation checking was unavailable during this batch because the
  documentation host returned HTTP 403. Grammar evidence comes from the pinned
  engine outputs and the official tagged source, not guessed website examples.

CH26-007 and CH26-008 remain for the next approved batch. No historical hazard
provenance, function catalog, failure ledger, publication or consumer dependency pin
is changed here.
