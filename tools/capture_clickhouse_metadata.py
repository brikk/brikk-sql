#!/usr/bin/env python3
"""Capture read-only metadata from the immutable ClickHouse audit engines. No live credentials.

Uses the same network-disabled, deadline-bounded Docker local runner as the SQL audit.
Settings captures are deliberately limited to catch-up-relevant query settings. Documentation
is evidence only: its prose signatures are never converted into invented overloads.
"""
import argparse
import hashlib
import json
import pathlib

from probe_clickhouse_2026 import DATA, ROOT, query

SETTINGS = (
    "enable_streaming_queries", "enable_nullable_tuple_type", "allow_nullable_tuple_in_extracted_subcolumns",
    "allow_experimental_lateral_join", "sql_compatible_division_for_integers", "enable_trino_dialect",
    "enable_json_ast_dialect", "allow_experimental_eval_table_function", "enable_ipcrypt", "enable_xgboost",
    "enable_elasticsearch", "enable_analyzer", "allow_experimental_analyzer", "allow_experimental_window_view",
    "compatibility", "session_timezone", "timezone", "join_default_strictness", "group_by_use_nulls",
    "validate_group_by_all_key_types", "max_bytes_before_external_distinct", "max_bytes_ratio_before_external_distinct",
    "max_bytes_before_external_set", "run_query_in_background", "query_cache_for_subqueries",
    "ai_function_text_default_credentials", "ai_function_embedding_default_credentials",
    "enable_logsql_dialect", "enable_pco_codec", "enable_alp_codec", "allow_experimental_codecs",
)
DOC_NAMES = (
    "LIMIT", "FROM", "JOIN", "CREATE TOKEN", "CREATE HANDLER", "QBit", "MultiPoint", "Tuple",
    "year", "arrayTopK", "arrayBottomK", "MVTEncode", "groupFormat", "randomHadamardTransform",
    "xxHash64Spark", "dictGetRoot", "gini", "mergedJSONPatch", "notHas", "parseQueryToJSON",
    "formatQueryFromJSON", "arrayFlattenedLength", "parseISO8601Duration", "hierarchicalKMeans",
    "assignCentroid", "arrayMinIndex", "arrayMaxIndex", "arrayRemoveAt", "mapRemove", "mapEntries",
    "mapContainsKeyValue", "obfuscate", "elasticsearch", "predictXGBoost", "normalizedQueryHashUnordered",
)


def quoted(names):
    return ",".join("'" + n + "'" for n in names)


def capture(engine, output):
    version = engine["version"]
    verified = query(engine, "SELECT version()")
    if verified["returncode"] != 0 or verified["stdout"].strip() != version:
        raise RuntimeError(f"Version mismatch: {verified}")
    queries = {
        "functions": "SELECT name, is_aggregate, case_insensitive, alias_to, introduced_in, deterministic, higher_order FROM system.functions ORDER BY name FORMAT TSVWithNames",
        "table-functions": "SELECT name, allow_readonly, description FROM system.table_functions ORDER BY name FORMAT TSVWithNames",
        "keywords": "SELECT keyword AS word, 'UNKNOWN' AS category, '' AS token FROM system.keywords ORDER BY keyword FORMAT TSVWithNames",
        "types": "SELECT name, case_insensitive, alias_to, description, syntax, introduced_in FROM system.data_type_families ORDER BY name FORMAT TSVWithNames",
        "settings": "SELECT name, type, default, alias_for, is_obsolete, tier, description FROM system.settings WHERE name IN (" + quoted(SETTINGS) + ") ORDER BY name FORMAT TSVWithNames",
        "documentation": "SELECT name, toString(type) AS type, source, description FROM system.documentation WHERE name IN (" + quoted(DOC_NAMES) + ") ORDER BY type, name FORMAT JSONEachRow",
        "statements": "SELECT name, syntax, description, parent_name, related FROM system.statements ORDER BY name FORMAT JSONEachRow",
        "build-options": "SELECT name, value FROM system.build_options ORDER BY name FORMAT TSVWithNames",
    }
    manifest = {"engine": engine, "version_query": verified, "captures": {}}
    for kind, sql in queries.items():
        result = query(engine, sql)
        if result["returncode"] != 0:
            if kind not in ("statements",):
                raise RuntimeError(f"{version}/{kind}: {result}")
            manifest["captures"][kind] = {"query": sql, "unavailable": result}
            continue
        suffix = "jsonl" if kind in ("documentation", "statements") else "tsv"
        name = f"clickhouse-{kind}-{version}.{suffix}"
        data = result["stdout"].encode()
        (output / name).write_bytes(data)
        manifest["captures"][kind] = {"query": sql, "file": name, "sha256": hashlib.sha256(data).hexdigest()}
    (output / f"clickhouse-capture-{version}.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(version, "captured", len(manifest["captures"]), "registries/evidence tables")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "vendor/data")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for engine in json.loads((DATA / "engines.json").read_text())["engines"]:
        capture(engine, args.output)


if __name__ == "__main__":
    main()
