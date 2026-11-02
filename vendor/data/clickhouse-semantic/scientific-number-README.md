# CHSEM-022 scientific numeric boundaries

The ClickHouse dialect now separates compact binary +/- from scientific-number
tokens. A sign is part of an exponent only immediately after E/e, and a decimal
point cannot extend an exponent. Thus 1e0+2.0 parses as Add with two numeric
operands, while 1e-2 and 1e+2 retain their exponent signs.

The rule is an opt-in immutable TokenizerConfig policy. The ClickHouse dialect
enables it; the base and raw generated tokenizer configurations keep legacy
SQLGlot behavior. Other configuration builders preserve the policy. No generated
tables, corpus ledgers or published constructor/default bridge are changed.
The existing 26-field constructor and defaults bridge are checked by reflection.

Tests cover token boundaries, exponent signs, arithmetic ASTs, type inference,
UTF-16 offsets, comments/strings, editor recovery, script framing, cancellation,
SQL generation and serde. Both preserved CHSEM-021 counterexamples now have Add
ASTs and Float64 types; their earlier captures remain unchanged and a separate
refinement records the corrected result.

`scientific-number-cases.json` contains 22 original/generated finite witnesses on
both exact pins. They include compact and spaced operators, signed exponents,
unary signs, precedence and the large-double infinity case. Every generated SQL
form has the same observed value/type as its original. Native lexer and numeric
parser excerpts are tagged/hashed in `scientific-number-sources.json`.

This is a scoped lexer correction, not complete numeric grammar validation or a
new arithmetic family. Closeout must retain a separate observation: a root
multiplication such as SELECT 1e0*(2e0+3.0) can keep the generic Nullable(Float64)
annotation even though native metadata is Float64. That pre-existing propagation
gap is not fixed by changing scientific token boundaries. Further feature expansion
is deferred to the user's final scope decision.

Validation: full JVM regression passes 1,913 tests with 61 optional integration
assumption aborts and zero failures. Complete Python discovery passes 136 tests
with one opt-in skip using the unchanged SQLGlot pin, restored after temporary
cache loss at the server restart. New finite/refinement captures regenerate
byte-identically; historical source/native captures and parity fixtures are intact.
No new IntelliJ API calls, release, publication, consumer or toolchain changes.
The IntelliJ gate passes 757 tests on 261.25134.95; other IDE versions were not run.
ClickHouse-only Python discovery passes 88 tests.

Reproduction:

```sh
./kotlin test -m brikk-sql --include-classes='*ClickhouseScientificBoundaryTest' \
  --jvm-args='-Dbrikk.clickhouseScientificOut=/tmp/opencode/scientific-brikk.json'
python3 tools/probe_clickhouse_2026.py \
  --cases vendor/data/clickhouse-semantic/scientific-number-cases.json --task CHSEM-022 \
  --brikk /tmp/opencode/scientific-brikk.json --output /tmp/opencode/scientific-native
```
