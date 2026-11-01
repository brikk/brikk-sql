#!/usr/bin/env python3
"""Observe bounded Decimal division types independently of Kotlin's precision/scale formula."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

DECIMALS = [("Decimal(5,2)", 2), ("Decimal(12,3)", 3), ("Decimal(28,5)", 5), ("Decimal(65,8)", 8),
            ("Decimal32(0)", 0), ("Decimal64(1)", 1), ("Decimal128(4)", 4), ("Decimal256(6)", 6)]
INTEGERS = [f"{sign}{bits}" for sign in ("Int", "UInt") for bits in (8,16,32,64,128,256)]


def matrix_input():
    types = DECIMALS + [(t, None) for t in INTEGERS]
    batches = []
    for form, nullable in [("slash-on", False), ("slash-off", False), ("divide-on", False), ("exact", False),
                           ("slash-on", True), ("exact", True)]:
        pairs, expressions = [], []
        for left, a_scale in types:
            for right, b_scale in types:
                if a_scale is None and b_scale is None:
                    continue
                if a_scale is not None and b_scale is not None and b_scale > a_scale:
                    continue  # Unproved dry-run dispatch is tested separately, never admitted as a type proof.
                a = f"Nullable({left})" if nullable else left
                b = f"Nullable({right})" if nullable else right
                x, y = f"CAST(7 AS {a})", f"CAST(2 AS {b})"
                operation = f"divideExact({x},{y})" if form == "exact" else f"divide({x},{y})" if form == "divide-on" else f"({x}/{y})"
                id = f"p{len(pairs):03}"
                pairs.append({"id": id, "left": a, "right": b})
                expressions.append(f"toTypeName({operation}) AS {id}")
        setting = "" if form == "exact" else " SETTINGS sql_compatible_division_for_integers=" + ("0" if form == "slash-off" else "1")
        batches.append({"id": form + ("-nullable" if nullable else ""), "pairs": pairs,
                        "sql": "SELECT " + ",".join(expressions) + setting + " FORMAT JSONEachRow"})
    return {"batches": batches}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(matrix_input(), indent=2) + "\n").encode()
    (args.output / "input.json").write_bytes(raw)
    engine = next(e for e in json.loads((DATA / "engines.json").read_text())["engines"] if e["version"] == "26.10.2.103")
    version = query(engine, "SELECT version()")
    assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n"
    batches = []
    for batch in json.loads(raw)["batches"]:
        observed = query(engine, batch["sql"])
        assert observed["returncode"] == 0, observed
        types = json.loads(observed["stdout"])
        assert set(types) == {p["id"] for p in batch["pairs"]}
        batches.append({"id": batch["id"], "raw": observed, "types": types})
        print(batch["id"], len(types), "observed types")
    (args.output / "26.10.2.103.json").write_text(json.dumps({"engine": engine, "version_query": version,
        "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": batches}, indent=2) + "\n")


if __name__ == "__main__":
    main()
