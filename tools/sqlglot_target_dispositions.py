#!/usr/bin/env python3
"""Check the complete, explicitly reviewed a00dc2e..v30.29.0 commit inventory.

No heuristic classifies a new commit. Every short SHA below belongs to one
review group; additions, omissions and overlapping groups fail closed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "a00dc2e5c489214bea53bacbbeba8713f21ebce1"
TARGET = "2fe25297af5b0f44f5c9e5d03e1b296424b7acbe"
OUTPUT = ROOT / "vendor/data/sqlglot-sg29-019/dispositions.json"
GROUPS = {}


def group(name, commits, disposition, reason, evidence, owner="Brikk SQL maintainers"):
    GROUPS[name] = dict(commits=commits.split(), disposition=disposition,
                        reason=reason, evidence=evidence, owner=owner)


for item, commits in {
    "002": "d6e78751",
    "003": "8a524dfa",
    "004": "c39005c3 093f2ec7 7696edf9",
    "005": "479c4cae",
    "006": "86abcde3 a4e08982",
    "007": "fdd0e9a4 8955d752 b1fd4e9e",
    "008": "f814e4cb",
    "009": "3cca3ea4 2d4b5a5b",
    "010": "4d7d7ca9 7ee62016 036c6107",
    "011": "1e5c649c eb03a972 78b24a74 5739cd6c 53ccce9f 8709510f 991d6ce5",
    "012": "90659e63 dca9a621",
    "013": "f3a6eb95 a0cc28b9",
    "014": "3ba19eed 6202d947 59a96d86 fbda0b91 f321207c cd41eeef af2b5773 954cca12 df5b53f9 ce7f6e39 caa920da fc1472a0 40dcaabd ec24376a 1852fd31 b06ed8ea 41a962d5 55277446 115ce02b",
    "015": "0b001782 03744a2a ab043477 2748f82e",
    "016": "d17c7680 f536a823 a8a7610d",
    "017": "f0b4347b a32ef636 fb36fb71",
}.items():
    evidence = ("vendor/data/sqlglot-30.29-batch-a-README.md" if int(item) <= 4 else
                "vendor/data/sqlglot-30.29-batch-b/README.md" if int(item) <= 7 else
                "vendor/data/sqlglot-sg29-014-rest/README.md" if item == "014" else
                f"vendor/data/sqlglot-sg29-{item}/README.md")
    group("SG29-" + item, commits, "ported/tested",
          "Bounded port and follow-ups reviewed in the named round; its native limits and refusals remain, not general engine equivalence.", evidence)

group("analysis-policy", "021991fb cb57d802 55bff612 5e0f7169 4678f6cb 2bf94d96 9d2966ed e54ad9aa",
      "intentionally divergent", "Retain Brikk inference-policy overloads, ordered recursive set reconciliation, identity caches and published uncache API instead of a wholesale upstream cache/qualification refactor.",
      "vendor/data/sqlglot-sg29-017/README.md")
group("sort-compatibility", "094509aa", "intentionally divergent",
      "Keep both published SortArray and ArraySort classes, constructors and serde identities. No implicit breaking node migration.",
      "vendor/data/sqlglot-sg29-018/README.md")
group("duckdb-native-policy", "df0a3006 530eab07 1f8d15bb 623dc888 e92c2228 68cd4a2d d335f898 83479d06 65eb0f22 a220ffb9 a97d0357 dd71d360 e0ac5e4e 4aa189a4 e3b3e23c 46af6e46 e88e67e6 5859d714 ebd5fb4d 8cf42771 31918e9a c514be04 840347fd f31f109d 5b7af1e7 549d64b9",
      "intentionally divergent", "Reuse the separately qualified DuckDB 2.0 native models and DDB20-055 refusal matrix. Do not import generic rewrites over session-sensitive/native execution contracts.",
      "vendor/data/sqlglot-sg29-018/README.md")
group("query-plan-optimizer", "e237d598 00fce66d 97134ffd 30ceae53 b994fa75 f872c442 37f09630 4d7421ee 0e7b2da5 bc7a5c46 114999ae 680873ae 20f01c7f da00199f d17073f2 2933aeac 518a36a5 c8919668 1cbcaf65 03ecb3ed",
      "deferred", "Query-plan rewrites, decorrelation, join elimination and constant folding are explicitly outside this parser/transpiler/analysis catch-up. No optimizer behavior is claimed ported.",
      "dev-docs/TODO-sqlglot-30.29.md", "Jayson Minard, separately approved optimizer work")
group("unsupported-profiles", "ffb9721d 98bedf64 7d9a6cd5 fb9b151a e38929ed ab0a291f 61c3ca44 08f2405c 5b949bd6 b679d0cf 2c7b9280 e71e4ee1 513beedd f8c314c4 9e57e448 2d3d3ff7 a2a8bb42 092e29a8 2b57c00a 83c09cdf 13528f03 8fb04fb3 184557f7 908809d3 634edd93 c5fe5404 a20a4d9e 41287a2d 13a570c6 a07698a7 e2a8b658 7c9dbb91 ffced075 70c2a840 52763cdd f70f4e5b 6de2df31 0852195e 49a6467d ec500798 59db279d 007fe450 4912880c",
      "intentionally divergent", "The changed Oracle/Athena/Teradata/Databricks/Fabric/SQLite/Snowflake/TSQL profile is not a supported Brikk dialect. Shared generated node definitions may be refreshed without adding that dialect or claiming execution.",
      "brikk-sql/testResources/corpus-policy.json")
group("python-runtime-and-diff", "579789dc c388227a 911ee761 c90a322c 7eff3a2f a673630a 897e1dd1 d18c57b8 ead649b9 902739a3 94afaf0b 2ef935d6 6f90b498 cff07856",
      "intentionally divergent", "Python packaging, compiled bindings, workflows and the Python AST edit-script diff implementation have no corresponding JVM runtime subsystem in this catch-up.",
      "dev-docs/TODO-sqlglot-30.29.md")
group("integration-test-revisions", "189fe3f4 b9c5f8d4 5f12cae5 7f415fbc 360b315c 7e627d56 29e07b5d e7aa0af6 a798b220 bd06614d 27a146b7 603dd92c d155947f 23446622 571d5122 c922dbe9 f3f2201f fe2d1485 feb0e08a f1fa6bf6 aa7d8e39 4ef575e2 ad8d374b f266a8ce a1ec85c6 e2585b03 f41f4f8b b8a1ce6f 2fe25297",
      "deferred", "These commits only advance upstream's external integration-test submodule. The frozen local fixtures are refreshed in SG29-020; unrun external engine suites remain explicitly unclaimed.",
      "dev-docs/TODO-sqlglot-30.29.md", "Jayson Minard, external oracle qualification")
group("release-changelogs", "f8287a09 e0ccb319 c034cbf2 9ae1a6a7 5c101a25",
      "intentionally divergent", "Release-note-only upstream commits do not require a Brikk behavioral port or change Brikk publication versions.",
      "dev-docs/TODO-sqlglot-30.29.md")
group("shared-escaping", "d3758367", "deferred",
      "Shared property keys, single-string intervals and Presto WEEK rewrites interpolate raw text. Review and cover quoting before coherent refresh; native acceptance is a separate claim.",
      "brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt", "Jayson Minard, SG29-020")
group("nested-explode", "9beb4ab2", "deferred",
      "explodeProjectionToUnnest searches through nested queries. Restrict every explode/zip discovery to its own scope and retain outer-map/zip semantics during SG29-020.",
      "brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt", "Jayson Minard, SG29-020")
group("copy-progress", "bc1cd99a", "deferred",
      "Initial COPY probes did not reproduce a hang. Kotlin parseVar(anyToken=true) consumes punctuation too. Pin finite malformed-input behavior and add a null/progress guard rather than claim a reproduced infinite loop.",
      "brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt", "Jayson Minard, SG29-020")
group("bounded-integer-classification", "9619dd18 d06bde08", "already equivalent",
      "Expression.isInt uses toLongOrNull, not Python int(float(...)); large exponents do not materialize enormous integers or throw ValueError. Keep the bounded classification policy, not Python's floating-point definition of integer.",
      "brikk-sql/src/dev.brikk.house.sql/ast/Members.kt")
group("replace-tables-api", "c241c2ff c9e673a2", "intentionally divergent",
      "No replace_tables-equivalent public helper exists. Structural Expression.transform/replace is not that name-based API; do not add a new API merely to port its upstream fix.",
      "brikk-sql/src/dev.brikk.house.sql/ast/Expression.kt")
group("generated-case-sensitivity", "cf39265a 282f7b29", "deferred",
      "The AST generator already emits _hash_raw_args but the frozen tables predate the new per-node flags. Refresh generated flags and hand-owned case/serde controls coherently, preserving structural rather than hash-collision equality.",
      "tools/gen_ast_nodes.py", "Jayson Minard, SG29-020")
group("ref-type", "98782638", "deferred",
      "REF requires a token and DType plus parsing of a named table target, not just enum regeneration. Preserve REF function/identifier parsing and do not claim native-engine support from generic parsing.",
      "tools/gen_ast_nodes.py", "Jayson Minard, SG29-020")
for commit, reason in {
    "91143554": "Unnamed MySQL constraints are a syntax feature; retain whole-command fallback for unsupported definitions instead of promising native DDL execution.",
    "676ba365": "Named FOREIGN KEY index syntax needs a separate structured/native DDL contract, not a name-only parser alias.",
    "c0a94574": "Tests follow the named FOREIGN KEY index feature; no independent runtime change.",
    "b1b244b1": "SPLIT_TO_TABLE column qualification belongs to an unsupported Snowflake TVF; do not infer arbitrary unknown TVF columns.",
    "fdba492c": "Alias collision in merge_subqueries is a query-plan transformation outside this catch-up.",
    "d747f5b2": "Python's huge-int materialization is not Kotlin's bounded Long conversion. Pin large interval rendering and failure behavior during integration without introducing an arbitrary-precision simplifier.",
    "ca870e84": "Unknown property classes currently emit a null name after warning; omit the unknown property instead, with an explicit diagnostic regression in SG29-020.",
    "b50e43e4": "PASTE JOIN needs a new token and supported grammar. Keep native execution unqualified; target token tables alone must not imply a new join contract.",
    "2444b6db": "The associated PASTE JOIN optimizer scope change does not independently qualify PASTE JOIN execution.",
    "7af2aa8d": "Pivoted CTE alias registration must be compared with Brikk's existing PIVOT scope model, not replace it with the upstream scope refactor.",
    "e3d0dfb2": "ORDER alias expansion must not share AST children. Compare the existing copy policy and target alias corpus before changing analysis order.",
    "d44db25a": "PARTITION BY EXCLUDING is a new generic AST/syntax feature. Refresh its schema but do not claim native engine support from generic rendering.",
    "5d588c0f": "Unsupported struct-star operands must remain unresolved, not fabricate complete schemas. Retain conservative Engine policy and review exact target fixture deltas.",
    "67961afb": "JSON-path alias matching must preserve unknown paths and not choose a false alias. Compare existing structural equality and target corpus evidence.",
    "e6c7d527": "INSERT target aliases must not consume the target column list. Inspect structured Schema ownership and add target regression during SG29-020.",
    "9fedfa5f": "Comment propagation follows the INSERT alias/column-list parser fix; retain comments without mutating caller-owned nodes.",
    "3377b957": "Parser cleanup is part of that same INSERT alias ownership fix, not a separate feature.",
    "43fbff20": "Positional alias type inference is compared with Brikk's existing recursive set/CTE output reconciliation; keep duplicate names and explicit unknowns conservative.",
    "7c4e124f": "Caching positional column alias mappings is not an API or correctness requirement. Keep existing identity-cache invalidation contracts.",
    "069a7517": "ClickHouse NATURAL JOIN already has independent native parsing/typing safeguards; target generic output is not authority to weaken them.",
    "76001235": "Snowflake splitter and BigQuery pivot changes include schema-less TVF inference. Keep the explicit Brikk inference-policy divergence and bounded pivot result contracts.",
    "5f3b52da": "Cleanup to unknown-source qualification follows the same splitter/pivot work; do not back-project guessed schemas.",
    "a9f1309c": "UNION BY NAME internal renaming is a query-plan canonicalization pass, not the native set-operation output reconciler.",
    "c068aefd": "replaceLambda must give each synthesized Cast its own DataType child; add a parent-ownership regression before target refresh.",
    "8c5600ad": "ClickHouse Contains under unary minus requires grouping. Review the generic parenthesization path and target fixture before claiming equivalence.",
    "bffbfbe4": "This only refines the corresponding ClickHouse test; no independent production change.",
    "33c72094": "SEMI/ANTI RHS tables must remain invisible to outer column resolution. Existing Engine/shape controls are retained; compare the new target scope fixture exactly.",
    "a357b7d9": "PostgreSQL TABLESPACE is new DDL syntax. Metadata preservation is not an authorization to execute native CREATE statements.",
    "9943dc46": "Resolver semi/anti unknown-source handling must preserve Brikk's explicit unknown-schema conservatism and inference overloads.",
    "b0842a1e": "PER aggregate syntax is a new generic node, not a supported-engine execution contract; refresh metadata only unless independently qualified.",
    "04f4a440": "IN as a contextual identifier needs grammar controls so the operator keeps its meaning. Review target cases during SG29-020.",
    "06aa274a": "Warning expectations are test harness data and are incorporated through the coherent corpus extraction, not a global warning suppression.",
    "90759771": "Unsupported-property warning expectations remain case-specific corpus data; retain diagnostics for unknown properties.",
    "7e4d995f": "ClickHouse parenthesized CTAS with COMMENT must not gain another wrapper on each generation. Review idempotence without executing DDL.",
}.items():
    group("review-" + commit, commit, "deferred", reason,
          "dev-docs/TODO-sqlglot-30.29.md", "Jayson Minard, SG29-020 case-level qualification")
group("semi-using-left-value", "09481cec", "already equivalent",
      "Brikk's USING expansion records a nonempty left source and selects lhs directly for SEMI/ANTI joins. It never constructs upstream's empty COALESCE; retain the richer mergedValues model.",
      "brikk-sql/src/dev.brikk.house.sql/optimizer/QualifyColumns.kt")
group("placeholder-replacement-api", "ad8764a0", "intentionally divergent",
      "No replace_placeholders public helper exists. Keep parameter/source-map ownership in existing Brikk APIs rather than add a new name-based expression-builder API.",
      "brikk-sql/src/dev.brikk.house.sql/ast/Builders.kt")
group("regex-count-qualification", "ba643d33", "deferred",
      "Do not equate Trino REGEXP_COUNT and ClickHouse countMatches from names alone. The separately queued bounded Trino work and unproved ClickHouse mapping remain separate.",
      "vendor/data/sqlglot-sg29-018/README.md", "Jayson Minard, bounded regex qualification")


def assignments():
    result = {}
    for name, review in GROUPS.items():
        for commit in review["commits"]:
            if commit in result:
                raise ValueError(f"Duplicate review for {commit}")
            result[commit] = name
    return result


def git(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args])


def inventory(source):
    reviews = assignments()
    commits = git(source, "rev-list", "--reverse", f"{BASE}..{TARGET}").decode().splitlines()
    if len(commits) != 250:
        raise ValueError(f"Expected the frozen 250-commit range, got {len(commits)}")
    missing = [sha for sha in commits if sha[:8] not in reviews]
    extra = set(reviews) - {sha[:8] for sha in commits}
    if missing or extra:
        raise ValueError(f"Unreviewed: {missing}; outside range: {sorted(extra)}")
    rows = []
    for sha in commits:
        name = reviews[sha[:8]]
        rows.append(dict(commit=sha, subject=git(source, "show", "-s", "--format=%s", sha).decode().strip(),
                         files=git(source, "diff-tree", "--no-commit-id", "--name-only", "-r", sha).decode().splitlines(),
                         patch_sha256=hashlib.sha256(git(source, "show", "--format=", "--binary", sha)).hexdigest(),
                         review=name))
    return dict(base=BASE, target=TARGET, target_tag="v30.29.0", commit_count=len(rows),
                history_sha256=hashlib.sha256("\n".join(commits).encode()).hexdigest(),
                groups=GROUPS, commits=rows)


def validate(document):
    if document["base"] != BASE or document["target"] != TARGET or document["commit_count"] != 250:
        raise ValueError("Wrong frozen range")
    if document["groups"] != GROUPS:
        raise ValueError("Review groups differ from the curated source")
    reviews = assignments()
    commits = document["commits"]
    if len(commits) != 250 or len({row["commit"] for row in commits}) != 250:
        raise ValueError("Missing or duplicate commit")
    if set(reviews) != {row["commit"][:8] for row in commits}:
        raise ValueError("Incomplete dispositions")
    for row in commits:
        if row["review"] != reviews[row["commit"][:8]] or not row["files"] or len(row["patch_sha256"]) != 64:
            raise ValueError(f"Invalid commit evidence: {row['commit']}")
    expected = hashlib.sha256("\n".join(row["commit"] for row in commits).encode()).hexdigest()
    if expected != document["history_sha256"]:
        raise ValueError("History order/hash changed")
    for review in GROUPS.values():
        if review["disposition"] not in {"ported/tested", "already equivalent", "intentionally divergent", "deferred"}:
            raise ValueError("Unknown disposition")
        if not review["owner"] or not (ROOT / review["evidence"]).is_file():
            raise ValueError("Missing owner/evidence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.write:
        if args.source is None:
            parser.error("--write requires the pinned upstream --source")
        data = inventory(args.source)
        validate(data)
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(json.dumps(data, indent=2) + "\n")
    else:
        data = json.loads(OUTPUT.read_text())
        validate(data)
        if args.source is not None and inventory(args.source) != data:
            raise ValueError("Pinned upstream evidence differs")
    print("Verified all 250 frozen-target commit dispositions")


if __name__ == "__main__":
    main()
