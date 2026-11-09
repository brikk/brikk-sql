# Frozen-target disposition ledger

The owner authorized SG29-019 and SG29-020 after SG29-018 was pushed as
`2d7a19f1`. The unrelated cancellation fix is separately pushed as `17a3d5d4`,
with full build, 2,598 JVM tests and all 829 IntelliJ tests passing. Its old-code
regression failures and final green logs remain in the approved task log folder.
This ledger covers every one of the 250 commits from
`a00dc2e5c489214bea53bacbbeba8713f21ebce1` through frozen release
`2fe25297af5b0f44f5c9e5d03e1b296424b7acbe`, tag `v30.29.0`.
The later changelog commit `f7a36727` is not in that range.

`dispositions.json` records each full commit ID, subject, changed paths, patch
SHA-256 and its explicit review group. Every group has a disposition, rationale,
evidence path and owner. Group membership is a hand-reviewed list of commit
IDs, not a subject/path classifier. New or omitted commits fail the checker.
The recorded history hash also detects ordering changes.

## Boundaries

Earlier tested ports retain their evidence and native limits. The DuckDB-only
DDB20-054/055 audit is reused, not relabeled as a global port. Both published
sort nodes and their serde identities remain. Query-plan optimization, Python
packaging/bindings/diff and unsupported dialects are not new features here.
Upstream external integration-test submodule revisions do not mean those
external suites ran locally.

The review identifies real integration prerequisites in shared quoting, nested
EXPLODE scope, generated case-sensitive flags and REF metadata/parser handling.
They are assigned to SG29-020, not marked implemented by reading a diff. Other
supported-source candidates have individual rationales and owners; their exact
target corpus differences must be reviewed during integration, never accepted
by aggregate failure counts. Unproved regex/native mappings remain deferred.

The original three investigation limits remain explicit:

- The malformed COPY probes did not reproduce an infinite loop. Kotlin's
  `parseVar(anyToken = true)` advances over punctuation. A defensive progress
  guard and bounded regression do not retroactively make that a reproduced bug.
- `Expression.isInt` uses bounded `toLongOrNull`, not Python float-to-int
  conversion. Huge exponent inputs do not justify importing Python's definition
  of an integral float or its unbounded integer materialization.
- There is no public `replace_tables` or `replace_placeholders` equivalent.
  Structural `Expression.replace`/`transform` is not that helper. No new API is
  added to claim parity with an API Brikk does not expose.

## Reproduction

```sh
python3 tools/sqlglot_target_dispositions.py
python3 tools/sqlglot_target_dispositions.py --source /path/to/upstream
python3 -m unittest discover -s tools -p 'test_sqlglot_target_dispositions.py'
```

The second form verifies the exact full history, paths, subjects and patch
hashes against an upstream object store. `--write` regenerates the inventory
only after all 250 explicit assignments are present. It does not change the
oracle pin, generated Kotlin, fixtures, failure ledgers or native baselines.
Publication and downstream adoption remain outside this approval.
