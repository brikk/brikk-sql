# Coherent SQLGlot 30.29 integration

The owner authorized this integration after the separate cancellation fix
`17a3d5d4` and complete disposition ledger `e9475518`. The frozen upstream
release is `2fe25297af5b0f44f5c9e5d03e1b296424b7acbe`, tag `v30.29.0`.
The later changelog commit is not the oracle. Publication, downstream adoption
and peer-consumer qualification are not implied.

Status: integration validation and exact ledger/native review are complete.
Failed attempts remain under `/var/tmp/jayson/opencode/sg29-020-*.log`; they
are not reported as passes. Commit/push is separately authorized.

## Generation and compatibility

AST nodes, raw upstream argument metadata, function builders, typing metadata,
tokenizer tables and behavioral corpora are regenerated from the same source.
`BrikkSql.SQLGLOT_PIN` and `FixturePinSyncTest` identify the same target. This
does not make every upstream feature a supported Brikk feature.

The generated manifest records compatibility deviations separately from its
raw upstream classes. Tests freeze the exact upstream and retained shapes:

- Keep both published sort classes, constructors and serde identities. Move
  SortArray's unchanged implementation to the handwritten legacy catalog;
  retain its parser alias and typing entry. ArraySort retains its old schema.
  Post-target direction/NULL fields on that node refuse execution rather than
  disappear through an old renderer. No implicit node migration is enabled.
- Keep Lambda.colon and the richer native NEAREST shape, including optional
  count and exact/approximate distinctions. Native semantics are not reduced to
  the generic upstream representation.
- Keep AggregateKeyProperty's published `brikk.doris` serde module even though
  upstream now has a same-named property. The exact overlap is allowlisted, not
  a general relaxation of native/module checks.
- Preserve the public PREPARE and INSERT_DATA token members. Runtime DuckDB
  PREPARE/EXECUTE bodies remain tokenized with exact parameter/source intervals;
  regenerated upstream command tables do not turn those bodies into strings.
- Retire the temporary pre-refresh Values, Match, Refresh and ReplaceColumns
  manifest exceptions now that they are ordinary target metadata entries.

Generated raw-text hash flags are refreshed. Handwritten Placeholder and Command
receive the same case-sensitive policy. Integral elements inside raw lists
retain Int/Long equality through serde without case-folding their strings.
No public data-class constructor, copy method or default bridge is replaced.

## Bounded integration fixes

Constructed property keys and single-string intervals use the dialect's literal
escaper. Presto WEEK rewriting does not interpolate arbitrary string text into
SQL. PostgreSQL quarter conversion neither invents zero for unconvertible input
nor overflows a bounded integer; it rewrites a detached node. Unsupported values
retain an explicit diagnostic.

EXPLODE discovery, zip counting and alias replacement stop at nested-query
boundaries. Existing outer-map/zip policies remain. Synthesized lambda casts
copy their DataType child rather than share parent ownership.

REF has a token, DType and named-target parser with nested-type and serde
controls. It does not take over ordinary REF identifiers/functions, and an
unqualified REF executable mapping refuses. Metadata for Oracle NLS parameters,
SCOPED objects and new nonempty constraint-index options likewise does not
silently qualify execution. Named MySQL foreign-key indexes retain their name
when rendering a loaded target node; foreign mappings remain guarded. No DDL is
executed to claim native storage/index semantics.

The DuckDB arithmetic typing helper is explicitly classified instead of being
silently treated as an unknown generator helper. Synthetic native controls check
temporal result values/types. Loaded upstream quoted variables preserve quoted
identifier syntax. An omitted slice end with an explicit step remains the `-`
placeholder, not the distinct explicit index `-1`.

Empty generic arrays now carry the target's ARRAY<NULL> representation. Unknown
nonempty elements remain UNKNOWN; this does not assert a native driver's default
binding type for an empty list.

Strict qualification does not resolve an alias against an unknown source or
borrow an unqualified child reference from a same-named outer field. Projection
alias expansion remains available without fabricating ownership; the established
unknown physical-table whole-row spelling and explicit legacy true/null policies
are retained. The corrected invalid-fixture generator keeps each dialect header
with its SQL instead of treating headers as SQL or silently using the base
dialect. All 37 invalid inputs now get their expected errors, with zero excluded
or oracle-skipped cases. The 90% gate is unchanged. The temporary six missing-
error observations are removed because the behavior is fixed, not grandfathered.

Malformed COPY inputs retain bounded ordinary-error/whole-command behavior;
the initial infinite-loop non-reproduction is not relabeled. Large exponent
integer classification retains Kotlin's bounded policy. No replace_tables or
replace_placeholders API is added.

## Corpus and ledger review

The generators are `gen_ast_nodes.py`, `gen_tokenizer_tables.py`,
`gen_function_registry.py`, `gen_typing_metadata.py`, `extract_dialect_tests.py`,
`gen_ast_corpus.py`, `gen_token_corpus.py`, `gen_parser_corpus.py`,
`gen_scope_corpus.py`, `gen_qualify_corpus.py`, `gen_lineage_corpus.py` and
`gen_serde_corpus.py`, both plain and annotated modes. Serde generation covers
base, MySQL, Doris, StarRocks, Presto, Trino, DuckDB, PostgreSQL, ClickHouse,
Hive, Spark and BigQuery. Spark2 has no separate identity fixture; its tokenizer
and typing tables are generated and its inherited behavior remains gated.

