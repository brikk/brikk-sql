# Optional IntelliJ JDBC support

`dev.brikk.house:brikk-sql-intellij-jdbc-support:0.2.2` contains only
`dev.brikk.house.sql.intellij.catalog.GenericJdbcIntrospectorGate`. Its class name and routing
contract are preserved. Consumers extending this gate must add this artifact explicitly when
upgrading common SQL IntelliJ support to 0.2.1. Common support has no dependency on this module.

DuckDB and Trino use it to veto fallback-engine native introspection and select JDBC metadata.
Doris and ClickHouse retain their own native introspectors and must not bundle this adapter.
The test kit depends on it only to exercise routing; the test kit is never bundled in plugins.

The gate uses `DBIntrospector.Factory`, an internal platform interface. Its reviewed contract consists
of five explicit factory methods and the implemented interface. In the separate adapter JAR, the
current plugin-verifier runs report the interface reference alone; the five overrides still exist
and remain covered by the bytecode contract test and exemption inventory. A class-local
`JvmDefaultWithoutCompatibility` leaves default capabilities inherited instead of emitting three
unnecessary forwarding methods. Exemptions remain plugin-specific; an existing Trino exemption
does not establish a DuckDB exemption.

The compile-only dependency on common support orders SDK preparation and tracks its SDK fingerprint.
It appears as `provided` in the POM, not as a runtime dependency. The runtime JAR contains one class,
LICENSE and NOTICE, with no SDK or copied support classes.

Build and publish with the existing support release script. Versions 0.2.1 and 0.2.2 are published
on Maven Central from main. Use a new, unpublished version for subsequent public releases.
