# brikk-sql-verify-clickhouse

`ParseOnlyClickhouseVerifier` checks ClickHouse SQL with the parser of the user's own server
(local, chDB-backed or remote), for editors. It never executes, binds or analyzes the SQL.

```kotlin
val verifier = ParseOnlyClickhouseVerifier({ JdbcClickhouseQueryConnection(openRestrictedConnection()) })
verifier.verify("SELECT 'ü' FRM t") // Rejected: "Syntax error at t: Expected one of: ..." at UTF-16 offset 15
```

- **How it asks.** Each call opens a connection with the supplier (null: no data source), runs
  `SELECT formatQuery(unhex('<the SQL's UTF-8 as hex>')) SETTINGS readonly = 1, max_execution_time = N`
  and closes it. The SQL is data, a string argument the server parses; the query cannot be anything
  else. A profile that refuses query settings (`readonly` users) is asked without them.
- **Verdicts.** Only a syntax error (code 62) in the supplied text (the server reports it in
  `formatQuery`'s scope) is `Rejected`, positioned from ClickHouse's 1-based
  UTF-8 byte position (`VerifyError.atUtf8Byte`), spanning the token. An error in the verifier's own query, a statement over the
  server's `max_query_size`, or one too deep, too big or too slow to parse is `Unverified`; anything else (no connection, authentication, access, no
  `formatQuery` before 23.10) is `Unavailable`, never clean.
- **INSERT data.** The server parses an INSERT's header only; `VALUES` rows and `FORMAT` data are read
  when it runs (`INSERT INTO t VALUES (1), (2` formats as `INSERT INTO t FORMAT Values`). An accepted
  INSERT with inline data is `Unverified`, decided from core's ClickHouse tokens of the SQL (a `VALUES`
  before the INSERT's query or a `FORMAT`, also under `EXPLAIN`), so text in strings never counts.
- **Cancellation.** `checkCanceled` is polled while the server works; what it throws cancels the
  query (on a worker: a JDBC cancel may itself wait on the server) and propagates. An interrupted
  caller gets a `CancellationException`. Calls are not single-flight.
- **Query log.** Verification queries appear in the server's `system.query_log` as the hex call.

## Why not `EXPLAIN AST`

`EXPLAIN AST <sql>` parses only, unless the SQL starts with a settings list:
`EXPLAIN AST optimize = 1 SELECT ...` runs the analyzer, which fetches `url()` schemas, evaluates
scalar subqueries and loads dictionaries, even with `readonly = 2`. The SQL's own `SETTINGS` also
apply to the EXPLAIN query (its `log_comment` lands in the query log). `formatQuery` over hex data has
neither hole.

## Safety evidence

`ClickhouseSafetyTest` runs on a real server (ClickHouse 26.9.17.8 in docker, task-only network;
`tools/clickhouse-verify-server.sh up`, then `BRIKK_CLICKHOUSE_URL=jdbc:clickhouse://127.0.0.1:18123/default`).
It verifies `url()` (with and without a schema), `s3()`, `remote()`, `dictGet`, a scalar subquery over
`url()`, `SETTINGS log_comment`, `INSERT ... SELECT`, `INSERT INTO FUNCTION file()`, inline `FORMAT`
data, `VALUES`, `input()`, `CREATE`, `DROP`, `SYSTEM RELOAD DICTIONARY`, `EXPLAIN PIPELINE` and a
leading `optimize = 1`, then finds no marker query in `system.query_log`, no rows, no table, no file and
the dictionary still `NOT_LOADED`. Its control shows `EXPLAIN AST optimize = 1` fetching the `url()` and
loading the dictionary; with the verifier switched to that query, the test fails on the logged fetch.
Without `BRIKK_CLICKHOUSE_URL` the server tests are skipped; the rest (no data source, a refused
connection, a hanging server canceled, an interrupted caller, message parsing) run anywhere.

With `com.clickhouse:clickhouse-jdbc` 0.9.8 (also as its all-in-one jar), the driver's default
compressed transport fails against ClickHouse 26.9: answers fail with "Invalid LZ4 magic byte" and
errors read `<Unreadable error message>`, which the verifier reports as `Unavailable`, never as a
rejection. Turn compression off on the verification connection (`compress=false`, `decompress=false`;
support's `DataSourceVerification` takes such properties). The driver also sends its own settings
(`async_insert`, `wait_end_of_query`) with each request, so a `readonly = 1` login gets `Unavailable`
through it, and its own console fails the same way; over the plain HTTP interface such a login gets
verdicts.
