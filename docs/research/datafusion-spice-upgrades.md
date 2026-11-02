# DataFusion and Spice upgrade workflow

DFS-011 adds immutable candidate captures and a deterministic comparison tool. It leaves
the supported DataFusion 55.2.0 and Spice 2.4.0 defaults in place. Weekly checks are deferred.
The CI changes are functional tests in the existing release gate and manual native captures.

## Refresh one product

Build the pinned references using `tools/datafusion-reference/README.md`. A candidate version
needs a reviewed reference adapter and lockfile for that version. The refresh rejects a
binary whose reported product, parser revision, dialect or normalization profile disagrees
with the candidate. DataFusion's actual `version()` is checked as well. Spice's executable
checksum and `--version` must match its declared release.

Produce current Brikk observations once for the stable corpus. Use the same generated SQL
when comparing two engine versions, so generator edits do not obscure engine changes:

```sh
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/cases.json build/review/brikk-datafusion.json
./kotlin run -m dialect-audit -- spice vendor/data/datafusion-spice/cases.json build/review/brikk-spice.json
```

The refresh commands are:

```sh
python3 tools/datafusion_upstream.py refresh datafusion \
  --reference build/datafusion-reference/debug/brikk-datafusion-reference \
  --brikk build/review/brikk-datafusion.json --output build/review/datafusion-current

python3 tools/datafusion_upstream.py refresh spice \
  --reference build/spice-parser-reference/debug/brikk-spice-parser-reference \
  --spiced /path/to/checksum-pinned/spiced \
  --brikk build/review/brikk-spice.json --output build/review/spice-current
```

Each command captures the catalog, native constraints where available, introspection samples,
keywords, settings/defaults, candidate type probes, parser verdicts and bounded execution
results. It runs original and generated SQL through the same stable corpus. Negative syntax
and same-name function controls are included. Search/AI calls and DDL/DML examples remain
parser-only; execution flags come from the reviewed, checksum-pinned corpus.

Spice starts in a disposable directory with the local Spicepod, two CPU cores and no models
or remote datasets. It has no option for a user server address. Network, startup and reference
protocol failures abort capture rather than becoming SQL rejections.

Every output directory must be new. Raw evidence, the profile, normalized inventory and source
graph are hashed in `manifest.json`, which is written last. The manifest has a content-derived
snapshot ID. Comparison verifies every checksum, checks for missing/unsealed JSON files, and
rebuilds the normalized view from the raw evidence. It does not accept an edited cached view.

## Propose a new pin

Export the current profile as a starting point:

```sh
python3 tools/datafusion_upstream.py profile datafusion --output build/review/datafusion-candidate.json
python3 tools/datafusion_upstream.py profile spice --output build/review/spice-candidate.json
```

Edit a copy with a new profile ID, release, full source commit, reference build/lock hashes,
expected parser identity, feature configuration and, for Spice, release asset and executable
hashes. Paths in profiles are repository-relative or absolute. Build the reviewed candidate
reference and run the product's refresh command with `--profile` and a new output directory.
Do not merely change a binary's label. The existing reference packages stay pinned; a future
version may need another reference package or an adapter change reviewed alongside its pins.

Source overrides must point to the candidate's source commit. Keywords can be reused only
when the exact sqlparser version, source and checksum agree, including any fork patches.
An optional `function_schemas` file must be checksum-pinned and identify the candidate profile;
its `functions` object maps `KIND:name` to observed output contracts. Dynamic or uncaptured
table-function schemas remain explicitly unknown.

`raw/generated-metadata.json` contains a candidate Kotlin catalog and its hash. The generator
runs twice against the same raw inputs and must produce identical bytes. This is a preview;
the refresh never writes into `brikk-sql-metadata`. Supporting another release still requires
reviewed snapshot registration that retains earlier supported versions.

## Dependency graph

The source capture reads complete Git trees at immutable commits. A truncated tree is a
capture failure. It records watched file hashes by component and retains raw commit/tree
responses and Cargo.lock text. The graph follows the actual DataFusion and Arrow Git pins
in Spice's lockfile, rather than substituting upstream packages with matching version names.

Watched components cover:

- Spice release tag target, build manifests, parser wrapper/session defaults, function
  registration, search/AI/JSON implementation areas and runtime/release tests.
- DataFusion's parser, optimizer/type logic, catalogs/session code, function registries and
  sqllogictests, for upstream and the Spice fork independently.
- Arrow types and Flight protocol code in a resolved Arrow fork, plus locked sqlparser,
  Arrow, JSON/Spark, protobuf and transport dependencies.
- The declared Flight SQL JDBC coordinates and checksum used by the IDE. A changed driver
  pin is a transport review item, not evidence that the new driver's operations work.

Cargo.lock presence is not treated as proof that an optional package was compiled. The
DataFusion reference has an explicit feature set. Spice reports `models`, while its other
compiled features remain partially known. Missing functions under changed or partially known
features are availability changes, not asserted product removals.

## Compare and review

```sh
python3 tools/datafusion_upstream.py compare build/review/datafusion-old build/review/datafusion-new \
  --output build/review/datafusion-diff.json
```

