# Source-preserving executable lowering (SQL-05)

**Release version: `0.18.0`.** This API was introduced in `0.17.0`; `0.18.0` also
fixes DDB-001 and updates the DuckDB reference to 1.5.6. All four SQL artifacts
are published by the `release/0.18.0` workflow. Engine integration remains ENG-01.

After Central publication, use `dev.brikk.house:brikk-sql-jvm:0.18.0` in all three
SQL consumers. Core's POM points to `brikk-sql-metadata-jvm:0.18.0`; verification
and oracle artifacts use the same release version. No Maven Local or sibling source
dependency is required for final delivery. The tested `0.18.0-SNAPSHOT` candidate
remains installed in Maven Local on the development machine. Development on `main`
advances to `0.19.0-SNAPSHOT` after this release.

## Public API

```kotlin
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable

val result = SqlFragment(source, dialect = "duckdb")
    .toSourcePreservingExecutable(target = "duckdb", embedded = true)
val executableSql = result.sql
```

Signature:

```kotlin
fun SqlFragment.toSourcePreservingExecutable(
    target: String = dialect,
    embedded: Boolean = false,
): SourcePreservingResult
```

The opt-in extension leaves `toExecutable`, `transpileTo`, `toStandardSql`, ordinary
parsing, and SQLGlot metadata/fixture formats unchanged. There is deliberately no
`pretty` option: presentation preferences do not authorize changing native text.

`SourcePreservingResult` contains:

- `sql`: the final returned SQL;
- `diagnostics`: `PreservationDiagnostic(range, message)` for regenerated source
  intervals and required FROM-first normalization; no empty-diagnostics claim of
  full preservation after structural rewriting;
- `unsupportedMessages`: the existing generator's best-effort diagnostics, separate
  from text-preservation diagnostics;
- `parameterOccurrences`: original occurrence and named-identifier token intervals;
- `rootKind` and `isRawPassthroughStatement`: the existing statement classification;
- `sourceMap`: **always null**. A generator's best-effort map is never returned after
  source patches or embedding edits invalidate its offsets.

All `SyntacticRange(start, end)` intervals index the **original input**, in UTF-16
code units, with exclusive `end`. Parameter occurrences are in textual order, not
AST traversal order. Name intervals include the authored identifier token (including
quoting when present); consumers must render any replacement with the same dialect.
Positional `?` occurrences have null `name` and `nameRange`. `ParamStyle` retains the
existing AST-oriented classification: Parameter = NAMED_AT, named Placeholder =
NAMED_COLON (including typed ClickHouse / PostgreSQL pyformat placeholders).

## Preservation proof and semantic boundary

The parser optionally records intervals at actual grammar consumption boundaries:
queries, wrapped queries, FROM/WHERE clauses, CSV expressions, modifiers and parameter
parsers. These are separate from leaf token-position metadata and are not serialized
into parity ASTs. Provenance identities survive AST copies.

A detached snapshot tree records source-affecting args, comments, metadata and types.
After existing `desugarPipes(inputDialect)` guards and target generator preprocessing,
the renderer:

1. Reuses an unchanged native syntactic interval only when no nested pipe or required
   FROM-first normalization remains inside it.
2. Patches a native parent only if its structure is unchanged and every changed child
   has the same provenance, a contained non-overlapping interval, and no changed scalar
   fields. This retains outer queries, WITH/recursive CTE scopes, aliases and trivia.
3. Regenerates changed portions through the existing generator and reports their source
   intervals. Synthetic wrappers whose consumed source is not their actual syntax are
   never treated as reusable fragments. Clause separators remain generator-owned.

There is no global string replacement, rendered-substring search, or regex pipe
splitting. Native expression spelling, casts and comments are retained where that
proof holds. Existing namespace, projection, DISTINCT, order and pagination boundaries
are not weakened in the name of fidelity.

Same-dialect native input is returned verbatim, including leading/trailing trivia,
hints, settings and parameter markers, unless embedding edits were explicitly requested.
DuckDB FROM-first remains native; unsupported FROM-first forms elsewhere receive the
required SELECT structure while unchanged clauses are reused where possible.

