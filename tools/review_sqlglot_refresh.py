#!/usr/bin/env python3
"""Produce exact ledger deltas; applying them requires explicit per-ID decisions.

The actual test artifacts are observations, never approvals. Unchanged failure
signatures keep their curated reasons. Only identical case/signature pairs may
be mechanically rekeyed after an upstream descriptor change.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "brikk-sql/testResources"
BASE = "e9475518"


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def actual_name(path):
    family, name = path.parts[-2:]
    if name.startswith("known-failures-"):
        return name.removeprefix("known-failures-").removesuffix(".json") + "-annotate-ledger-actual.json"
    if name == "known-failures.json":
        return {"parser-corpus": "parser-identity", "generator-corpus": "generator-identity",
                "scope-corpus": "scope", "qualify-corpus": "qualify", "lineage-corpus": "lineage"}[family] + "-ledger-actual.json"
    return name.removesuffix("-known-failures.json") + "-ledger-actual.json"


def changes(old, actual):
    old_rows = {row["id"]: row for row in old["cases"]}
    new_rows = {row["id"]: row for row in actual["cases"]}
    if len(old_rows) != len(old["cases"]) or len(new_rows) != len(actual["cases"]):
        raise ValueError("Duplicate assertion ID")
    unchanged, changed, rekeyed, added = [], [], [], []
    used = set()
    def label(row):
        return tuple((key, row.get(key)) for key in ("sql", "case", "key") if key in row)
    for key, row in new_rows.items():
        if key in old_rows:
            used.add(key)
            if row["signature"] == old_rows[key]["signature"]:
                unchanged.append(key)
            else:
                changed.append(dict(before=old_rows[key], after=row))
            continue
        prior = next((key for key, prior in old_rows.items() if key not in used and key not in new_rows and
                      prior["signature"] == row["signature"] and label(prior) == label(row)), None)
        if prior is not None:
            used.add(prior)
            rekeyed.append(dict(before=old_rows[prior], after=row))
        else:
            added.append(row)
    return dict(unchanged=unchanged, rekeyed=rekeyed, changed=changed, added=added,
                removed=[row for key, row in old_rows.items() if key not in used])


def review(actual_dir):
    result = {}
    for path in sorted(RESOURCES.rglob("*known-failures*.json")):
        relative = path.relative_to(ROOT).as_posix()
        baseline = json.loads(subprocess.check_output(["git", "show", f"{BASE}:{relative}"], cwd=ROOT))
        actual_path = actual_dir / actual_name(path)
        observed = json.loads(actual_path.read_text())
        result[relative] = dict(actual_file=actual_path.name, actual_sha256=fingerprint(observed),
                                before_sha256=fingerprint(baseline), **changes(baseline, observed))
    return dict(base_commit=subprocess.check_output(["git", "rev-parse", BASE], cwd=ROOT, text=True).strip(),
                target="v30.29.0", ledgers=result)


def required_decisions(document):
    return {f"{path}#{kind}#{row['id']}" for path, delta in document["ledgers"].items()
            for kind, rows in (("add", delta["added"]), ("change", [pair["after"] for pair in delta["changed"]]),
                               ("remove", delta["removed"])) for row in rows}


def validate_approval(document, approval):
    if approval["review_sha256"] != fingerprint(document):
        raise ValueError("Observations changed since review")
    if set(approval["decisions"]) != required_decisions(document):
        raise ValueError("Every added/changed/removed assertion needs an exact decision, with no extra approvals")
    for decision in approval["decisions"].values():
        if not decision.get("reason") or not decision.get("evidence"):
            raise ValueError("A decision needs its own rationale and evidence")


def apply(document, approval, actual_dir):
    validate_approval(document, approval)
    if review(actual_dir) != document:
        raise ValueError("Actual artifacts or baseline changed")
    outputs = {}
    for relative, delta in document["ledgers"].items():
        original = json.loads(subprocess.check_output(["git", "show", f"{BASE}:{relative}"], cwd=ROOT))
        prior = {row["id"]: row for row in original["cases"]}
        observed = json.loads((actual_dir / delta["actual_file"]).read_text())
        rekeyed = {pair["after"]["id"]: pair["before"] for pair in delta["rekeyed"]}
        changed = {pair["after"]["id"] for pair in delta["changed"]}
        added = {row["id"] for row in delta["added"]}
        rows = []
        for row in observed["cases"]:
            if row["id"] in changed or row["id"] in added:
                kind = "change" if row["id"] in changed else "add"
                decision = approval["decisions"][f"{relative}#{kind}#{row['id']}"]
                value = copy.deepcopy(row)
                value["reason"] = decision["reason"]
            else:
                value = copy.deepcopy(rekeyed.get(row["id"], prior.get(row["id"])))
                value["id"] = row["id"]
            rows.append(value)
        output = dict(original)
        if "sqlglot_version" in output:
            output["sqlglot_version"] = "v30.29.0"
        output["cases"] = rows
        outputs[relative] = output
    # No file is written until every observation and decision has been checked.
    for relative, output in outputs.items():
        path = ROOT / relative
        if json.loads(path.read_text()) != output:
            path.write_text(json.dumps(output, indent=4, ensure_ascii=False) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--actual-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", type=Path, help="explicit reviewed decision file, never the actual ledger itself")
    args = parser.parse_args()
    document = review(args.actual_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
    if args.apply:
        apply(document, json.loads(args.apply.read_text()), args.actual_dir)
    print(f"{len(document['ledgers'])} exact ledger comparisons; {len(required_decisions(document))} decisions required; review SHA-256 {fingerprint(document)}")


if __name__ == "__main__":
    main()
