#!/usr/bin/env python3
"""Capture parser and original/regenerated execution evidence on disposable references.

Build the Rust references and run tools/dialect-audit first. --spiced must be the
checksum-verified v2.4.0 Linux x86_64 release binary. The tool starts its own runtime,
never connects to a user's server, and terminates it on success or failure.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "vendor/data/datafusion-spice/cases.json"
SPICE_SHA256 = "257b9fd8cf1f46c1f4ddf0b6280ba5b6033b79c276ec6840dd5e5d81710b1002"


def sha256(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def load_cases(path: Path) -> list[dict]:
    cases = json.loads(path.read_text())["cases"]
    ids = [row["id"] for row in cases]
    if not cases or len(ids) != len(set(ids)):
        raise ValueError("Fixture cases must have unique, nonempty IDs")
    for row in cases:
        if not row["id"] or not isinstance(row["sql"], str) or type(row["execute"]) is not bool:
            raise ValueError(f"Invalid fixture: {row!r}")
    return cases


def run_reference(binary: Path, requests: list[dict], expected_profile: str | None = None) -> dict[str, dict]:
    run = subprocess.run([str(binary.resolve())], input="".join(json.dumps(r) + "\n" for r in requests),
                         capture_output=True, text=True, timeout=180, check=True)
    rows = [json.loads(line) for line in run.stdout.splitlines()]
    if len(rows) != len(requests) or [r.get("id") for r in rows] != [r["id"] for r in requests]:
        raise RuntimeError("Reference returned missing, duplicated, or out-of-order responses")
    if any(r.get("protocol_version") != 1 or r.get("status") in {"request-error", "transport-error", "setup-error"} for r in rows):
        raise RuntimeError(f"Reference protocol/infrastructure failure: {rows}")
    if any(r.get("status") not in {"accepted", "rejected"} for r in rows):
        raise RuntimeError("Reference did not provide a native verdict")
    if expected_profile and any(r.get("profile", {}).get("id") != expected_profile for r in rows):
        raise RuntimeError(f"Reference does not implement expected profile {expected_profile}")
    return {r["id"]: r for r in rows}


def port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextlib.contextmanager
def spice_runtime(binary: Path):
    if sha256(binary) != SPICE_SHA256:
        raise ValueError("Spice binary does not match the pinned v2.4.0 release checksum")
    version = subprocess.check_output([str(binary.resolve()), "--version"], text=True).strip()
    if version != "v2.4.0+models":
        raise ValueError(f"Unexpected Spice version: {version}")
    temp_parent = "/tmp/opencode" if Path("/tmp/opencode").is_dir() else None
    with tempfile.TemporaryDirectory(prefix="brikk-spice-reference-", dir=temp_parent) as tmp:
        pod = Path(tmp) / "spicepod.yaml"
        shutil.copyfile(ROOT / "tools/datafusion-reference/spicepod.yaml", pod)
        http_port, flight_port = port(), port()
        while flight_port == http_port:
            flight_port = port()
        with (Path(tmp) / "runtime.log").open("w+") as log:
            process = subprocess.Popen([str(binary.resolve()), str(pod), "--http", f"127.0.0.1:{http_port}",
                                        "--flight", f"127.0.0.1:{flight_port}", "--cpu-cores", "2"],
                                       cwd=tmp, stdout=log, stderr=subprocess.STDOUT)
            url = f"http://127.0.0.1:{http_port}"
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        log.seek(0)
                        raise RuntimeError(f"Spice exited during startup:\n{log.read()}")
                    try:
                        with urllib.request.urlopen(url + "/v1/ready", timeout=1) as response:
                            if response.status == 200:
                                break
                    except (urllib.error.URLError, TimeoutError):
                        pass
                    time.sleep(0.2)
                else:
                    log.seek(0)
                    raise RuntimeError(f"Spice readiness timeout:\n{log.read()}")
                yield url, {"version": version, "binary_sha256": SPICE_SHA256,
                            "spicepod_sha256": sha256(pod), "cpu_cores": 2,
                            "transport": "isolated loopback HTTP /v1/sql; not Flight/JDBC qualification"}
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def spice_sql(url: str, sql: str) -> dict:
    request = urllib.request.Request(url + "/v1/sql", data=sql.encode(),
                                     headers={"Content-Type": "text/plain", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    # Network/startup failures propagate; they must never become SQL rejections.
    result = {"status": "accepted" if status == 200 else "rejected", "http_status": status,
              "evidence": "execution" if status == 200 else "runtime-error; parsing/planning/execution not inferred",
              "raw": raw}
    if status == 200:
        result["rows"] = json.loads(raw, parse_float=lambda value: {"decimal_lexeme": value})
    else:
        result["message"] = raw
    return result


def spice_execute(url: str, sql: str) -> dict:
    result = spice_sql(url, sql)
    rows = result.get("rows")
    if result["status"] == "accepted" and isinstance(rows, list) and rows and isinstance(rows[0], dict):
        columns = list(rows[0])
        quote = lambda name: '"' + name.replace('"', '""') + '"'
        projection = ", ".join(f"arrow_typeof({quote(name)}) AS {quote(name)}" for name in columns)
        schema = spice_sql(url, f"SELECT {projection} FROM ({sql.rstrip().removesuffix(';')}) AS probe LIMIT 1")
        result["type_observation"] = schema
    result["nullability"] = "not captured by HTTP; no inferred non-null guarantee"
    return result


def compare(original: dict, generated: dict | None) -> str:
    if generated is None:
        return "no-generated-sql"
    if original["status"] != "accepted":
        return "original-not-accepted"
    if generated["status"] != "accepted":
        return "generated-rejected"
    # Compare names/types/rows within one transport, never errors or codec differences.
    if "fields" in original:
        return "same-observation" if (original.get("fields"), original.get("rows")) == (generated.get("fields"), generated.get("rows")) else "different-observation"
    old_types = original.get("type_observation", {})
    new_types = generated.get("type_observation", {})
    if old_types.get("status") != "accepted" or new_types.get("status") != "accepted":
        return "incomplete-type-evidence"
    return "same-observation" if (original.get("rows"), old_types.get("rows")) == (generated.get("rows"), new_types.get("rows")) else "different-observation"


def capture(args) -> dict:
    cases = load_cases(args.cases)
    generated = {}
    if args.brikk:
        brikk = json.loads(args.brikk.read_text())
        generated = {row["id"]: row.get("generated") for row in brikk["cases"]}
        if set(generated) != {c["id"] for c in cases}:
            raise ValueError("Brikk observations do not cover exactly the supplied fixture IDs")
        by_id = {c["id"]: c["sql"] for c in cases}
        if any(row["sql"] != by_id[row["id"]] for row in brikk["cases"]):
            raise ValueError("Brikk observations have stale SQL")
    result = {"schema_version": 1, "fixtures_sha256": sha256(args.cases),
              "brikk_observations_sha256": sha256(args.brikk) if args.brikk else None, "profiles": {}}
    for name, binary, execution in [("datafusion-55.2.0", args.upstream, True), ("spice-2.4.0-parser", args.spice_parser, False)]:
        if not binary:
            continue
        requests = []
        for case in cases:
            for variant, sql in [("original", case["sql"]), ("generated", generated.get(case["id"]))]:
                if sql is None:
                    continue
                requests.append({"id": case["id"] + "/" + variant + "/parse", "sql": sql, "operation": "parse"})
                if execution and case["execute"]:
                    requests.append({"id": case["id"] + "/" + variant + "/execute", "sql": sql, "operation": "execute"})
        responses = run_reference(binary, requests, expected_profile=name)
        observations = []
        for case in cases:
            row = {"id": case["id"]}
            for variant in ["original", "generated"]:
                row[variant] = {op: responses[key] for op in ["parse", "execute"]
                                if (key := case["id"] + "/" + variant + "/" + op) in responses}
            if "execute" in row["original"]:
                row["comparison"] = compare(row["original"]["execute"], row["generated"].get("execute"))
            observations.append(row)
        result["profiles"][name] = {"binary_sha256": sha256(binary), "cases": observations}
    if args.spiced:
        with spice_runtime(args.spiced) as (url, runtime):
            health = spice_sql(url, "SELECT version() AS version")
            if health["status"] != "accepted":
                raise RuntimeError(f"Spice query readiness failed: {health}")
            observations = []
            for case in cases:
                if not case["execute"]:
                    continue
                original = spice_execute(url, case["sql"])
                new_sql = generated.get(case["id"])
                new = spice_execute(url, new_sql) if new_sql is not None else None
                observations.append({"id": case["id"], "original": original, "generated": new,
                                     "comparison": compare(original, new)})
            settings = spice_sql(url, "SELECT * FROM information_schema.df_settings WHERE name LIKE 'datafusion.sql_parser.%' ORDER BY name")
            namespaces = spice_sql(url, "SELECT table_catalog, table_schema, table_name FROM information_schema.tables WHERE table_schema <> 'information_schema' ORDER BY table_catalog, table_schema, table_name")
            discovery = {name: spice_sql(url, sql) for name, sql in {
                "routines": "SELECT routine_name, function_type, data_type FROM information_schema.routines WHERE routine_name IN ('vector_search', 'text_search', 'rrf', 'ai', 'embed', 'truncate') ORDER BY routine_name, function_type, data_type",
                "parameters": "SELECT * FROM information_schema.parameters WHERE specific_name = 'truncate' ORDER BY ordinal_position, parameter_mode",
                "show_functions": "SHOW FUNCTIONS LIKE 'vector_search'",
                "list_udfs": "SELECT * FROM list_udfs() WHERE name IN ('vector_search', 'text_search', 'rrf', 'ai', 'embed', 'truncate') ORDER BY name",
            }.items()}
            result["profiles"]["spice-2.4.0-runtime"] = {"runtime": runtime, "version_query": health,
                "parser_settings": settings, "namespace_inventory": namespaces, "discovery_smoke": discovery,
                "cases": observations}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=FIXTURES)
    parser.add_argument("--brikk", type=Path)
    parser.add_argument("--upstream", type=Path)
    parser.add_argument("--spice-parser", type=Path)
    parser.add_argument("--spiced", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not any([args.upstream, args.spice_parser, args.spiced]):
        parser.error("At least one reference binary is required")
    result = capture(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print("Captured " + ", ".join(result["profiles"]) + f" to {args.output}")


if __name__ == "__main__":
    main()