`review_sqlglot_refresh.py` compares every actual ledger with the pre-integration
commit. It requires an exact observation hash and an explicit decision for each
added, changed or removed assertion ID. An unchanged case/signature may retain
its curated reason across a mechanical rekey. Matching display labels alone
never approve a changed failure. Safety tests cover those distinctions.

The final observation packet requires 383 explicit decisions: 374 newly
inventoried target failures and nine stale old-pin removals. Seventy-six existing
failure signatures and their curated reasons are retained. No pre-existing core
signature is replaced and there are no blanket allowances. Newly exposed, unported MySQL/PostgreSQL
DDL, PASTE JOIN, unresolved JSON alias and pivot/CTE cases are named correctness
or feature deferrals with an owner, not semantic equivalence. Native-model and
legacy-schema differences retain their separate prior proofs and refusal limits.

Coverage inventory counts change with the target inputs, not with a relaxed
gate. There are 3,244 supported named transpile assertions, 3,226 distinct
display labels and 18 collisions. The existing policy still explicitly labels
2,657 named identities, 59 non-pipe base identities and 1,498 supported base
transpile directions as unexecuted by a direct identity/base consumer. Those
deferrals are not relabeled as passes by overlapping parser/generator gates.

The native coverage audit remains separate from parity and execution proof.
Its native-accepted-input preservation assertion is not removed or excluded.
The old baselines and all SG29-018 evidence remain historical records.

The cross-module native verifier gate also consumes the refreshed corpora. Its
Doris loader now recognizes non-SQL UnsupportedError expectations instead of
assuming every target entry is a string. Such negative-generation expectations
remain checked by the core transpile gate, not falsely fed to a native parser.
Known native rejects now compare exact generated SQL and error records as well
as source keys; a changed outcome cannot pass under an old SQL label.
`oracle-ledger-review.json` records nine new PostgreSQL DDL rejects, four exact
updates to existing native diagnostics/passthrough outcomes, and two newly
inventoried DuckDB local BY(TYPE ...) parser failures. Their before/after
records and owners are explicit; local parsing failures are not native verdicts.

Six newly inventoried DuckDB routing forms are explicitly Unverified because the
parse-only SELECT serializer cannot serialize them. The verifier still never
prepares/binds routing SQL, contacts endpoints or changes routing state. The
existing negative controls still reject Unavailable, unexpected Unverified and
stale classifications. These are not accepted grammar/execution verdicts.

Historical precedence result controls select their exact original SQL/recipe
instead of numeric fixture-array positions. Their nullable truth tables and
expected rows are unchanged; target insertion must not make the test execute an
unrelated function merely because it now occupies an old index.

## Validation record

All final gates run from the task-private `build/sg29-020-build` directory:

- Full build succeeds. All 1,843 core and 765 other JVM tests pass, including
  SQL-side Engine/binary compatibility contracts and native preservation checks.
  The existing 72 opt-in JVM assumptions remain. No test class is excluded from
  the full IntelliJ gate.
- All 829 IntelliJ tests pass on 261.25134.95, the original 827 plus the separate
  cancellation regressions. No new IntelliJ internal/experimental calls are added.
- All 241 Python tooling tests pass with `TEST_KOTLIN_RELEASE_MODEL=1`; no tooling
  test is skipped. Thirty-five regeneration commands reproduce all 400 copied
  core source/fixture files byte-for-byte in an independent preview.
- Eight synthetic DuckDB temporal/slice controls compare actual values and exact
  canonical result types. No new DDL/backend/index semantic proof is claimed.
- The native audit inventories 25,326 inputs: 13,899 accepted, 8,077 rejected and
  3,350 explicitly unverified syntax outcomes. All 5,419 historical unique SQL
  inputs and 5,664 current unique inputs replay, 5,678 in their union. All 5,405
  shared inputs have identical native verdicts/types; removed fixture SQL is also
  replayed. Engine/JDBC/settings are identical. The 68 local parser refusals are
  individually recorded, not called native verdicts. The old baselines remain.
- The compiled ABI scan preserves 2,671 prior non-local core class signatures
  and 138 metadata class signatures. It checks constructor/copy/default bridges
  and scans all current classes for JVM major <= 61. Local implementation classes
  identified by EnclosingMethod are recorded separately, not public ABI waivers.

Logs use the `sg29-020-` prefix under `/var/tmp/jayson/opencode`. Final logs are
`core-qualified-final`, `other-all-verified`, `build-all-verified`,
`ide-source-final` and `tooling-qualified-final`. Detailed native, ABI, disposition
follow-up and exact ledger reviews are checked in beside this file.

External peer compiler/runtime candidate qualification is not rerun, and no
consumer source, dependency adoption or publication is changed. Prior native
engine observations retain their exact versions; unavailable opt-in engines and
BigQuery backend execution are not inferred from parsing or local type metadata.
