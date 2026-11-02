# [JDBC] Failed parameter binding leaks Arrow memory

Repository: `apache/arrow-java`

## Main body

### Describe the bug, including details regarding any error messages, version, and platform.

When a prepared statement parameter fails to bind after earlier parameters were bound, the Flight SQL JDBC driver keeps the Arrow memory for those earlier values. Closing the statement does not free it. Repeated failures add up, and closing the connection throws `Memory was leaked by query`.

The bind failure is fine. The leaked memory is the bug.

Reproduce against a Flight SQL server that marks the parameters as non-nullable (Spice 2.4.0 does):

```java
for (int i = 0; i < 3; i++) {
    try (PreparedStatement p = connection.prepareStatement(
            "SELECT CAST($1 AS VARCHAR) AS txt, CAST($2 AS BIGINT) AS n")) {
        p.setString(1, "O'Reilly 😀");
        p.setNull(2, Types.BIGINT);
        try {
            p.executeQuery(); // Can't set null on non-nullable parameter
        } catch (SQLException expected) {
        }
    }
}
connection.close();
// SQLException: Memory was leaked by query. Memory leaked: (3168)
```

After each statement closes, the connection's allocator still holds 1,056, then 2,112, then 3,168 bytes.

A type mismatch in the second parameter leaks the same way (`setString` on a `BIGINT` parameter: 1,040 bytes). A failure on the first parameter leaks nothing, and successful executions leave nothing allocated after the statement closes.

The same leak happens with no server: calling `AvaticaParameterBinder.bind` against a stub prepared statement whose second parameter is non-nullable leaves memory allocated after the binder throws.

Version: flight-sql-jdbc-driver 19.0.0. Platform: Linux x86_64, JDK 25.

---

## Additional topics for comments

### Where it happens

[`AvaticaParameterBinder`](https://github.com/apache/arrow-java/blob/b410fb26d01c7cefc4c6e3443a53562164001371/flight/flight-sql-jdbc-core/src/main/java/org/apache/arrow/driver/jdbc/utils/AvaticaParameterBinder.java#L71-L108) creates the parameter `VectorSchemaRoot` and fills it one value at a time. It hands the root to `preparedStatement.setParameters()` only after every value succeeds. If a value throws, nothing owns the root and nothing closes it. Closing the root before rethrowing would fix it. We have no patch.

### Reproducer

`localBinder()` in [`FlightFailureProbe.java`](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/datafusion-reference/FlightFailureProbe.java) reproduces it without a server. A regression test could bind a valid value then a failing one (NULL into a non-nullable field, or a wrong type), repeat it, and assert the allocator is empty afterwards.

<details>
<summary>Server-free reproducer (Java)</summary>

Tested with the classes shaded inside `flight-sql-jdbc-driver-19.0.0.jar`; imports are shown unshaded. No server or connection is needed.

```java
static void failedBindLeaks() {
    var allocator = new RootAllocator();
    Schema schema = new Schema(List.of(
        new Field("text", FieldType.notNullable(new ArrowType.Utf8()), null),
        new Field("number", FieldType.notNullable(new ArrowType.Int(64, true)), null)));
    var stub = new ArrowFlightSqlClientHandler.PreparedStatement() {
        VectorSchemaRoot owned;
        public FlightInfo executeQuery() { throw new UnsupportedOperationException(); }
        public long executeUpdate() { throw new UnsupportedOperationException(); }
        public Meta.StatementType getType() { return Meta.StatementType.SELECT; }
        public Schema getDataSetSchema() { return new Schema(List.of()); }
        public Schema getParameterSchema() { return schema; }
        public void setParameters(VectorSchemaRoot root) { owned = root; }
        public void close() { if (owned != null) owned.close(); }
    };
    try {
        new AvaticaParameterBinder(stub, allocator)
            .bind(Arrays.asList(TypedValue.ofLocal(ColumnMetaData.Rep.STRING, "text"), null));
    } catch (UnsupportedOperationException e) {
        System.out.println("bind: " + e.getMessage()); // Can't set null on non-nullable parameter
    }
    stub.close();
    System.out.println("still allocated: " + allocator.getAllocatedMemory()); // 16388 in our run
    try {
        allocator.close();
    } catch (IllegalStateException e) {
        System.out.println("close: " + e.getMessage().lines().findFirst().orElse("")); // Memory was leaked
    }
}
```

</details>

### Related

#156 tracks broader parameter-binding improvements, including NULL handling. This leak is a separate issue.

The non-nullable parameter schema in the example comes from spiceai/spiceai#15950.
