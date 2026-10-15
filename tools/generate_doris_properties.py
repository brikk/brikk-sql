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
    """Each FE string literal of the line and the files naming it, paths from org/apache/doris/."""
    out = subprocess.run(["git", "-C", doris, "grep", "-o", "-E", r'"[a-zA-Z_][a-zA-Z0-9_.*\-]*"', f"origin/branch-{line}", "--",
                          "fe/fe-core/src/main/java", "fe/fe-common/src/main/java"], capture_output=True, text=True, check=True).stdout
    found = {}
    for row in out.splitlines():
        path, _, literal = row.split(":", 1)[1].rpartition(":")
        found.setdefault(literal.strip('"'), set()).add(path.split("org/apache/doris/", 1)[-1])
    return found


def files(line):
    out = subprocess.run(["git", "-C", doris, "ls-tree", "-r", "--name-only", f"origin/branch-{line}", "--",
                          "fe/fe-core/src/main/java", "fe/fe-common/src/main/java"], capture_output=True, text=True, check=True).stdout
    return [f.split("org/apache/doris/", 1)[-1] for f in out.splitlines()]


LIT = {line: literals(line) for line in LINES}
FILES = {line: files(line) for line in LINES}


class Source:
    """
    The FE code that owns a list or a table function: the list exists from the first line where a file matches
    [marker], and one of its keys from the first line after that where a file matching [evidence] names it in full.
    A literal elsewhere (another statement's key of the same name) is no evidence, and a key the owning code names
    only after other code did (code that moved) gets no release.
    """

    def __init__(self, marker, evidence=None):
        self.marker, self.evidence = re.compile(marker), re.compile(evidence or marker)

    def line_of(self, key):
        literal = key.removesuffix("*")
        floor = next((line for line in LINES if any(self.marker.search(f) for f in FILES[line])), None)
        if floor is None:
            return None
        for i, line in enumerate(LINES[LINES.index(floor):], LINES.index(floor)):
            if any(self.evidence.search(f) for f in LIT[line].get(literal, ())):
                # Named elsewhere before the owning code names it: code that moved, so no evidence of a release.
                return line if line == floor or not any(literal in LIT[earlier] for earlier in LINES[:i]) else None
        return None


def since(key, summary, source):
    """The release a page states for [key], or else the line its owning [source] first names it in; None when neither."""
    stated = re.search(r"[Ss]upported (?:since|from) (?:Doris |version )?(\d+\.\d+(?:\.\d+)?)", summary or "")
    return stated.group(1) if stated else source.line_of(key) if source else None


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
    for m in re.finditer(r"CREATE CATALOG(?:(?!```).)*?\)\s*;", text, re.S | re.I):
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
    # PropertyAnalyzer.analyzeStorageFormat: V1 is rejected as deprecated.
    "storage_format": ["V2", "V3", "default"],
    # PropertyAnalyzer.analyzeInvertedIndexFileStorageFormat.
    "inverted_index_storage_format": ["V1", "V2", "V3", "default"],
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
# http.header.xxx is the page's placeholder for http.header.<name>; json_paths a typo of jsonpaths on the local page.
RENAMES = {"property": None, "broker.property_key": "broker.*", "http.header.xxx": "http.header.*", "json_paths": None}


def entries(raw, source):
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
        out.append({"name": k, "summary": summary or None, "since": since(k, summary, source), "values": values or []})
    return out


lists = []


def add(title, context, path, raw, source, opener="PROPERTIES", clause=False, variant=None):
    lists.append({"title": title, "context": context, "opener": opener, "path": path, "clause": clause, "variant": variant, "keys": entries(raw, source)})


