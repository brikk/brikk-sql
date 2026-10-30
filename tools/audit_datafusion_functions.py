#!/usr/bin/env python3
"""Enumerate every captured name and probe native call spelling/argument preservation.

Marker calls are parser-only, including intentionally invalid arities. Their purpose is
to detect silently discarded/reordered inputs, not to claim those arities execute.
Typed-NULL execution probes supplement the non-null native regression corpus.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/datafusion/55.2.0/metadata-capture.json"

TYPES = {"String": "VARCHAR", "Utf8": "VARCHAR", "LargeUtf8": "VARCHAR", "Utf8View": "VARCHAR",
         "Boolean": "BOOLEAN", "Int8": "TINYINT", "Int16": "SMALLINT", "Int32": "INT", "Int64": "BIGINT",
         "Float32": "REAL", "Float64": "DOUBLE", "Binary": "BYTEA", "LargeBinary": "BYTEA",
         "Date32": "DATE", "Date64": "DATE", "Timestamp": "TIMESTAMP", "Time": "TIME", "Null": None}


def generate():
    capture = json.loads(DATA.read_text())
    names = sorted({r["name"] for r in capture["registry"]})
    cases = []
    inventory = []
    samples = capture["queries"]["show_functions"]["rows"]
    for name in names:
        registrations = [r for r in capture["registry"] if r["name"] == name]
        item = {"name": name, "kinds": sorted(r["kind"] for r in registrations), "execution_probe": None}
        for arity in range(7):
            args = ", ".join(f"__df_arg_{i}" for i in range(arity))
            cases.append({"id": f"fn-{name}-{arity}", "name": name, "arity": arity,
                          "execute": False, "sql": f"SELECT {name}({args}) AS v"})
        cases.append({"id": f"fn-{name}-named", "name": name, "arity": 2, "execute": False,
                      "sql": f"SELECT {name}(arg0 => __df_arg_0, arg1 => __df_arg_1) AS v"})
        for row in samples:
            if row["function_name"] != name or row["function_type"] not in ["SCALAR", "AGGREGATE"]:
                continue
            registry = next(r for r in registrations if r["kind"] == row["function_type"])
            if registry["volatility"] != "Immutable":
                continue
            types = row.get("parameter_types")
            if types is None or any(t not in TYPES for t in types):
                continue
            args = ", ".join("NULL" if TYPES[t] is None else f"CAST(NULL AS {TYPES[t]})" for t in types)
            id = f"exec-{name}"
            cases.append({"id": id, "name": name, "execute": True, "sql": f"SELECT {name}({args}) AS v"})
            item["execution_probe"] = id
            break
        if item["execution_probe"] is None:
            item["execution_limit"] = "No immutable scalar/aggregate sample with representable primitive argument types; covered by parser-only markers"
        inventory.append(item)
    return {"schema_version": 1, "capture_sha256": hashlib.sha256(DATA.read_bytes()).hexdigest(),
            "inventory": inventory, "cases": cases}


def summarize(cases, observations):
    by_id = {c["id"]: c for c in observations["cases"]}
    if set(by_id) != {c["id"] for c in cases["cases"]}:
        raise ValueError("Incomplete observation inventory")
    failures = []
    for c in cases["cases"]:
        if "arity" not in c:
            continue
        row = by_id[c["id"]]
        if row["status"] != "accepted":
            continue
        generated = row.get("generated", "")
        markers = re.findall(r"__df_arg_\d+", generated, re.I)
        expected = [f"__df_arg_{i}" for i in range(c["arity"])]
        if [m.lower() for m in markers] != expected:
            failures.append({"id": c["id"], "name": c["name"], "sql": c["sql"], "generated": generated,
                             "issue": "argument-loss-or-reorder", "expected_markers": expected, "actual_markers": markers})
    return {"schema_version": 1, "names": len(cases["inventory"]), "cases": len(cases["cases"]),
            "argument_failures": failures}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--observations", type=Path)
    p.add_argument("--execution-only", action="store_true")
    args = p.parse_args()
    cases = generate()
    if args.execution_only:
        cases["cases"] = [c for c in cases["cases"] if c["execute"]]
    result = summarize(cases, json.loads(args.observations.read_text())) if args.observations else cases
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
