# Trino 484 and Doris 4.1.5 JSON evidence

Captured 2026-10-28 from two live engines. This is a direct paired run, not an
inference through DuckDB or a relabeling of the July evidence.

## Scope and provenance

- [47 paired expressions](../../vendor/data/trino-484-doris-4.1.5-json/vectors.json)
  cover exponents, decimals, duplicate keys/member order, legacy JSON paths, SQL
  NULL versus JSON null, invalid input, and function availability.
- [Raw results](../../vendor/data/trino-484-doris-4.1.5-json/results.json) retain
  each exact query, exit status, stdout, stderr, decoded scalar observation,
  native version output and container image identity.
- Trino reports `484`. Both Doris FE and BE report
  `doris-4.1.5-release-917b20be838`. MySQL-compatible VERSION() is not used to
  identify Doris.

| Component | Image digest |
|---|---|
| Trino | `trinodb/trino@sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f` |
| Doris FE, amd64 | `apache/doris@sha256:e67f66e621d2d77be7d210b8cab4b1e62e89ff4a0bdaf894bf155fdb1fe441dc` |
| Doris BE, amd64 | `apache/doris@sha256:3f7ff231e3a8b586b0556391f020089b666f786525ad90736bccea31761759bb` |

The SQL wrapper distinguishes SQL NULL from a value and hex-encodes UTF-8 text.
This avoids CLI escaping differences without normalizing the JSON itself. It
does not parse numbers as doubles, sort object keys, collapse duplicate keys,
trim string values, or accept a tolerance. Errors remain errors, with raw causes
retained; two errors are never counted as equivalent.

There are 20 exact matches and 27 differing outcomes. Those counts describe only
these expressions. No finite probe set establishes universal function equivalence.

## Numbers and serialization

Representative parse-and-serialize results:

| Input | Trino JSON_FORMAT(JSON_PARSE(input)) | Doris CAST(JSON_PARSE(input) AS STRING) |
|---|---|---|
| `1e2` | `100.0` | `100` |
| `1e-7` | `1.0E-7` | `1e-07` |
| `-0e0` | `-0.0` | `-0` |
| `1.2300` | `1.2300` | `1.23` |
| `-0.00` | `0.00` | `-0` |
| `9007199254740993.1200` | `9007199254740993.1200` | `9007199254740994` |
| `1e9999` | Error | `inf` |
| `null` | Text `null` | SQL NULL |

The large decimal is a value/precision difference, not just a different spelling.
The overflowing exponent produces a Doris serialization that is not valid JSON.
Both results matter when a consumer parses, compares, casts or returns JSON text.

For `JSON_PARSE('null') IS NULL`, both engines return false. Doris also returns
false for `JSON_PARSE('not json') IS NULL`, whereas Trino throws. Doris's malformed
input becomes a JSON null value, and casting it to STRING produces SQL NULL.
Do not confuse that cast result with JSON_PARSE returning SQL NULL.

Trino's legacy varchar-input and typed-JSON extraction overloads are not textually
interchangeable. For `{"n":1.2300}`, serializing `JSON_EXTRACT(text, '$.n')`
produces `1.23`, but serializing `JSON_EXTRACT(JSON_PARSE(text), '$.n')` produces
`1.2300`. The corresponding typed Doris case produces `1.23`. Scalar extraction
and exponent cases in the raw fixture preserve this input-type distinction.

Doris still has no JSON_FORMAT function. CAST to STRING is only a candidate
replacement subject to the differences above. The tested typed DECIMAL(3,2)
JSON_ARRAY constructor retains `[1.20]` on both engines; that one constructor
result does not clear the parsed-JSON decimal hazards.

## Duplicate keys and member order

Both engines retain `{"z":1,"a":2}` in that order and preserve the tested
`{"a":1,"a":2}` duplicate members during parsing and serialization. Legacy
JSON_EXTRACT/JSON_EXTRACT_SCALAR and the paired Doris expressions select the first
`a`, yielding `1`, including the typed JSON input case. These are scoped positive
results, not a claim about every JSON operator or SQL/JSON uniqueness clause.

## Path semantics

The tested ordinary string/boolean, root, nested, zero-index, missing and
out-of-range cases agree. JSON null scalar extraction yields SQL NULL on both
engines. Invalid JSON passed directly to legacy JSON_EXTRACT yields SQL NULL in
the tested case on both engines, unlike Trino JSON_PARSE.

Important counterexamples:

