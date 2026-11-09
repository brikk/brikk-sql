# sqlglot dialect test corpus

Machine-extracted from the inline dialect test assertions of
[sqlglot](https://github.com/tobymao/sqlglot) (`tests/dialects/test_*.py`),
pinned at **v30.29.0** (see `reference/sqlglot`).

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
| athena | 61 | 1 | 1 |
| base | 118 | 298 | 47 |
| bigquery | 372 | 299 | 35 |
| clickhouse | 339 | 104 | 28 |
| databricks | 146 | 33 | 5 |
| doris | 57 | 20 | 3 |
| dremio | 43 | 7 | 2 |
| drill | 4 | 8 | 0 |
| druid | 10 | 0 | 3 |
| duckdb | 433 | 264 | 29 |
| dune | 2 | 0 | 1 |
| exasol | 85 | 66 | 11 |
| fabric | 47 | 3 | 0 |
| hive | 79 | 98 | 4 |
| materialize | 18 | 6 | 0 |
| mysql | 426 | 121 | 45 |
| oracle | 230 | 40 | 24 |
| postgres | 514 | 112 | 25 |
| presto | 51 | 145 | 3 |
| prql | 0 | 29 | 0 |
| redshift | 136 | 54 | 4 |
| risingwave | 8 | 1 | 0 |
| singlestore | 108 | 116 | 2 |
| snowflake | 873 | 548 | 51 |
| solr | 3 | 0 | 0 |
| spark | 121 | 144 | 7 |
| sqlite | 136 | 66 | 11 |
| starrocks | 144 | 28 | 12 |
| tableau | 0 | 7 | 0 |
| teradata | 78 | 32 | 0 |
| trino | 121 | 13 | 9 |
| tsql | 266 | 229 | 43 |
| **TOTAL** | **5029** | **2892** | **405** |

Coverage: 7921 of 8069 textual `validate_*` call sites
(98.2%). Skipped calls use runtime-computed arguments (loops,
variables, f-string interpolation) and cannot be extracted statically.