Explicit cross-dialect requests always delegate to the established source-aware
translation path and report whole-fragment regeneration. Original-dialect source is
never grafted into the requested target. Each Engine graph fragment must still be
constructed with its own source dialect.

## Refusals and embedding

- Exactly one statement is required. AST changes made independently of the source
  are refused; edit the source first and construct a new fragment.
- Existing pipe semantic refusals remain authoritative.
- Hint placement must survive a proved native-parent patch, or the original
  hint-owning SELECT must remain completely unchanged. Other regeneration of
  hint-owning SELECTs is refused. Hints in unchanged native subqueries can be retained.
- Query-level ClickHouse `SETTINGS` on a pipeline head is refused: moving it across
  a new CTE boundary is not proved safe. SETTINGS inside an unchanged native subquery
  can be retained. This is an explicit supported/refused boundary, not silent removal.
- A named parameter whose occurrence/name interval cannot be proved is refused,
  rather than guessed from colon punctuation. Tested styles include `:name`, `@name`,
  `$name`, `%(name)s`, `{name:Type}` and positional `?`.
- No fatal/cancellation exception is caught or converted into a successful result.

With `embedded = true`, final target tokenization removes actual statement-separator
tokens and adds a terminating newline. Semicolons/pipe markers/parameter-like text
inside literals and comments remain text. The newline protects an enclosing
`WITH stage AS (...)` closing parenthesis from a trailing line comment. Original
source coordinates remain unchanged in diagnostics/parameter occurrences.

Engine may continue applying its own slot and binding source edits **before** making
the fragment. This API has no knowledge of Rel, slot values or graph ownership. The
parameter intervals let Engine replace its ambiguity-prone punctuation scan with
parser-proved coordinates with the `0.17.0` release.

### Portable DuckDB projection markers (DDB-001, fixed in 0.18.0)

The published `0.17.0` parser rejects bare `SELECT :name` and parenthesized
`(:name)` projections because it confuses them with prefix-alias syntax. The
SQL-side correction is documented in [extension 33](brikk-extensions.md#33-duckdb-prefix-alias-lookahead-and-portable-template-markers-ddb-001):
only an actual identifier can introduce a DuckDB prefix alias. The source remains
unchanged, and actual parameter/name intervals remain available to Engine.
This is still a portable template contract: Engine must adapt/bind markers for
DuckDB JDBC, not execute raw `:name` as native DuckDB syntax. Engine can replace
its published-version blocker reproducers after integrating `0.18.0`.

For local consumer testing, all four SQL artifacts are now installed in Maven Local
as **`0.18.0-SNAPSHOT`**, including this fix and the DuckDB 1.5.6 reference update.
Resolve Maven Local before Central and use
`dev.brikk.house:brikk-sql-jvm:0.18.0-SNAPSHOT` in the SQL consumers. The installed
JAR passes the original DDB-001 source/parameter-range reproducers on Java 21;
this is a test candidate, not a Central publication or completed Engine integration.

## Verification and delivery

Focused tests:

```sh
./kotlin test -m brikk-sql -m brikk-sql-verify --include-classes='*SourcePreserving*'
```

`SourcePreservingTest` covers text differences, dialect aliases, UTF-16 ranges,
ambiguous colon punctuation, hints/settings boundaries, native and nested pipes,
recursive CTEs, nested FROM-first, identical repeated source text, literals/comments,
cross-dialect delegation, and AST/namespace refusals.

`SourcePreservingLoweringTest` verifies actual DuckDB grammar and result rows against
both expected results and the existing lowering path: native casts, input binding,
nested pipelines, recursive/colliding CTEs, repeated WHERE after SELECT/EXTEND,
DISTINCT, ORDER/LIMIT/OFFSET, quoted join namespaces, embedding and Unicode/literal
punctuation. It also exercises the vendored Doris FE grammar, including retained hints.

Verified before release: `./kotlin build`, all 1,090 Kotlin tests (one optional
native integration test skipped), and nine offline release-configuration tests.
Exact known-failure signatures and intentional-divergence entries are unchanged.
After publication, Engine can update all three consumers to `0.18.0`, adopt this
signature, and complete ENG-01 integration with its own build and full suite.
