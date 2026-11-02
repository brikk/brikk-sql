#!/usr/bin/env python3
"""Observe all bounded floating +/- result types and wide-integer rejection boundaries."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

FLOATS = ["Float32", "Float64"]
INTEGERS = [f"{sign}{bits}" for sign in ("Int", "UInt") for bits in (8,16,32,64)]
WIDE = [f"{sign}{bits}" for sign in ("Int", "UInt") for bits in (128,256)]


def matrix_input():
    batches, rejected = [], []
    def operation(form, left, right):
        x, y = f"CAST(7 AS {left})", f"CAST(2 AS {right})"
        return f"{form.split('-')[0]}({x},{y})" if form.endswith("fn") else f"({x}{'+' if form.startswith('plus') else '-'}{y})"
    for form in ("plus-op", "minus-op", "plus-fn", "minus-fn"):
        for left_null, right_null in ((False, False), (True, False), (False, True), (True, True)):
            def wrap(type, nullable):
                return f"Nullable({type})" if nullable else type
            id = form + f"-nullable-{int(left_null)}{int(right_null)}"
            pairs, expressions = [], []
            for left in FLOATS + INTEGERS:
                for right in FLOATS + INTEGERS:
                    if left not in FLOATS and right not in FLOATS:
                        continue
                    a, b = wrap(left, left_null), wrap(right, right_null)
                    pair = f"p{len(pairs):03}"
                    pairs.append({"id": pair, "left": a, "right": b})
                    expressions.append(f"toTypeName({operation(form,a,b)}) AS {pair}")
            batches.append({"id": id, "pairs": pairs, "sql": "SELECT " + ",".join(expressions) + " FORMAT JSONEachRow"})
            for floating in FLOATS:
                for wide in WIDE:
                    for left, right in ((floating, wide), (wide, floating)):
                        a, b = wrap(left, left_null), wrap(right, right_null)
                        rejected.append({"id": f"r{len(rejected):03}", "form": id, "left": a, "right": b,
                                         "sql": f"SELECT toTypeName({operation(form,a,b)})"})
    return {"batches": batches, "rejected": rejected}


def capture(engine, source, raw, output):
    version = query(engine, "SELECT version()")
    assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n"
    batches, rejected = [], []
    for batch in source["batches"]:
        observed = query(engine, batch["sql"])
        assert observed["returncode"] == 0, observed
        types = json.loads(observed["stdout"])
        assert set(types) == {p["id"] for p in batch["pairs"]}
        batches.append({"id": batch["id"], "raw": observed, "types": types})
    for row in source["rejected"]:
        observed = query(engine, row["sql"])
        assert observed["returncode"] == 43 and "ILLEGAL_TYPE_OF_ARGUMENT" in observed["stderr"] and not observed["stdout"], (row, observed)
        rejected.append({"id": row["id"], "raw": observed})
    (output / (engine["version"] + ".json")).write_text(json.dumps({"engine": engine, "version_query": version,
        "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": batches, "rejected": rejected}, indent=2) + "\n")
    print(engine["version"], sum(len(b["types"]) for b in batches), "native types;", len(rejected), "native rejection controls")


def main():
    import concurrent.futures
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(matrix_input(), indent=2) + "\n").encode()
    (args.output / "input.json").write_bytes(raw)
    engines = json.loads((DATA / "engines.json").read_text())["engines"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(capture, engine, json.loads(raw), raw, args.output) for engine in engines]
        for future in futures:
            future.result()


if __name__ == "__main__":
    main()
