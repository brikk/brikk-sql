# Java 17 embedding

Duckbridge loads these libraries inside Doris's Java 17 JVM. The JVM running
Trino or an IDE is irrelevant to that deployment; the embedded libraries and
their dependency chain must run on Java 17.

The following development artifacts compile and run their own suites on JDK 17,
with `release: 17` and class-file major version 61:

- `dev.brikk.house:brikk-sql-jvm`
- `dev.brikk.house:brikk-sql-metadata-jvm`
- `dev.brikk.house:brikk-sql-verify-api`
- `dev.brikk.house:brikk-sql-verify-doris`

The Doris verifier links the caller-provided native parser, which must itself
support Java 17. The vendored Doris 4.1.5 parser is exercised on that JVM.
Other verifier/IDE modules keep their Java 21 targets. The Trino verifier and
aggregate also depend on trino-parser 484, which needs a Java 25 runtime. Java 17
consumers must not bring the aggregate verifier into their deployment.

Kotlin can emit Java 17 bytecode without changing the Kotlin version or public
library APIs. That alone is not sufficient: newer Java API calls and dependencies
can still prevent loading on 17. Compiling against JDK 17, running the suites on
17, and auditing every packaged class catch those problems. The only newer Java
API found during the change was Thread.threadId() in an allocation test; it now
uses the Java 17 getId() API. No production API change was needed.

## Verification

- All 1,401 methods in the four Java 17 module suites pass on JDK 17; explicit
  runtime/version tests check both the running JVM and emitted bytecode.
- The combined suite passes 1,703 methods with 61 optional engine assumptions
  aborted and zero failures, retaining the separate higher-JDK verifier modules.
- Maven Local publication and audits check all classes in the four JARs, their
  declared Brikk dependency closure and any JVM-version variant attributes.
  CI repeats this audit with `tools/verify_java17_publication.py`.
- A standalone Java 17.0.20 consumer loads the actual packaged JARs, initializes
  Trino/Doris metadata and hazards, parses and serializes an AST, generates Doris
  SQL, and accepts/rejects SQL with the native Doris parser. Its classpath contains
  only the four JARs, Kotlin stdlib, serialization and ANTLR dependencies.
- Existing verifier and IDE publication audits remain separate; an IDE helper is
  not advertised as Java 17-compatible just because its core dependency is.
- All 752 IDE platform contracts pass with the lower-targeted dependencies, and
  46 Python tooling/release checks pass with the opt-in model check enabled.

Build-tool bootstrap JREs do not determine the consumer's runtime floor. The
module JDK/release configuration, dependency graph, packaged bytecode and actual
consumer runtime are checked separately.

## Previously published builds

This does not retarget existing artifacts. In particular, the metadata/core
0.20.0-SNAPSHOT builds ending in `-8`, published from `8ef3506`, contain Java 21
classes and cannot run inside Java 17 Doris. Stable 0.20.1 also predates this
change. Use a newly published and verified snapshot/release containing the Java
17 change, refreshing any cached rolling SNAPSHOT resolution.

This library compatibility work does not replace Duckbridge's own integration
qualification inside Doris, nor the connector's live SQL/pushdown qualification.
