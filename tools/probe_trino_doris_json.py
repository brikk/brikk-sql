#!/usr/bin/env python3
"""Capture exact scalar JSON behavior for Trino 484 and Doris 4.1.5.

Run only against disposable containers. SELECT wrappers hex-encode text so SQL
NULL, JSON null, whitespace, escapes, decimal scale and member order stay distinct.
No JSON canonicalization or numeric tolerance is applied to the observations.

Usage: python3 tools/probe_trino_doris_json.py --trino-container NAME
       --doris-container FE --backend-container BE [--output PATH]
"""
from __future__ import annotations

import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "vendor/data/trino-484-doris-4.1.5-json"


def command(engine: str, container: str, sql: str) -> list[str]:
    client = (["trino", "--output-format=TSV", "--execute"] if engine == "trino" else
              ["mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-N", "-B", "-e"])
    return ["docker", "exec", container, *client, sql]


def run(engine: str, container: str, sql: str) -> dict:
    result = subprocess.run(command(engine, container, sql), capture_output=True, text=True, timeout=90)
    if re.search(r"Error response from daemon|Can't connect to|Lost connection|Connection refused", result.stderr, re.I):
        raise RuntimeError(f"Probe infrastructure unavailable: {result.stderr}")
    return {"exitCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


def scalar_query(engine: str, expression: str) -> str:
    encoded = "TO_HEX(TO_UTF8(CAST(v AS VARCHAR)))" if engine == "trino" else "HEX(CAST(v AS STRING))"
    return f"SELECT CASE WHEN v IS NULL THEN 'SQL_NULL' ELSE CONCAT('VALUE:', {encoded}) END FROM (SELECT {expression} AS v) AS probe"


def observation(raw: dict) -> dict:
    if raw["exitCode"]:
        return {"status": "error"}
    value = raw["stdout"].removesuffix("\n").removesuffix("\r")
    if value == "SQL_NULL":
        return {"status": "sql-null"}
    if not value.startswith("VALUE:"):
        raise ValueError(f"Unexpected scalar transport: {raw!r}")
    return {"status": "value", "value": bytes.fromhex(value[6:]).decode("utf-8")}


def inspect(container: str) -> dict:
    info = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
    image = json.loads(subprocess.check_output(["docker", "image", "inspect", info["Image"]], text=True))[0]
    return {"requestedImage": info["Config"]["Image"], "imageId": info["Image"], "repoDigests": image["RepoDigests"]}


def capture(trino: str, doris: str, backend: str, vectors: list[dict]) -> dict:
    versions = {"trino": run("trino", trino, "SELECT version()"),
                "frontend": run("doris", doris, "SHOW FRONTENDS"),
                "backend": run("doris", doris, "SHOW BACKENDS")}
    assert versions["trino"]["exitCode"] == 0 and versions["trino"]["stdout"].strip() == "484", versions
    for part in ("frontend", "backend"):
        assert versions[part]["exitCode"] == 0 and re.search(r"doris-4\.1\.5(?:-|\s|$)", versions[part]["stdout"]), versions
    ready = run("doris", doris, "SELECT number FROM numbers('number'='1')")
    assert ready["exitCode"] == 0 and ready["stdout"].strip() == "0", ready
    rows = []
    for vector in vectors:
        row = {"id": vector["id"], "area": vector["area"], "concept": vector["concept"]}
        for engine, container in (("trino", trino), ("doris", doris)):
            sql = scalar_query(engine, vector[engine])
            raw = run(engine, container, sql)
            row[engine] = {"sql": sql, "raw": raw, "observation": observation(raw)}
        a, b = row["trino"]["observation"], row["doris"]["observation"]
        # Two errors do not establish equivalence.
        row["comparison"] = "both-error" if a["status"] == b["status"] == "error" else "same" if a == b else "different"
        rows.append(row)
        print(vector["id"], row["comparison"], a, b, flush=True)
    return {"capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "scope": "Exact text/error observations for these vectors only; not universal function equivalence.",
            "images": {"trino": inspect(trino), "frontend": inspect(doris), "backend": inspect(backend)},
            "versions": versions, "cases": rows}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("trino", "doris", "backend"):
        parser.add_argument(f"--{name}-container", required=True)
    parser.add_argument("--output", type=Path, default=DATA / "results.json")
    args = parser.parse_args()
    vectors = json.loads((DATA / "vectors.json").read_text())["cases"]
    result = capture(args.trino_container, args.doris_container, args.backend_container, vectors)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
