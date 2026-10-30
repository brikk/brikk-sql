# DataFusion and Spice reference tools

Development-only references for DFS-001/002. They do not ship with core, register an IDE
verifier, or change either production dialect. The protocol is JSON Lines, version 1.

## Pinned profiles

- `upstream`: DataFusion 55.2.0, GenericDialect, identifier normalization enabled, a fresh
  in-memory session per execution request. Its lock was seeded from upstream release commit
  `500744d5c25499055e3b2ca46b022bc425989203` and retains Arrow 59.2.0. Optional Spice,
  Spark, and JSON packages are absent. Features are listed in its manifest.
- `spice`: parser from Spice's DataFusion revision
  `cf084aa85dfdc27b4736208f5c132828020774ea`, PostgreSQL dialect, with an adapted copy of
  Spice v2.4.0's CREATE TABLE CLUSTER BY wrapper. It does not bind functions or execute SQL.
  Its locked transitive Arrow packages are registry packages, not Spice's patched Arrow
  runtime. Parser observations must not be presented as a reconstructed Spice engine.
- Native Spice execution uses the released Linux x86_64 `v2.4.0+models` binary, verified by
  checksum. The runner starts and stops its own isolated loopback runtime with local views
  and no configured models or remote datasets. HTTP is used for these tests, not JDBC.

`vendor/data/{datafusion/55.2.0,spice/2.4.0}/dependency-provenance.json` records source pins,
lockfile and source hashes, release checksum, settings, profile limits, and refresh commands.

## Build and test

Rust 1.94 or newer is required by the pinned engine; the initial capture used rustc 1.96.0.
Cargo.lock files are committed. Ordinary builds must use `--locked`.

```sh
cargo build --locked --manifest-path tools/datafusion-reference/upstream/Cargo.toml --target-dir build/datafusion-reference -j 4
cargo build --locked --manifest-path tools/datafusion-reference/spice/Cargo.toml --target-dir build/spice-parser-reference -j 4
cargo test --locked --manifest-path tools/datafusion-reference/upstream/Cargo.toml --target-dir build/datafusion-reference -j 4
cargo test --locked --manifest-path tools/datafusion-reference/spice/Cargo.toml --target-dir build/spice-parser-reference -j 4
python3 -m unittest discover -s tools -p 'test_probe_datafusion_spice.py'
```

Both packages share the source directory but intentionally have separate dependency graphs.
If a local Git configuration rewrites public HTTPS URLs to unavailable SSH credentials,
use `GIT_CONFIG_GLOBAL=/dev/null CARGO_NET_GIT_FETCH_WITH_CLI=true` for the Cargo invocation.
This does not change the user's Git settings or the pinned source.

## Capture current Brikk behavior

```sh
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/cases.json vendor/data/datafusion-spice/brikk-observations.json
```

This captures every statement's AST/node kinds and generated SQL, or its parse/generation
error. It is an observation tool, not a test asserting that current defects are correct.
The initial pass uses `datafusion` because the production `spice` dialect does not exist yet.
Once it does, capture it separately instead of relabeling the older observations.

## Obtain the pinned Spice executable

Download the v2.4.0 release asset to a disposable directory:

```sh
curl -fL https://github.com/spiceai/spiceai/releases/download/v2.4.0/spiced_linux_x86_64.tar.gz -o /tmp/opencode/spiced-2.4.0-linux-x86_64.tar.gz
sha256sum /tmp/opencode/spiced-2.4.0-linux-x86_64.tar.gz
```

Expected archive SHA-256:
`4c8179e1a7983a06d907b4efff29b17f0c1d779159efe117c1cafcc3bd3cd918`.
After verifying it, extract the `spiced` member into a new temporary directory. The runner
independently requires the executable SHA-256
`257b9fd8cf1f46c1f4ddf0b6280ba5b6033b79c276ec6840dd5e5d81710b1002`
and checks `--version`. It does not install or replace a user's Spice executable.

## Run the native audit

```sh
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference --brikk vendor/data/datafusion-spice/brikk-observations.json --output vendor/data/datafusion/55.2.0/observations.json
python3 tools/probe_datafusion_spice.py --spice-parser build/spice-parser-reference/debug/brikk-spice-parser-reference --brikk vendor/data/datafusion-spice/brikk-observations.json --output vendor/data/spice/2.4.0/parser-observations.json
python3 tools/probe_datafusion_spice.py --spiced /tmp/opencode/spice-2.4.0-runtime/spiced --brikk vendor/data/datafusion-spice/brikk-observations.json --output vendor/data/spice/2.4.0/runtime-observations.json
python3 tools/capture_datafusion_spice_provenance.py
```

Run against reviewable copies under `build/` when checking an upgrade; compare before
replacing committed evidence. Pin bumps are explicit source changes, not automatic refreshes.

Every case has a stable ID, source reference, area, SQL, and an explicit `execute` flag.
The runner sends original and Brikk-generated SQL through each selected reference. DDL,
DML, search, AI, EXPLAIN ANALYZE, and malformed script cases are parser-only in this corpus.
Execution is limited to the reviewed finite SELECT fixtures. Do not point this development
tool at arbitrary editor text. Process/network/protocol failures abort the capture instead
of becoming SQL rejections. A missing generated query is recorded, never silently skipped
from the comparison.

## JSONL protocol

Input examples:

```json
{"id":"parse-1","operation":"parse","sql":"SELECT 1; SELECT 2"}
{"id":"execute-1","operation":"execute","sql":"SELECT 1 + 2 AS v"}
```

The upstream execution operation optionally accepts a `setup` array, run inside the same
fresh session as that request. Setup failures are distinct from SQL rejection. The Spice
parser returns `unavailable` for execution. Unknown request fields/operations are errors.

Responses include `protocol_version`, `id`, `profile`, `status`, and an evidence level.
Parse success returns statement kinds, native display SQL, and native debug ASTs. Debug AST
text is version-pinned diagnostic evidence, not a stable cross-version interchange format.
Parse errors retain the native message and explicit locations when supplied. Line/column
are one-based Unicode-scalar coordinates; byte and UTF-16 offsets are zero-based. If the
native error supplies no position, `position` is null. No accepted prefix is returned for
a malformed script tail.

Upstream execution returns names, Arrow field types, nullability, and rows as Arrow display
strings with SQL NULL separate. This is not a lossless nested/binary codec. Spice HTTP
captures raw JSON plus per-column `arrow_typeof` observations; it does not infer nullability.
JSON decimal lexemes are retained separately from strings. Empty-result type evidence remains
incomplete. Comparisons are within one profile/transport; matching observations do not certify
universal function equivalence. Two errors never count as equivalent execution.

## Historical documentation audit

`vendor/data/datafusion-spice/docs-audit-2026-10-29.json` preserves 352 documentation examples
and 24 focused probes from the earlier investigation. It records the original input hash,
stable content-derived IDs, and local observations. Its sources were unversioned docs and
its results are not native v2.4.0 evidence. The importer accepts the original research export;
the current small regression corpus and commands above do not depend on that temporary export.

See `docs/research/datafusion-spice-reference-baseline.md` for findings and remaining work.
