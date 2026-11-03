#!/usr/bin/env python3
"""Preserve the exploratory documentation audit without treating it as a release oracle.

Input: results.json from the 2026-10-29 exploratory audit. The immutable historical
observations are supplementary; current reproducible inputs live in datafusion-spice/cases.json.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def archive(source: Path) -> dict:
    raw = source.read_bytes()
    rows = json.loads(raw)
    result = []
    seen = set()
    for row in rows:
        key = row["page"] + "\0" + row["sql"]
        id = "doc-" + hashlib.sha256(key.encode()).hexdigest()[:20]
        if id in seen:
            raise ValueError(f"Duplicate source SQL: {id}")
        seen.add(id)
        result.append({"id": id, "source_id": row["id"], "source_page": row["page"],
                       "sql": row["sql"], "status": row["status"],
                       "output": re.sub(r"\x1b\[[;\d]*m", "", row["output"]),
                       "nodes": row["nodes"].split(",") if row["nodes"] else []})
    return {"schema_version": 1, "observed_on": "2026-10-29",
            "source_sha256": hashlib.sha256(raw).hexdigest(),
            "evidence": "Historical local datafusion parser/generator observations against unversioned Spice docs; not native runtime evidence",
            "documentation_root": "https://spiceai.org/docs/reference/sql/",
            "cases": result}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(archive(args.source), indent=2, ensure_ascii=False) + "\n")
