# Main integration, 2026-11-06

Merge `origin/main` at `aea71fd68e425de51b0010a4489a1e7e00cf10c4` into
`sqlglot-30-29-batch-a` at `a81516a7529009c861efe2a895ee9a12d5089d94`.
The merge preserves published history without force-pushing. It keeps main's
live-catalog refresh fencing, its regression tests and IntelliJ support
`0.4.2-SNAPSHOT` development versions after the 0.4.1 release.

## Confirmed failures and fixes

The merged baseline has three failing core tests and one failing native test.
Vendored inputs and expected native rows are unchanged.

After fixing the exponent defect, the boundary test exposed a test-adaptation
error for leading-dot numbers. It required Polyglot's single NUMBER token despite
the documented Brikk DOT + NUMBER policy. The assertion now checks the exact
Brikk token types/text and END offset. It still rejects a swallowed exponent or
keyword; compact CASE value/copy/serde assertions are unchanged. No lexer-wide
leading-dot migration or oracle exception is introduced.

- Number scanning consumed the `E` at the start of `END`. It now distinguishes
  exponent markers from word prefixes. Complete scientific notation and the
  existing incomplete-exponent validation behavior remain covered. Additional
  controls check END/ELSE/EXCEPT and exact UTF-16 token intervals.
- MySQL primary-key parts did not consume ASC/DESC. Ordering now wraps the
  identifier or column prefix separately, while unmodified key parts retain
  their existing AST classes. Index/UNIQUE prefix parsing uses the unordered
  helper to avoid recursive or double ordering.
- VALUES generation rendered attached relation joins before the VALUES alias.
  Local pagination now stays inside the derived relation, with attached joins
  after its alias and PIVOT. The same fix applies to VALUES-to-SELECT lowering.
  Rendering does not strip fields from or reparent the caller's AST.

The unchanged native fixture checks the original nested-join row `1\t1\t1`
and separately rejects malformed joins. Additional private in-memory DuckDB
controls compare paginated nested VALUES joins, including unmatched outer rows,
through copy/serde and both rendering paths. Compact CASE numeric results also
run against native DuckDB.

## Validation

Final task-private output was written to `build/main-integration-final-build`.
The earlier baseline/control output was written to `build/main-integration-build`.
Baseline logs were written to
`/var/tmp/jayson/opencode/main-integration-core-baseline.log` and
`main-integration-native-baseline.log`. These outputs and intermediate failed logs
were local scratch files on the author's machine, not part of the repository.

- Full build succeeds.
- Core passes all 1,863 tests. Other JVM modules pass 770 tests, for 2,633 total.
  The 72 existing opt-in assumptions remain; there are no new test exclusions.
- The unfiltered IntelliJ 261.25134.95 suite passes all 841 tests, including main's
  live-catalog refresh races and both cancellation regressions. It uses the real
  DuckDB window capture/extension fixture. `BRIKK_INTELLIJ_TESTS` is unset.
- All 247 tooling tests pass with `TEST_KOTLIN_RELEASE_MODEL=1`, without skips.
  All 16 vendored upstream files retain their pinned hashes.
- The code/documentation whitespace check passes. Git flags an upstream blank
  line at EOF in `01915_json_extract_raw_string.reference`; that pinned reference
  file is deliberately unchanged rather than normalized.
- Compiled ABI checks preserve all 2,691 prior non-local core class signatures
  and 138 metadata class signatures. Current class files remain JVM major <= 61.
- The complete existing parity and native-audit gates pass without changing a
  failure ledger, oracle pin, generated fixture or native baseline.

Final logs were written to the local scratch location `/var/tmp/jayson/opencode/` on the
author's machine, not part of the repository: `main-integration-final-build.log`,
`main-integration-final-core.log`, `main-integration-final-other-jvm.log`,
`main-integration-final-ide.log` and `main-integration-tooling.log`. The IDE's
platform log was written to `main-integration-final-ide-test.log`; ABI reports
were written to `main-integration-final-core-abi.json` and `main-integration-final-metadata-abi.json`.

No shared daemon or host/user database is modified. No publication or consumer
adoption is part of this merge. Previously documented feature deferrals retain
their existing scope; they are not relabeled as implemented by these gate results.
