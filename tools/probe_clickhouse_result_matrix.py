#!/usr/bin/env python3
"""Capture all primitive multiply arrayMap return types on the existing exact pins.

Expected types come from the engines, never from a copy of the implementation's
promotion formula. Uses the shared isolated, bounded clickhouse-local runner.
"""
import argparse
import datetime
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

TYPES = ["UInt8", "UInt16", "UInt32", "UInt64", "Int8", "Int16", "Int32", "Int64", "Float32", "Float64"]


def matrix_input():
    pairs = [{"id": f"p{i:03}", "left": left, "right": right}
             for i, (left, right) in enumerate((a, b) for a in TYPES for b in TYPES)]
    expressions = [f"toTypeName(arrayMap(multiply,CAST([2] AS Array({p['left']})),CAST([3] AS Array({p['right']})))) AS {p['id']}"
                   for p in pairs]
    return {"types": TYPES, "pairs": pairs, "sql": "SELECT " + ",".join(expressions) + " FORMAT JSONEachRow"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    source = matrix_input()
    raw = (json.dumps(source, indent=2) + "\n").encode()
    (args.output / "input.json").write_bytes(raw)
    for engine in json.loads((DATA / "engines.json").read_text())["engines"]:
        version = query(engine, "SELECT version()")
        if version["returncode"] != 0 or version["stdout"] != engine["version"] + "\n":
            raise RuntimeError(f"Unexpected engine: {version}")
        result = query(engine, source["sql"])
        if result["returncode"] != 0:
            raise RuntimeError(result)
        observed = json.loads(result["stdout"])
        if set(observed) != {p["id"] for p in source["pairs"]}:
            raise RuntimeError("Incomplete native matrix")
        report = {"captured_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "engine": engine, "version_query": version,
                  "input_sha256": hashlib.sha256(raw).hexdigest(), "query": result, "types": observed}
        (args.output / f"{engine['version']}.json").write_text(json.dumps(report, indent=2) + "\n")
        print(engine["version"], len(observed), "native primitive result types")


if __name__ == "__main__":
    main()
