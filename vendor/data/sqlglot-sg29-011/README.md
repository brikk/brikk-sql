# SG29-011 PostgreSQL mapping qualification

Five separate sub-rounds are covered by core regressions and private native
PostgreSQL/DuckDB tests. The global oracle remains
`v30.22.0-5-ga00dc2e5c`; generated tables/corpora are not refreshed.

1. MAKE_INTERVAL, #8800/#8801, `5739cd6c`/`53ccce9f`: named arguments own their
   canonical units, including mixed positional/named calls and mins/secs.
   Anonymous markers still bypass canonical conversion. Unknown names,
   duplicates, too many arguments and positional-after-named calls remain
   anonymous rather than silently dropping/reassigning input. Native invalid-call
   acceptance is not claimed. DuckDB's existing lowering recognizes the named
   aliases without mutating the input.
2. DATEDIFF, #8802/#8810, `8709510f`/`991d6ce5`: DuckDB DATE_DIFF carries the
   boundary-counting flag for literal non-week units. PostgreSQL preview
   arithmetic truncates the endpoints for reviewed sub-day/month/quarter/year
   units. Forward/reverse boundary examples execute on PostgreSQL. Existing
   strict DuckDB cross-engine temporal refusals remain. Week-start conventions,
   general week arithmetic, dynamic units, zones and nanosecond types are not
   newly qualified.
   The existing PostgreSQL DAY lowering returns int4 while DuckDB DATE_DIFF
   returns BIGINT; the tests record that distinction, not a cross-engine type
   equivalence claim. Strict cross-engine temporal execution remains refused.
3. ISO formats, #8672, `1e5c649c`: IYYY/IW and lower-case spellings map to %G/%V,
   distinct from calendar year and non-ISO week numbers. Native tests cover
   year boundaries in both directions.
4. SHA2, #8764, `78b24a74`: native PostgreSQL SHA256/384/512 are binary digest
   nodes. Generic SHA2 hex generation wraps the native digest in ENCODE(...,
   'hex'). PostgreSQL tests check actual bytea/text metadata, digest bytes and
   NULL. ASCII literal controls do not qualify arbitrary text-column to bytea
   coercion, encodings or every other backend's digest sizes.
   DuckDB SHA384/512 strict generation now refuses instead of merely warning and
   silently executing SHA256. A native DuckDB BLOB/byte comparison covers the
   supported SHA256 binary conversion only.
5. Regex flags, #8755, `eb03a972`: literal i/c flags select ~*/~ with the last
   case flag winning. Unsupported or dynamic flags produce a preview finding
   and refuse strict execution instead of silently disappearing. Native ASCII
   Boolean/NULL controls are finite examples, not a general regex-language or
   Unicode equivalence claim. Existing DuckDB regex execution refusals remain.

The source/target tests are `PostgresMappingCatchupTest`,
`PostgresMappingResultTest` and `PostgresMappingDuckdbResultTest`. PostgreSQL
instances and DuckDB in-memory connections are test-owned, with no user data or
connections. No extension installation, host kernel/trust-store change or
production IDE API change is required. Existing public JVM signatures and
data-class constructor/copy shapes are retained.

Starting state and the verified native coverage report are saved under
`build/sg29-011/`. Logs use `/var/tmp/jayson/opencode/sg29-011-*.log` and retain
failed iterations. SG29-012/013 remain separate commits after this item passes;
the owner approved commit/push between each item. No publication or downstream
adoption follows from this work.

## Frozen-pin and audit review

`ledger-review.json` records exactly five new frozen-pin rows across four files.
Existing rows/reasons/signatures remain unchanged. The SHA384 rows distinguish
native binary parsing, binary typing and generic hex generation. Two Trino
preview rows activate its existing DAY endpoint truncation after the DuckDB
boundary flag is added. Those rows do not qualify native Trino execution.

`native-coverage-review.json` reviews exactly three SHA384 current-parser SQL
origins, now binary UNHEX(SHA256) previews rather than hex SHA256. All retain
their unsupported-size finding, and strict execution refuses them. Their parse
acceptance is not SHA384 result equivalence. The other 24,603 origins and every
local disposition remain unchanged under identical engine/JDBC/settings. Only
the four reviewed ledger hashes change the inventory; substituting their old
hashes reconstructs the complete old inventory fingerprint. The strict audit
uses a new exact baseline and retains all historical baselines.

## Final validation

All modules build. Core passes 1,793 tests with no skips or assumption aborts;
other JVM modules pass 756 tests with 72 existing optional assumptions and no
failures. Shared IntelliJ passes all 827 tests on `261.25134.95`, using the
genuine signed-window fixture. Python discovery passes 228 tests with one optional
skip. Nine tested source hashes are retained in `build/sg29-011/source-manifest.json`.
No global pin, generated corpus/table, public data-class shape or IDE production
API changes are included. Commit/push is authorized before SG29-012 begins.
