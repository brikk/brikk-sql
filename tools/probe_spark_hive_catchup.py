"""SG29-015: Spark 4 value/type SELECT pairs and parse-only ALTER controls.

Run in a task-private PySpark environment, never against a remote/user catalog.
Hive dialect output uses the shipped Hive ParseDriver, not a Hive backend.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import pyspark
    from pyspark.sql import SparkSession

    table = "FROM VALUES ('b'), ('a'), ('a'), (CAST(NULL AS STRING)) AS t(x)"
    queries = [f"SELECT LISTAGG({q}x{sep}){order} AS v {table}"
               for q in ("", "ALL ", "DISTINCT ")
               for sep in ("", ", '-'", ", ''")
               for order in ("", " WITHIN GROUP (ORDER BY x DESC)")]
    queries += ["SELECT LISTAGG(x, '-') AS v FROM VALUES (CAST(NULL AS STRING)) AS t(x)",
                "SELECT LISTAGG(x, '-') AS v FROM (SELECT CAST(NULL AS STRING) x WHERE FALSE) t",
                "SELECT LISTAGG(x, '-') WITHIN GROUP (ORDER BY x ASC) AS v FROM VALUES ('b'), ('a'), ('') AS t(x)",
                "SELECT LISTAGG(x, NULL) AS v FROM VALUES ('a'), ('b') AS t(x)"]
    alters = ["ALTER TABLE t DROP " + s for s in ("COLUMN a", "COLUMN IF EXISTS a.b", "COLUMNS (a, b.c)",
              "COLUMNS (from)", "COLUMNS IF EXISTS (a, b.c)", "COLUMNS a, b")]
    replaces = ["ALTER TABLE t REPLACE COLUMNS " + s for s in
                ("(a INT COMMENT 'c', b STRING)", "(from INT)", "a INT, b STRING")]
    deck = [("spark", "result", q) for q in queries]
    deck += [("spark", "parse-only", q) for q in alters + replaces[:2]]
    deck += [("spark", "normalization-only-native-source-rejected", replaces[2])]
    deck += [("hive", "hive-parser-only", replaces[0]),
             ("hive", "hive-parser-only", replaces[0] + " CASCADE")]
    payload = "".join(d + "\t" + d + "\t" + base64.b64encode(sql.encode()).decode() + "\n" for d, _, sql in deck)
    bridge = subprocess.run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload,
                            text=True, capture_output=True, timeout=120, check=True)
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(deck), bridge.stdout
    spark = SparkSession.builder.master("local[1]").appName("sg29-015-readonly-controls") \
        .config("spark.ui.enabled", "false").config("spark.driver.bindAddress", "127.0.0.1") \
        .config("spark.driver.host", "127.0.0.1").config("spark.sql.shuffle.partitions", "1") \
        .config("spark.sql.warehouse.dir", "/var/tmp/jayson/opencode/sg29-015-empty-warehouse").getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    records = []
    before = spark.catalog.listTables()
    try:
        for (dialect, kind, sql), line in zip(deck, lines, strict=True):
            fields = line.split("\t")
            assert fields[0] == "OK", (sql, line)
            ast, preview, status, generated = [base64.b64decode(s).decode() for s in fields[1:]]
            assert status == "accepted", (sql, status)
            row = dict(dialect=dialect, kind=kind, sql=sql, ast=ast, preview=preview, generated=generated)
            if kind == "result":
                assert sql.startswith("SELECT ") and generated.startswith("SELECT ")
                original = spark.sql(sql)
                result = spark.sql(generated)
                row["original_values"] = [list(r) for r in original.collect()]
                row["generated_values"] = [list(r) for r in result.collect()]
                row["original_schema"] = original.schema.jsonValue()
                row["generated_schema"] = result.schema.jsonValue()
                assert row["original_values"] == row["generated_values"], row
                assert row["original_schema"] == row["generated_schema"], row
            elif kind == "normalization-only-native-source-rejected":
                native_parser = spark._jsparkSession.sessionState().sqlParser()
                try:
                    native_parser.parsePlan(sql)
                except Exception as error:
                    row["native_source_rejection"] = str(error)
                else:
                    raise AssertionError("Reconcile source rejection disposition if native grammar changes")
                row["generated_plan"] = native_parser.parsePlan(generated).treeString()
                row["classification"] = "SQLGlot-compatible normalization only, not original/generated native equivalence"
            elif kind == "hive-parser-only":
                hive_parser = spark._jvm.org.apache.hadoop.hive.ql.parse.ParseDriver()
                row["original_plan"] = hive_parser.parse(sql).toStringTree()
                row["generated_plan"] = hive_parser.parse(generated).toStringTree()
                row["hive_parser_version"] = spark._jvm.org.apache.hive.common.util.HiveVersionInfo.getVersion()
                assert row["original_plan"] == row["generated_plan"], row
            else:
                # No spark.sql, analyzer, session command or catalog mutation for ALTER.
                native_parser = spark._jsparkSession.sessionState().sqlParser()
                row["original_plan"] = native_parser.parsePlan(sql).treeString()
                row["generated_plan"] = native_parser.parsePlan(generated).treeString()
                assert row["original_plan"] == row["generated_plan"], row
            records.append(row)
        after = spark.catalog.listTables()
        assert before == after == [], "Task-local catalog must remain empty"
        report = dict(spark_version=spark.version, scope="Synthetic SELECT execution only. ALTER parser plans are not catalog/analyzer/storage qualification. Hive uses its shipped parser only, no backend execution.",
                      java_version=spark._jvm.java.lang.System.getProperty("java.version"), catalog_unchanged=True,
                      probe_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), records=records)
        jars = Path(pyspark.__file__).parent / "jars"
        report["native_jar_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                        for p in sorted(jars.glob("*.jar"))
                                        if p.name.startswith(("spark-catalyst_", "spark-sql_", "spark-sql-api_", "hive-exec-", "hive-common-"))}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Matched {len(queries)} SELECT value/type pairs, {len(alters)+len(replaces)-1+2} parse-only ALTER plan pairs; one source-rejected normalization, catalog unchanged")
    finally:
        spark.stop()


if __name__ == "__main__":
    os.environ.setdefault("SPARK_LOCAL_IP", "127.0.0.1")
    main()
