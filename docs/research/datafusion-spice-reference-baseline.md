# DataFusion and Spice reference baseline

Date: 2026-10-30. Worktree: `spice-support`. Implements the initial reference work for
DFS-001 and DFS-002 in [the support plan](../spice-datafusion-support-plan.md).

## Delivered

- A stable 76-case regression/audit corpus with explicit parser-only versus execution scope.
- A portable Kotlin observation exporter for Brikk parsing, statement ASTs, and generation.
- Two separately locked Rust references: upstream DataFusion 55.2.0 and the parser used by
  Spice v2.4.0, including its statement-level CLUSTER BY extension.
- Original/generated SQL execution against upstream DataFusion and the checksum-verified
  released Spice v2.4.0 Linux x86_64 binary.
- Native messages, source coordinates, UTF-16 offsets, values/types, profile settings, and
  introspection smoke results. Process/network failures remain infrastructure errors.
- The earlier 352 documentation examples and 24 focused probes, archived separately as
  historical local observations rather than retroactively labeled native release evidence.

No production dialect behavior changes in this batch. There is not yet a production
`spice` registry entry. The current local observations intentionally use `datafusion`.

## Profiles and provenance

Upstream DataFusion is pinned to release 55.2.0, commit
`500744d5c25499055e3b2ca46b022bc425989203`, with Arrow 59.2.0, sqlparser 0.62.0,
GenericDialect, identifier normalization enabled, and one execution partition. It includes
the expression packages listed in the reference Cargo.toml, but not optional JSON/Spark
packages or any Spice functions. It is one explicit embedding profile.

Spice is pinned to v2.4.0, commit `78a47787ef74dec52d705bf9e26a0f9b7a26ca94`, and its
DataFusion fork revision `cf084aa85dfdc27b4736208f5c132828020774ea`. The released binary
reports `v2.4.0+models`; the fixture configures no models or external datasets. The parser
adapter has separately locked registry Arrow dependencies and claims only parser behavior.
All product-specific execution evidence comes from the actual released binary.

The source and actual reference dependency graphs are both recorded in
`vendor/data/{datafusion/55.2.0,spice/2.4.0}/dependency-provenance.json`. This keeps the
parser adapter's dependencies distinct from the Spice runtime's patched Arrow 59.3.0.

The Spice archive SHA-256 is
`4c8179e1a7983a06d907b4efff29b17f0c1d779159efe117c1cafcc3bd3cd918`.
The extracted executable SHA-256 is
`257b9fd8cf1f46c1f4ddf0b6280ba5b6033b79c276ec6840dd5e5d81710b1002`.
The runner checks the executable and version before starting a new runtime, uses fresh local
ports and a temporary Spicepod, and terminates its process on success or failure.

## Results

The native parsers accept 70/76 original cases for the upstream Generic profile and 67/76
for the Spice PostgreSQL profile. Rejected negative controls are intentional. These ratios
are corpus observations, not support percentages.

Forty cases have explicit finite execution permission. The remaining 36 cover statement
syntax, errors, search/AI calls, and parameter forms without executing them.

| Original versus generated execution | DataFusion | Spice |
| --- | ---: | ---: |
| Same captured observation | 12 | 14 |
| Different value/type/name observation | 5 | 7 |
| Original accepted, generated rejected | 2 | 4 |
| No generated SQL | 5 | 5 |
| Original not accepted | 16 | 10 |

The upstream rejection count includes Spice-only functions and fixture namespaces that do
not exist in the upstream embedding. The Spice count includes negative controls and genuine
documentation discrepancies. Neither group is evidence of a Brikk generation defect by itself.

Values/types are compared within one profile and transport. Upstream captures Arrow display
strings, type names, and nullability. Spice captures raw HTTP JSON and `arrow_typeof` values;
HTTP nullability is explicitly unknown. Display strings are not a lossless nested/binary
codec. Matching observations are not universal semantic equivalence certificates.

## Confirmed correction priorities

Stable case IDs refer to `vendor/data/datafusion-spice/cases.json`.

| Case | Measured Spice original | Measured generated SQL |
| --- | --- | --- |
| DFS-regex-flags | `true` | `false`, case-insensitive flag lost |
| DFS-like-order | `true` | `false`, arguments reversed |
| DFS-map-variadic | Both map entries | Only the first entry |
| DFS-to-char-format | `01-03-2023` | `2023-03-01` |
| DFS-array-sort-nulls | NULL last | NULL first |
| DFS-date-bin-origin | Timestamp value | Parser rejects the unquoted origin |
| DFS-date-add-int32 | `2024-03-01` | Extra argument prevents function binding |
| DFS-string-agg-order | `Bob, Ada` | Delimiter becomes an ORDER BY expression; binding fails |
| DFS-json-arrow | Text `30` | `json_extract_scalar` is not registered |
| DFS-truncate-partition | Integer `100` | Floating-point `10.0` |
| DFS-dollar-string | `a;b` | `a; b`, with Brikk incorrectly splitting one statement into two |

The upstream execution profile independently reproduces the regex, variadic map, to_char,
array_sort, date_bin, string_agg, and dollar-string defects. Spice-only functions need local
overrides rather than being added unconditionally to the DataFusion parent.

Spice also accepts `#`, `@>`, `<@`, E-string escapes, and the hex-literal bitmap_count case
that Brikk currently rejects. These are lexical/parser regressions to address in DFS-004/005.

Two additional wrong-AST cases retain native output text and therefore escape a string-only
identity test:

