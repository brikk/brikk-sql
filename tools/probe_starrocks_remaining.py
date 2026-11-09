"""SG29-014 remainder: read-only SELECT results and shipped FE parse-only checks.

Administrative, refresh and DDL statements are NEVER sent to the query executor.
"""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path


def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, timeout=120, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java", default="java")
    p.add_argument("--classpath", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--parse-only-recheck", action="store_true", help="Recheck recorded native AST fields without rerunning any SELECT")
    args = p.parse_args()
    container = "brikk-sg014-rest-starrocks"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    host = info["HostConfig"]
    assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings") and not host.get("Privileged")

    if args.parse_only_recheck:
        report = json.loads(args.output.read_text())
        native_java = Path(__file__).with_name("StarrocksNativeParserProbe.java")
        copied = run(["docker","cp",str(native_java),container+":/var/tmp/StarrocksNativeParserProbe.java"])
        assert copied.returncode == 0, copied.stderr
        records = report["records"]
        payload = "".join(base64.b64encode(q.encode()).decode()+"\n" for row in records for q in (row["sql"],row["executable"]))
        result = run(["docker","exec","-i",container,"java","-Xmx256m","-XX:ActiveProcessorCount=2","-cp",
                      "/data/deploy/starrocks/fe/lib/*","/var/tmp/StarrocksNativeParserProbe.java"],input=payload)
        assert result.returncode == 0, result.stderr
        parsed = [line.split("\t") for line in result.stdout.splitlines() if line.startswith("SG14\t")]
        assert len(parsed)==2*len(records),result.stdout
        checked=0
        for i,row in enumerate(records):
            a,b=parsed[2*i],parsed[2*i+1]
            if row["kind"].startswith("structured"):
                assert a[1]==b[1]=="OK",(row,a,b)
                left=base64.b64decode(a[3]).decode() if len(a)>3 else ""
                right=base64.b64decode(b[3]).decode() if len(b)>3 else ""
                assert left and left==right,(row,left,right)
                row["native_original_field_snapshot"]=left
                row["native_generated_field_snapshot"]=right
                row["native_explicit_fields_equal"]=True
                checked+=1
            if row["kind"]=="opaque":assert row["sql"]==row["executable"],row
        assert checked==12
        report["native_field_harness_sha256"]=hashlib.sha256(native_java.read_bytes()).hexdigest()
        report["native_field_recheck_stderr"]=result.stderr
        report["native_field_recheck_scope"]="Parsed getters only; previously recorded SELECT results retained, no backend statements rerun"
        args.output.write_text(json.dumps(report,indent=2)+'\n')
        print(f"Rechecked {checked} structured native AST field pairs without executing any SQL")
        return

    def query(sql, metadata=False):
        assert re.match(r"^SELECT\b", sql, re.I), "Only synthetic SELECTs may execute"
        flags = ["--column-type-info", "-vvv"] if metadata else ["--batch", "--raw"]
        r = run(["docker", "exec", container, "mysql", "-h127.0.0.1", "-P9030", "-uroot", *flags, "-e", sql])
        assert r.returncode == 0, (sql, r.stderr)
        return dict(stdout=r.stdout, stderr=r.stderr, returncode=r.returncode)

    scalar = ["SELECT TO_DAYS('2026-03-10') AS v", "SELECT TO_DAYS(NULL) AS v",
              "SELECT TO_DAYS(CAST('2024-02-29 12:00:00' AS DATETIME)) AS v",
              "SELECT DATEDIFF(CAST('2026-03-11 01:00:00' AS DATETIME), CAST('2026-03-10 23:00:00' AS DATETIME)) AS v",
              "SELECT DATE_DIFF('DAY', CAST('2026-03-11 01:00:00' AS DATETIME), CAST('2026-03-10 23:00:00' AS DATETIME)) AS v",
              "SELECT DATEDIFF(CAST('2026-03-10 23:00:00' AS DATETIME), CAST('2026-03-11 01:00:00' AS DATETIME)) AS v",
              "SELECT DATEDIFF(NULL, CAST('2026-03-11' AS DATE)) AS v"]
    source = "(SELECT 1 g,'a' k,10 v UNION ALL SELECT 1,'b',20) t"
    pivots = [f"SELECT * FROM {source} PIVOT(MAX(v) FOR k IN ('a','b'))",
              f"SELECT * FROM {source} PIVOT(SUM(v) AS s, MAX(v) AS m FOR k IN ('a','b'))",
              f"SELECT * FROM {source} PIVOT(MAX(v) AS m FOR k IN ('a' AS one,'b' AS two))",
              f"SELECT * FROM {source} PIVOT(SUM(v),MAX(v) FOR k IN ('a','b'))",
              "SELECT * FROM (SELECT 1 g,1 a,2 b,10 v) t PIVOT(MAX(v) FOR (a,b) IN ((1,2)))"]
    unnests = ["SELECT * FROM (SELECT 1 AS id) s, UNNEST([1,2],['a','b']) AS u",
               "SELECT * FROM TABLE(UNNEST([1,2],['a','b'])) AS t(x,y)"]
    ddl = [f"CREATE TABLE t (k INT, v INT {agg}) AGGREGATE KEY (k) DISTRIBUTED BY HASH (k)"
           for agg in ("SUM", "MAX", "MIN", "REPLACE", "REPLACE_IF_NOT_NULL")]
    ddl += ["CREATE TABLE t (k INT, v HLL HLL_UNION) AGGREGATE KEY (k) DISTRIBUTED BY HASH (k)"]
    partition = "PARTITION START ('2020-02-01') END ('2020-03-01')"
    refresh = ["REFRESH MATERIALIZED VIEW db.mv" + (" " + tail if tail else "")
               for tail in ("", "FORCE", "WITH SYNC MODE", "WITH ASYNC MODE", partition, partition + " FORCE WITH SYNC MODE")]
    opaque = ["ADMIN SET FRONTEND CONFIG ('disable_balance'='true')", "ADMIN SHOW FRONTEND CONFIG LIKE 'disable_balance'",
              "BACKUP SNAPSHOT db.s TO repo ON (t)", "RESTORE SNAPSHOT db.s FROM repo ON (t) PROPERTIES ('backup_timestamp'='2018-05-04-16-45-08')",
              "RECOVER TABLE db.t", "CANCEL LOAD FROM db WHERE LABEL = 'x'", "EXPORT TABLE db.t TO 's3://not-executed/'",
              "SUBMIT TASK AS INSERT INTO t2 SELECT * FROM t1", "PAUSE ROUTINE LOAD FOR db.job", "RESUME ROUTINE LOAD FOR db.job",
              "STOP ROUTINE LOAD FOR db.job", "DEALLOCATE PREPARE stmt1", "INSTALL PLUGIN FROM '/not-executed.zip'", "UNINSTALL PLUGIN p",
              "ADD SQLBLACKLIST 'select .+'", "DELETE SQLBLACKLIST 3,4", "ADD SQL DIGEST BLACKLIST abcdef0123456789abcdef0123456789",
              "DELETE SQL DIGEST BLACKLIST abcdef0123456789abcdef0123456789", "ADD BACKEND BLACKLIST 10001,10002",
              "DELETE BACKEND BLACKLIST 10001", "ADD COMPUTE NODE BLACKLIST 10005", "DELETE COMPUTE NODE BLACKLIST 10005",
              "TRANSLATE TRINO SELECT id FROM products", "REFRESH DICTIONARY dict_obj", "REFRESH CONNECTIONS FORCE"]
    deck = [("result", q) for q in scalar + pivots + unnests] + [("structured-ddl", q) for q in ddl] + [("structured-refresh", q) for q in refresh] + [("opaque", q) for q in opaque]
    payload = "".join("starrocks\tstarrocks\t" + base64.b64encode(q.encode()).decode() + "\n" for _, q in deck)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines(); assert len(lines) == len(deck), bridge.stdout
    records = []
    for (kind, sql), line in zip(deck, lines, strict=True):
        f = line.split("\t"); assert f[0] == "OK", (sql, line)
        ast, preview, status, executable = [base64.b64decode(x).decode() for x in f[1:]]
        assert status == "accepted", (sql, status)
        assert ast.startswith("Command(") if kind == "opaque" else not ast.startswith("Command("), (kind, ast)
        records.append(dict(kind=kind, sql=sql, ast=ast, preview=preview, executable=executable))
    native_java = Path(__file__).with_name("StarrocksNativeParserProbe.java")
    copied = run(["docker", "cp", str(native_java), container + ":/var/tmp/StarrocksNativeParserProbe.java"])
    assert copied.returncode == 0, copied.stderr
    native_sql = [q for row in records for q in (row["sql"], row["executable"])]
    native = run(["docker", "exec", "-i", container, "java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp",
                  "/data/deploy/starrocks/fe/lib/*", "/var/tmp/StarrocksNativeParserProbe.java"],
                 input="".join(base64.b64encode(q.encode()).decode() + "\n" for q in native_sql))
    assert native.returncode == 0, native.stderr
    parsed = [line.split("\t") for line in native.stdout.splitlines() if line.startswith("SG14\t")]
    assert len(parsed) == len(native_sql), native.stdout
    for i, row in enumerate(records):
        a, b = parsed[2*i], parsed[2*i+1]
        row["native_original"] = dict(status=a[1], detail=base64.b64decode(a[2]).decode(), canonical=base64.b64decode(a[3]).decode() if len(a)>3 else "")
        row["native_generated"] = dict(status=b[1], detail=base64.b64decode(b[2]).decode(), canonical=base64.b64decode(b[3]).decode() if len(b)>3 else "")
        if row["kind"] != "opaque":
            assert a[1] == b[1] == "OK", row
            assert row["native_original"]["detail"] == row["native_generated"]["detail"], row
        else:
            row["classification"] = "Opaque text preservation only; native rejection is not an equivalence pass"
            assert row["sql"] == row["executable"], "Opaque statement text must remain whole"
        if row["kind"].startswith("structured"):
            assert row["native_original"]["canonical"] and row["native_generated"]["canonical"], row
            assert row["native_original"]["canonical"] == row["native_generated"]["canonical"], row
            row["native_canonical_ast_equal"] = True
        if row["kind"] == "result":
            old, new = query(row["sql"]), query(row["executable"])
            assert old["stdout"] == new["stdout"], (row, old, new)
            old_meta, new_meta = query(row["sql"],True), query(row["executable"],True)
            def meta(r): return re.findall(r"^(Type|Decimals|Flags):\s*(.*)$",r["stdout"],re.M)
            assert meta(old_meta) and meta(old_meta) == meta(new_meta), row
            row.update(original_result=old,generated_result=new,original_metadata=old_meta,generated_metadata=new_meta)
        # No non-SELECT statement ever reaches query().
    jar = run(["docker","exec",container,"sha256sum","/data/deploy/starrocks/fe/lib/fe-core-4.1.4.jar"])
    assert jar.returncode == 0, jar.stderr
    version = query("SELECT current_version(),@@sql_mode")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Read-only SELECT value/type controls and shipped FE parser classes. DDL/refresh/admin/job-control are NEVER executed. Opaque preservation is not structured support.",
                                         image=info["Config"]["Image"],version=version,native_parser_jar_sha256=jar.stdout.split()[0],
                                         native_harness_sha256=hashlib.sha256(native_java.read_bytes()).hexdigest(),
                                         native_parser_stderr=native.stderr,records=records),indent=2)+'\n')
    print(f"Captured {len(scalar+pivots+unnests)} read-only value/type pairs, {len(ddl+refresh)} structured parse-only pairs and {len(opaque)} opaque-preservation records")


if __name__ == "__main__":
    main()
