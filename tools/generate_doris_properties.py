#!/usr/bin/env python3
"""Generates brikk-sql-metadata's GeneratedDorisProperties.kt: the keys of Doris's PROPERTIES lists.

Inputs:
  - apache/doris-website at WEBSITE_COMMIT, versioned_docs/version-4.x: the pages named in PAGES,
    fetched into a cache directory (fetched again when missing);
  - an apache/doris checkout with the origin/branch-<line> branches of LINES: a key's `since` is the
    earliest line whose FE sources name it as a string literal (2.0 stands for "2.0 or earlier"), or the
    release a page states ("Supported since 2.1.5"), whichever is later; null for keys Doris passes on to
    another client (Hadoop, Kafka) without naming them.

Usage: tools/generate_doris_properties.py <cache-dir> <doris-checkout>
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

WEBSITE_COMMIT = "81077becd6a77efa3e237ed9159bf413f36c9902"
RAW = f"https://raw.githubusercontent.com/apache/doris-website/{WEBSITE_COMMIT}/versioned_docs/version-4.x/"
LINES = ["2.0", "2.1", "3.0", "3.1", "4.0", "4.1"]
STATEMENTS = "sql-manual/sql-statements/"
PAGES = {
    "CREATE-TABLE": STATEMENTS + "table-and-view/table/CREATE-TABLE",
    "CREATE-ASYNC-MATERIALIZED-VIEW": STATEMENTS + "table-and-view/async-materialized-view/CREATE-ASYNC-MATERIALIZED-VIEW",
    "CREATE-CATALOG": STATEMENTS + "catalog/CREATE-CATALOG",
    "CREATE-RESOURCE": STATEMENTS + "cluster-management/compute-management/CREATE-RESOURCE",
    "CREATE-ROUTINE-LOAD": STATEMENTS + "data-modification/load-and-export/CREATE-ROUTINE-LOAD",
    "EXPORT": STATEMENTS + "data-modification/load-and-export/EXPORT",
    "OUTFILE": STATEMENTS + "data-modification/load-and-export/OUTFILE",
    "CREATE-STREAMING-JOB": STATEMENTS + "job/CREATE-STREAMING-JOB",
    "cat-hive-catalog": "lakehouse/catalogs/hive-catalog",
    "cat-iceberg-catalog": "lakehouse/catalogs/iceberg-catalog",
    "cat-paimon-catalog": "lakehouse/catalogs/paimon-catalog",
    "cat-jdbc-catalog-overview": "lakehouse/catalogs/jdbc-catalog-overview",
    "cat-es-catalog": "lakehouse/catalogs/es-catalog",
    "cat-maxcompute-catalog": "lakehouse/catalogs/maxcompute-catalog",
    "cat-doris-catalog": "lakehouse/catalogs/doris-catalog",
}
TVF = "sql-manual/sql-functions/table-valued-functions/"
TVF_PAGES = {"s3": "s3", "local": "local", "http": "http", "file": "file", "query": "query", "partition_values": "partition-values",
             "hudi_meta": "hudi-meta", "parquet_meta": "parquet-meta", "parquet_file_metadata": "parquet-meta",
             "parquet_kv_metadata": "parquet-meta", "parquet_bloom_probe": "parquet-meta", "jobs": "jobs", "tasks": "tasks",
             "mv_infos": "mv_infos", "partitions": "partitions", "numbers": "numbers"}

cache, doris = sys.argv[1], sys.argv[2]


def page(name, path):
    for ext in (".md", ".mdx"):
        local = os.path.join(cache, name + ext)
        if os.path.exists(local):
            return open(local).read()
    for ext in (".md", ".mdx"):
        try:
            text = urllib.request.urlopen(RAW + path + ext, timeout=30).read().decode()
        except Exception:
            continue
        open(os.path.join(cache, name + ext), "w").write(text)
        return text
    raise SystemExit(f"cannot fetch {path}")


def literals(line):
    out = subprocess.run(["git", "-C", doris, "grep", "-h", "-o", "-E", r'"[a-zA-Z_][a-zA-Z0-9_.*\-]*"', f"origin/branch-{line}", "--",
                          "fe/fe-core/src/main/java", "fe/fe-common/src/main/java"], capture_output=True, text=True, check=True).stdout
    return {l.strip('"') for l in out.split()}


LIT = {line: literals(line) for line in LINES}


def in_line(key, line):
    names = LIT[line]
    k = key.rstrip("*").rstrip(".")
    if k in names or k + "." in names:
        return True
    # A dotted key Doris builds from a prefix and a name: function_column.sequence_col, broker.kerberos_keytab.
    head, _, tail = k.partition(".")
    return bool(tail) and (head in names or head + "." in names) and tail in names


def since(key, summary):
    line = next((l for l in LINES if in_line(key, l)), None)
    stated = re.search(r"[Ss]upported (?:since|from) (?:Doris |version )?(\d+\.\d+(?:\.\d+)?)", summary or "")
    if stated and (line is None or tuple(map(int, stated.group(1).split("."))) >= tuple(map(int, line.split(".")))):
        return stated.group(1)
    return line


def clean(t):
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = t.replace("`", "").replace("**", "").replace("<br/>", " ").replace("<br>", " ").replace("\\_", "_").replace("\\|", "|")
    return re.sub(r"\s+", " ", t).strip()


def first_sentence(t):
    t = clean(t)
    m = re.match(r"(.+?[.!?])(\s|$)", t)
    s = m.group(1) if m else t
    return s if len(s) <= 200 else s[:197].rsplit(" ", 1)[0] + "..."


KEY = r"[a-z_][a-z0-9_.*\-]*"


def keyname(raw):
    k = clean(raw).strip("\"'<> ")
    return k if re.fullmatch(KEY, k) else None


def section(ls, start, end):
    s = next(i for i, l in enumerate(ls) if re.search(start, l))
    e = next((i for i in range(s + 1, len(ls)) if re.search(end, ls[i])), len(ls))
    return ls[s + 1:e]


def table(body):
    out = []
    for l in body:
        if not l.lstrip().startswith("|") or re.match(r"^\s*\|[\s:\-|]+\|\s*$", l):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        while len(cells) > 1 and not cells[-1]:
            cells.pop()
        k = keyname(cells[0])
        if k and len(cells) > 1:
            default = cells[1] if len(cells) >= 3 else None
            out.append((k, first_sentence(cells[-1]), default))
    return out


def tables(text):
    """Every markdown table whose first header cell names properties or parameters."""
    ls = text.splitlines()
    out = []
    for i, l in enumerate(ls):
        if l.lstrip().startswith("|") and re.match(r"^\s*\|\s*(Property|Parameter|Parameter Name|Property Name)\b", l):
            body = []
            for m in ls[i + 2:]:
                if not m.lstrip().startswith("|"):
                    break
                body.append(m)
            out += table(body)
    return out


def bullets(body):
    out = []
    for l in body:
        m = re.match(r"^\s*>?\s*[-*]\s+`?([a-z_][a-z0-9_.]*)`?\s*(?:\(Optional\))?\s*(?:[:：]\s*(.*))?$", l)
        if m and keyname(m.group(1)) and (m.group(2) is not None or "`" in l):
            out.append((m.group(1), first_sentence(m.group(2) or "") or None, None))
    return out


def numbered(body):
    out, cur, text = [], None, []

    def flush():
        for k in cur or []:
            out.append((k, first_sentence(" ".join(text)), None))
    for l in body:
        m = re.match(r"^\s*>\s*[0-9]+\.\s+`((?:<?[a-z_][a-z0-9_.]*>?/?)+)`", l)
        if m:
            flush()
            cur, text = [k.strip("<>") for k in m.group(1).split("/") if k], []
        elif cur is not None:
            t = l.lstrip("> ").strip()
            if t and not t.startswith(("`", '"', "-")) and not text:
                text.append(t)
    flush()
    return out


def example_keys(text):
    """Keys of the CREATE CATALOG examples, without placeholders such as your-nameservice."""
    keys = []
    for m in re.finditer(r"CREATE CATALOG.*?\)\s*;", text, re.S | re.I):
        for k in re.findall(r"[\"']([a-z_][a-z0-9_.\-]*)[\"']\s*=", m.group(0)):
            if k not in keys and "your" not in k and k != "type":
                keys.append(k)
    return [(k, None, None) for k in keys]


def lines_of(name):
    return page(name, PAGES[name]).splitlines()


BOOLEAN = ["true", "false"]
# Closed sets of values the pages list in prose.
VALUES = {
    "storage_medium": ["SSD", "HDD"],
    "compression": ["LZ4F", "LZ4", "ZSTD", "SNAPPY", "ZLIB", "NO_COMPRESSION"],
    "compaction_policy": ["time_series", "size_based"],
    "function_column.sequence_type": None,
    "format": ["csv", "csv_with_names", "csv_with_names_and_types", "json", "parquet", "orc"],
    "data_consistency": ["none", "partition"],
    "compress_type": ["snappy", "gzip", "zstd", "lz4", "plain", "zlib", "none"],
    "hadoop.security.authentication": ["kerberos", "simple"],
    "partition_sync_time_unit": ["DAY", "MONTH", "YEAR"],
    "timezone": None,
    "partial_update_new_key_behavior": ["APPEND", "ERROR"],
    "strict_mode": ["true", "false"],
}


# Where a page's sentence does not describe the key on its own.
SUMMARIES = {
    "max_batch_interval": "Maximum execution time of each subtask, in seconds.",
    "max_batch_rows": "Maximum number of rows each subtask reads.",
    "max_batch_size": "Maximum number of bytes each subtask reads.",
    "kafka_partitions": "The Kafka partitions to subscribe to, comma-separated.",
    "kafka_offsets": "The starting offset of each partition in kafka_partitions: a number, OFFSET_BEGINNING, OFFSET_END or a time.",
}
# Page keys that stand for a family: property.client.id, broker.username.
RENAMES = {"property": None, "broker.property_key": "broker.*"}


def entries(raw):
    seen, out = set(), []
    for k, summary, default in raw:
        if k in RENAMES:
            if RENAMES[k] is None:
                continue
            k = RENAMES[k]
        summary = SUMMARIES.get(k, summary)
        if k in seen:
            continue
        seen.add(k)
        values = VALUES.get(k)
        if values is None and re.search(r"\b(true|false)\b", (default or "") + " " + (summary or "")) and re.search(
                r"(?i)whether|default (?:is |value is )?`?(true|false)|^`?\"?(true|false)", (summary or "") + " " + (default or "")):
            values = BOOLEAN
        out.append({"name": k, "summary": summary or None, "since": since(k, summary), "values": values or []})
    return out


lists = []


def add(title, context, path, raw, opener="PROPERTIES", clause=False, variant=None):
    lists.append({"title": title, "context": context, "opener": opener, "path": path, "clause": clause, "variant": variant, "keys": entries(raw)})


ct = lines_of("CREATE-TABLE")
add("CREATE TABLE properties", "CREATE [EXTERNAL] [TEMPORARY] TABLE", PAGES["CREATE-TABLE"] + "/",
    table(section(ct, r"^\*\*<table_property>\*\*", r"^## ")))
mv = lines_of("CREATE-ASYNC-MATERIALIZED-VIEW")
add("CREATE MATERIALIZED VIEW properties", "CREATE MATERIALIZED VIEW", PAGES["CREATE-ASYNC-MATERIALIZED-VIEW"] + "/",
    table(section(mv, r"^\*\*9\. `<table_property>`", r"^## ")))

sj = page("CREATE-STREAMING-JOB", PAGES["CREATE-STREAMING-JOB"]).splitlines()
groups, i = [], 0
for i, l in enumerate(sj):
    if l.startswith("| Parameter"):
        body = []
        for m in sj[i + 2:]:
            if not m.startswith("|"):
                break
            body.append(m)
        groups.append(table(body))
add("CREATE JOB properties", "CREATE JOB", PAGES["CREATE-STREAMING-JOB"] + "/", groups[2])
add("CREATE JOB source properties", "CREATE JOB", PAGES["CREATE-STREAMING-JOB"] + "/", groups[0], opener="MYSQL|POSTGRES", clause=True)

rl = lines_of("CREATE-ROUTINE-LOAD")
rl_path = PAGES["CREATE-ROUTINE-LOAD"] + "/"
add("CREATE ROUTINE LOAD job properties", "CREATE ROUTINE LOAD", rl_path, numbered(section(rl, r"^\*\*4\. `<job_properties>`", r"^\*\*5\.")))
add("CREATE ROUTINE LOAD Kafka properties", "CREATE ROUTINE LOAD", rl_path,
    numbered(section(rl, r"^\*\*3\. `<data_source_properties>`", r"^## ")) +
    numbered(section(rl, r"^\*\*5\. Optional properties", r"^\*\*6\.")) +
    [("property.*", "A Kafka client property, passed on to librdkafka without the property. prefix.", None)],
    opener="KAFKA", clause=True)

ex = lines_of("EXPORT")
ex_path = PAGES["EXPORT"] + "/"
add("EXPORT properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^\*\*3\. `<properties>`", r"^\*\*4\.")))
add("EXPORT broker properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*BROKER", r"^- \*\*HDFS")), opener="BROKER", clause=True)
add("EXPORT HDFS properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*HDFS", r"^- \*\*S3")), opener="HDFS", clause=True)
add("EXPORT S3 properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*S3", r"^## ")), opener="S3", clause=True)

of = lines_of("OUTFILE")
add("OUTFILE properties", "INTO OUTFILE", PAGES["OUTFILE"] + "/",
    bullets(section(of, r"^\*\*Properties related to export file itself", r"^## Return")), clause=True)

CATALOG_TYPES = {"hms": "cat-hive-catalog", "iceberg": "cat-iceberg-catalog", "paimon": "cat-paimon-catalog",
                 "jdbc": "cat-jdbc-catalog-overview", "es": "cat-es-catalog", "max_compute": "cat-maxcompute-catalog",
                 "doris": "cat-doris-catalog"}
add("CREATE CATALOG properties", "CREATE CATALOG", PAGES["CREATE-CATALOG"] + "/",
    [("type", "The kind of external data source the catalog connects.", None), ("resource", "A resource whose properties the catalog takes.", None)])
lists[-1]["keys"][0]["values"] = list(CATALOG_TYPES) + ["trino-connector"]
cc = page("CREATE-CATALOG", PAGES["CREATE-CATALOG"])
jdbc_examples = [(k, None, None) for k in dict.fromkeys(re.findall(r"[\"']([a-z_][a-z0-9_.\-]*)[\"']\s*=", cc)) if k != "type"]
for catalog_type, name in CATALOG_TYPES.items():
    text = page(name, PAGES[name])
    # Only the ES page tabulates the catalog's own keys; the other pages' tables are cache modules and session variables.
    raw = (tables(text)[:10] if catalog_type == "es" else []) + example_keys(text) + (jdbc_examples if catalog_type == "jdbc" else [])
    add(f"CREATE CATALOG properties ({catalog_type})", "CREATE CATALOG", PAGES[name] + "/", raw, variant=["type", catalog_type])

rs = lines_of("CREATE-RESOURCE")
RESOURCE_TYPES = [("spark", r"^\*\*1\.", r"^\*\*2\."), ("odbc_catalog", r"^\*\*2\.", r"^\*\*3\."), ("s3", r"^\*\*3\.", r"^\*\*4\."),
                  ("jdbc", r"^\*\*4\.", r"^\*\*5\."), ("hdfs", r"^\*\*5\.", r"^\*\*6\."), ("hms", r"^\*\*6\.", r"^\*\*7\."), ("es", r"^\*\*7\.", r"^## ")]
rs_path = PAGES["CREATE-RESOURCE"] + "/"
add("CREATE RESOURCE properties", "CREATE [EXTERNAL] RESOURCE", rs_path, [("type", "The kind of resource.", None)])
lists[-1]["keys"][0]["values"] = [t for t, _, _ in RESOURCE_TYPES]
for resource_type, start, end in RESOURCE_TYPES:
    add(f"CREATE RESOURCE properties ({resource_type})", "CREATE [EXTERNAL] RESOURCE", rs_path,
        bullets(section(rs, start, end)), variant=["type", resource_type])

# Summaries and since of the table functions' keys (their keys and values are in DorisTableFunctions.kt).
tvf = {}
for function, slug in TVF_PAGES.items():
    rows = tables(page("tvf-" + slug, TVF + slug))
    keys = {}
    for k, summary, _ in rows:
        if not keys.get(k, {}).get("summary"):
            keys[k] = {"summary": summary or None, "since": since(k, summary)}
    tvf[function] = {"path": TVF + slug + "/", "keys": keys}
handwritten = open(os.path.join(os.path.dirname(__file__), "..", "brikk-sql-metadata", "src", "dev.brikk.house.sql.metadata",
                                "DorisTableFunctions.kt")).read()
handwritten_since = {k: since(k, None) for k in sorted(set(re.findall(r'PropertyDef\("([^"]+)"', handwritten)))}


def kt(s):
    return "null" if s is None else json.dumps(s, ensure_ascii=False).replace("$", "\\$")


def key(k):
    args = [kt(k["name"])]
    if k["values"]:
        args.append("listOf(" + ", ".join(kt(v) for v in k["values"]) + ")")
    else:
        args.append("emptyList()")
    args += [kt(k["summary"]), kt(k["since"])]
    return "PropertyDef(" + ", ".join(args) + ")"


out = ["package dev.brikk.house.sql.metadata", "",
       "/*",
       " * GENERATED by tools/generate_doris_properties.py; do not edit. Sources: apache/doris-website",
       f" * {WEBSITE_COMMIT} (versioned_docs/version-4.x) and the FE sources of apache/doris",
       " * origin/branch-" + ", origin/branch-".join(LINES) + ".",
       " */", "",
       "internal val DORIS_PROPERTY_LISTS: List<PropertyList> = listOf("]
for l in lists:
    out.append("    PropertyList(")
    out.append(f"        title = {kt(l['title'])}, context = {kt(l['context'])}, opener = {kt(l['opener'])}, path = {kt(l['path'])},")
    if l["clause"]:
        out.append("        clause = true,")
    if l["variant"]:
        out.append(f"        variant = {kt(l['variant'][0])} to {kt(l['variant'][1])},")
    out.append("        keys = listOf(")
    for k in l["keys"]:
        out.append(f"            {key(k)},")
    out.append("        ),")
    out.append("    ),")
out.append(")")
out.append("")
out.append("/** The summary and since of each documented key of a table function, by function name, and its page. */")
out.append("internal val DORIS_TABLE_FUNCTION_KEYS: Map<String, Pair<String, Map<String, Pair<String?, String?>>>> = mapOf(")
for function, data in tvf.items():
    out.append(f"    {kt(function)} to ({kt(data['path'])} to mapOf(")
    for k in sorted(data["keys"]):
        v = data["keys"][k]
        out.append(f"        {kt(k)} to ({kt(v['summary'])} to {kt(v['since'])}),")
    out.append("    )),")
out.append(")")
out.append("")
out.append("/** The since of every key DorisTableFunctions.kt lists, from the FE sources. */")
out.append("internal val DORIS_TABLE_FUNCTION_KEY_SINCE: Map<String, String?> = mapOf(")
for k, v in handwritten_since.items():
    out.append(f"    {kt(k)} to {kt(v)},")
out.append(")")
out.append("")
target = os.path.join(os.path.dirname(__file__), "..", "brikk-sql-metadata", "src", "dev.brikk.house.sql.metadata", "GeneratedDorisProperties.kt")
open(target, "w").write("\n".join(out))
print(f"{len(lists)} lists, {sum(len(l['keys']) for l in lists)} keys; {len(tvf)} table functions")
