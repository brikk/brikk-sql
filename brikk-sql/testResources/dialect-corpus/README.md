# sqlglot dialect test corpus

Machine-extracted from the inline dialect test assertions of
[sqlglot](https://github.com/tobymao/sqlglot) (`tests/dialects/test_*.py`),
pinned at **v30.22.0-5-ga00dc2e5c** (see `reference/sqlglot`).

sqlglot is Copyright (c) 2025 Toby Mao and released under the MIT License.
This corpus is a mechanical transformation of its test suite and carries the
same license and attribution.

## Regeneration

```
python3 tools/extract_dialect_tests.py
```

## Semantics

- `identity`: parse `sql` under `dialect`, regenerate under `dialect`
  (`pretty`/`identify` as flagged); result must equal `expected` if non-null,
  else `sql`. Entries flagged `check_command_warning` parse into a bare
  `Command` node with a warning in sqlglot — the round-trip still holds.
- `transpile`: for each `read` entry, parse under that dialect and generate
  under `dialect` (unsupported errors ignored); result must equal `sql`.
  For each `write` entry, parse `sql` under `dialect` and generate under the
  entry's dialect; result must equal the entry value — unless the value is
  `{"error": "UnsupportedError"}`, in which case generation with
  `unsupported_level=RAISE` must raise.
- Dialect `""` (file `base.json`) is sqlglot's generic dialect, including the
  pipe-syntax gate tests from `test_pipe_syntax.py`.

## Stats

| dialect | identity | transpile | skipped_dynamic |
|---|---|---|---|
| athena | 52 | 1 | 0 |
| base | 102 | 286 | 44 |
| bigquery | 368 | 296 | 31 |
| clickhouse | 332 | 96 | 25 |
| databricks | 146 | 32 | 1 |
| doris | 44 | 18 | 1 |
| dremio | 43 | 7 | 2 |
| drill | 4 | 8 | 0 |
| druid | 10 | 0 | 3 |
| duckdb | 393 | 244 | 17 |
| dune | 2 | 0 | 1 |
| exasol | 84 | 66 | 11 |
| fabric | 45 | 3 | 0 |
| hive | 74 | 97 | 2 |
| materialize | 18 | 6 | 0 |
| mysql | 415 | 114 | 41 |
| oracle | 184 | 34 | 12 |
| postgres | 479 | 100 | 20 |
| presto | 49 | 146 | 3 |
| prql | 0 | 29 | 0 |
| redshift | 135 | 52 | 4 |
| risingwave | 8 | 1 | 0 |
| singlestore | 108 | 116 | 2 |
| snowflake | 857 | 533 | 38 |
| solr | 3 | 0 | 0 |
| spark | 117 | 140 | 4 |
| sqlite | 129 | 57 | 3 |
| starrocks | 86 | 23 | 9 |
| tableau | 0 | 7 | 0 |
| teradata | 76 | 24 | 0 |
| trino | 121 | 4 | 6 |
| tsql | 264 | 228 | 43 |
| **TOTAL** | **4748** | **2768** | **323** |

Coverage: 7516 of 7670 textual `validate_*` call sites
(98.0%). Skipped calls use runtime-computed arguments (loops,
variables, f-string interpolation) and cannot be extracted statically.
