#!/usr/bin/env python3
"""Replay every old/new native syntax input before recording a refreshed baseline.

Input SQL is only passed to the existing parse-only, locked-down child JVM.
No input statement is prepared, bound or executed. Syntax is not value proof.
"""
import argparse
import base64
import collections
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def validate(report):
    outcomes = report["outcomes"]
    summary = report["summary"]
    if len(outcomes) != summary["inputs"] or len({r["sql"] for r in outcomes}) != summary["unique_sql"]:
        raise ValueError("Incomplete native outcome inventory")
    if dict(collections.Counter(r["status"] for r in outcomes)) != summary["native_counts"]:
        raise ValueError("Wrong native counts")
    if any(r["status"] == "unavailable" for r in outcomes):
        raise ValueError("Unavailable transport is not a native verdict")
    fingerprint = sha("\n".join(f"{r['origin']}\t{sha(r['sql'].encode())}\t{r['status']}\t{r['type']}" for r in outcomes).encode())
    if fingerprint != summary["native_fingerprint"]:
        raise ValueError("Wrong native fingerprint")
    for field in ("local", "inventory"):
        if sha(compact(report[field]).encode()) != summary[field + "_fingerprint"]:
            raise ValueError(f"Wrong {field} fingerprint")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--jdbc", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    old, new = json.loads(args.before.read_text()), json.loads(args.after.read_text())
    validate(old)
    validate(new)
    if old["engine"] != new["engine"] or sha(args.jdbc.read_bytes()) != old["engine"]["jdbc_sha256"]:
        raise ValueError("Engine, settings or trusted JDBC bytes changed")
    before = {r["sql"]: (r["status"], r["type"]) for r in old["outcomes"]}
    after = {r["sql"]: (r["status"], r["type"]) for r in new["outcomes"]}
    if any(before[sql] != after[sql] for sql in before.keys() & after.keys()):
        raise ValueError("An unchanged SQL input has a changed native verdict")
    directory = Path(tempfile.mkdtemp(prefix="sg29-native-replay-", dir=args.scratch))
    sqls = list(dict.fromkeys([*before, *after]))
    source, result, log = [directory / name for name in ("inputs.tsv", "results.tsv", "process.log")]
    source.write_text("\n".join(f"{i}\t{base64.b64encode(sql.encode()).decode()}" for i, sql in enumerate(sqls)) + "\n")
    helper = ROOT / "brikk-sql-verify-duckdb/testResources/NativeParserProbe.java"
    with log.open("w") as stream:
        subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", str(args.jdbc),
                        str(helper), str(source), str(result)], cwd=ROOT / "brikk-sql-verify-duckdb", stdout=stream, stderr=subprocess.STDOUT,
                       timeout=120, check=True)
    lines = [line.split("\t") for line in result.read_text().splitlines()]
    decode = lambda value: base64.b64decode(value).decode()
    engine = [decode(value) for value in lines[0][1:]]
    settings = json.loads(decode(lines[1][1]))
    if engine != new["engine"]["engine"] or settings != new["engine"]["settings"]:
        raise ValueError("Replay engine/settings differ")
    if len(lines[2:]) != len(sqls):
        raise ValueError("Incomplete native replay")
    for index, (sql, row) in enumerate(zip(sqls, lines[2:])):
        observed = (row[1], decode(row[2]))
        if row[0] != str(index) or observed != before.get(sql, after.get(sql)) or observed != after.get(sql, before.get(sql)):
            raise ValueError(f"Native replay differs for exact input {index}")
    new_local_failures = [row for row in new["local"] if row["status"] == "local-failure"]
    if any(not row["detail"].startswith("ParseError:") for row in new_local_failures):
        raise ValueError("New unreviewed runtime failure, not an ordinary parser refusal")
    review = dict(before_summary=old["summary"], after_summary=new["summary"], engine=new["engine"],
                  before_report_sha256=sha(args.before.read_bytes()), after_report_sha256=sha(args.after.read_bytes()),
                  old_unique_sql_replayed=len(before), new_unique_sql_replayed=len(after), union_unique_sql_replayed=len(sqls),
                  shared_sql=len(before.keys() & after.keys()), changed_shared_verdicts=[],
                  added_sql=[dict(sql=sql, status=after[sql][0], type=after[sql][1]) for sql in sorted(after.keys() - before.keys())],
                  removed_fixture_sql_replayed=[dict(sql=sql, status=before[sql][0], type=before[sql][1]) for sql in sorted(before.keys() - after.keys())],
                  local_parser_refusals=new_local_failures,
                  inventory=[dict(file=row["file"], before=next((prior for prior in old["inventory"] if prior["file"] == row["file"]), None), after=row) for row in new["inventory"]],
                  replay_inputs_sha256=sha(source.read_bytes()), replay_results_sha256=sha(result.read_bytes()),
                  qualification="Native syntax only; explicit local refusals, no input execution/value/type-equivalence claim")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(review, indent=2, ensure_ascii=False) + "\n")
    if args.baseline:
        if args.baseline.exists() and json.loads(args.baseline.read_text()) != new["summary"]:
            raise ValueError("Do not overwrite a different historical baseline")
        args.baseline.write_text(json.dumps(new["summary"], indent=2) + "\n")
    print(f"Replayed all {len(sqls)} old/new unique SQL inputs; native verdicts/settings identical. {args.output}")


if __name__ == "__main__":
    main()
