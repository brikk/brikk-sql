#!/usr/bin/env python3
"""Capture finite multi-statement SET witnesses in isolated exact-pin local sessions."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query, execution_expectation, expected_execution


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=pathlib.Path, required=True)
    parser.add_argument("--brikk", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    raw, typed = args.cases.read_bytes(), args.brikk.read_bytes()
    cases = json.loads(raw)["execution"]
    brikk = json.loads(typed)
    assert [r["id"] for r in cases] == [r["id"] for r in brikk]
    args.output.mkdir(parents=True, exist_ok=True)
    for engine in json.loads((DATA / "engines.json").read_text())["engines"]:
        version = query(engine, "SELECT version()")
        assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n"
        report = {"engine": engine, "version_query": version, "fixture_sha256": hashlib.sha256(raw).hexdigest(),
                  "brikk_sha256": hashlib.sha256(typed).hexdigest(), "execution": [], "brikk_execution": []}
        for case, row in zip(cases, brikk, strict=True):
            assert row["input_sql"] == case["sql"]
            for phase, sql in (("execution", case["sql"]), ("brikk_execution", row["generated"])):
                result = {"id": case["id"], **query(engine, sql)}
                assert expected_execution(execution_expectation(case, engine["version"]), result), (case, result)
                report[phase].append(result)
        (args.output / (engine["version"] + ".json")).write_text(json.dumps(report, indent=2) + "\n")
        print(engine["version"], len(cases), "original/generated session scripts")


if __name__ == "__main__":
    main()
