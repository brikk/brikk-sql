# Flight SQL: prepared updates return 0 without running the statement

Repository: `spiceai/spiceai`

## Main body

### Describe the bug

`DoPut(CommandPreparedStatementUpdate)` stores the bound parameters but never runs the statement. It replies with `DoPutPreparedStatementResult` (a new handle) where clients expect `DoPutUpdateResult` (a row count). Clients that decode the reply as an update result read `record_count = 0` and report success.

Arrow JDBC sends every statement with an empty result schema down this path. So JDBC `PREPARE` and `DEALLOCATE` return 0 and do nothing.

### To Reproduce

Use the same `x-session-id` header on every request.

1. `CreatePreparedStatement` with:

   ```sql
   PREPARE no_effect(BIGINT) AS SELECT $1 AS n
   ```

2. `DoPut(CommandPreparedStatementUpdate)` with the returned handle and an empty batch.
3. The reply is a prepared-statement handle, not a row count. Decoded as `DoPutUpdateResult`, it gives `record_count = 0`.
4. Run `EXECUTE no_effect(9)` with `CommandStatementQuery` and read the `DoGet` stream. It fails with `Prepared statement 'no_effect' does not exist`.

If the same `PREPARE` is run with `CommandStatementQuery` instead, the statement is created and `EXECUTE` works.

Through JDBC with a shared `x-session-id`, `DEALLOCATE` of an existing statement returns 0 and the statement still exists afterwards.

### Expected behavior

The prepared-update call runs the statement and returns `DoPutUpdateResult` with the affected-row count, or returns the execution error.

### Runtime Details

- spiced: `v2.4.0+models`, local Spicepod with views only
- Client: Arrow Flight SQL Java and JDBC 19.0.0
- OS: Linux x86_64

Not tested on trunk.

---

## Additional topics for comments

### Where it happens

[`do_put_update`](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_update.rs#L179-L219) only saves the parameters into a new handle. The code that runs the statement is in a separate `DoGet` handler that the update flow never calls. A fix would run the statement in `DoPut` and return `DoPutUpdateResult`. We have no patch.

### Reproducer

[`FlightFailureProbe.java`](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/datafusion-reference/FlightFailureProbe.java), cases `raw_prepare_update_reply`, `raw_execute_after_update_prepare` and `jdbc_shared_session_control`. A regression test should check the effect, not just the reply: `PREPARE` creates the statement, `DEALLOCATE` removes it, and execution errors come back as errors.

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

static void preparedUpdateDoesNotExecute(FlightClient client) throws Exception {
    var opts = new CallOption[]{ sessionHeader("repro-update") };
    var req = ActionCreatePreparedStatementRequest.newBuilder()
        .setQuery("PREPARE no_effect(BIGINT) AS SELECT $1 AS n").build();
    var prepared = Any.parseFrom(client.doAction(
            new Action("CreatePreparedStatement", Any.pack(req).toByteArray()), opts).next().getBody())
        .unpack(ActionCreatePreparedStatementResult.class);
    var descriptor = FlightDescriptor.command(Any.pack(CommandPreparedStatementUpdate.newBuilder()
        .setPreparedStatementHandle(prepared.getPreparedStatementHandle()).build()).toByteArray());
    try (var empty = VectorSchemaRoot.of(); var listener = new SyncPutListener()) {
        var put = client.startPut(descriptor, empty, listener, opts);
        put.putNext();
        put.completed();
        put.getResult();
        try (PutResult reply = listener.read()) {
            ArrowBuf meta = reply.getApplicationMetadata();
            byte[] bytes = new byte[(int) meta.readableBytes()];
            meta.getBytes(0, bytes);
            System.out.println("count: " + DoPutUpdateResult.parseFrom(bytes).getRecordCount()); // 0
            System.out.println("handle bytes: " + DoPutPreparedStatementResult.parseFrom(bytes)
                .getPreparedStatementHandle().size()); // > 0: a handle, not a count
        }
    }
    var sql = new FlightSqlClient(client);
    try {
        drain(client, sql.execute("EXECUTE no_effect(9)", opts), opts);
        System.out.println("EXECUTE unexpectedly succeeded");
    } catch (FlightRuntimeException e) {
        System.out.println("execute: " + e.getMessage()); // Prepared statement 'no_effect' does not exist
    }
}
```

</details>

### Related

Missing-column errors also reach this path and come back as 0 (#15944).
