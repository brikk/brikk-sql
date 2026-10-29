# ClickHouse 2026 final batch and handoff

CH26-007 and CH26-008 complete the approved eight-step catch-up scope on
`clickhouse-catchup`. This is not a claim that every ClickHouse function is typed,
every statement is structured, or every cross-engine translation is equivalent.
The default target remains 26.10.2.103 stable. The historical 26.5.1.1 embedded
snapshot and pinned 26.8.34.13 LTS snapshot remain available.

## Version- and setting-sensitive corrections

### Current advertising and year

Current documentation patterns no longer advertise WINDOW VIEW. The previous
patterns are retained in the additive `CLICKHOUSE_HISTORICAL_DOCS` export; the
existing `CLICKHOUSE_DOCS` getter stays in its original JVM file class. WATCH and
model-management statement patterns were not present to remove. Native keyword
captures remain verbatim engine registries, not a statement-support list.

The 26.10 function snapshot already excludes catboostEvaluate and windowID, while
older snapshots retain them. Fresh native registry checks verify that difference
and keep tumble and hop available on both target pins.

Both pins still register YEAR as a deterministic alias of toYear. That registry
fact describes the registered date-taking implementation, not every possible
arity. Both engines reject `year()` with code 42. The parser retains its distinct
zero-argument node, and the catalog does not invent an overload or determinism
profile for it. Date-taking calls, including the timezone form, keep their inputs
and measured results. The changelog's zero-argument claim is not substituted for
the observed engine behavior.

### Integer division

The stable engine changes `/` and `divide` when
`sql_compatible_division_for_integers` is enabled and operands are integers.

| Checked expression | Setting 0 | Setting 1 |
| --- | --- | --- |
| Int64 -7 / 2 | -3.5, Float64 | -3, Int64 |
| Int64 7 / 2 | 3.5 | 3 |
| Int64 1 / 0 | inf | ILLEGAL_DIVISION, code 153 |
| Int64 minimum / -1 | Floating-point result | ILLEGAL_DIVISION, code 153 |
| Float64 7 / 2 | Fractional | 3.5, Float64 |

The new helper reads literal boolean query settings, including inner-query
overrides and outer-query inheritance. Nested result fixtures verify that scoping.
It does not guess a server's session settings. Source-aware cross-dialect
generation reports a diagnostic when potentially integer division depends on an
unknown ClickHouse setting. A known enabled source mode refuses executable
conversion rather than silently become fractional division. Strict certification
does not clear these findings. Native-to-native rendering keeps authored SQL and
settings without changing its runtime behavior.

For explicitly enabled integer mode, type inference no longer reports the
historical Float64 result when actual integer width is not known. It returns
unknown. Existing default-mode inference remains unchanged; it is not a proof of
an arbitrary connected session's settings.

### Native TRY_CAST

Native TRY_CAST is preserved as TRY_CAST. It is not rewritten to plain
`CAST(... AS Nullable(T))`: the stable engine's accurate-cast behavior differs.
For example, TRY_CAST of 256 to UInt8 returns NULL, whereas the plain Nullable
cast returns 0. Unsupported composite targets remain errors rather than becoming
successful casts.

The stable snapshot now identifies TRY_CAST as a grammar built-in with an AS-type
template, not a fabricated system.functions row or overload. The LTS snapshot does
not advertise it. Paired native results retain the older engine's actual behavior:
simple TRY_CAST forms fail with UNKNOWN_FUNCTION; the checked parameterized array
target fails in parsing. Stable returns scalar NULL/results and rejects Array
targets with ILLEGAL_TYPE_OF_ARGUMENT.

Foreign-source legacy Nullable CAST lowering is unchanged. Its historical
cross-engine evidence is not upgraded by this native-only correction. TRY_CAST
still cannot borrow plain CAST evidence during certification.

### Arrays, nullable tuples, time and aggregates

