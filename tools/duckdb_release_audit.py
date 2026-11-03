#!/usr/bin/env python3
"""Reproduce the reviewed, title-level disposition of every final DuckDB 2.0 release PR.

No network, engine execution or publication. A new body requires a fresh human review.
Mapped checklist IDs are ownership, never a claim that the associated change is implemented.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re

BODY_SHA256 = "1921a5b29657a1231fe1505c67e5b50fa8db6eab19ed9e377b8953f30ad985b2"
SOURCE_COMMIT = "d54cbbe3df17e8e5ce68312f483b4155836112aa"
# These are positions in the immutable body above, not project task IDs. Every emitted
# row also carries its actual upstream PR number, original title and link.
GROUPS = [
    ([20], [3,4,69,117,366,371,411,771,781,810], "Trigger SQL, transition/dependency semantics; retain the native trigger contract and its limits."),
    ([62], [6,7,11,19,29,35,55,63,80,86,285,358,374,384,509,553,607,758], "Function/catalog or aggregate-state addition; exact signatures, types and semantics remain separately auditable."),
    ([33], [8,268,290,424,599,748,753], "Parquet writing/options/result metadata; engine fixes are not fresh parser features."),
    ([24,45], [9,132,152,158,324,489,609,647], "EXPLAIN/profiling rendering; never an editor bind/execute validation fallback."),
    ([45,60], [31,70,78,113,205,431,814], "Settings and option metadata; current values are not universal defaults."),
    ([78], [39], "Dialect compatibility mode remains an explicit open semantic audit, not inferred helper parity."),
    ([74], [73], "Identifier-to-string table-function deprecation remains an open SQL boundary audit."),
    ([16,17], [75], "Native variable/default spelling and session binding contract."),
    ([51,55], [88], "Explicit internal join kinds require native mapping/refusal proof, not bare alias interpretation."),
    ([43], [96,335,516,531,546,551,677], "Timezone/calendar/collation implementation and result changes; only measured native samples are qualified."),
    ([37], [125], "Native regex operator partial/full session behavior, not a target-name equivalence."),
    ([30,63], [130,583,653,754], "Regex function overload/error/collation behavior; preserve historical hazard evidence."),
    ([1,2], [122,127,131,135,139,146,149,150,153,154,157,294,340,348,396,442,484,740,787,791], "Qualified/nested names: core preservation is distinct from deliberately deferred recursive IDE hierarchy."),
    ([52,65], [61,172,224,276,390,391,433,508,573,574,782], "Native parser/transport/robustness/location behavior; limits and failures are not acceptance."),
    ([53], [177], "POSITION special syntax needs expression and registry distinctions, not generic comma-call assumptions."),
    ([12], [186], "FETCH FIRST/NEXT grammar and ordered pagination contract."),
    ([19], [198,200,266,430,687], "Routing/attach/detach ownership and original path preservation; no user connection mutation/retry."),
    ([11], [215], "GROUP BY UNNEST binding and result contract, not an arbitrary cross-target rewrite."),
    ([31,63], [223,227,230,507,511,555,589,603,619,635,650,655,806,807,808,818], "Window syntax/catalog and independent runtime fixes; results are not certified by registration alone."),
    ([23], [287,364,444,449,525,660], "External-resource grammar/option ownership only; no real provider provisioning authorized."),
    ([13,14,15,63], [77,160,309,469,536,582,790], "JSON mutation/normalization versus VARIANT/result changes remain distinct contracts."),
    ([72], [306], "ANALYSE synonym remains an explicit open grammar audit."),
    ([28], [325,733], "SHOW statement forms and setting lookup need native grammar/catalog boundaries."),
    ([75], [344,604,704], "TRANSACTION/connection-scoped secrets remain open; never capture or log credentials."),
    ([29], [394,656], "Sequence/setval state changes; no retry of side-effecting SQL."),
    ([10], [437], "COPY-producing CTEs and their result/side-effect channels."),
    ([8], [438], "NEAREST counts, ranking and native result semantics; foreign mappings refuse when unproved."),
    ([5,7], [439], "Multi-matched UPDATE/MERGE behavior depends on native policy, not just syntax."),
    ([18], [488,506,539,723], "Recursive USING KEY delta/execution changes; only measured local binding/results are qualified."),
    ([36,44], [407], "Lambda syntax migration in benchmarks is not removal of binder policy distinctions."),
    ([73], [22,64,168,216,242,243,252,278,316,426,428,501,518,522,542,558,560,598,613,682,801,830], "Compound/numeric typing, literal/cast/default and precision gaps remain in the open type audit."),
    ([77], [115,180,273,291,293,301,302,317,382,541,612,624,628,652,674,707,766], "Temporal precision/TRY_CAST/result changes remain explicitly open beyond existing measured samples."),
    ([71], [549], "SET SCHEMA grammar and namespace switching remain an explicit open audit."),
    ([26,27], [579,614,663,684,690], "COPY order/partition/path and Hive collisions need native lossless semantics."),
    ([32], [563,678,734], "Parquet schema/UTF8/nested remap contracts; no read-side provider I/O during editor checking."),
    ([60], [664], "duckdb_settings typed_value is live connection data, not a static universal default."),
    ([68], [685,686,689,708,709,711,712,713,737,761,788], "Extensible grammar internals do not make every loaded grammar part of the static core dialect."),
    ([34], [661], "GeoJSON reader/writer/geometry native contracts, not JSON string substitution."),
    ([21], [710], "SECURE view information-hiding contract must survive or refuse."),
    ([41], [670,742], "Identifier-case policy and catalog spelling are session-sensitive."),
    ([76], [772], "Subquery colon aliases retain their separate open placement/scope audit."),
    ([42], [789], "Native integer-division zero policy and result width are not silently mapped."),
    ([44,52], [584,813], "Native string adjacency/NUL escape changes require lexical and result controls."),
    ([22], [829], "External extension repositories and trusted install/load grammar; no installation policy change implied."),
    ([46,59], [377,819,820], "HTTP/provider transport/caching changes are not new core SQL grammar; no mutation retries or real resources."),
    ([70], [334,493,514], "Client query/metadata APIs are separate from core SQL grammar; no ABI adoption or executor change implied."),
    ([12,44], [526], "OFFSET-without-order warning is an engine/result-policy change, not a new grammar acceptance claim."),
    ([67,71], [730], "Default-database removal needs namespace and exact-helper policy review; no fallback session guessing."),
]


def mapped_rows():
    result = {}
    for items, indexes, reason in GROUPS:
        for index in indexes:
            if index in result:
                raise ValueError(f"duplicate explicit review for row {index}")
            result[index] = ([f"DDB20-{i:03}" for i in items], reason)
    return result


def classify(index, title):
    explicit = mapped_rows().get(index)
    if explicit:
        return "existing-checklist-owner", *explicit
    if re.search(r"\b(?:CLI|shell|C-?API|CAPI|C\+\+|julia|appender|Arrow|workflow|CI|cmake|build|benchmark|test|unittest|sqllogic|agent|tidy|docs|security|include|windows|musl|linux|osx|architecture|linking|mold|rclone|release|bump|patches)\b", title, re.I):
        return "client-build-test-or-extension", ["DDB20-059", "DDB20-070"], "Client/build/test/extension change; no independently new core grammar or consumer adoption claim."
    if re.search(r"(?:date|time|interval|decimal|bignum|hugeint|cast|NULL|VARCHAR|UTF.?8|Unicode|ENUM|substring|list|array|variant|json|regexp|regex|coalesce|quantile|median|percentile|window|asof|first_value|rank|count\(|SUM|AVG)", title, re.I):
        return "engine-result-or-type-followup", ["DDB20-062", "DDB20-063", "DDB20-073", "DDB20-077"], "Engine function/type/result change; title reviewed, exact behavior not certified by parser gates."
    if re.search(r"(?:parquet|csv|storage|cache|fsst|roaring|buffer|WAL|\bART\b|index|file|checkpoint|transaction|corrupt|metadata reader)", title, re.I):
        return "storage-or-io-implementation", ["DDB20-047", "DDB20-059"], "Storage/I/O implementation or bugfix; no format rewrite, provider access or new grammar inferred."
    if re.search(r"(?:^(?:Issue|Internal) #|^Fix\s*#|^Fix https://|^Commits on top|^Revive #)", title, re.I):
        return "opaque-title-followup", ["DDB20-063"], "Title alone does not establish SQL-visible scope; remains unqualified, not silently declared covered."
    return "engine-implementation", [], "Optimizer/executor/internal maintenance from the reviewed title; no new parser feature or semantic equivalence claimed."


def audit(release):
    body = release["body"]
    digest = hashlib.sha256(body.encode()).hexdigest()
    if release["tag_name"] != "v2.0.0" or digest != BODY_SHA256:
        raise ValueError("Release body changed: review it, do not reuse positional dispositions")
    source = {key: release[key] for key in ("tag_name", "html_url", "published_at", "updated_at", "body")}
    source.update(body_sha256=digest, engine_source=SOURCE_COMMIT)
    rows = []
    for line in body.splitlines():
        match = re.search(r"https://github.com/duckdb/duckdb/pull/(\d+)", line)
        if not match:
            continue
        title = re.sub(r" by @.*", "", line).removeprefix("* ")
        category, owners, reason = classify(len(rows), title)
        rows.append({"pr": int(match[1]), "title": title, "url": match[0], "classification": category,
                     "owners": owners, "reason": reason})
    if len(rows) != 837 or max(mapped_rows()) >= len(rows):
        raise ValueError("Unexpected release PR inventory")
    return {"scope": "Full release-body PR title triage, not an exhaustive PR-body/engine-code or semantic audit. Owners are not DONE claims.",
            "source": source, "summary": {"rows": len(rows), "unique_prs": len({r['pr'] for r in rows}),
                "classifications": dict(sorted(collections.Counter(r['classification'] for r in rows).items()))}, "rows": rows}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--release", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--check", type=Path)
    args = p.parse_args()
    source = json.loads(args.release.read_text())
    result = audit(source.get("source", source))
    if args.check:
        assert result == json.loads(args.check.read_text()), "Release triage changed; review every changed disposition"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result["summary"], indent=2))


if __name__ == "__main__":
    main()
