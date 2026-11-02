# Flight SQL issue drafts

Filed on 2026-11-02 as `apatrida`. Each file holds the issue body and the follow-up comment as
posted. There is no implemented fix or upstream test patch; the comments identify source
locations, possible fixes and the checks the reproducer covers.

| Draft | Filed issue |
| --- | --- |
| 1 | [spiceai/spiceai#15944](https://github.com/spiceai/spiceai/issues/15944) |
| 2 | [spiceai/spiceai#15946](https://github.com/spiceai/spiceai/issues/15946) |
| 3 | [spiceai/spiceai#15948](https://github.com/spiceai/spiceai/issues/15948) |
| 4 | [spiceai/spiceai#15950](https://github.com/spiceai/spiceai/issues/15950) |
| 5 | [apache/arrow-java#1439](https://github.com/apache/arrow-java/issues/1439) |

Setting the Spice issue type failed after `gh` had created each issue, so a retry created
duplicates: #15945, #15947, #15949 and #15951. Those are closed as duplicates of the issues
above. No type or label could be set from this account.

The Java snippets in the comments were compiled and run against Spice v2.4.0 with the classes
shaded inside `flight-sql-jdbc-driver-19.0.0.jar` before filing.

1. [Spice: missing-column errors](01-spice-missing-column-error.md)
2. [Spice: prepared updates do not execute](02-spice-prepared-update.md)
3. [Spice: EXECUTE result schema](03-spice-execute-schema.md)
4. [Spice: parameter nullability](04-spice-parameter-nullability.md)
5. [Arrow Java: binder allocation leak](05-arrow-binder-leak.md)

## Style checked before drafting

- [Spice bug template](https://github.com/spiceai/spiceai/blob/trunk/.github/ISSUE_TEMPLATE/bug_report.md):
  description, reproduction, expected behavior and runtime details, including whether trunk was tested.
  Contributor examples: [#13860](https://github.com/spiceai/spiceai/issues/13860),
  [#15666](https://github.com/spiceai/spiceai/issues/15666), and [#4333](https://github.com/spiceai/spiceai/issues/4333).
- [Arrow Java bug form](https://github.com/apache/arrow-java/blob/main/.github/ISSUE_TEMPLATE/bug_report.yaml):
  one required description field covering the error, expected behavior, version and platform.
  Contributor examples: [JDBC #1412](https://github.com/apache/arrow-java/issues/1412) and
  [memory bug #1417](https://github.com/apache/arrow-java/issues/1417).

The drafts keep the useful headings and omit irrelevant screenshots, EXPLAIN output and
empty template sections. Arrow's [#156](https://github.com/apache/arrow-java/issues/156)
tracks broader binding improvements; a targeted binder/leak search did not find a matching report.
This was not an exhaustive duplicate search or a check of fixes on current trunk/main.

## Shared reproduction setup

The snippets assume a JDBC connection to
`jdbc:arrow-flight-sql://127.0.0.1:50051/?useEncryption=false` on a local disposable Spice runtime.
The driver is `org.apache.arrow:flight-sql-jdbc-driver:19.0.0`, class
`org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. Use
`--add-opens=java.base/java.nio=ALL-UNNAMED`; the recorded Java 25 run also uses
`--sun-misc-unsafe-memory-access=allow`.

The [tested runner](https://github.com/brikk/brikk-sql/blob/7e0db9a/tools/probe_spice_flight_failures.py)
sets up the local fixture and records all five failures. Its invocation and the raw captures
are linked from the [investigation](../spice-flight-failures.md#reproduction-and-evidence).
