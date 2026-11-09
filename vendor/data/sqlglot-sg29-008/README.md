# SG29-008 population and sample aggregates

This bounded port of SQLGlot #8632, commit
`f814e4cb2a8743bba63e8818bae25207bbd3966f`, leaves the global oracle at
`v30.22.0-5-ga00dc2e5c`. It follows the uncommitted A/B work in the same worktree.
No other Batch C item, publication or downstream adoption is included.

## Changes

The handwritten MySQL parser maps `STD` and `STDDEV` to `StddevPop`, and
`VARIANCE` to `VariancePop`. Explicit sample names retain their sample kinds.
MySQL generation spells canonical sample nodes `STDDEV_SAMP` and `VAR_SAMP`,
and population variance `VAR_POP`. Doris and StarRocks inherit these mappings.
Existing explicit population/sample standard-deviation names remain explicit.

The generated typing metadata already returns DOUBLE for all five canonical
classes. No generated registry, typing table, public data-class shape or JVM
signature changes are needed. New tests cover AST copy/serialization,
non-mutation, quoted Unicode identifiers, emitted source positions, window
frames, conservative nullability and caller-supplied transform overrides.
DataFusion/Spice anonymous aggregate handling is unchanged.

MySQL 8.4.11 and StarRocks 4.1.4 reject statistical DISTINCT syntax. Previews
preserve DISTINCT, but executable generation refuses it for these two targets.
No DISTINCT execution claim is made for Doris.

## Evidence

`native-results.json` records 36 generated native controls and six native alias
controls. The test-only public-facade bridge compiles against published 0.23.0
and runs the new classes on Java 17. The generated statements actually execute
on private MySQL 8.4.11 and StarRocks 4.1.4 instances. Their immutable image
references, versions, MySQL session modes, exact SQL and CLI outputs are saved.

The controls include empty, all-NULL, singleton and two-row DOUBLE input,
grouping and inline sliding windows. PostgreSQL/DuckDB sample defaults generate
explicit sample names in the MySQL family. Singleton population results are 0;
singleton sample results are NULL. Input 0,2 produces population standard
deviation 1, sample standard deviation sqrt(2), population variance 1 and sample
variance 2. Native metadata reports four DOUBLE columns without NOT_NULL.
Finite value comparisons use a tolerance of 1e-12; they do not certify general
floating-point algorithm, DECIMAL, overflow or coercion equivalence.

`doris-source.json` independently checks exact Doris 4.1.5 source registration
and signatures at `917b20be83878e98f94e3ebf7efccc80346e3e50`. STD/STDDEV bind
the population Stddev class and VARIANCE binds population Variance. Explicit
sample names bind different sample classes. Their signatures return DOUBLE.
This is source evidence, not a backend result run. The earlier host
vm.max_map_count startup guard remains intact; no kernel setting changed.

The new private DuckDB and embedded PostgreSQL regression classes execute
generated MySQL/Doris/StarRocks-source queries and check DOUBLE/float8 metadata.
DuckDB also checks grouped and sliding-window rows and serialized ASTs.

StarRocks 4.1.4 rejects named WINDOW references. Native StarRocks captures use
authored inline frames, not an unimplemented named-window lowering. Named
windows remain covered as AST/generation and DuckDB execution controls only.
The failed initial probe remains in the log; it is not counted as a value match.

## Reproduction and safety

`tools/probe_mysql_statistics.py` uses `tools/SqlglotPrecedenceProbe.java` and
accepts `--java`, `--classpath` and `--output`. Its runtime classpath needs the
compiled bridge, current core/metadata classes, Kotlin and serialization JARs.
It queries only `brikk-sg008-mysql` and `brikk-sg008-starrocks`, verifies network
isolation and refuses host bind mounts or published ports. It starts no service.
Both task-private containers were stopped and automatically removed after
capture. No user connection, data, host setting or trust store was changed.

Logs use `/var/tmp/jayson/opencode/sg29-008-*.log`. The starting file hashes,
diff and verified Batch B native-coverage report are retained in
`build/sg29-008/`. Failed red/build iterations remain available alongside final
runs. One iteration compiled an earlier MySQL-only DISTINCT guard while its
source was changing. Bytecode inspection exposed the mismatch; subsequent
validation runs compilation and suites sequentially against the fixed source.
The recorded successful native SQL is replay-checked through the final facade
before accepting the captures. Changes are uncommitted; nothing has been
pushed or published.

## Final validation

- All modules build. Core passes 1,773 tests. Other JVM modules pass 744 tests
  with 72 existing optional assumptions and no failures. The new tests comprise
  six core methods, two DuckDB result methods and one PostgreSQL result method.
- Python discovery passes 228 tests with one optional skip out of 229.
- All 827 IntelliJ platform tests pass on `261.25134.95`, using the existing
  genuine signed-window fixture, with no test exclusions or IDE production edits.
- The final Java 17 facade reproduces all 36 native-captured ASTs, previews and
  executable statements exactly. It also confirms all 12 DISTINCT refusals for
  MySQL/StarRocks. `final-facade-replay.json` records the final generator class
  hash, so the stale compilation iteration is not mistaken for final evidence.
- All 2,856 core and 143 metadata classes retain Java 17 bytecode, major 61.
- `native-coverage-review.json` confirms all 24,606 pre/post audit origin,
  SQL, status and type tuples are unchanged, as are local dispositions and the
  complete fixture inventory. No new audit baseline or parity ledger is needed.
- Final source hashes are saved in `build/sg29-008/final-source-manifest.json`.
  Removing only the four new parser lines reproduces its exact A/B starting
  hash. No generated table, global pin, core fixture or IntelliJ production
  source changed in this item.
