#!/usr/bin/env python3
"""Fetch or verify the unmodified, pinned sources used by the regression harvest.

No downloads during tests. --fetch checks bytes against the committed manifest;
--record is only for an explicitly reviewed source refresh.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1] / "vendor/data/test-expansion"
PINS = {
    "polyglot": ("tobilg/polyglot", "a62f5b8f5c64f2d30e1072237f5586c98f799066", "v0.14.0"),
    "duckdb": ("duckdb/duckdb", "d54cbbe3df17e8e5ce68312f483b4155836112aa", "v2.0.0"),
    "clickhouse": ("ClickHouse/ClickHouse", "c6428ebcc39fdff48bb0f50e128b2078701901d5", "v26.10.2.103-stable"),
}
SOURCES = {
    "polyglot": ["LICENSE"] + ["crates/polyglot-sql/tests/" + name + ".rs" for name in (
        "numeric_exponent_boundaries", "quoted_relation_columns", "query_analysis_regression",
        "deep_nesting_regression", "unicode_token_ranges",
    )],
    "duckdb": ["LICENSE", "test/sql/json/scalar/test_json_arrow_expr.test",
               "test/sql/parser/nested_join_backtracking.test"],
    "clickhouse": ["LICENSE"] + ["tests/queries/0_stateless/" + name + extension for name in (
        "00157_aliases_and_lambda_formal_parameters", "01960_lambda_precedence",
        "01915_json_extract_raw_string",
    ) for extension in (".sql", ".reference")],
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--record", action="store_true", help="explicitly refresh source hashes")
    options = parser.parse_args()
    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    old_rows = {row["local_path"]: row for row in manifest.get("files", [])}
    rows = []
    for project, paths in SOURCES.items():
        repo, revision, tag = PINS[project]
        for path in paths:
            local_path = project + "/" + path
            url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
            destination = ROOT / local_path
            if options.fetch or options.record:
                with urlopen(url, timeout=60) as response:
                    data = response.read()
            else:
                data = destination.read_bytes()
            row = dict(project=project, repository=repo, revision=revision, tag=tag,
                       source_path=path, local_path=local_path, url=url,
                       sha256=hashlib.sha256(data).hexdigest())
            if not options.record and old_rows.get(local_path) != row:
                raise SystemExit(f"Source identity/hash mismatch: {local_path}")
            if options.fetch or options.record:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
            rows.append(row)
    if options.record:
        manifest_path.write_text(json.dumps({"format": 1, "files": rows}, indent=2) + "\n")
    elif set(old_rows) != {row["local_path"] for row in rows}:
        raise SystemExit("Unexpected files in source manifest")
    print(f"Verified {len(rows)} pinned upstream files")


if __name__ == "__main__":
    main()
