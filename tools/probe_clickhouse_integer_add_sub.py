#!/usr/bin/env python3
"""Independent exact-pin integer +/- operator/builtin type matrices, including nullable inputs."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

TYPES = [f"{sign}{bits}" for sign in ("Int", "UInt") for bits in (8,16,32,64,128,256)]


def matrix_input():
    batches = []
    for form in ("plus-op", "minus-op", "plus-fn", "minus-fn"):
        for left_null, right_null in ((False, False), (True, False), (False, True), (True, True)):
            pairs, expressions = [], []
            for left in TYPES:
                for right in TYPES:
                    a = f"Nullable({left})" if left_null else left
                    b = f"Nullable({right})" if right_null else right
                    x, y = f"CAST(7 AS {a})", f"CAST(2 AS {b})"
                    operation = f"{form.split('-')[0]}({x},{y})" if form.endswith("fn") else f"({x}{'+' if form.startswith('plus') else '-'}{y})"
                    id = f"p{len(pairs):03}"
                    expressions.append(f"toTypeName({operation}) AS {id}")
                    pairs.append({"id": id, "left": a, "right": b})
            id = form + f"-nullable-{int(left_null)}{int(right_null)}"
            batches.append({"id": id, "pairs": pairs, "sql": "SELECT " + ",".join(expressions) + " FORMAT JSONEachRow"})
    return {"batches": batches}


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
            print(engine["version"], batch["id"], len(types), "native types")
        (args.output / (engine["version"] + ".json")).write_text(json.dumps({"engine": engine,
            "version_query": version, "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": batches}, indent=2) + "\n")


if __name__ == "__main__":
    main()
