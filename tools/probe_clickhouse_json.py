"""SG29-010 finite native ClickHouse JSON values/types and facade dispositions.

Requires only the private brikk-sg010-clickhouse container. Provisions nothing.
"""
import argparse
import base64
import json
import subprocess
from pathlib import Path


def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, timeout=60, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--classpath", required=True)
    p.add_argument("--java", default="java")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    container = "brikk-sg010-clickhouse"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    host = info["HostConfig"]
    assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings")

    def query(sql):
        r = run(["docker", "exec", container, "clickhouse-client", "--query", sql + " FORMAT JSONCompactEachRowWithNamesAndTypes"])
        assert r.returncode == 0, (sql, r.stderr)
        lines = [json.loads(x) for x in r.stdout.splitlines()]
        assert len(lines) >= 2
        return dict(types=lines[1], rows=lines[2:], stdout=r.stdout, stderr=r.stderr)

    doc = "'{\"obj\":{\"b\":1},\"arr\":[10,20],\"s\":\"hello\",\"n\":12,\"bool\":true,\"null\":null,\"12\":\"key\",\"a.b\":\"dot\",\"\":\"empty\"}'"
    cases = [(f"SELECT JSONExtractRaw({doc}, '{key}') AS v", "String", value) for key, value in
             (("obj", '{"b":1}'), ("arr", '[10,20]'), ("s", '"hello"'), ("n", '12'),
              ("bool", 'true'), ("null", 'null'), ("missing", ''), ("12", '"key"'), ("a.b", '"dot"'), ("", '"empty"'))]
    cases += [(f"SELECT JSONExtractRaw({doc}, 'arr', {i}) AS v", "String", value) for i, value in
              ((1, '10'), (2, '20'), (0, ''), (-1, '20'), (3, ''))]
    cases += [
        (f"SELECT JSONExtractString({doc}, 's') AS v", "String", 'hello'),
        (f"SELECT JSONExtractString({doc}, 'n') AS v", "String", '12'),
        (f"SELECT JSONExtractString({doc}, 'null') AS v", "String", ''),
        (f"SELECT JSONExtractString({doc}, '12') AS v", "String", 'key'),
        (f"SELECT JSONExtractString({doc}, 'bool') AS v", "String", 'true'),
        (f"SELECT JSONExtractString({doc}, 'arr') AS v", "String", '[10,20]'),
        (f"SELECT JSONExtractString({doc}, 'obj') AS v", "String", '{"b":1}'),
        ("SELECT JSONExtractRaw(CAST(NULL AS Nullable(String)), 'a') AS v", "Nullable(String)", None),
        ("SELECT JSONExtractRaw('[10,20]') AS v", "String", '[10,20]'),
        (f"SELECT JSONExtractRaw({doc}, path, 2) AS v FROM (SELECT 'arr' path)", "String", '20'),
        ("SELECT JSONExtractRaw('{\"a\\'b\":42}', 'a\\'b') AS v", "String", '42'),
    ]
    foreign = [("mysql", f"SELECT JSON_EXTRACT({doc}, '$.arr[0]') AS v", '10'),
               ("mysql", f"SELECT JSON_EXTRACT({doc}, '$.obj') AS v", '{"b":1}'),
               ("mysql", f"SELECT JSON_EXTRACT({doc}, '$.null') AS v", 'null'),
               ("mysql", f"SELECT JSON_EXTRACT({doc}, '$.missing') AS v", '')]
    requests = [("clickhouse", "clickhouse", sql) for sql, _, _ in cases]
    requests += [(source, "clickhouse", sql) for source, sql, _ in foreign]
    requests += [("clickhouse", target, cases[0][0]) for target in ("postgres", "duckdb", "mysql")]
    payload = "".join(s + "\t" + t + "\t" + base64.b64encode(q.encode()).decode() + "\n" for s, t, q in requests)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(requests), bridge.stdout
    observations, previews, refusals = [], [], []
    for i, (request, line) in enumerate(zip(requests, lines, strict=True)):
        fields = line.split("\t")
        assert fields[0] == "OK", (request, line)
        ast, preview, status, executable = [base64.b64decode(x).decode() for x in fields[1:]]
        if i < len(cases):
            sql, wanted_type, wanted_value = cases[i]
            assert status == "accepted", (request, status)
            original, generated = query(sql), query(executable)
            assert original["types"] == generated["types"] == [wanted_type], (request, original, generated)
            assert original["rows"] == generated["rows"] == [[wanted_value]], (request, original, generated)
            observations.append(dict(sql=sql, ast=ast, preview=preview, executable=executable, original=original, generated=generated))
        elif i < len(cases) + len(foreign):
            source, sql, wanted = foreign[i - len(cases)]
            assert status.startswith("unsupported:") and not executable, (request, status)
            result = query(preview)
            assert result["types"] == ["String"] and result["rows"] == [[wanted]], (request, result)
            previews.append(dict(source=source, sql=sql, ast=ast, preview=preview, status=status, preview_native_result=result,
                                 classification="Finite ClickHouse preview result only, not source/target execution equivalence"))
        else:
            assert status.startswith("unsupported:") and not executable, (request, status)
            refusals.append(dict(source=request[0], target=request[1], sql=request[2], preview=preview, status=status))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Native ClickHouse String/Nullable(String) behavior. JSON null, missing empty string and SQL NULL are distinct; no general cross-engine or arbitrary JSONPath equivalence.",
                                         image=info["Config"]["Image"], version=query("SELECT version()"),
                                         observations=observations, preview_controls=previews, outbound_refusals=refusals), indent=2) + "\n")
    print(f"Captured {len(observations)} native JSON value/type pairs, {len(previews)} preview-only controls and {len(refusals)} outbound refusals")


if __name__ == "__main__":
    main()