- `DFS-xor-caret`: upstream Generic DataFusion returns integer `6` for `5 ^ 3`, but Spice's
  PostgreSQL profile returns floating-point `125.0`. Brikk currently always builds BitwiseXor.
- `DFS-default-nulls-desc`: both engines return NULL before descending non-null values.
  Their default is `nulls_max`; Brikk's DataFusion Ordered node incorrectly says
  `nulls_first=false`. Same-dialect text regeneration happens to retain the native default,
  but analysis and cross-dialect lowering cannot trust that AST fact.

`DFS-bind-dollar` still produces Column/Identifier nodes in Brikk rather than parameter
nodes. All advertised important SHOW, EXPLAIN, and prepared forms need a structured-AST
audit; opaque passthrough is recorded in the local observation file, not counted as support.

## Documentation and configuration discrepancies

The live runtime confirms `datafusion.sql_parser.dialect=postgresql`,
`enable_ident_normalization=false`, and `default_null_ordering=nulls_max`.
Unquoted mixed-case column aliases remain mixed case. Referencing the same alias with a
lowercase spelling fails. Plain DataFusion normalizes the alias and accepts that query.

The fixture view named `MixedRows` in the Spicepod is registered as `mixedrows`, while its
quoted column remains `MixedColumn`. Dataset/view-name configuration and SQL identifier
resolution must be tested separately. The fixture table name `"a.b"` remains one identifier;
unquoted `a.b` is a namespace-qualified path and does not resolve to it. Catalog/schema
inventory reports `spice.public`; both two- and three-part references to `sample` work.

The default Spice v2.4.0 profile rejects these documented forms:

- Star EXCEPT, REPLACE, and ILIKE cases in this corpus. Their upstream Generic counterparts
  have separate results; sharing an implementation must not advertise identical profiles.
- `ORDER BY ALL`, which reaches binding as a column named `ALL` in the measured query.
- The extra-comma `array_agg(c, ORDER BY d)` example. Correct `array_agg(c ORDER BY d)` works.
- `date_add(date, 3)` because the integer literal is Int64 while the registered function
  accepts Int8/Int16/Int32. The explicit INT control works and proves the extra-argument
  generation defect separately.

The PostgreSQL parser rejects bare `?` expression placeholders. This is not a conclusion
about JDBC prepared statements: Flight SQL parameter handling is a separate path and still
requires DFS-010 driver qualification. The runtime's `^` exponentiation also contradicts
the docs' claim that it is interchangeable with XOR `#`.

Do not broaden the native reference profile to GenericDialect to make these examples pass.
Versioned runtime behavior wins over unversioned documentation for this baseline.

## Introspection confirmed on the runtime

The smoke queries for `information_schema.routines`, `information_schema.parameters`,
`SHOW FUNCTIONS`, and `list_udfs()` all execute successfully. The targeted routine query
returns both scalar and table registrations for search functions. `SHOW FUNCTIONS LIKE
'vector_search'` returns a table entry. The sampled `truncate` parameter query returns no
rows, demonstrating why absent parameter metadata cannot mean zero arity.

These are discovery checks, not the full catalogs required by DFS-003. The HTTP functions
endpoint and Flight SQL/JDBC metadata remain unqualified. Full capture must preserve
scalar/table dual registrations and distinguish representative or absent signatures from
authoritative overload constraints.

## Statement inventory and remaining scope

Parser-only inputs cover SHOW TABLES/COLUMNS/ALL/FUNCTIONS, EXPLAIN and ANALYZE options,
PREPARE/EXECUTE, CREATE TABLE/AS/VIEW/EXTERNAL TABLE, Cayenne CLUSTER BY, ALTER ADD COLUMN,
DROP TABLE, COPY TO, INSERT, UPDATE, DELETE, MERGE, SET, RESET, and USE. Original and generated
AST kinds/display SQL are retained for every accepted case. The CLUSTER BY negative control
proves that its wrapper does not enable unrelated generic backtick identifiers.

Parser acceptance does not establish provider support. CREATE/ALTER/DROP variants, storage
engines, external I/O, connector write access, DML transactions, namespace/session changes,
and named PREPARE/EXECUTE lifetime remain explicitly unverified. Carry that inventory into
DFS-004/005/009/010 instead of claiming every parsed statement is executable.

Search/AI/embedding queries are parser-only because the baseline has no models or indexes.
Their values, output schemas, and effects need deterministic configured fixtures later.
The historical 376-row documentation archive has local evidence only except where a
corresponding stable native case above establishes the specific behavior. Native error
locations are nullable when the engine supplies none, such as some EOF errors.

The next work is DFS-003 metadata capture and DFS-004 shared correctness fixes, followed by
the Spice child and analysis contracts. No completion, function-type, IDE, or published
support claims are made by these reference tools.

## Reproduction and verification

Commands and protocol details are in
[`tools/datafusion-reference/README.md`](../../tools/datafusion-reference/README.md).
The Rust references have unit tests for scripts, malformed tails, Unicode/UTF-16 positions,
the Spice grammar extension boundary, and upstream execution/type/error distinctions.
Python tests cover protocol/infrastructure errors, wrong profile/binary rejection, comparison
limits, and consistency of the committed fixture/capture/lockfile hashes.

The existing DataFusion suite remains green: 15 test methods, 275 identity cases,
2 transpile cases, and 1,208 parse cases. New native evidence does not weaken those gates
or overwrite their existing ledgers. The portable Kotlin observation exporter builds on
Java 17. No production IntelliJ sources or public JVM signatures changed.