The JSON report is deterministic. It gives each change a stable `DFS-UP-...` follow-up ID,
profile boundaries, before/after values and an impact category: metadata-only, parser,
generator, type/scope, IDE, transport or breaking behavior. Breaking behavior is a review
category for potentially incompatible contracts, not an automatic verdict about an upgrade.

Comparison includes names, aliases/canonical identity, scalar/table kinds, native signature
constraints, finite overload evidence, observed return types, descriptions/examples, keywords,
settings, probed types, table-function schemas, native parser outcomes and execution values/types.
An accepted-to-rejected parse is reported as a regression. A rejected-to-accepted parse is
reported as increased permissiveness, including negative controls. Unchanged parse acceptance
does not hide changed execution results.

Unknown signatures stay unknown; samples do not become exhaustive overloads. Failed inventory
queries do not become mass removals. Missing native verdicts and changed corpus inputs become
coverage gaps. Expected local refusals of SQL that both native parsers reject are recorded
separately from missing infrastructure. Two errors never count as equivalent execution.

Use `--fail-on-change` for a strict gate: it writes the report, then returns exit code 2 if
there are changes or coverage gaps. Ordinary comparison returns success after producing the
report; it does not approve any change. Every report has `promotion_allowed: false` and lists
the remaining release gates. Unknown table-function contracts are listed separately.

Before changing defaults:

1. Re-run the same stable corpus on both pinned profiles with current Brikk output. Retain both
   manifests and raw captures. Review parser, type and value changes, not just parse success.
2. Review source/dependency changes and feature scope. Resolve or explicitly exclude gaps.
3. Review the generated catalog and retain earlier supported metadata snapshots. Run the
   metadata generator determinism checks and core/native tests.
4. Re-run Flight SQL metadata/execution probes, including the DFS-010-T1..T4 negative controls,
   and the shared IDE platform/ABI gates before qualifying a consumer driver/runtime pair.
5. Update the support matrix, compatibility notes and consumer handoff. Publication and default
   promotion require separate authorization; this tool provides neither.

The current default policy remains the pinned snapshots with labeled fallback. A candidate
capture is not another supported snapshot. DFS-012 owns the final consumer support matrix.

## Functional and manual CI

The existing `.github/workflows/test.yml`, also used by publication workflows, now runs the
offline DataFusion/Spice evidence and tracking tests. The manual `datafusion-upstream.yml`
workflow exercises the controlled-delta contracts, builds the selected reviewed reference,
exports fresh Brikk observations and captures a native candidate. Its token has read-only
repository access. It uploads evidence without committing, opening pull requests, publishing,
or changing defaults. A missing native prerequisite makes that job fail.

Manual CI qualifies one supplied profile per run. Capture the old and candidate profiles
separately and compare the downloaded snapshot directories. The native-capture job is not
a substitute for the final IDE/driver/release gates above. There is no weekly trigger.

## Demonstrated delta

`vendor/data/datafusion-spice/round11/controlled/` is explicitly synthetic evidence, not a
new engine release. It uses the same normalizer, immutable storage and comparator as native
captures. Its report detects a function addition/removal, a signature/return change, a new
keyword, setting and table-function schema changes, a parser regression, newly permissive
negative syntax, and a semantic result change.

```sh
python3 tools/datafusion_upstream.py demo --output build/review/controlled-demo
python3 tools/datafusion_upstream.py compare build/review/controlled-demo/before build/review/controlled-demo/after \
  --output build/review/controlled-repeat.json
cmp build/review/controlled-demo/report.json build/review/controlled-repeat.json
```

The tests also cover aliases, same-name scalar/table functions, incomplete captures, disabled
features, unknown signatures, changed SQL, old source overrides, mismatched versions, tampered
manifests, truncated source trees, deterministic follow-up IDs and CI exit behavior.

## Validation and handoff

The round passed 51 offline Python checks, including 22 upgrade-tool contracts. Both workflow
files parse as YAML. The controlled report has 12 changes and no coverage gaps; regenerating
and comparing it produces identical bytes.

Both real refresh commands also completed against the current pins with fresh Brikk output:
76 stable cases per product, 320 DataFusion name/kind entries and 447 Spice entries. The source
graph covers the upstream DataFusion repository and, separately, Spice plus its resolved
DataFusion and Arrow forks. All configured watch components matched actual source paths.
`round11/native-refresh-summary.json` records the snapshot IDs, manifest hashes and verdict
counts. The complete local validation snapshots are under `/tmp/opencode/dfs011/*-final`;
manual CI uploads the complete snapshot directories for review. Archive approved snapshots
with the versioned evidence before those temporary files or CI artifacts expire.

The real DataFusion self-comparison also exposed a pre-existing coverage gap:
`DFS-GAP-612e0f186332398a`, `DFS-reset/generated/parse`. Its native parser accepted RESET but
the exporter then supplied no generated statement. The subsequent
[RESET parser/render correction](../../vendor/data/datafusion-spice/reset/README.md) resolves
that gap with native round-trip evidence; the round11 summary remains unchanged. Spice's self-comparison
has no missing-verdict gaps. Dynamic function-output schemas remain labeled unknown for both.

No supported catalog, Kotlin/IDE API, driver pin or default version changed in this round.
The existing release hold and DFS-010 transport limitations remain in force.
