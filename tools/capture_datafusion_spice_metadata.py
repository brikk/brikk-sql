#!/usr/bin/env python3
"""Capture profile-specific catalogs. No production connection or model calls are used."""
import argparse
import json
from pathlib import Path

from probe_datafusion_spice import ROOT, run_reference, sha256, spice_runtime, spice_sql

QUERIES = {
    "show_functions": "SHOW FUNCTIONS",
    "routines": "SELECT * FROM information_schema.routines ORDER BY routine_name, function_type, data_type",
    "parameters": "SELECT * FROM information_schema.parameters ORDER BY specific_name, rid, ordinal_position, parameter_mode",
    "settings": "SELECT * FROM information_schema.df_settings ORDER BY name",
    "list_udfs": "SELECT * FROM list_udfs() ORDER BY name",
}
TYPE_CANDIDATES = ["BOOLEAN", "BOOL", "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT",
                   "INT2", "INT4", "INT8", "FLOAT", "REAL", "DOUBLE", "FLOAT4", "FLOAT8",
                   "VARCHAR", "TEXT", "STRING", "CHAR", "BYTEA", "BINARY", "DATE", "TIME",
                   "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "INTERVAL", "DECIMAL(18, 3)",
                   "NUMERIC(18, 3)", "INT[]", "STRUCT<x INT>", "MAP<VARCHAR, INT>"]


def sorted_rows(rows):
    return sorted(rows, key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))


def query_result(response):
    return {"status": response["status"], **(
        {"rows": sorted_rows(response["rows"])} if response["status"] == "accepted"
        else {"message": response.get("message")})}


def capture(args):
    request = {"id": "catalog", "sql": "", "operation": "catalog"}
    upstream = run_reference(args.upstream, [request], expected_profile="datafusion-55.2.0")["catalog"]
    for query in upstream["queries"].values():
        if query["status"] == "accepted":
            query["rows"] = sorted_rows(query["rows"])
    requests = [{"id": name, "sql": f"SELECT arrow_typeof(CAST(NULL AS {name})) AS native_type", "operation": "execute"}
                for name in TYPE_CANDIDATES]
    upstream["type_probes"] = run_reference(args.upstream, requests, expected_profile="datafusion-55.2.0")
    upstream["schema_version"] = 1
    upstream["binary_sha256"] = sha256(args.upstream)
    upstream["queries_sql"] = QUERIES
    upstream["availability"] = "Explicit DataFusion embedding; optional JSON/Spark packages and user functions absent"
    with spice_runtime(args.spiced) as (url, runtime):
        spice = {"schema_version": 1, "profile": "spice-2.4.0", "runtime": runtime,
                 "queries_sql": QUERIES, "queries": {name: query_result(spice_sql(url, sql)) for name, sql in QUERIES.items()},
                 "keywords": upstream["keywords"],
                 "keyword_source": "sqlparser 0.62.0 keyword inventory; reservation remains context-dependent and unknown",
                 "registry": None,
                 "availability": "v2.4.0+models binary; no configured models or user functions; registry presence does not imply configured model availability",
                 "signature_limit": "SQL exposes representative signatures only; aliases/canonical identity and full constraints are unknown without source evidence",
                 "type_probes": {name: query_result(spice_sql(url, request["sql"])) for name, request in zip(TYPE_CANDIDATES, requests)}}
    for name, version, result in [("datafusion", "55.2.0", upstream), ("spice", "2.4.0", spice)]:
        if result["queries"]["routines"]["status"] != "accepted":
            raise RuntimeError(f"Cannot capture {name} without its routine inventory")
        path = args.output_root / name / version / "metadata-capture.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        print(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--spiced", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, default=ROOT / "vendor/data")
    capture(parser.parse_args())
