# Trino grammar parity

Reference grammar: Trino 484
(`core/trino-grammar/src/main/antlr4/io/trino/grammar/sql/SqlBase.g4`).

## Completed in the 484 upgrade

| Feature | Release | Required work |
|---|---:|---|
| `NEAREST` relation | 481 | Implemented as a separate relation node with generation, serde, input-column propagation, lateral correlation and unaliased input qualifiers. Registered in `TRINO_GRAMMAR_BUILTINS` and completion forms. |

No items from the original 483 parity list remain open. This is not a claim that
every Trino construct or cross-dialect translation has complete semantic coverage.
See [extension 58](brikk-extensions.md#58-trino-484-sql-and-metadata) for the upgrade.

## Remaining 484 work

- [ ] Refresh paired JSON evidence against Trino 484 and Doris 4.1.5. Capture
  engine versions, image digests, SQL inputs, results and failures for exponent
  rendering, decimal scale, duplicate keys/member order, and path semantics.
- [ ] Update the Trino/Doris semantic hazard records and generated metadata from
  those measured results. Candidate function-gap reports are not execution proof;
  retain historical evidence without relabeling it as a new-version result.
- [ ] Add repeatable paired regression tests and rerun the relevant certification
  and native-engine gates for any mappings changed by the evidence refresh.

The Trino DuckLake consumer reports no blocking API changes. Its metadata
dependency remains at 0.7.0; publishing refreshed artifacts and updating that
consumer pin are separate release/integration work, not completed by this branch.

## Verification

`Trino484Test` checks parsing, generation, serde, types and scope behavior.
`Trino484GrammarTest` checks authored and regenerated SQL with the native parser.
`Trino484ResultTest` executes correlated NEAREST and the 484 JSON, interval and
window-exclusion regressions against the official 484 server image.
