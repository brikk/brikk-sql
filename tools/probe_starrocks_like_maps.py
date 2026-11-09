"""Finite SG29-014 LIKE/map value/type controls in a private StarRocks instance."""
import argparse
import base64
import json
import re
import subprocess
from pathlib import Path


def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, timeout=60, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java", default="java")
    p.add_argument("--classpath", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    container = "brikk-sg014-starrocks"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    host = info["HostConfig"]
    assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings")

    def query(sql, metadata=False):
        flags = ["--column-type-info", "-vvv"] if metadata else ["--batch", "--raw", "--skip-column-names"]
        r = run(["docker", "exec", container, "mysql", "-h127.0.0.1", "-P9030", "-uroot", *flags, "-e", sql])
        assert r.returncode == 0, (sql, r.stderr)
        return dict(stdout=r.stdout, stderr=r.stderr, returncode=r.returncode)

    cases = [
        ("like", "LIKE('xyz','x%')", "", "1"),
        ("like-reverse", "LIKE('x%','xyz')", "", "0"),
        ("like-null", "LIKE(NULL,'x%')", "", "NULL"),
        ("like-null-pattern", "LIKE('xyz',NULL)", "", "NULL"),
        ("like-empty", "LIKE('','')", "", "1"),
        ("like-columns", "LIKE(t.value,t.pattern)", " FROM (SELECT 'xyz' value,'x%' pattern) t", "1"),
        ("map-int", "MAP{1:2}", "", "{1:2}"),
        ("map-cast-int", "MAP{CAST(1 AS INT):CAST(2 AS INT)}", "", "{1:2}"),
        ("map-empty", "MAP{}", "", "{}"),
        ("map-null-key", "MAP{NULL:1}", "", "{null:1}"),
        ("map-duplicate", "MAP{'a':1,'a':2}", "", '{"a":2}'),
        ("map-lookup", "MAP{1:2,3:NULL}[1]", "", "2"),
        ("map-missing", "MAP{1:2}[9]", "", "NULL"),
        ("map-nested", "MAP{1:MAP{2:10},3:NULL}[1][2]", "", "10"),
        ("map-array", "MAP{1:[10,20]}[1][2]", "", "20"),
        ("map-expression", "MAP{1+2:10}[3]", "", "10"),
        ("map-qualified", "MAP{t.k:10}[1]", " FROM (SELECT 1 k) t CROSS JOIN (SELECT 2 k) u", "10"),
        ("map-columns", "MAP{t.k:t.v}['a']", " FROM (SELECT 'a' k,10 v) t", "10"),
        ("map-function-regression", "MAP('a',10,'b',20)['b']", "", "20"),
    ]
    requests = [("starrocks", "starrocks", f"SELECT {expr} AS v, TYPEOF({expr}) AS engine_type{source}") for _, expr, source, _ in cases]
    requests += [("starrocks", target, "SELECT MAP{'a':1}") for target in ("duckdb", "postgres", "mysql", "trino", "clickhouse")]
    requests += [("duckdb", "starrocks", "SELECT MAP {'a':1}")]
    payload = "".join(s + "\t" + t + "\t" + base64.b64encode(q.encode()).decode() + "\n" for s, t, q in requests)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(requests), bridge.stdout
    observations, refusals = [], []
    for i, (request, line) in enumerate(zip(requests, lines, strict=True)):
        fields = line.split("\t")
        assert fields[0] == "OK", (request, line)
        ast, preview, status, executable = [base64.b64decode(x).decode() for x in fields[1:]]
        if i >= len(cases):
            assert status.startswith("unsupported:") and not executable, (request, line)
            refusals.append(dict(source=request[0], target=request[1], sql=request[2], preview=preview, status=status))
            continue
        label, _, _, expected = cases[i]
        assert status == "accepted", (request, line)
        original, generated = query(request[2]), query(executable)
        assert original["stdout"] == generated["stdout"], (request, executable, original, generated)
        row = original["stdout"].strip().split("\t")
        assert row[0] == expected and len(row) == 2, (request, row)
        original_meta, generated_meta = query(request[2], True), query(executable, True)
        def metadata(result):
            return re.findall(r"^(Type|Decimals|Flags):\s*(.*)$", result["stdout"], re.M)
        assert metadata(original_meta) and metadata(original_meta) == metadata(generated_meta), (request, original_meta, generated_meta)
        observations.append(dict(case=label, sql=request[2], ast=ast, preview=preview, executable=executable,
                                 original=original, generated=generated, original_metadata=original_meta, generated_metadata=generated_meta))
    version = query("SELECT current_version(),@@sql_mode")
    assert "4.1.4-4a9848e" in version["stdout"], version
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Finite native StarRocks LIKE/map original/generated value and type witnesses, not general map coercion or cross-engine null/duplicate semantics.",
                                         image=info["Config"]["Image"], version=version,
                                         observations=observations, map_execution_refusals=refusals), indent=2) + "\n")
    print(f"Captured {len(observations)} paired native LIKE/map value/type controls and {len(refusals)} map execution refusals")


if __name__ == "__main__":
    main()
