#!/usr/bin/env python3
"""Capture pinned ClickHouse parsing and bounded execution evidence. No live server or credentials.

Requires Docker and the images in engines.json, explicitly pulled by the operator. Uses no
network or host mounts inside containers. DDL audit vectors only enter formatQueryOrNull as
hex strings; only the separate finite execution fixtures run. Every process has a deadline.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/clickhouse-2026-audit"


def parsing_cases(fixtures):
    # Semantic failures can still be valid syntax. Opt in to formatting every finite
    # execution vector too, without duplicating SQL or changing historical audits.
    return fixtures["cases"] + (fixtures["execution"] if fixtures.get("parse_execution", False) else [])


def parsing_expectation(case, version):
    if "parse_expectations" not in case:
        return case.get("accept", True)
    if version not in case["parse_expectations"]:
        raise ValueError(f"Missing explicit parser expectation: {case['id']}/{version}")
    return case["parse_expectations"][version]


def query(engine, sql, streaming=False):
    name = "brikk-ch26-audit-" + uuid.uuid4().hex
    cmd = ["docker", "run", "--rm", "--pull", "never", "--platform", "linux/amd64", "--name", name, "--network", "none",
           "--cpus", "2", "--memory", "2g", "--pids-limit", "256", "--entrypoint", "clickhouse",
           engine["image"], "local", "--max_threads", "2", "--background_schedule_pool_size", "4",
           "--max_execution_time", "5"]
    if streaming:
        cmd += ["--enable_streaming_queries", "1"]
    try:
        result = subprocess.run(cmd + ["--query", sql], capture_output=True, text=True, timeout=30)
        return {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except subprocess.TimeoutExpired:
        return {"returncode": None, "stdout": "", "stderr": "Host deadline exceeded (30 seconds)"}
    finally:
        # The UUID names only a container created by this invocation, never another session's.
        subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=15)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--engine", action="append", help="Exact pinned version; defaults to both")
    parser.add_argument("--brikk", type=pathlib.Path, help="JSON observations from the audit script or fixture test")
    parser.add_argument("--cases", type=pathlib.Path, default=DATA / "cases.json")
    parser.add_argument("--task", action="append", help="Plan tasks whose generated parsing is checked; defaults to CH26-002")
    args = parser.parse_args()
    fixture_bytes = args.cases.read_bytes()
    fixtures = json.loads(fixture_bytes)
    parse_cases = parsing_cases(fixtures)
    tasks = set(args.task or ["CH26-002"])
    brikk_bytes = args.brikk.read_bytes() if args.brikk else None
    brikk = {r["id"]: r for r in json.loads(brikk_bytes)} if brikk_bytes is not None else {}
    engines = json.loads((DATA / "engines.json").read_text())["engines"]
    if args.engine:
        known = {e["version"] for e in engines}
        if set(args.engine) - known:
            parser.error("Unknown engine version")
        engines = [e for e in engines if e["version"] in args.engine]
    args.output.mkdir(parents=True, exist_ok=True)
    failures = []
    for engine in engines:
        version = query(engine, "SELECT version()")
        if version["returncode"] != 0 or version["stdout"].strip() != engine["version"]:
            raise RuntimeError(f"Pinned engine mismatch: {engine}: {version}")
        tuples = ",".join("('" + c["id"] + "','" + c["sql"].encode().hex() + "')" for c in parse_cases)
        parsed = query(engine, "SELECT item.1 AS id, formatQueryOrNull(unhex(item.2)) AS formatted "
                       f"FROM (SELECT arrayJoin([{tuples}]) AS item) FORMAT JSONEachRow")
        if parsed["returncode"] != 0:
            raise RuntimeError(parsed)
        rows = [json.loads(line) for line in parsed["stdout"].splitlines()]
        for case, row in zip(parse_cases, rows, strict=True):
            assert case["id"] == row["id"]
            # Older engines are observations only for syntax introduced in later releases.
            if tuple(map(int, case.get("release", "0.0").split("."))) <= tuple(map(int, engine["version"].split(".")[:2])):
                if (row["formatted"] is not None) != parsing_expectation(case, engine["version"]):
                    failures.append(engine["version"] + "/" + case["id"] + ": native parse expectation")
        brikk_parsed = None
        if args.brikk:
            fixed = [c for c in parse_cases if c["task"] in tasks and parsing_expectation(c, engine["version"])
                     and tuple(map(int, c.get("release", "0.0").split("."))) <= tuple(map(int, engine["version"].split(".")[:2]))]
            generated_tuples = []
            for case in fixed:
                generated = brikk[case["id"]].get("generated")
                if generated is None:
                    failures.append(engine["version"] + "/" + case["id"] + ": Brikk generation failed")
                    continue
                generated_tuples.append("('" + case["id"] + "','" + generated.encode().hex() + "')")
            brikk_parsed = query(engine, "SELECT item.1 AS id, formatQueryOrNull(unhex(item.2)) AS formatted "
                                 "FROM (SELECT arrayJoin([" + ",".join(generated_tuples) + "]) AS item) FORMAT JSONEachRow")
            if brikk_parsed["returncode"] != 0:
                failures.append(engine["version"] + ": Brikk generated parser query failed")
            else:
                originals = {r["id"]: r["formatted"] for r in rows}
                explicit_formats = {c["id"]: c["native_generated_format"] for c in fixed if "native_generated_format" in c}
                for row in map(json.loads, brikk_parsed["stdout"].splitlines()):
                    expected = explicit_formats.get(row["id"], originals[row["id"]])
                    if row["formatted"] is None or row["formatted"] != expected:
                        failures.append(engine["version"] + "/" + row["id"] + ": native AST formatting differs")
        execution = []
        brikk_execution = []
        for case in fixtures["execution"]:
            if tuple(map(int, case.get("release", "0.0").split("."))) > tuple(map(int, engine["version"].split(".")[:2])):
                execution.append({"id": case["id"], "skipped": "Introduced after this pinned engine"})
                continue
            expected = execution_expectation(case, engine["version"])
            sql = case.get("setup", "") + "; " + case["sql"]
            result = query(engine, sql.lstrip("; "), streaming=" STREAM" in case["sql"])
            execution.append({"id": case["id"], **result})
            if not expected_execution(expected, result):
                failures.append(engine["version"] + "/" + case["id"] + ": native execution expectation")
            if args.brikk:
                generated = brikk[case["id"]].get("generated")
                if generated is None:
                    failures.append(engine["version"] + "/" + case["id"] + ": Brikk generation failed")
                    continue
                result = query(engine, (case.get("setup", "") + "; " + generated).lstrip("; "),
                               streaming=" STREAM" in case["sql"])
                brikk_execution.append({"id": case["id"], "sql": generated, **result})
                if not expected_execution(expected, result):
                    failures.append(engine["version"] + "/" + case["id"] + ": Brikk execution expectation")
        report = {"captured_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "fixture_sha256": hashlib.sha256(fixture_bytes).hexdigest(),
                  "brikk_sha256": hashlib.sha256(brikk_bytes).hexdigest() if brikk_bytes is not None else None,
                  "engine": engine, "version_query": version, "parse_query": parsed,
                  "parsed": rows, "brikk_parse_query": brikk_parsed,
                  "execution": execution, "brikk_execution": brikk_execution}
        (args.output / (engine["version"] + ".json")).write_text(json.dumps(report, indent=2) + "\n")
        print(engine["version"], len(rows), "parse observations;",
              sum("skipped" not in r for r in execution), "native execution outcomes;",
              len(brikk_execution), "generated execution outcomes;",
              sum("skipped" in r for r in execution), "version-inapplicable cases")
    if failures:
        raise SystemExit("\n".join(failures))


def expected_execution(case, result):
    if "error_code" in case:
        return (result["returncode"] == case["error_code"] % 256 and result["stdout"] == ""
                and case["error_name"] in result["stderr"]
                and all(text in result["stderr"] for text in case.get("error_contains", [])))
    return result["returncode"] == 0 and result["stdout"] == case["stdout"]


def execution_expectation(case, version):
    if "expectations" not in case:
        return case
    # A changed native behavior is tested on each pin, not relabeled as an unavailable case.
    if version not in case["expectations"]:
        raise ValueError(f"Missing explicit native expectation: {case['id']}/{version}")
    return case["expectations"][version]


if __name__ == "__main__":
    main()
