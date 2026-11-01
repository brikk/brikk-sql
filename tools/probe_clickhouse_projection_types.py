#!/usr/bin/env python3
"""Independently observe scalar projection types, including explicit native nullability."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query
from probe_clickhouse_literal_types import LITERALS

SCALARS = LITERALS + ["+0", "-0", "00", "0.0", "-0.0", ".5", "-.5", "1.", "1e0", "1E+3", "-1e-3", "1e-400",
    "1.7976931348623157e308", "-1.7976931348623157e308", "18446744073709551616", "-9223372036854775809",
    "1" + "0" * 100, "''", "'hello'", "'a''b'", "'😀'", "'NULL'", "'\\0'", "TRUE", "FALSE", "NULL"]


def matrix_input():
    batches = []
    for form in ("aliased", "parenthesized"):
        cases, selects = [], []
        for i, literal in enumerate(SCALARS):
            id = f"p{i:03}"
            expression = f"(({literal}))" if form == "parenthesized" else literal
            cases.append({"id": id, "literal": literal})
            selects.extend([f"{expression} AS {id}", f"toTypeName({id}) AS t{i:03}"])
        batches.append({"id": form, "cases": cases, "sql": "SELECT " + ",".join(selects) + " FORMAT JSONEachRow"})
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
            values = json.loads(observed["stdout"])
            types = {case["id"]: values["t" + case["id"][1:]] for case in batch["cases"]}
            batches.append({"id": batch["id"], "raw": observed, "types": types})
            print(engine["version"], batch["id"], len(types), "native projection types")
        (args.output / (engine["version"] + ".json")).write_text(json.dumps({"engine": engine,
            "version_query": version, "input_sha256": hashlib.sha256(raw).hexdigest(), "batches": batches}, indent=2) + "\n")


if __name__ == "__main__":
    main()
