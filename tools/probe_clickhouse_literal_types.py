#!/usr/bin/env python3
"""Independent engine observations at every supported integer literal width boundary."""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, query

LITERALS = ["0", "255", "256", "65535", "65536", "4294967295", "4294967296", "18446744073709551615",
            "-1", "-128", "-129", "-32768", "-32769", "-2147483648", "-2147483649", "-9223372036854775808"]


def matrix_input():
    cases = [{"id": f"l{i:02}", "literal": literal} for i, literal in enumerate(LITERALS, 1)]
    return {"cases": cases, "sql": "SELECT " + ",".join(
        f"toTypeName(arrayMap(x -> x,[{c['literal']}])) AS {c['id']}" for c in cases) + " FORMAT JSONEachRow"}


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
        assert version["returncode"] == 0 and version["stdout"] == engine["version"] + "\n", version
        observed = query(engine, source["sql"])
        assert observed["returncode"] == 0, observed
        types = json.loads(observed["stdout"])
        assert set(types) == {c["id"] for c in source["cases"]}
        report = {"engine": engine, "version_query": version, "input_sha256": hashlib.sha256(raw).hexdigest(),
                  "query": observed, "types": types}
        (args.output / f"{engine['version']}.json").write_text(json.dumps(report, indent=2) + "\n")
        print(engine["version"], len(types), "native integer boundary observations")


if __name__ == "__main__":
    main()
