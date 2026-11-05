# SG29-014 wrong-result sub-round: StarRocks LIKE and maps

This round ports only the two wrong-result fixes the owner approved:
LIKE argument order, #8645/`f321207c8dcc2973d149744fcaa4d1a13edb0026`,
and map literals, #8682/`fc1472a0d54c4e8d0d6700b0a4dbe66f0f32707b`.
The rest of SG29-014 remains open. The global oracle stays frozen, and no new
public data-class fields, constructor/copy shapes or AST classes are needed.

## Implementation

StarRocks LIKE(value, pattern) now maps to value LIKE pattern instead of reversing
its operands. The base pattern-first function builder and other dialects are
unchanged. Native NULL operands stay NULL. StarRocks' native function grammar
accepts exactly two arguments; the parser rejects an unsupported third argument
rather than invent an ESCAPE mapping.

MAP{...} now builds ToMap(Struct(...)) rather than a struct value. Only the
literal is wrapped, leaving following/nested subscripts in place. Ordinary map
columns, qualified t.map, arrays and variadic MAP(k,v,...) calls stay separate.
The shared parser preserves column keys only when parsing actual map keys, so
t.k does not become the struct field name k. This also corrects that qualified
key path in DuckDB without changing ordinary struct-field handling.

Native literal rendering keeps every key/value expression and emits MAP{...}.
StarRocks bracket offset preparation annotates a copy, so copy=false generation
does not add type payloads or change the owned tree's parent links. Homogeneous
known map key/value types are inferred from the actual expressions, not forced
to VARCHAR struct-field keys. Mixed, empty and unknown domains remain
conservative; this is not a new general common-type/coercion implementation.

## Native evidence and limits

The task-private StarRocks 4.1.4 `4a9848e` instance uses an immutable image,
network none, no host bind mounts or published ports. Original and generated
statements must both execute. Controls compare scalar/map values, TYPEOF engine
type witnesses and protocol type/flags/decimals. Qualified keys, nested map and
array lookups, missing entries, NULL values, empty maps, NULL keys, duplicate
keys and variadic constructor regression controls are included.

Native StarRocks accepts NULL keys and resolves duplicate keys by keeping the
last value. Native DuckDB 2.0.0 rejects NULL and duplicate keys. Empty/default
and literal integer type widths can also differ. Correct syntax does not prove
general map equivalence, so strict map-literal execution across StarRocks engine
boundaries refuses. Corrected outbound/inbound preview syntax remains available.
DuckDB tests execute finite non-NULL/nonduplicate preview lookups and independently
assert strict refusal; they do not turn those examples into general coercion or
missing-key qualification. LIKE's finite Boolean/NULL controls execute normally.

`tools/probe_starrocks_like_maps.py` captures paired native value/type witnesses
and explicit map execution refusals through the public Java consumer facade.
It provisions no service and rejects a nonisolated container. All SQL uses
synthetic values, with no user database/connection, host kernel, trust store or
IDE production API changes.

Starting state and verified native coverage are saved under
`build/sg29-014-wrong-results/`. Logs use
`/var/tmp/jayson/opencode/sg29-014-*.log`, retaining red/failed iterations. A
standalone probe initially reused a JDBC statement after an error; that diagnostic
is not evidence of duplicate-key rejection. The corrected probe and native test
use separate statements and require actual Map-key errors.

Validation uses `--build-dir=build/sg29-014-build`, preserving previous outputs
and the genuine signed-window fixture. Commit/push of this bounded round is
authorized at the end. Other StarRocks features, publication and consumer
adoption require separate decisions.

## Validation record

All modules build. Core passes 1,810 tests with no failures, skips or assumption
aborts. Other JVM modules pass 760 tests with zero failures and 72 existing
optional assumptions. Six new core methods and two private DuckDB result methods
cover the bounded fixes and refusals. Python discovery passes 228 tests with one
optional skip.

`native-results.json` records 19 successful original/generated StarRocks value/
type pairs and six map execution refusals. `final-facade-replay.json` reproduces
them through the published consumer facade on actual Java 17. All 2,857 core
and 143 metadata classes retain Java 17 bytecode. The private native container
was stopped and automatically removed after capture.

`native-coverage-review.json` verifies that all 24,606 native origin/SQL/status/
type tuples, inventory, local dispositions and engine/JDBC/settings remain
unchanged. No fixture, parity ledger, native-audit baseline, generated registry/
typing table or global pin changes. Eight final source hashes are retained in
`build/sg29-014-wrong-results/source-manifest.json`.

The final shared IntelliJ gate passes all 827 tests on `261.25134.95`, using
fresh build artifacts and the genuine signed-window fixture. No exclusions,
new IDE API calls or production IDE source changes were introduced. The bounded
pair is ready for its approved commit/push; the rest of SG29-014 remains open.