ct = lines_of("CREATE-TABLE")
# The FE code owning each list, paths from org/apache/doris/ (see Source).
TABLE = Source(r"common/util/PropertyAnalyzer\.java$", r"common/util/PropertyAnalyzer\.java$|/CreateTable[A-Za-z]*\.java$|catalog/TableProperty\.java$")
MV = Source(r"/CreateMTMV[A-Za-z]*\.java$", r"common/util/PropertyAnalyzer\.java$|^mtmv/|MTMV[A-Za-z]*\.java$")
JOB = Source(r"/StreamingJobProperties\.java$", r"job/extensions/insert/streaming/")
JOB_SOURCE = Source(r"job/cdc/DataSourceConfigKeys\.java$", r"job/cdc/")
ROUTINE_LOAD = Source(r"/CreateRoutineLoad(Stmt|Info)\.java$", r"^load/routineload/|/CreateRoutineLoad[A-Za-z]*\.java$")
EXPORT_CODE = r"/Export(Stmt|Command)\.java$|^load/ExportJob\.java$"
EXPORT = Source(EXPORT_CODE)
OUTFILE = Source(r"/OutFileClause\.java$", r"/OutFileClause\.java$|datasource/property/fileformat/")
CATALOG = Source(r"datasource/CatalogFactory\.java$", r"datasource/CatalogFactory\.java$|/CreateCatalog[A-Za-z]*\.java$|datasource/ExternalCatalog\.java$")
CATALOG_SOURCES = {
    "hms": Source(r"/HMSExternalCatalog\.java$", r"^datasource/hive/|^datasource/property/(metastore|constants)/[A-Za-z]*(HMS|Hive)"),
    "iceberg": Source(r"/IcebergExternalCatalog\.java$", r"^datasource/iceberg/|^datasource/property/(metastore|constants)/[A-Za-z]*Iceberg"),
    "paimon": Source(r"/PaimonExternalCatalog\.java$", r"^datasource/paimon/|^datasource/property/(metastore|constants)/[A-Za-z]*Paimon"),
    "jdbc": Source(r"/JdbcExternalCatalog\.java$", r"^datasource/jdbc/|^catalog/JdbcResource\.java$"),
    "es": Source(r"/EsExternalCatalog\.java$", r"^datasource/es/|^catalog/EsResource\.java$|^external/elasticsearch/"),
    "max_compute": Source(r"/MaxComputeExternalCatalog\.java$", r"^datasource/maxcompute/|MaxCompute[A-Za-z]*\.java$"),
    "doris": Source(r"/RemoteDorisExternalCatalog\.java$", r"^datasource/doris/"),
}
RESOURCE_CLASSES = {"spark": "Spark", "odbc_catalog": "OdbcCatalog", "s3": "S3", "jdbc": "Jdbc", "hdfs": "Hdfs", "hms": "HMS", "es": "Es"}

# Table keys PropertyAnalyzer (branch-4.1) reads that the 4.x CREATE TABLE page does not document.
UNDOCUMENTED_TABLE_KEYS = [
    ("storage_format", "The segment storage format: V2, or V3 (V2 with column metadata stored apart).", None),
    ("inverted_index_storage_format", "The storage format of the table's inverted index files; default takes the FE configuration's.", None),
    ("file_cache_ttl_seconds", "How long, in seconds, newly loaded data stays in the file cache; compute-storage decoupled mode only.", None),
]
add("CREATE TABLE properties", "CREATE [EXTERNAL] [TEMPORARY] TABLE", PAGES["CREATE-TABLE"] + "/",
    table(section(ct, r"^\*\*<table_property>\*\*", r"^## ")) + UNDOCUMENTED_TABLE_KEYS, TABLE)
table_keys = lists[-1]["keys"]
mv = lines_of("CREATE-ASYNC-MATERIALIZED-VIEW")
add("CREATE MATERIALIZED VIEW properties", "CREATE MATERIALIZED VIEW", PAGES["CREATE-ASYNC-MATERIALIZED-VIEW"] + "/",
    table(section(mv, r"^\*\*9\. `<table_property>`", r"^## ")), MV)

# The page: "Properties used by internal tables, most of which can be used by materialized views". CreateMTMVInfo
# (branch-4.1) builds the view as a Duplicate-model OLAP table and passes its other properties on to it, so it takes
# the table keys but those of another model, and rejects dynamic partition keys. Its own keys come first and win.
MV_EXCLUDED = {
    # Unique-model keys.
    "function_column.sequence_col", "function_column.sequence_type", "enable_unique_key_merge_on_write",
    "enable_mow_light_delete", "enable_unique_key_skip_bitmap_column",
    # Chooses the model of a table that names none; a view's model is set.
    "enable_duplicate_without_keys_by_default",
}


def version(v):
    return tuple(map(int, v.split(".")))


mv_keys = lists[-1]["keys"]
mv_floor = min((k["since"] for k in mv_keys if k["since"]), key=version)
for k in table_keys:
    if k["name"] in MV_EXCLUDED or k["name"].startswith("dynamic_partition.") or any(m["name"] == k["name"] for m in mv_keys):
        continue
    # Not before views existed, whenever the table took the key.
    mv_keys.append(dict(k, since=k["since"] and max(k["since"], mv_floor, key=version)))

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
add("CREATE JOB properties", "CREATE JOB", PAGES["CREATE-STREAMING-JOB"] + "/", groups[2], JOB)
add("CREATE JOB source properties", "CREATE JOB", PAGES["CREATE-STREAMING-JOB"] + "/", groups[0], JOB_SOURCE, opener="MYSQL|POSTGRES", clause=True)

