# Flight SQL: every parameter is advertised as non-nullable

Repository: `spiceai/spiceai`

## Main body

### Describe the bug

Spice marks every prepared-statement parameter as non-nullable. Arrow JDBC follows that schema and refuses to send NULL, even for queries that handle NULL correctly.

### To Reproduce

Over Arrow Flight SQL JDBC 19.0.0:

```java
try (PreparedStatement p = connection.prepareStatement(
        "SELECT CAST($1 AS BIGINT) AS n")) {
    p.setNull(1, Types.BIGINT); // succeeds
    p.executeQuery();           // SQLException: Can't set null on non-nullable parameter
}
```

The parameter schema from `CreatePreparedStatement` has `$1` as `Int64` with `nullable=false`. The same happens for:

```sql
SELECT CAST($1 AS BIGINT) IS NULL AS is_null
SELECT COALESCE(CAST($1 AS BIGINT), 42) AS n
```

Sending a typed Arrow null directly, with only the field's nullable flag set to true, works: the first query returns NULL and the `COALESCE` query returns 42.

### Expected behavior

Parameters that accept NULL are advertised as nullable.

### Runtime Details

- spiced: `v2.4.0+models`, local Spicepod with views only
- Client: Arrow Flight SQL Java and JDBC 19.0.0
- OS: Linux x86_64

Not tested on trunk.

---

## Additional topics for comments

### Where it happens

[`parameter_schema_for_plan`](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/datafusion/query.rs#L2098-L2142) builds each field with `Field::new(name, typ, false)`. It infers the type but always sets nullable to false. Marking parameters nullable unless a constraint is known would fix these cases. We have no patch.

### Reproducer

[`FlightFailureProbe.java`](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/datafusion-reference/FlightFailureProbe.java), cases `jdbc_null_only`, `raw_parameter_schemas`, `raw_nullable_override` and `raw_nullable_coalesce`. A regression test should check the advertised flag and run the `CAST` and `COALESCE` queries with a NULL parameter.

<details>
<summary>Raw Flight SQL control with a nullable parameter (Java)</summary>

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

static void nullableParameterWorks(FlightClient client, BufferAllocator allocator) throws Exception {
    var opts = new CallOption[]{ sessionHeader("repro-null") };
    var sql = new FlightSqlClient(client);
    try (var p = sql.prepare("SELECT CAST($1 AS BIGINT) AS n", opts)) {
        System.out.println("advertised: " + p.getParameterSchema()); // Schema<$1: Int(64, true) not null>
        var field = new Field("$1", FieldType.nullable(new ArrowType.Int(64, true)), null);
        var params = VectorSchemaRoot.create(new Schema(List.of(field)), allocator);
        params.allocateNew();
        ((BigIntVector) params.getVector(0)).setNull(0);
        params.setRowCount(1);
        p.setParameters(params); // p closes params
        FlightInfo info = p.execute(opts);
        try (var stream = client.getStream(info.getEndpoints().get(0).getTicket(), opts)) {
            stream.next();
            System.out.println("value: " + stream.getRoot().getVector(0).getObject(0)); // null
        }
    }
}
```

</details>

### Related

A failed NULL bind after a successful bind also leaks memory in Arrow JDBC. That is a separate driver bug: apache/arrow-java#1439.
