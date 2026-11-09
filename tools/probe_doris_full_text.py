"""SG29-013 private Doris FE parsed/analyzed-plan controls, never backend rows."""
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
    container = "brikk-sg013-doris-fe"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    host = info["HostConfig"]
    assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings")

    def query(sql):
        r = run(["docker", "exec", container, "mysql", "-h127.0.0.1", "-P9030", "-uroot",
                 "--batch", "--raw", "--skip-column-names", "-e", sql])
        assert r.returncode == 0, (sql, r.stderr)
        return dict(stdout=r.stdout, stderr=r.stderr, returncode=r.returncode)

    def normalized(plan):
        # These synthetic operands contain no numeric ID-shaped string data.
        return re.sub(r"#\d+", "#ID", re.sub(r"\[\d+\](?= \()", "[ID]", plan["stdout"]))

    operators = ("MATCH", "MATCH_ANY", "MATCH_ALL", "MATCH_PHRASE", "MATCH_PHRASE_PREFIX", "MATCH_PHRASE_EDGE", "MATCH_REGEXP")
    cases = [("PARSED", f"SELECT * FROM t WHERE c {op} ('word1 word2'){analyzer}")
             for op in operators for analyzer in ("", " USING ANALYZER 'standard'")]
    cases += [("PARSED", q) for q in (
        "SELECT c match_any FROM t", "SELECT c match_any", "SELECT c AS match_all FROM t",
        "SELECT * FROM t WHERE c NOT MATCH_ALL ('x') USING ANALYZER 'standard' AND d MATCH_ANY 'y' OR e = 1",
        "SELECT c MATCH_ANY terms AS found FROM t",
        "SELECT c MATCH_ALL CONCAT('a', 'b') AS found FROM t",
        "SELECT c MATCH_ANY 'x' USING ANALYZER `std_analyzer` AS found FROM t",
    )]
    cases += [("ANALYZED", f"SELECT c {op} 'word' AS found FROM (SELECT CAST('word' AS STRING) AS c) t") for op in operators]
    requests = [("doris", "doris", sql) for _, sql in cases]
    requests += [("doris", target, "SELECT c MATCH_ALL 'word' USING ANALYZER 'standard' FROM t")
                 for target in ("mysql", "starrocks", "postgres", "duckdb", "trino", "clickhouse", "bigquery", "spark")]
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
        if i < len(cases):
            level, sql = cases[i]
            assert status == "accepted", (request, status)
            original = query(f"EXPLAIN {level} PLAN " + sql)
            generated = query(f"EXPLAIN {level} PLAN " + executable)
            assert normalized(original) == normalized(generated), (request, original, generated)
            observations.append(dict(level=level, sql=sql, ast=ast, preview=preview, executable=executable,
                                     original=original, generated=generated, normalized_plans_equal=True,
                                     classification="FE plan/binding evidence, not backend value/index/analyzer execution"))
        else:
            assert status.startswith("unsupported:") and not executable, (request, line)
            refusals.append(dict(source=request[0], target=request[1], sql=request[2], preview=preview, status=status))
    version = query("SHOW FRONTENDS")
    assert "doris-4.1.5-release-917b20be838" in version["stdout"], version
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Private FE parser and semantic binding only. No backend result, index/tokenizer behavior, analyzer catalog availability or general target equivalence is claimed.",
                                         image=info["Config"]["Image"], version=version,
                                         host_vm_max_map_count=int(Path('/proc/sys/vm/max_map_count').read_text()),
                                         backend_execution=False, kernel_setting_changed=False,
                                         observations=observations, outbound_refusals=refusals), indent=2) + "\n")
    print(f"Captured {len(observations)} paired FE plans and {len(refusals)} outbound refusals; no backend rows")


if __name__ == "__main__":
    main()
