# SG29-013 Doris full-text operators

Review of SQLGlot #8585/#8607, `f3a6eb95` and `a0cc28b9`, following separately
committed/pushed SG29-011 and SG29-012. The global oracle remains frozen.

The handwritten Doris range parser models MATCH, MATCH_ANY, MATCH_ALL,
MATCH_PHRASE, MATCH_PHRASE_PREFIX, MATCH_PHRASE_EDGE and MATCH_REGEXP with the
existing Match node. It preserves bitwise/parenthesized operands, prefix/infix
negation and identifier or string analyzers. Lookahead retreats when no right
operand is present, preserving implicit aliases named match_any and similar
unreserved names. Other inherited range behavior remains delegated to MySQL/base
parsing, including deferred IS precedence.

Match receives exactly two optional args, kind and analyzer, with both legacy
constructors unchanged. The frozen global arg-types manifest is not rewritten.
Its exact gate still verifies all classes and the full old Match shape before
requiring the reviewed suffix, alongside SG29-012's Values suffix. Native origin
is retained in serialized metadata. Doris rendering preserves every kind and
analyzer; other targets explicitly refuse unqualified full-text execution,
including after serde. Foreign generic MATCH is not assumed equivalent to Doris
tokenization/index semantics. Preview findings are distinct from execution.

## Native qualification boundaries

`source-provenance.json` pins exact Doris 4.1.5 grammar and Java opcode/type/
nullability implementation at `917b20be83878e98f94e3ebf7efccc80346e3e50`.
The grammar permits value-expression operands and identifier-or-text analyzers.
The native implementation maps plain MATCH to MATCH_ANY, returns Boolean and
propagates nullability from either operand. That is a source-backed contract,
not a substitute for runtime inverted-index/tokenizer behavior.

The private FE capture uses the released 4.1.5 image. Parsed-plan controls check
operators, quoted analyzers, parentheses, aliases, negation and structured
operands. Analyzed-plan controls bind the seven operators against a synthetic
STRING relation. Original and generated plans are compared after normalizing
synthetic node IDs; string operands and analyzer text are retained. These are
native FE grammar/binding observations, never backend rows or search-result
equivalence. Analyzer catalog availability and physical index requirements are
not validated by the library's structured parser.

The host vm.max_map_count remains 1,048,576, below the earlier backend image's
2,000,000 startup requirement. No backend is started, guard bypassed or host
kernel setting changed. The FE uses only a task-private container, no network,
host bind mounts or published ports. Source/query data are synthetic and no
user connection or trust store is touched.

The test-only `tools/probe_doris_full_text.py` starts no services and refuses a
nonisolated container. It captures paired FE parsed/analyzed plans and explicit
outbound refusals through the public Java facade. Core regressions distinguish
structured AST support, same-dialect generation and foreign execution refusal;
none calls opaque Command preservation full-text semantic support.

Starting state and native coverage are retained under `build/sg29-013/`. Logs
use `/var/tmp/jayson/opencode/sg29-013-*.log` and retain failed attempts. A fresh
`--build-dir=build/sg29-013-build` avoids the earlier incremental classpath cache
failure without deleting previous outputs or the genuine signed-window fixture.
Commit/push is authorized after this last item passes. SG29-014 onward,
publication and consumer adoption remain outside the request.

## Validation record

All modules build. Core passes 1,804 tests with zero failures, skips or assumption
aborts. Other JVM modules pass 758 tests with zero failures and 72 existing
optional assumptions. Six new core methods cover the seven variants, structured
operands, analyzers, negation, implicit aliases, constructor/type contracts,
serialization, parent links and strict foreign-target refusals. Python discovery
passes 228 tests with one optional skip; the new capture script passes py_compile.

`native-fe-plans.json` records 21 paired parsed plans, seven paired analyzed
plans and eight outbound refusals on the pinned FE. `final-facade-replay.json`
reproduces their generated SQL and dispositions using the old published consumer
facade on actual Java 17. All 2,857 compiled core and 143 metadata classes retain
Java 17 bytecode. `native-coverage-review.json` verifies that all 24,606 native
origin/SQL/status/type tuples, inventory, local dispositions and engine settings
remain unchanged. No parity ledger or native-audit baseline is updated.

Eight final source hashes are retained in `build/sg29-013/source-manifest.json`.
No global pin, generated registry/typing table or IDE production source is
changed. The private FE container has been stopped and removed. There is still
no backend row, inverted-index/tokenizer or analyzer-catalog execution claim.

The final shared IntelliJ gate passes all 827 tests on `261.25134.95`, with no
exclusions, new IDE API calls or production IDE edits. It uses the fresh build
artifacts and genuine signed-window fixture. SG29-013 is ready for its separately
authorized commit/push; the three-item sequence ends here.