| Case | Trino | Doris paired expression |
|---|---|---|
| Scalar extractor finds object | SQL NULL | Object text |
| Scalar extractor finds array | SQL NULL | Array text |
| `$[-1]` | Invalid path error | Last element |
| `$[*]` | Invalid path error | Array of matches |
| `$."a.b"` | Invalid path error | Value of `a.b` key |
| `$["a.b"]` | Value of `a.b` key | Invalid path error |

The primary Doris scalar candidate is JSON_UNQUOTE(JSON_EXTRACT(j,p)). Unquoting fixes
strings, but does not enforce Trino's scalar-only result rule. Numeric text also
differs by decimal scale and exponent representation. These path findings concern
legacy JSON_EXTRACT functions, not the separate SQL/JSON path language used by
Trino JSON_VALUE/JSON_QUERY/JSON_TABLE.

Five additional GET_JSON_STRING probes confirm the same object/array and numeric
text hazards for that alternative. Its old string-only identity entry is also
made conditional, including the reverse lookup.

## Shipped metadata and certification

The refreshed JSON_EXTRACT and JSON_EXTRACT_SCALAR/JSON_UNQUOTE entries are
CONDITIONALLY_EQUIVALENT rather than IDENTICAL. JSON_PARSE remains conditional
with its broader measured constraints. JSON_FORMAT remains NO_EQUIVALENT as a
named Doris function. Per-entry provenance points here; the rest of the historical
hazard file is not marked as rerun.

Both directional generated registries retain these constraints. Certification
tests require the refreshed semantic findings. Existing certification policy
still treats conditional verdicts as warnings; strict consumers that reject only
REFUSAL are not thereby guaranteed exact JSON text equivalence. No public API or
global certification-policy change is made by this refresh.

## Reproduction and runtime limits

Use disposable, digest-pinned containers and run:

```sh
python3 tools/probe_trino_doris_json.py \
  --trino-container brikk-trino-484-json \
  --doris-container brikk-doris-415-json-fe \
  --backend-container brikk-doris-415-json-be \
  --output /tmp/opencode/json-results-recheck.json

BRIKK_TRINO_TEST_CONTAINER=brikk-trino-484-json \
BRIKK_DORIS_TEST_CONTAINER=brikk-doris-415-json-fe \
  ./kotlin test -m brikk-sql-verify --include-classes='*Trino484Doris415*'
```

The test-only FE/BE setup uses an internal Docker network, with BE sharing the FE
network namespace and priority_networks set to 127.0.0.1/32. No host ports or
production data are used. Limits are Trino 4 GiB/4 CPUs, FE 3 GiB/4 CPUs, BE
6 GiB/6 CPUs. FE uses a 1536 MiB JVM heap, BE 512 MiB, with
`-XX:-UseContainerSupport` for this host's bundled-JDK/cgroup issue.
SKIP_CHECK_ULIMIT=true bypasses BE's production kernel preflight; no host kernel
setting changes. BE file caching is disabled because its initial cache disk monitor
crashed on this host. JSON semantics are not inferred from cache behavior or from
that failed startup. Version and backend readiness checks precede capture.

The paired regressions exposed an inherited MySQL generator bug: ParseJSON nodes
were emitted as their raw input in Doris. In particular,
CAST(JSON_PARSE('not json') AS STRING) became CAST('not json' AS STRING), changing
SQL NULL to the text `not json`. Doris generation now retains JSON_PARSE. This
fix does not clear the measured cross-engine parse and numeric-rendering hazards,
and does not change MySQL generation.

The recorded observations are raw native SQL, not generated equivalents.
Regression tests replay both native SQL and same-dialect regenerated successful
forms against each engine, preserving known divergences rather than declaring
them equal. Publishing and updating the DuckLake consumer's metadata pin remain
separate integration work.

## Validation

The full Kotlin suite runs with both Trino container variables and the Doris
container variable configured: 1,726 methods pass, 33 optional engine assumptions
abort, and none fail. This includes the live Doris 4.1.5 FE/BE version gate and
100,000-row nullable JSON/Boolean regression under both execution modes, not just
the scalar capture. Python tooling runs 41 methods with one optional skip.
Hazard generation is byte-deterministic, and sync tests check the reviewed JSON
against both directional shipped maps. No parity-ledger changes are needed for
the Doris JSON_PARSE correction.

A final focused run passes all six JSON evidence/certification methods with no
skips, including 15 actual Trino-to-Doris scalar renderings checked against the
recorded Doris outcomes. Known differences are kept as constraints, not rewritten
as successful equivalence assertions. The task-only engine containers and network
are removed after validation; raw evidence is retained with the inputs in the
versioned evidence directory.
