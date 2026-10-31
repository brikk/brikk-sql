#!/usr/bin/env python3
"""Independently observe division result types across the exact 26.10 integer/float families."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

TYPES = ["UInt8", "UInt16", "UInt32", "UInt64", "UInt128", "UInt256",
         "Int8", "Int16", "Int32", "Int64", "Int128", "Int256", "Float32", "Float64"]


def matrix_input():
    batches = []
    for form, nullable in [("slash-on", False), ("divide-on", False), ("exact", False), ("slash-off", False),
                           ("slash-on", True), ("exact", True), ("slash-off", True)]:
        pairs = []
        expressions = []
        for left in TYPES:
            for right in TYPES:
                id = f"p{len(pairs):03}"
                a = f"Nullable({left})" if nullable else left
                b = f"Nullable({right})" if nullable else right
                x, y = f"CAST(7 AS {a})", f"CAST(2 AS {b})"
                operation = f"divideExact({x},{y})" if form == "exact" else f"divide({x},{y})" if form == "divide-on" else f"({x}/{y})"
                expressions.append(f"toTypeName({operation}) AS {id}")
                pairs.append({"id": id, "left": a, "right": b})
        setting = "" if form == "exact" else " SETTINGS sql_compatible_division_for_integers=" + ("0" if form == "slash-off" else "1")
        batches.append({"id": form + ("-nullable" if nullable else ""), "pairs": pairs,
                        "sql": "SELECT " + ",".join(expressions) + setting + " FORMAT JSONEachRow"})
    return {"types": TYPES, "batches": batches}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(matrix_input(), indent=2) + "\n").encode()
    (args.output / "input.json").write_bytes(raw)
    engine = next(e for e in json.loads((DATA / "engines.json").read_text())["engines"] if e["version"] == "26.10.2.103")
    version = query(engine, "SELECT version()")
    assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n", version
    observed = []
    for batch in json.loads(raw)["batches"]:
        result = query(engine, batch["sql"])
        assert result["returncode"] == 0, (batch["id"], result)
        types = json.loads(result["stdout"])
        assert set(types) == {p["id"] for p in batch["pairs"]}
        observed.append({"id": batch["id"], "raw": result, "types": types})
        print(batch["id"], len(types), "observed native result types")
    report = {"engine": engine, "version_query": version, "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": observed}
    (args.output / "26.10.2.103.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
