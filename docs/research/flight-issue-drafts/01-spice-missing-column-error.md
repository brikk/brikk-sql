# Flight SQL: prepared statements hide missing-column errors

Repository: `spiceai/spiceai`

## Main body

### Describe the bug

Preparing a query that references a missing column succeeds over Flight SQL. Spice returns an empty result schema instead of the planning error. Arrow JDBC then reports the query as an update with count `0`, or `executeQuery()` fails with `Statement did not return a result set`. The real error never reaches the user.

Unknown tables, unknown functions and malformed SQL still fail with their errors. Only missing-column errors are affected.

### To Reproduce

1. Start `spiced` with this Spicepod:

   ```yaml
   version: v1
   kind: Spicepod
   name: repro
   views:
     - name: sample
       sql: "SELECT 1 AS id, 'Ada' AS name, 30 AS age, 'example' AS body UNION ALL SELECT 2, 'Bob', 40, 'second'"
   ```

   ```bash
   spiced spicepod.yaml --flight 127.0.0.1:50051
   ```

2. Run this over Arrow Flight SQL JDBC 19.0.0 (`jdbc:arrow-flight-sql://127.0.0.1:50051/?useEncryption=false`):

   ```java
   try (Statement s = connection.createStatement()) {
       System.out.println(s.execute("SELECT missing_column FROM sample")); // false
       System.out.println(s.getUpdateCount());                           // 0
       s.executeQuery("SELECT missing_column FROM sample");
       // SQLException: Statement did not return a result set
   }
   ```

Through Flight SQL directly, `CreatePreparedStatement` for the same SQL returns empty result and parameter schemas. Running it with `CommandStatementQuery` instead returns `No field named missing_column`.

### Expected behavior

Preparing the statement returns `No field named missing_column`. The query has no parameters, so nothing is waiting on type inference.

### Runtime Details

- spiced: `v2.4.0+models`
- Client: Arrow Flight SQL JDBC 19.0.0
- OS: Linux x86_64

Not tested on trunk.

---

## Additional topics for comments

### Where it happens

The [prepare handler](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_query.rs#L210-L239) treats any error containing `No field named` as a type-inference failure to retry once parameters are bound. [Prepared GetFlightInfo](https://github.com/spiceai/spiceai/blob/78a47787ef74dec52d705bf9e26a0f9b7a26ca94/crates/runtime/src/flight/flightsql/prepared_statement_query.rs#L284-L346) has the same check. A fix would only defer errors caused by unresolved parameters. We have no patch.

### Reproducer

[`FlightFailureProbe.java`](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/datafusion-reference/FlightFailureProbe.java), cases `jdbc_matrix` and `raw_invalid_prepared`. A regression test should check that this query fails at prepare time and that a valid query with untyped parameters still prepares.

### Related

#15946 explains why JDBC reports this as update count 0.
