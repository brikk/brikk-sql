# Flight SQL: `EXECUTE` advertises an empty schema but returns rows

Repository: `spiceai/spiceai`

## Main body

### Describe the bug

For SQL `EXECUTE`, `GetFlightInfo` and `CreatePreparedStatement` report a result schema with no columns. The `DoGet` stream then returns rows. Arrow JDBC sees the empty schema and treats `EXECUTE` as an update, so the rows are lost.

### To Reproduce

Use the same `x-session-id` header on every request, including `DoGet`.

1. Run with `CommandStatementQuery` and read the `DoGet` stream so the statement is created:

   ```sql
   PREPARE named_raw(BIGINT) AS SELECT $1 + 1 AS n
   ```

2. `GetFlightInfo` for `EXECUTE named_raw(7)`. The schema has **0 fields**.
3. Read the returned ticket with `DoGet`. It returns one `Int64` column `n` with the value **8**.

`CreatePreparedStatement` for the same `EXECUTE` also returns an empty result schema. Through JDBC, `execute()` on `EXECUTE named_raw(7)` returns update count 0 even though the statement exists in that session.

### Expected behavior

The advertised schema matches what `DoGet` returns: one `Int64` column `n`.

### Runtime Details

- spiced: `v2.4.0+models`, local Spicepod with views only
- Client: Arrow Flight SQL Java and JDBC 19.0.0
- OS: Linux x86_64

Not tested on trunk.

---

## Additional topics for comments

### Where it happens

[`get_schema`](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L2056-L2084) returns the schema of the `EXECUTE` statement plan, which is empty. [Execution](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L1634-L1671) later looks up the stored prepared query and returns its rows. A fix would report the stored query's schema without running it. We have no patch.

### Reproducer

[`FlightFailureProbe.java`](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/datafusion-reference/FlightFailureProbe.java), cases `raw_named_execute` and `jdbc_shared_session_control`. A regression test should compare the advertised and streamed schemas, and keep the existing failures for another session and after `DEALLOCATE`.

<details>
<summary>Raw Flight SQL reproducer (Java)</summary>

Tested with the classes shaded inside `flight-sql-jdbc-driver-19.0.0.jar`; imports are shown unshaded. Run against spiced v2.4.0 on `127.0.0.1:50051`.

```java
static CallOption sessionHeader(String id) {
    var headers = new FlightCallHeaders();
    headers.insert("x-session-id", id);
    return new HeaderCallOption(headers);
}

static void drain(FlightClient client, FlightInfo info, CallOption... opts) throws Exception {
    for (var endpoint : info.getEndpoints()) {
        try (var stream = client.getStream(endpoint.getTicket(), opts)) {
            while (stream.next()) { }
        }
    }
}

static void executeSchemaIsEmpty(FlightClient client) throws Exception {
    var opts = new CallOption[]{ sessionHeader("repro-execute") };
    var sql = new FlightSqlClient(client);
    drain(client, sql.execute("PREPARE named_raw(BIGINT) AS SELECT $1 + 1 AS n", opts), opts);
    FlightInfo info = sql.execute("EXECUTE named_raw(7)", opts);
    System.out.println("advertised: " + info.getSchema().getFields()); // []
    try (var stream = client.getStream(info.getEndpoints().get(0).getTicket(), opts)) {
        stream.next();
        System.out.println("streamed: " + stream.getSchema()); // Schema<n: Int(64, true) not null>
        System.out.println("value: " + stream.getRoot().getVector(0).getObject(0)); // 8
    }
}
```

</details>

### Related

Fixing #15946 alone does not fix this. JDBC would still send `EXECUTE` down the update path.
