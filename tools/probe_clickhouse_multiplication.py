#!/usr/bin/env python3
"""Independent native primitive multiplication matrices; no frontend-derived expected types."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

TYPES = [f"{sign}{bits}" for sign in ("Int", "UInt") for bits in (8, 16, 32, 64, 128, 256)] + ["Float32", "Float64"]


def matrix_input():
    batches = []
    for form in ("operator", "builtin"):
        for left_null, right_null in ((False, False), (True, False), (False, True), (True, True)):
            pairs, expressions = [], []
            for left in TYPES:
                for right in TYPES:
                    # NumberTraits cannot construct a Float128/256 result. Capture refusal separately.
                    if (left.startswith("Float") and right in ("Int128", "UInt128", "Int256", "UInt256") or
                            right.startswith("Float") and left in ("Int128", "UInt128", "Int256", "UInt256")):
                        continue
                    a = f"Nullable({left})" if left_null else left
                    b = f"Nullable({right})" if right_null else right
                    x, y = f"CAST(7 AS {a})", f"CAST(2 AS {b})"
                    operation = f"multiply({x},{y})" if form == "builtin" else f"({x}*{y})"
                    id = f"p{len(pairs):03}"
                    pairs.append({"id": id, "left": a, "right": b})
                    expressions.append(f"toTypeName({operation}) AS {id}")
            batches.append({"id": form + f"-nullable-{int(left_null)}{int(right_null)}", "pairs": pairs,
                            "sql": "SELECT " + ",".join(expressions) + " FORMAT JSONEachRow"})
    return {"task": "CHSEM-025", "batches": batches}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(matrix_input(), indent=2) + "\n").encode()
    (args.output / "input.json").write_bytes(raw)
    for engine in json.loads((DATA / "engines.json").read_text())["engines"]:
        version = query(engine, "SELECT version()")
        assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n"
        batches = []
        for batch in json.loads(raw)["batches"]:
            observed = query(engine, batch["sql"])
            assert observed["returncode"] == 0, observed
            types = json.loads(observed["stdout"])
            assert set(types) == {p["id"] for p in batch["pairs"]}
            batches.append({"id": batch["id"], "raw": observed, "types": types})
        (args.output / (engine["version"] + ".json")).write_text(json.dumps({"engine": engine,
            "version_query": version, "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": batches}, indent=2) + "\n")
        print(engine["version"], sum(len(b["types"]) for b in batches), "independent native types")


if __name__ == "__main__":
    main()
