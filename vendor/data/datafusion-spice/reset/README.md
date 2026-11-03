# DataFusion RESET coverage

Resolves `DFS-GAP-612e0f186332398a` from the DFS-011 refresh. The original round11
summary remains historical evidence of that gap.

`DatafusionReset` stores configuration-name components separately and retains quoted
component values. Rendering preserves those components rather than copying DFParser's
display string, which drops quote delimiters. Keyword names and adjacent words follow
the pinned parser's grammar, including `RESET time zone`. `ALL` is represented as a name;
this capture makes no promise about executing a reset-all operation.

The 25 controls include 12 accepted statements/scripts and 13 rejected inputs. Every
accepted regeneration is accepted by DataFusion 55.2.0 with identical native RESET ASTs.
Missing names, malformed dots, assignments, numeric names, unsupported string kinds and
malformed script tails remain rejected. The corpus exercises ordinary, double-quoted,
single-quoted and E-string components; it does not qualify every GenericDialect quote form.

These are parser-only checks. RESET is classified as modifying state and is not executed
by the capture. Cross-dialect generation remains refused; Spice retains its own RESET node.

```sh
./kotlin run -m dialect-audit -- datafusion vendor/data/datafusion-spice/reset/cases.json vendor/data/datafusion-spice/reset/brikk.json
python3 tools/probe_datafusion_spice.py --upstream build/datafusion-reference/debug/brikk-datafusion-reference \
  --cases vendor/data/datafusion-spice/reset/cases.json --brikk vendor/data/datafusion-spice/reset/brikk.json \
  --output vendor/data/datafusion-spice/reset/native.json
```

For later checks, use new output paths instead of replacing these captures.

Validation: all 1,363 core tests passed; 31 focused IDE tests passed on each of
261.25134.95, 262.10968.63 and 263.5701.42, including native RESET editor diagnostics
and API containment. The 49 DataFusion Python evidence/tracking checks also passed.
