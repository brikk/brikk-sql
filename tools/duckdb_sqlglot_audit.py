#!/usr/bin/env python3
"""Read-only, isolated reconciliation of the old parity pin and reviewed upstream DuckDB code."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

PIN = "a00dc2e5c489214bea53bacbbeba8713f21ebce1"
UPSTREAM = "6de2df31b0ae8b21155828f575a14dc8903c1b9b"
FILES = ["sqlglot/dialects/duckdb.py", "sqlglot/parsers/duckdb.py",
         "sqlglot/generators/duckdb.py", "tests/dialects/test_duckdb.py"]
VECTORS = [
    ("sort-by-name", "2.0", "INSERT INTO s.sort BY NAME SELECT 1 AS n"),
    ("regex-v2", "2.0", "SELECT 'abc' ~ 'b'"),
    ("regex-legacy", "1.5", "SELECT 'abc' ~ 'b'"),
    ("nearest", "2.0", "SELECT * FROM q JOIN p NEAREST 2 BY DISTANCE abs(q.x-p.x)"),
    ("connect-options", "2.0", "CONNECT 'quack:localhost' (TOKEN 'synthetic')"),
    ("disconnect", "2.0", "DISCONNECT"),
    ("trigger", "2.0", "CREATE TRIGGER tr AFTER INSERT ON t INSERT INTO log VALUES(1)"),
    ("quoted-variable", "2.0", 'SELECT $"Mixed"'),
    ("secure-view", "2.0", "CREATE SECURE VIEW v AS SELECT 1"),
    ("using-key", "2.0", "WITH RECURSIVE t(x) USING KEY(x) AS (SELECT 1 UNION SELECT x+1 FROM t WHERE x<2) SELECT * FROM t"),
    ("variant", "2.0", "SELECT CAST(1 AS VARIANT)"),
    ("json-mutation", "2.0", "SELECT json_set('{}','$.x','2')"),
    ("json-multi-path", "2.0", "SELECT json_extract('{}','$.x','$.y')"),
    ("factorial", "2.0", "SELECT 3!+1"),
    ("binary", "2.0", "SELECT X'00AA',B'0010'"),
    ("is-precedence", "2.0", "SELECT 1 IS NULL = false"),
    ("list-sort", "2.0", "SELECT list_sort([2,NULL,1],'DESC','NULLS FIRST')"),
    ("fractional-interval", "2.0", "SELECT INTERVAL '1.5 days'"),
]
WORKER = r'''
import json,sys
sys.path.insert(0,sys.argv[1])
import sqlglot
from sqlglot import Dialect
rows=[]
for name,version,sql in json.load(sys.stdin):
    d=Dialect.get_or_raise("duckdb, version="+version)
    row={"id":name,"version":version,"sql":sql}
    try:
        tree=sqlglot.parse_one(sql,read=d)
        row.update(status="parsed",root=tree.key,opaque=tree.key=="command")
        row["projection"]=tree.expressions[0].key if tree.expressions else None
        row["generated"]=tree.sql(dialect=d)
    except Exception as e:
        row.update(status="error",error=type(e).__name__,message=str(e).splitlines()[0])
    rows.append(row)
print(json.dumps(rows,sort_keys=True))
'''


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def inspect(path, expected):
    path = Path(path).resolve()
    sha = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if sha != expected:
        raise ValueError(f"Expected {expected}, got {sha}")
    if subprocess.check_output(["git", "-C", str(path), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise ValueError("Reference source has tracked changes")
    result = subprocess.run([sys.executable, "-c", WORKER, str(path)], input=json.dumps(VECTORS),
                            capture_output=True, text=True, timeout=60, check=True)
    rows = json.loads(result.stdout)
    assert [r["id"] for r in rows] == [v[0] for v in VECTORS]
    return {"commit": sha, "files": {f: hashlib.sha256((path / f).read_bytes()).hexdigest() for f in FILES},
            "outcome_sha256": fingerprint(rows), "outcomes": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pinned", required=True)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--check", type=Path)
    args = parser.parse_args()
    report = {"pinned": inspect(args.pinned, PIN), "upstream": inspect(args.upstream, UPSTREAM)}
    report["history"] = subprocess.check_output(["git", "-C", args.upstream, "log", "--format=%H %s",
                                                 f"{PIN}..{UPSTREAM}", "--", *FILES], text=True).splitlines()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    summary = {name: {key: value for key, value in report[name].items() if key != "outcomes"}
               for name in ("pinned", "upstream")}
    summary["history_sha256"] = fingerprint(report["history"])
    if args.check:
        expected = json.loads(args.check.read_text())
        assert expected["source_summary"] == summary, "Review upstream/source/outcome changes; do not refresh the parity corpus"
        assert set(expected["review"]) == {line[:8] for line in report["history"]}, "Every touching commit needs an explicit reviewed disposition"
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
