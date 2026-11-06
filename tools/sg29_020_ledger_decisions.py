#!/usr/bin/env python3
"""Materialize the case-by-case SG29-020 review, ONLY for its frozen observations.

This is not a future failure classifier. The complete observation hash is fixed;
any different ID, signature, metadata or inventory requires a new review.
"""
import argparse
import collections
import json
from pathlib import Path

import review_sqlglot_refresh as gate

REVIEW_SHA256 = "55ec8b4a5cafddb84489e3c4053b378903a06e42228d84e30da132bad6c408e4"
EVIDENCE = "vendor/data/sqlglot-sg29-020/README.md"


def decision(group, reason, evidence=EVIDENCE, deferred=False):
    return dict(group=group, reason="SG29-020: " + reason +
                (" Deferred owner: Jayson Minard, separately bounded feature/qualification work; no equivalence claimed." if deferred else ""),
                evidence=evidence)


def classify(path, row):
    sql = row.get("sql", row.get("case", row.get("key", "")))
    upper = sql.upper()
    if "starrocks-" in path:
        if "TO_DAYS" in upper:
            return decision("starrocks-native-date", "Retain the separately native-tested TO_DAYS representation and source-aware refusal, not upstream's generic date node.", "vendor/data/sqlglot-sg29-014-rest/README.md")
        if "MAP{" in upper or "MAP{" in upper.replace(" ", ""):
            return decision("starrocks-native-map", "Retain native map identity, NULL/duplicate-key/type contracts and qualified-key ownership. The upstream ToMap/struct representation is not substituted.", "vendor/data/sqlglot-sg29-014-wrong-results/README.md")
        if "PIVOT" in upper:
            return decision("starrocks-native-pivot", "Retain bounded static native pivot names and conservative unresolved outputs; target AST/position metadata differs without granting dynamic-name inference.", "vendor/data/sqlglot-sg29-014-rest/README.md")
        if upper.startswith("CREATE TABLE"):
            return decision("starrocks-native-ddl", "Keep the published Doris aggregate-key/HLL native node/module and separately reviewed parsed fields. No DDL execution is claimed.", "vendor/data/sqlglot-sg29-014-rest/README.md")
        if upper.startswith(("REFRESH ", "ADMIN ", "BACKUP ", "RESTORE ", "RECOVER ", "CANCEL ", "EXPORT ", "SUBMIT ", "PAUSE ", "RESUME ", "STOP ", "DEALLOCATE ", "INSTALL ", "UNINSTALL ", "ADD ", "DELETE ", "TRANSLATE ")):
            return decision("starrocks-whole-command", "Preserve the complete opaque administrative/unknown-refresh command and its authored payload; differing Command payload layout/UNKNOWN annotation is not structured semantic support.", "vendor/data/sqlglot-sg29-014-rest/README.md")
    if "doris-" in path and "MATCH" in upper:
        return decision("doris-native-match", "Retain the native MATCH operator/analyzer representation and parenthesization proved by Doris 4.1.5 parsed/analyzed plans. Do not infer backend/index execution from generic AST equality.", "vendor/data/sqlglot-sg29-013/README.md")
    if "mysql-" in path and upper.startswith(("ALTER TABLE", "CREATE TABLE")) and ("FOREIGN KEY" in upper or "CONSTRAINT" in upper):
        return decision("mysql-new-constraint-parser", "The new unnamed-constraint/named-FK-index parser forms are not all modeled. Loaded named FK nodes now retain their name; these exact parser/annotation gaps remain explicit.", deferred=True)
    if "postgres-" in path:
        if upper.startswith("SELECT INTERVAL"):
            return decision("interval-fraction-metadata", "Current parsing retains the literal interval and unit but not the new fraction_in_string marker. Same-profile textual rendering remains faithful; unproved DuckDB fractional conversions still refuse.", "vendor/data/sqlglot-sg29-018/README.md")
        if upper.startswith("INSERT INTO"):
            return decision("insert-alias-column-ownership", "The new INSERT alias/target-column ownership representation is not ported wholesale; record its exact schema-tree gap rather than claim catalog/assignment equivalence.", deferred=True)
        if "TABLESPACE" in upper or "ON COMMIT" in upper:
            return decision("postgres-tablespace-ddl", "New TABLESPACE/index-option/CREATE lifecycle placement is not qualified. Unknown locations refuse or stay opaque; nonempty new constraint options are guarded for execution. No native DDL is run.", deferred=True)
        if upper.startswith(("CREATE TABLE", "ALTER TABLE")):
            return decision("postgres-empty-index-metadata", "The target adds an empty IndexParameters child where the existing parser omits it. Retain the old empty optional shape; positive new index options are separately refused/deferred.")
    if "clickhouse-" in path:
        if "PASTE JOIN" in upper:
            return decision("paste-join-parser", "PASTE remains an ordinary identifier/function name, but new PASTE JOIN grammar is explicitly unimplemented, not mistaken for a table alias or silently erased.", deferred=True)
        if "JSONEXTRACT" in upper:
            return decision("clickhouse-native-json", "Retain the independently native-tested raw/String extraction model and only_json_types/source markers. Cross-engine JSON String/missing/null equivalence remains refused.", "vendor/data/sqlglot-sg29-010/README.md")
    if "duckdb-" in path and "transpile" not in path:
        if "JOIN BY (TYPE" in upper:
            return decision("duckdb-join-by-type", "These newly inventoried BY(TYPE ...) normalization variants are not all modeled in the parity grammar/renderer; do not call a local parse failure a native grammar verdict.", deferred=True)
        if '$"' in sql:
            return decision("duckdb-parameter-node", "Retain the existing Parameter node and quoted-variable ownership rather than upstream's Placeholder migration. Loaded Placeholder quoting and native parameter spans are separately controlled.")
        if "NEAREST" in upper:
            return decision("duckdb-native-nearest", "Retain the native optional-count/exact/approximate/distance model and exact=false field instead of the reduced generic NEAREST payload.", "vendor/data/sqlglot-sg29-018/README.md")
        if "LAMBDA" in upper:
            return decision("lambda-colon-compatibility", "Preserve the published Lambda.colon field and its native binder/serde representation. Do not delete an old field merely because the generic target no longer records it.")
        if "~" in sql:
            return decision("duckdb-regex-profile", "Keep the historical direct-parser regex contract separate from explicitly native 2.0 partial/session semantics. The new oracle does not authorize silently changing the legacy profile.", "vendor/data/sqlglot-sg29-018/README.md")
        if any(name in upper for name in ("LIST_SORT", "ARRAY_SORT", "LIST_REVERSE_SORT", "ARRAY_REVERSE_SORT")):
            return decision("sort-node-compatibility", "Keep both published sort node/schema identities and native order/NULL ownership. Loaded new ArraySort direction fields now warn/refuse rather than silently qualifying a lossy migration.", "vendor/data/sqlglot-sg29-018/README.md")
        if upper.startswith("ARRAY_SLICE"):
            return decision("native-slice-metadata", "Retain native step/endpoint and explicit default metadata with the existing executable refusal boundary. Generic AST/default-field equality is not a foreign slicing proof.", "vendor/data/sqlglot-sg29-018/README.md")
        if upper.startswith("SELECT INTERVAL"):
            return decision("interval-fraction-metadata", "Preserve the existing literal/unit AST and native fractional-interval refusal instead of inferring a new execution contract from fraction_in_string metadata.", "vendor/data/sqlglot-sg29-018/README.md")
        if upper.startswith(("CONNECT", "DISCONNECT")):
            return decision("native-routing-model", "Keep Brikk's session/resource-owned routing nodes, credentials redaction and foreign execution refusal. Generic ConnectTo/Disconnect dumps do not automatically gain a connection handler.", "vendor/data/sqlglot-sg29-018/README.md")
        if upper.startswith("DROP TRIGGER"):
            return decision("native-trigger-model", "Keep structured native trigger ownership and image/transition-scope requirements. Payload order differs; schema-less trigger query typing deliberately refuses.", "vendor/data/sqlglot-sg29-018/README.md")
    if path.endswith("qualify-corpus/known-failures.json"):
        if "READ_PARQUET" in upper or "RANGE(" in upper:
            return decision("explicit-inference-policy", "Retain the published legacy inference-policy overloads and disabled default unknown-TVF back-projection. The target's changed validation/inference expectations are not silently substituted.", "vendor/data/sqlglot-sg29-017/README.md")
        if "ANY_VALUE" in upper:
            return decision("unresolved-json-alias-qualification", "The new unresolved JSON-path/ORDER/DISTINCT alias matching fix is unported. Preserve the exact failing output/signature as a correctness deferral, not as semantic equivalence.", deferred=True)
        if "UNPIVOT" in upper or "PIVOT" in upper:
            return decision("pivot-row-qualification", "Retain the bounded pivot/unknown-schema contract; these newly inventoried UNPIVOT alias/correlation and unknown row-reference cases remain unqualified.", deferred=True)
    if path.endswith("scope-corpus/known-failures.json") and "PIVOT" in upper:
        return decision("pivoted-cte-scope", "The new pivoted-CTE reference-alias source entry is not registered in the existing scope model. Keep this exact graph difference visible; no broad cache/scope refactor is imported.", deferred=True)
    if "spark-" in path and "VALUES" in upper and "UNION ALL" in upper:
        return decision("values-wrapper-representation", "The ordered/paginated VALUES arm retains the existing wrapper/ownership representation. Exact Spark execution for this new corpus case is not claimed; native wrapping/modifier controls remain separate.", deferred=True)
    if "transpile" in path:
        if "MAKE_INTERVAL" in upper:
            return decision("escaped-interval-expression", "Use separately escaped per-unit interval expressions instead of one combined literal. The malicious string remains inside a literal; no native validity/equivalence is asserted for malformed numeric input.")
        if "APPROX_" in upper:
            return decision("approximate-array-preview", "Retain explicit BigQuery OFFSET syntax and the existing approximate/weighted-percentile execution refusal. A generic bracket rendering difference is not a newly qualified approximation.", "vendor/data/sqlglot-sg29-016/README.md")
        if "JSONEXTRACTRAW" in upper:
            return decision("clickhouse-json-outbound-preview", "Keep JSON_EXTRACT_PATH/function rendering separate from JSON operator text and preserve the existing String/missing/null outbound execution refusal.", "vendor/data/sqlglot-sg29-010/README.md")
        if "JOIN BY (TYPE" in upper:
            return decision("duckdb-join-by-type", "The newly inventoried BY(TYPE ...) source grammar is unimplemented in this parity path. An ordinary parser diagnostic is not silently accepted as a successful native translation.", deferred=True)
        if "ARRAY_TO_STRING" in upper or "LIST_TRANSFORM" in upper or "LIST_CONCAT" in upper:
            return decision("array-null-replacement-preview", "Retain the independently guarded insertion/null-replacement model; do not import unproved casts, nullability or append semantics from a generic preview expectation.", "vendor/data/sqlglot-sg29-018/README.md")
        if "ARRAY_SLICE" in upper:
            return decision("foreign-slice-preview", "This generic endpoint/length/start rewrite is not the native executable slicing contract. Keep exact preview drift and the DDB20-055 refusal rather than infer equivalence.", "vendor/data/sqlglot-sg29-018/README.md")
        if "RANGE(" in upper:
            return decision("exclusive-series-preview", "Negative-step exclusive endpoints retain an explicit unqualified preview difference; executable cross-engine series mapping is not granted by native syntax acceptance.", "vendor/data/sqlglot-sg29-018/README.md")
        if "JSON_OBJECT" in upper:
            return decision("sql-json-option-preview", "Do not drop PostgreSQL SQL/JSON NULL/unique-key clauses merely because the target oracle discards them. Existing unproved-option execution refusals remain.", "vendor/data/sqlglot-sg29-018/README.md")
        if "~" in sql:
            return decision("regex-preview-profile", "The direct parser keeps its historical full-match canonical node; native DuckDB 2.0/session operators use the separate native path. Foreign regex equivalence is not claimed.", "vendor/data/sqlglot-sg29-018/README.md")
        if "DATE_DIFF" in upper:
            return decision("qualified-temporal-boundary", "Retain the separately controlled source-aware boundary lowering and explicit casts; a changed generic cast/SQL spelling does not broaden temporal execution qualification.", "vendor/data/sqlglot-sg29-011/README.md")
        if "TIMESTAMPADD" in upper:
            return decision("timestampadd-literal-binding", "Keep the explicit TIMESTAMP cast required by the independently tested source-aware lowering rather than rely on an untyped PostgreSQL literal's operator binding.", "vendor/data/sqlglot-sg29-009/README.md")
        if "INTERVAL" in upper:
            return decision("interval-preview-boundary", "Preserve separately qualified fractional temporal handling and bounded whole-quarter conversion. Unproved/overflowing values diagnose/refuse instead of being coerced to zero or losing precision.", "vendor/data/sqlglot-sg29-018/README.md")
        if "U&'" in upper:
            return decision("unicode-preview-boundary", "Incoming Unicode escape conversion retains its explicit native qualification/refusal boundary; a decoded oracle preview is not proof of cross-engine execution.", "vendor/data/sqlglot-sg29-018/README.md")
    raise ValueError(f"No reviewed disposition for {path}: {sql}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review", type=Path)
    args = parser.parse_args()
    document = json.loads(args.review.read_text())
    if gate.fingerprint(document) != REVIEW_SHA256:
        raise ValueError("This is not the frozen, individually reviewed observation packet")
    decisions = {}
    removed = {
        "d0cb329bb688", "e9bff597219f", "c4011a57b71b", "e6e630a59bff", "065facbe285c",
        "145b41f19ff1", "1d94871b8d96", "4ff96202c8d1", "811d0adf78a0",
    }
    for path, delta in document["ledgers"].items():
        for kind, rows in (("add", delta["added"]), ("change", [pair["after"] for pair in delta["changed"]]), ("remove", delta["removed"])):
            for row in rows:
                if kind == "remove":
                    if row["id"].split(":")[-2][:12] not in removed:
                        raise ValueError("Unreviewed removal")
                    value = decision("resolved-old-pin-drift", "The coherent target now agrees with the earlier bounded aggregate/digest/temporal/precedence port. Remove this exact stale old-pin assertion; retain its old record in the review packet.")
                else:
                    value = classify(path, row)
                value.update(label=row.get("sql", row.get("case", row.get("key"))), signature=row["signature"])
                decisions[f"{path}#{kind}#{row['id']}"] = value
    approval = dict(review_sha256=REVIEW_SHA256, decisions=decisions)
    gate.validate_approval(document, approval)
    output = gate.ROOT / "vendor/data/sqlglot-sg29-020"
    (output / "ledger-decisions.json").write_text(json.dumps(approval, indent=2, ensure_ascii=False) + "\n")
    (output / "ledger-review.json").write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
    print(collections.Counter(row["group"] for row in decisions.values()))


if __name__ == "__main__":
    main()
