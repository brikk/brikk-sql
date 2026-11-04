#!/usr/bin/env python3
"""Capture finite SG29-B SELECTs in private, network-isolated Docker oracles.

This starts/stops no containers and installs nothing. Provision the documented
brikk-sg29b-* test containers first. Never point this tool at a user's database.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ENGINES = {
    "mysql": ("brikk-sg29b-mysql", ["-uroot"], "SELECT VERSION(), @@sql_mode"),
    "doris": ("brikk-sg29b-doris-fe", ["-h127.0.0.1", "-P9030", "-uroot"], "SHOW FRONTENDS"),
    "starrocks": ("brikk-sg29b-starrocks", ["-h127.0.0.1", "-P9030", "-uroot"], "SELECT CURRENT_VERSION()"),
}


def run(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, timeout=30, **kwargs)


def query(engine, sql):
    container, options, _ = ENGINES[engine]
    frontend = engine == "doris" and not sql.startswith("SHOW")
    original = sql
    if frontend:
        sql = "SET enable_fold_constant_by_be=false; EXPLAIN REWRITTEN PLAN " + sql
    result = run(["docker", "exec", container, "mysql", "--connect-timeout=5",
                  "--batch", "--raw", "--skip-column-names", *options, "-e", sql])
    observed = {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
                "evidence": "frontend-rewrite-plan" if frontend else "native-rows"}
    if frontend and result.returncode == 0:
        if re.search(r"\b(UNION|INTERSECT|EXCEPT)\b", original, re.I):
            observed["evidence"] = "frontend-set-plan-only-not-row-execution"
        else:
            literal = re.findall(r"projects=\[(TRUE|FALSE|NULL|-?\d+) AS", result.stdout)
            if len(literal) == 1:
                observed["constant_value"] = {"TRUE": "1", "FALSE": "0"}.get(literal[0], literal[0]) + "\n"
            else:
                observed["evidence"] = "frontend-expression-plan-only-not-value-execution"
    return observed


def comparable(observed, sql):
    if observed["returncode"] != 0 or "plan-only" in observed["evidence"]:
        return None
    value = observed.get("constant_value", observed["stdout"])
    # Unordered set outputs are bags. An authored ORDER BY must still be compared in order.
    return value if re.search(r"\bORDER\s+BY\b", sql, re.I) else "\n".join(sorted(value.splitlines()))


def capture_clickhouse(args):
    container = "brikk-sg29b-clickhouse"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    assert info["HostConfig"]["NetworkMode"] == "none" and not info["HostConfig"].get("Binds")
    assert not info["HostConfig"].get("PortBindings")

    def native(sql):
        result = run(["docker", "exec", container, "clickhouse-client", "--query", sql])
        return {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr, "evidence": "native-rows"}

    version = native("SELECT version()")
    assert version["returncode"] == 0 and version["stdout"].strip() == "26.10.2.103", version
    queries = [
        "SELECT 1 AS v UNION ALL SELECT 2 INTERSECT DISTINCT SELECT 2 LIMIT 0 SETTINGS max_threads = 1 FORMAT TabSeparated",
        "SELECT 1 AS v UNION ALL SELECT 2 INTERSECT DISTINCT SELECT 2 ORDER BY 1 LIMIT 1 SETTINGS max_threads = 1 FORMAT TabSeparated",
        "SELECT 1 = NULL IS NULL FORMAT TabSeparated",
        "SELECT 1 = NULL IS NOT NULL FORMAT TabSeparated",
    ]
    expected = ["1", "1\n2", "1", "0"]
    payload = "".join("clickhouse\tclickhouse\t" + base64.b64encode(sql.encode()).decode() + "\n" for sql in queries)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(queries), bridge.stdout
    cases = []
    for index, (sql, line, values) in enumerate(zip(queries, lines, expected, strict=True)):
        fields = line.split("\t")
        assert fields[0] == "OK", line
        ast, preview, strict, executable = [base64.b64decode(value).decode() for value in fields[1:]]
        assert strict == "accepted", (sql, strict)
        original, generated = native(sql), native(executable)
        assert original["returncode"] == generated["returncode"] == 0, (original, generated)
        assert original["stdout"].strip() == values, original
        assert comparable(original, sql) == comparable(generated, executable), (sql, generated)
        cases.append({"id": f"CH-{index + 1:02}", "sql": sql, "ast": ast, "preview": preview,
                      "executable": executable, "original": original, "generated": generated, "equal_values": True})
    report = {"scope": "Four finite ClickHouse global SETTINGS/FORMAT, arm-limit and IS controls; no other version qualification.",
              "image_id": info["Image"], "image_reference": info["Config"]["Image"], "version": version, "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Captured {len(cases)} ClickHouse native controls -> {args.output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--cases", type=Path, default=ROOT / "vendor/data/sqlglot-30.29-batch-b/cases.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--clickhouse-only", action="store_true")
    args = parser.parse_args()
    if args.clickhouse_only:
        return capture_clickhouse(args)
    cases = json.loads(args.cases.read_text())["cases"]
    for case in cases:
        assert re.match(r"^(SELECT\b|\(SELECT\b)", case["sql"]), case
        assert ";" not in case["sql"], case
    engines = {}
    for name, (container, _, version_sql) in ENGINES.items():
        inspected = run(["docker", "inspect", container])
        assert inspected.returncode == 0, inspected.stderr
        info = json.loads(inspected.stdout)[0]
        assert info["HostConfig"]["NetworkMode"] == "none", container
        assert not info["HostConfig"].get("Binds"), container
        assert not info["HostConfig"].get("PortBindings"), container
        version = query(name, version_sql)
        assert version["returncode"] == 0, (name, version)
        engines[name] = {"image_id": info["Image"], "image_reference": info["Config"]["Image"],
                         "network_mode": "none", "version": version}
        if name == "doris":
            assert "4.1.5" in version["stdout"], version
            engines[name]["profile"] = "FE parser/rewrite only; enable_fold_constant_by_be=false; no backend row execution"
        else:
            engines[name]["profile"] = "private backend row execution, default session settings"
    requests = [(case, source, target) for case in cases for source in case.get("sources", ENGINES)
                for target in ENGINES if source not in case.get("rejected_sources", [])]
    payload = "".join(source + "\t" + target + "\t" + base64.b64encode(case["sql"].encode()).decode() + "\n"
                      for case, source, target in requests)
    bridged = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridged.returncode == 0, bridged.stderr
    lines = bridged.stdout.splitlines()
    assert len(lines) == len(requests), bridged.stdout
    generated = {}
    for request, line in zip(requests, lines, strict=True):
        case, source, target = request
        fields = line.split("\t")
        assert fields[0] == "OK", (request, line)
        ast, sql, strict, executable = [base64.b64decode(value).decode() for value in fields[1:]]
        generated[(case["id"], source, target)] = {"ast": ast, "sql": sql, "strict": strict, "executable": executable}
    rows = []
    for case in cases:
        for source in case.get("sources", ENGINES):
            original = query(source, case["sql"])
            row = {"id": case["id"], "source": source, "sql": case["sql"], "original": original, "targets": []}
            rows.append(row)
            if source in case.get("rejected_sources", []):
                assert original["returncode"] != 0, row
                assert re.search(r"1064|syntax error|Unexpected input", original["stderr"], re.I), row
                row["classification"] = "native-rejection-control-not-equivalence"
                continue
            assert original["returncode"] == 0, row
            for target in ENGINES:
                translated = generated[(case["id"], source, target)]
                observed = {"target": target, **translated}
                row["targets"].append(observed)
                if target in case.get("unqualified_targets", []):
                    assert translated["strict"].startswith("unsupported:"), observed
                    observed["classification"] = "explicitly-unqualified-not-executed"
                    continue
                assert translated["strict"] == "accepted", observed
                observed["result"] = query(target, translated["executable"])
                assert observed["result"]["returncode"] == 0, observed
                source_value, target_value = comparable(original, case["sql"]), comparable(observed["result"], translated["executable"])
                if source_value is not None and target_value is not None:
                    observed["equal_values"] = source_value == target_value
                else:
                    observed["classification"] = "native-parser-plan-control-without-paired-row-proof"
    report = {"scope": "Finite scalar/row controls, not general semantic or metadata equivalence. Doris uses native FE constant folding with enable_fold_constant_by_be=false, not backend execution. Its set plans have no row-equivalence claim; host backend startup guard is not bypassed.",
              "cases_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(), "engines": engines, "rows": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    failures = [(r["id"], r["source"], t["target"], t.get("result")) for r in rows for t in r["targets"]
                if t.get("equal_values") is False]
    assert not failures, failures
    print(f"Captured {len(rows)} source controls; {sum(len(r['targets']) for r in rows)} target dispositions -> {args.output}")


if __name__ == "__main__":
    main()