PostgreSQL cardinality counts every scalar element across dimensions. The
[PostgreSQL array documentation](https://www.postgresql.org/docs/current/functions-array.html)
explicitly gives `cardinality(ARRAY[[1,2],[3,4]]) = 4`. Source-aware PostgreSQL
generation now uses arrayFlattenedLength for ClickHouse. Trino cardinality,
native ClickHouse cardinality and ordinary LENGTH keep their outer-array behavior.
No global LENGTH or ArraySize mapping is replaced. Native checks include nested
arrays, NULL elements, empty arrays and a NULL argument. The generated PostgreSQL
example returns 4; the generated Trino example returns 2.
Qualified calls are not assumed to be that unqualified PostgreSQL built-in; a
custom-schema function named cardinality is not rewritten to arrayFlattenedLength.

That PostgreSQL check combines the documented source contract with target native
execution. It is not a live PostgreSQL differential certificate. The pair remains
uncertified by the existing policy. The new target function requires 26.9 or later.

Named field extraction from a known Nullable Tuple now keeps the container's
nullability on the field type, without mutating the tuple's field declaration.
Native fixtures exercise enable_nullable_tuple_type both on and off, retaining
the disabled-setting error. Generic tupleElement and unresolved argument typing
are not promoted to complete type support.

The version-sensitive review also records:

- Date plus Time executes on both pins. DateTime plus Time fails on the LTS pin
  and succeeds on stable. Neither side is rewritten to an invented success/error.
- Map-taking aggregate calls and the legacy key-array/value-array sumMap form
  execute on both pins. New scalar value/key aggregate Map forms execute on
  stable and retain ILLEGAL_TYPE_OF_ARGUMENT on LTS. Argument order is not swapped.
- All nine regr aggregates execute on the stable pin with a bounded linear input.
  Their generic aggregate ASTs still have unknown return types where inference
  lacks a dedicated rule.
- The higher-order expression and binding tests from batch 3 remain passing;
  native composition is not reinterpreted as integer OR.

### Executable-generation guards

NATURAL JOIN is now included in ClickHouse's lowered pipe-stage capabilities.
Both target pins execute the checked authored pipe and its actual lowered SQL,
with the same columns and rows. Standard NATURAL join side/key behavior retains
the batch-2 checks. CALL, unexpanded RENAME, PIVOT and UNPIVOT restrictions are
not relaxed.

LATERAL remains experimental. Executable generation requires a literal enabled
allow_experimental_lateral_join setting visible to that join; previews retain a
diagnostic otherwise. Native checks verify both the disabled error and enabled
correlated result. Unchanged source-preserving native SQL is still left to the
server, as before; it is not a claim that a feature is enabled in that session.

Native pipe support does not replace the library's lowering contract. Pipe
execution fixtures explicitly request lowering, and their saved generated SQL
has no pipe operators. Native alias requirements and pipe-stage syntax are not
assumed away. The NATURAL fixture uses an explicit AS stage.

## Final coverage by plan step

| Step | Delivered coverage and evidence |
| --- | --- |
| CH26-001 | Exact image digests, version checks, audit fixtures and bounded isolated probes. |
| CH26-002 | Structured STREAM reads and Tuple aggregate combinators; finite native results and refusals. |
| CH26-003 | Versioned function, keyword, type and relevant-setting captures; selection and deterministic generation. |
| CH26-004 | Boundary LIMIT, year syntax, NATURAL joins, AT LOCAL, intervals, remote settings, QBit and MultiPoint. |
| CH26-005 | Name-filtered stars, contextual built-ins, date-part/EXTRACT analysis, composition and implicit arguments. |
| CH26-006 | Structured refresh/index/EXPLAIN/enum/token/handler/projection/SYSTEM forms; redaction and parse-only safety. |
| CH26-007 | The measured version/setting boundaries above, corrected generation and conservative unknowns/refusals. |
| CH26-008 | Full regression/compatibility gates, dedicated retained-coverage tests and fresh native runs of every batch. |

The detailed earlier evidence and limits remain in
[batch 1](clickhouse-2026-catchup.md), [batch 2](clickhouse-2026-batch2.md) and
[batch 3](clickhouse-2026-batch3.md).

## Reproduction and saved evidence

The new cases are `vendor/data/clickhouse-2026-audit/batch4-cases.json`. Execution
cases with changed behavior have an explicit expected result/error for each pin.
They are not counted as unavailable simply because an older engine rejects them.
Release-inapplicable cases, such as the new division setting, are counted
separately. Error checks require the server code and category, not any failure.

```sh
./kotlin test --jvm-args="-Dbrikk.clickhouseAuditCases=$PWD/vendor/data/clickhouse-2026-audit/batch4-cases.json -Dbrikk.clickhouseAuditOut=/tmp/opencode/clickhouse-batch4.json"
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-2026-audit/batch4-cases.json \
  --task CH26-007 --task CH26-008 \
  --brikk /tmp/opencode/clickhouse-batch4.json \
  --output /tmp/opencode/clickhouse-batch4-native
python3 -m unittest discover -s tools -p 'test_*clickhouse*.py'
```

The audit capture and standalone Kotlin capture support brikk_sql/brikk_dialect
for source-aware target checks and lower_pipes for executable lowering. The raw
native input remains separate from the source-language example.

For earlier batches, run Clickhouse2026AuditTest with each earlier fixture path,
then run the probe with the corresponding task selectors: CH26-002 for cases.json,
CH26-003/004 for batch2-cases.json, and CH26-005/006 for batch3-cases.json.
Their final reruns are saved separately under the batch-4 evidence, rather than
overwriting any earlier capture or semantic-hazard provenance.

## Validation results

The final full wrapper run using the batch-4 audit properties discovered **1,813
tests: 1,752 passed, 61 optional integration assumption aborts, zero failures**.
Core, metadata, verifier, AST serde, source preservation, type/scope/shape/lineage
and JVM compatibility gates ran. No failure ledger or comparison policy changed.
No public data-class field or constructor signature changed; no runtime uplift
was introduced.

The new batch has 22 native parser fixtures and 41 execution fixtures. Both pins
record all parser observations; applicable generated parser comparisons number
11 on LTS and 22 on stable. LTS has 23 original and 23 generated execution
outcomes, with 18 later-release cases explicitly inapplicable. Stable has 41
original and 41 generated outcomes. The 63-row Brikk capture has no generation
errors. Known native errors are passing outcome checks, not successful queries.
The standalone Kotlin capture, wrapper audit output and saved evidence agree.

Fresh native reruns of every batch passed:

| Batch | LTS original/generated outcomes | Stable original/generated outcomes |
| --- | ---: | ---: |
| 1 | 6 / 6 | 6 / 6 |
| 2 | 13 / 13 | 22 / 22 |
| 3 | 5 / 5 | 10 / 10 |
| 4 | 23 / 23 | 41 / 41 |

Together these runs record 320 original parser observations, 165 applicable
generated parser comparisons and 126 paired original/generated execution
outcomes. Earlier raw captures and historical hazard provenance remain untouched.
The earlier-batch audit tests also passed against the final implementation.

Eleven focused Python tests pass, including deterministic catalog generation,
capture hashes, exact registry binding flags, isolation/deadlines, expected native
errors, per-pin expectation completeness and separation of DDL from expression
execution fixtures. No task-owned probe containers remain.

The 61 optional aborts were 14 unconfigured ClickHouse HTTP checks, 17 Trino,
8 PostgreSQL, 3 StarRocks, 6 MySQL and 10 Doris container checks, plus one chDB
native-library check, one chDB Python check and one paired native JSON check.
The pinned local ClickHouse probes ran independently. Website documentation
checking and custom IntelliJ platform tests were not run. The PostgreSQL source
contract above was read from its documentation; no live PostgreSQL comparison
is claimed.

The native formatter preserves function-name case. The length comparison parser
fixture uses the generator's canonical LENGTH casing, which the captured registry
marks case-insensitive; a separate lowercase-input result fixture still verifies
the behavior. No clause or result expectation was removed to bypass that difference.

## Remaining boundaries and adoption

- Generator defaults target stable 26.10; there is no automatic runtime server or
  session negotiation. Selecting a historical metadata snapshot does not silently
  reconfigure the generator. Source-aware checks require source-aware entry points
  such as SqlFragment, not a provenance-free AST rendering call.
- Unknown session settings remain unknown. LATERAL enablement is not inferred from
  a connection, and target-side integer division can still be blocked by a setting
  diagnostic. Caller code must honor unsupportedMessages or strict certification.
- The batch-3 authentication, handler, EXPLAIN and projection limitations remain.
  DDL and dangerous statement text enter the editor verifier as data only. This
  task does not deploy objects, issue credentials or enable server features.
- Projection WHERE has structural/native-parser coverage here, not a deployment,
  optimizer-choice or materialization proof. Unknown higher-order, aggregate and
  temporal result types remain unknown where not specifically implemented.
- Existing historical hazard vectors and pairwise certification policy are not
  relabeled as tested on 26.10. Fresh native behavior evidence is a separate
  artifact, and does not certify a whole cross-engine direction.
- Optional unconfigured live-engine integrations and custom IntelliJ platform
  tests are not replaced by parser acceptance. Their actual validation status is
  recorded below. Native probes use the immutable local images without network,
  credentials or host mounts and remove only their own containers.

All eight plan steps are complete within these boundaries. Publishing, merging,
consumer dependency updates and broader support still require separate approval.

The subsequently authorized main merge, Java 17 integration and full IDE validation
are recorded in [the integration handoff](clickhouse-main-integration.md).