rl = lines_of("CREATE-ROUTINE-LOAD")
rl_path = PAGES["CREATE-ROUTINE-LOAD"] + "/"
add("CREATE ROUTINE LOAD job properties", "CREATE ROUTINE LOAD", rl_path, numbered(section(rl, r"^\*\*4\. `<job_properties>`", r"^\*\*5\.")), ROUTINE_LOAD)
add("CREATE ROUTINE LOAD Kafka properties", "CREATE ROUTINE LOAD", rl_path,
    numbered(section(rl, r"^\*\*3\. `<data_source_properties>`", r"^## ")) +
    numbered(section(rl, r"^\*\*5\. Optional properties", r"^\*\*6\.")) +
    [("property.*", "A Kafka client property, passed on to librdkafka without the property. prefix.", None)],
    ROUTINE_LOAD, opener="KAFKA", clause=True)

ex = lines_of("EXPORT")
ex_path = PAGES["EXPORT"] + "/"
add("EXPORT properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^\*\*3\. `<properties>`", r"^\*\*4\.")), EXPORT)
add("EXPORT broker properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*BROKER", r"^- \*\*HDFS")),
    Source(EXPORT_CODE, EXPORT_CODE + r"|/BrokerDesc\.java$"), opener="BROKER", clause=True)
add("EXPORT HDFS properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*HDFS", r"^- \*\*S3")),
    Source(EXPORT_CODE, EXPORT_CODE + r"|^datasource/property/storage/HdfsProperties\.java$"), opener="HDFS", clause=True)
add("EXPORT S3 properties", "EXPORT TABLE", ex_path, bullets(section(ex, r"^- \*\*S3", r"^## ")),
    Source(EXPORT_CODE, EXPORT_CODE + r"|^datasource/property/(constants|storage)/S3Properties\.java$"), opener="S3", clause=True)

of = lines_of("OUTFILE")
add("OUTFILE properties", "INTO OUTFILE", PAGES["OUTFILE"] + "/",
    bullets(section(of, r"^\*\*Properties related to export file itself", r"^## Return")), OUTFILE, clause=True)

CATALOG_TYPES = {"hms": "cat-hive-catalog", "iceberg": "cat-iceberg-catalog", "paimon": "cat-paimon-catalog",
                 "jdbc": "cat-jdbc-catalog-overview", "es": "cat-es-catalog", "max_compute": "cat-maxcompute-catalog",
                 "doris": "cat-doris-catalog"}
add("CREATE CATALOG properties", "CREATE CATALOG", PAGES["CREATE-CATALOG"] + "/",
    [("type", "The kind of external data source the catalog connects.", None), ("resource", "A resource whose properties the catalog takes.", None)], CATALOG)
lists[-1]["keys"][0]["values"] = list(CATALOG_TYPES) + ["trino-connector"]
cc = page("CREATE-CATALOG", PAGES["CREATE-CATALOG"])
# The page's JDBC examples only: its other examples belong to other types.
jdbc_examples = example_keys("\n".join(m.group(0) for m in re.finditer(r"CREATE CATALOG(?:(?!```).)*?\)\s*;", cc, re.S | re.I)
                                       if re.search(r"[\"']type[\"']\s*=\s*[\"']jdbc[\"']", m.group(0))))
for catalog_type, name in CATALOG_TYPES.items():
    text = page(name, PAGES[name])
    # Only the ES page tabulates the catalog's own keys; the other pages' tables are cache modules and session variables.
    raw = (tables(text)[:10] if catalog_type == "es" else []) + example_keys(text) + (jdbc_examples if catalog_type == "jdbc" else [])
    add(f"CREATE CATALOG properties ({catalog_type})", "CREATE CATALOG", PAGES[name] + "/", raw, CATALOG_SOURCES[catalog_type],
        variant=["type", catalog_type])

rs = lines_of("CREATE-RESOURCE")
RESOURCE_TYPES = [("spark", r"^\*\*1\.", r"^\*\*2\."), ("odbc_catalog", r"^\*\*2\.", r"^\*\*3\."), ("s3", r"^\*\*3\.", r"^\*\*4\."),
                  ("jdbc", r"^\*\*4\.", r"^\*\*5\."), ("hdfs", r"^\*\*5\.", r"^\*\*6\."), ("hms", r"^\*\*6\.", r"^\*\*7\."), ("es", r"^\*\*7\.", r"^## ")]
rs_path = PAGES["CREATE-RESOURCE"] + "/"
add("CREATE RESOURCE properties", "CREATE [EXTERNAL] RESOURCE", rs_path, [("type", "The kind of resource.", None)],
    Source(r"^catalog/Resource\.java$"))
lists[-1]["keys"][0]["values"] = [t for t, _, _ in RESOURCE_TYPES]
for resource_type, start, end in RESOURCE_TYPES:
    add(f"CREATE RESOURCE properties ({resource_type})", "CREATE [EXTERNAL] RESOURCE", rs_path,
        bullets(section(rs, start, end)), Source(rf"^catalog/{RESOURCE_CLASSES[resource_type]}Resource\.java$"), variant=["type", resource_type])

# Summaries and since of the table functions' keys (their keys and values are in DorisTableFunctions.kt), each
# function's since from its own class, and for the file readers their shared base and file-format code.
FILE_READER = r"|/ExternalFileTableValuedFunction\.java$|common/util/FileFormatConstants\.java$|datasource/property/fileformat/"
TVF_CLASSES = {
    "s3": ("S3TableValuedFunction", FILE_READER + r"|^datasource/property/(constants|storage)/S3Properties\.java$"),
    "hdfs": ("HdfsTableValuedFunction", FILE_READER), "local": ("LocalTableValuedFunction", FILE_READER),
    "http": ("HttpTableValuedFunction", FILE_READER), "file": ("FileTableValuedFunction", FILE_READER),
    "http_stream": ("HttpStreamTableValuedFunction", FILE_READER), "group_commit": ("GroupCommitTableValuedFunction", ""),
    "cdc_stream": ("CdcStreamTableValuedFunction", r"|job/cdc/"), "query": ("QueryTableValueFunction", ""),
    "partition_values": ("PartitionValuesTableValuedFunction", ""), "hudi_meta": ("HudiTableValuedFunction", ""),
    "parquet_meta": ("ParquetMetadataTableValuedFunction", FILE_READER), "parquet_file_metadata": ("ParquetMetadataTableValuedFunction", FILE_READER),
    "parquet_kv_metadata": ("ParquetMetadataTableValuedFunction", FILE_READER), "parquet_bloom_probe": ("ParquetMetadataTableValuedFunction", FILE_READER),
    "jobs": ("JobsTableValuedFunction", ""), "tasks": ("TasksTableValuedFunction", ""), "mv_infos": ("MvInfosTableValuedFunction", ""),
    "partitions": ("PartitionsTableValuedFunction", ""), "numbers": ("NumbersTableValuedFunction", ""),
    "backends": ("BackendsTableValuedFunction", ""), "frontends": ("FrontendsTableValuedFunction", ""),
    "frontends_disks": ("FrontendsDisksTableValuedFunction", ""), "catalogs": ("CatalogsTableValuedFunction", ""),
}


def tvf_source(function):
    if function not in TVF_CLASSES:
        return None
    cls, extra = TVF_CLASSES[function]
    own = rf"^tablefunction/{cls}\.java$"
    return Source(own, own + extra)


tvf = {}
for function, slug in TVF_PAGES.items():
    source = tvf_source(function)
    keys = {}
    for k, summary, _ in tables(page("tvf-" + slug, TVF + slug)):
        if k in RENAMES:
            if RENAMES[k] is None:
                continue
            k = RENAMES[k]
        if not keys.get(k, {}).get("summary"):
            keys[k] = {"summary": summary or None, "since": since(k, summary, source)}
    tvf[function] = {"path": TVF + slug + "/", "keys": keys}
handwritten = open(os.path.join(os.path.dirname(__file__), "..", "brikk-sql-metadata", "src", "dev.brikk.house.sql.metadata",
                                "DorisTableFunctions.kt")).read()
handwritten_keys = sorted(set(re.findall(r'PropertyDef\("([^"]+)"', handwritten)))
handwritten_since = {}
for function in sorted(set(re.findall(r'TableFunctionInfo\(\s*"([a-z_0-9]+)"', handwritten)) | set(TVF_PAGES)):
    source = tvf_source(function)
    found = {k: v for k in handwritten_keys if source and (v := source.line_of(k))}
    if found:
        handwritten_since[function] = found

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
out.append("/** Per table function, the since of the keys DorisTableFunctions.kt lists, from that function's FE code. */")
out.append("internal val DORIS_TABLE_FUNCTION_KEY_SINCE: Map<String, Map<String, String>> = mapOf(")
for function, found in handwritten_since.items():
    out.append(f"    {kt(function)} to mapOf(" + ", ".join(f"{kt(k)} to {kt(v)}" for k, v in found.items()) + "),")
out.append(")")
out.append("")
target = os.path.join(os.path.dirname(__file__), "..", "brikk-sql-metadata", "src", "dev.brikk.house.sql.metadata", "GeneratedDorisProperties.kt")
open(target, "w").write("\n".join(out))
print(f"{len(lists)} lists, {sum(len(l['keys']) for l in lists)} keys; {len(tvf)} table functions")
