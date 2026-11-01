#!/usr/bin/env python3
"""Bounded literal-only, typed DuckDB/Trino/Doris/ClickHouse comparisons.

Uses operator-supplied local test containers and a pinned JDBC jar. Never starts engines,
installs extensions, queries providers, mutates a database or rewrites historical hazards.
"""
import argparse
import base64
import datetime
import hashlib
import itertools
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
JDBC_SHA256="5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186"
CASES=[
 {"id":"json-duplicate-key","family":"json","sql":{
  "duckdb":"json_extract('{\"a\":1,\"a\":2}','$.a')","trino":"json_extract('{\"a\":1,\"a\":2}','$.a')",
  "doris":"json_extract('{\"a\":1,\"a\":2}','$.a')","clickhouse":"JSONExtractRaw('{\"a\":1,\"a\":2}','a')"}},
 {"id":"json-null","family":"json","sql":{
  "duckdb":"json_extract('{\"a\":null}','$.a')","trino":"json_extract('{\"a\":null}','$.a')",
  "doris":"json_extract('{\"a\":null}','$.a')","clickhouse":"JSONExtractRaw('{\"a\":null}','a')"}},
 {"id":"json-scalar-unicode","family":"json","sql":{
  "duckdb":"json_extract_string('{\"a\":\"é\"}','$.a')","trino":"json_extract_scalar('{\"a\":\"é\"}','$.a')",
  "doris":"get_json_string('{\"a\":\"é\"}','$.a')","clickhouse":"JSONExtractString('{\"a\":\"é\"}','a')"}},
 {"id":"variant-type-boundary","family":"variant","sql":{e:"CAST('{\"a\":2}' AS VARIANT)" for e in ["duckdb","trino","doris","clickhouse"]}},
 {"id":"month-boundaries","family":"temporal","sql":{
  "duckdb":"date_diff('month',DATE '2026-01-31',DATE '2026-02-01')","trino":"date_diff('month',DATE '2026-01-31',DATE '2026-02-01')",
  "doris":"months_diff(CAST('2026-02-01' AS DATE),CAST('2026-01-31' AS DATE))","clickhouse":"dateDiff('month',toDate('2026-01-31'),toDate('2026-02-01'))"}},
 {"id":"submicrosecond-text","family":"temporal","sql":{
  "duckdb":"TIMESTAMP_NS '2024-01-01 00:00:00.123456789'","trino":"TIMESTAMP '2024-01-01 00:00:00.123456789'",
  "doris":"CAST('2024-01-01 00:00:00.123456789' AS DATETIMEV2(6))","clickhouse":"toDateTime64('2024-01-01 00:00:00.123456789',9,'UTC')"}},
 {"id":"regex-partial","family":"regex","sql":{
  "duckdb":"'abc' ~ 'b'","trino":"regexp_like('abc','b')","doris":"'abc' REGEXP 'b'","clickhouse":"CAST(match('abc','b') AS Bool)"}},
 {"id":"regex-lookbehind","family":"regex","sql":{
  "duckdb":"regexp_matches('ab','(?<=a)b')","trino":"regexp_like('ab','(?<=a)b')",
  "doris":"'ab' REGEXP '(?<=a)b'","clickhouse":"CAST(match('ab','(?<=a)b') AS Bool)"}},
 {"id":"regex-replace-default","family":"regex","sql":{
  "duckdb":"regexp_replace('aba','a','x')","trino":"regexp_replace('aba','a','x')",
  "doris":"regexp_replace('aba','a','x')","clickhouse":"replaceRegexpAll('aba','a','x')"}},
 {"id":"integer-zero","family":"numeric","sql":{
  "duckdb":"CAST(1 AS BIGINT)//CAST(0 AS BIGINT)","trino":"BIGINT '1'/BIGINT '0'",
  "doris":"CAST(1 AS BIGINT) DIV CAST(0 AS BIGINT)","clickhouse":"intDiv(toInt64(1),toInt64(0))"}},
 {"id":"floating-modulo-zero","family":"numeric","sql":{
  "duckdb":"CAST(1 AS DOUBLE)%CAST(0 AS DOUBLE)","trino":"mod(DOUBLE '1',DOUBLE '0')",
  "doris":"CAST(1 AS DOUBLE)%CAST(0 AS DOUBLE)","clickhouse":"modulo(toFloat64(1),toFloat64(0))"}},
]
FINDINGS=[
 {"family":"json","verdict":"conditional-unqualified","cases":["json-duplicate-key","json-null","json-scalar-unicode"],
  "reason":"Only the explicit typed/string projections are observed. JSON null is not SQL NULL; CAST to a string can produce SQL NULL while the original JSON value is non-NULL. No general extraction identity follows."},
 {"family":"variant","verdict":"unqualified-embedded-shape","cases":["variant-type-boundary"],
  "reason":"Trino 484 accepts VARIANT in this literal probe. That does not qualify Trino 483, matching embedded VARIANT shapes, a generic JSON substitute or a cross-target lowering."},
 {"family":"temporal","verdict":"no-general-equivalence","cases":["month-boundaries","submicrosecond-text"],
  "reason":"The month-boundary candidate returns one in DuckDB/ClickHouse and zero in Trino/Doris. Doris rounds the selected DATETIMEV2(6) value while nanosecond-capable candidates retain nine digits. No unqualified DATE_DIFF/precision mapping is admitted."},
 {"family":"regex","verdict":"conditional-unqualified","cases":["regex-partial","regex-lookbehind","regex-replace-default"],
  "reason":"Partial literal matches agree in the selected projections, but lookbehind is accepted only by the selected Trino oracle and default replacement changes first versus all matches. No general regex or REGEXP_COUNT qualification."},
 {"family":"numeric","verdict":"no-general-equivalence","cases":["integer-zero","floating-modulo-zero"],
  "reason":"Default zero arithmetic distinguishes errors, SQL NULL and NaN. Two errors are not equivalent successful results; no error-to-NULL emulation or setting mutation is inferred."},
]


def query(engine,expression):
    if engine=="clickhouse":return f"SELECT toTypeName(({expression})) AS sql_type,toString(({expression})) AS value,isNull(({expression})) AS sql_null FORMAT JSONEachRow"
    if engine=="doris":return f"SELECT ({expression}) AS native_value,HEX(CAST(({expression}) AS STRING)) AS value_hex,(({expression}) IS NULL) AS sql_null"
    return f"SELECT typeof(({expression})) AS sql_type,CAST(({expression}) AS VARCHAR) AS value,(({expression}) IS NULL) AS sql_null"


def normalized(row):
    if row["status"]!="ok":return None
    t=row["sql_type"].upper()
    aliases={"VARCHAR":"TEXT","STRING":"TEXT","TEXT":"TEXT","BOOLEAN":"BOOL","BOOL":"BOOL",
             "BIGINT":"INT64","INT64":"INT64","INTEGER":"INT32","INT":"INT32","INT32":"INT32",
             "DOUBLE":"FLOAT64","FLOAT64":"FLOAT64","JSONB":"JSON","JSON":"JSON"}
    typ=aliases.get(t,t)
    value=row["value"]
    if value is not None and typ=="BOOL" and value.lower() in ("true","false","1","0"):
        value="true" if value.lower() in ("true","1") else "false"
    if value is not None and typ=="FLOAT64" and value.lower() in ("nan","-nan","+nan"):value="NaN"
    return typ,row["sql_null"],value


def comparisons(rows):
    out=[]
    for case in CASES:
        for a,b in itertools.combinations(["duckdb","trino","doris","clickhouse"],2):
            x=rows[a][case["id"]];y=rows[b][case["id"]]
            nx,ny=normalized(x),normalized(y)
            verdict="unqualified-error-or-unavailable" if nx is None or ny is None else "unqualified-logical-type" if "doris" in (a,b) else "same-observed" if nx==ny else "typed-or-representation-difference"
            out.append({"case":case["id"],"engines":[a,b],"verdict":verdict,
                        "projection_equal":None if nx is None or ny is None else x["value"]==y["value"] and x["sql_null"]==y["sql_null"]})
    return out


def docker_rows(engine,spec):
    rows={}
    for case in CASES:
        sql=query(engine,case["sql"][engine])
        command={"trino":["trino","--server","http://localhost:8080","--execute",sql,"--output-format","JSON"],
                 "clickhouse":["clickhouse-client","--query",sql],
                 "doris":["mysql","-h127.0.0.1","-P9030","-uroot","--default-character-set=utf8mb4","--column-type-info","-vvv","-N","-B","-e",sql]}[engine]
        try:
            p=subprocess.run(["docker","exec",spec["name"],*command],capture_output=True,text=True,timeout=30)
            result={"sql":sql,"status":"ok","stdout":p.stdout,"stderr":p.stderr}
            if p.returncode:
                detail=p.stderr or p.stdout
                unavailable=re.search(r"No alive backends|No backend available|Connection refused|server is still initializing|container .* not running|Cannot connect to",detail,re.I)
                result.update(status="engine_error" if not unavailable and re.search(r"ERROR|DB::Exception|Query .* failed",detail,re.I) else "unavailable",error=detail)
            elif engine=="doris":
                header=re.search(r"Field\s+1:.*?\n(.*?)\nField\s+2:",p.stdout,re.S).group(1)
                metadata=dict(re.findall(r"^(\w+):\s*([^\n]*)",header,re.M))
                line=re.search(r"^\|.*\|\s*([0-9A-F]*|NULL)\s*\|\s*([01])\s*\|$",p.stdout,re.M)
                value,null=line.groups()
                result.update(sql_type="MYSQL_WIRE:"+metadata["Type"].strip(),value=None if value=="NULL" else bytes.fromhex(value).decode(),sql_null=null=="1",
                              type_evidence="protocol-only; exact logical type unavailable",wire_metadata=metadata)
            else:
                values=json.loads(p.stdout.strip());result.update(sql_type=values["sql_type"],value=values["value"],sql_null=bool(values["sql_null"]))
        except (subprocess.TimeoutExpired,ValueError,KeyError,AttributeError) as e:result={"sql":sql,"status":"unavailable","error":type(e).__name__+": "+str(e)}
        rows[case["id"]]=result
    return rows


def duckdb_rows(jar):
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=JDBC_SHA256:raise ValueError("Unreviewed DuckDB JDBC bytes")
    with tempfile.TemporaryDirectory(prefix="semantic-probe-",dir=ROOT/"build") as scratch:
        inp=Path(scratch)/"input.tsv"
        inp.write_text("".join(c["id"]+"\t"+base64.b64encode(query("duckdb",c["sql"]["duckdb"]).encode()).decode()+"\n" for c in CASES))
        p=subprocess.run(["java","--enable-native-access=ALL-UNNAMED","-cp",str(jar),str(ROOT/"brikk-sql-verify-duckdb/testResources/NativeSemanticProbe.java"),str(inp)],capture_output=True,text=True,timeout=90,check=True)
    def decode(s):return None if s=="-" else base64.b64decode(s).decode()
    lines=[line.split("\t") for line in p.stdout.splitlines()]
    assert lines[0][0]=="ENGINE" and [decode(x) for x in lines[0][1:]]==["v2.0.0","d54cbbe3df"]
    rows={}
    settings=dict(zip(["timezone","error_on_division_by_zero","regex_match_operator_semantics"],[decode(x) for x in lines[1][1:]]))
    for identifier,status,typ,value,null in lines[2:]:
        rows[identifier]={"status":status,"sql_type":decode(typ),"value":decode(value),"sql_null":decode(null)=="true"} if status=="ok" else {"status":status,"error":decode(value),"sql_state":decode(typ)}
    assert set(rows)=={c["id"] for c in CASES}
    return rows,settings


def profile(engine,spec):
    if engine=="trino":command=["trino","--server","http://localhost:8080","--execute","SELECT version() AS version,current_timezone() AS timezone","--output-format","JSON"]
    elif engine=="clickhouse":command=["clickhouse-client","--query","SELECT version() AS version,timezone() AS timezone,getSetting('short_circuit_function_evaluation') AS short_circuit FORMAT JSONEachRow"]
    else:command=["mysql","-h127.0.0.1","-P9030","-uroot","-N","-B","-e","SELECT @@version_comment,@@time_zone,@@sql_mode,version()"]
    p=subprocess.run(["docker","exec",spec["name"],*command],capture_output=True,text=True,timeout=30,check=True)
    if engine=="doris":
        version,timezone,mode,compat=p.stdout.rstrip("\n").split("\t");return {"version":version,"mysql_compatibility_version":compat,"timezone":timezone,"sql_mode":mode}
    return json.loads(p.stdout)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--oracles",type=Path);p.add_argument("--duckdb-jdbc",type=Path);p.add_argument("--output",type=Path,required=True);p.add_argument("--replay",type=Path)
    args=p.parse_args()
    if args.replay:
        report=json.loads(args.replay.read_text())
        assert report["cases"]==CASES,"Changed candidates need a fresh reviewed capture"
        assert report["comparisons"]==comparisons(report["rows"]),"Changed comparisons need review"
        assert report["findings"]==FINDINGS
        assert not any(r["status"]=="unavailable" for v in report["rows"].values() for r in v.values())
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
        print("Replayed",len(report["comparisons"]),"bounded comparisons; no engine or registry changes")
        return
    if not args.oracles or not args.duckdb_jdbc:p.error("Live capture requires --oracles and --duckdb-jdbc")
    oracles=json.loads(args.oracles.read_text())
    for engine in ("trino","doris","clickhouse"):
        if not oracles["engines"][engine]["ready"]:raise ValueError("Oracle unavailable: "+engine)
    native,native_settings=duckdb_rows(args.duckdb_jdbc)
    rows={"duckdb":native}
    profiles={e:profile(e,oracles["engines"][e]) for e in ("trino","doris","clickhouse")}
    assert profiles["trino"]["version"]=="484"
    assert "4.1.5" in profiles["doris"]["version"]
    assert profiles["clickhouse"]["version"]=="26.10.2.103"
    for engine in ("trino","doris","clickhouse"):rows[engine]=docker_rows(engine,oracles["engines"][engine])
    report={"captured_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"scope":"Eleven hand-owned literal candidates: top-level native types, SQL-NULL flags and explicit native string projections. Projection equality is not embedded VARIANT/JSON equality, general equivalence, generator/pushdown or Trino 483 qualification.",
            "duckdb":{"version":"2.0.0","source":"d54cbbe3df","jdbc_sha256":JDBC_SHA256,"external_access":False,"autoload":False,"autoinstall":False},
            "native_settings":native_settings,"profiles":profiles,
            "function_gap_scope":"Selected candidate-call observations only; no general name/semantic equivalence or global gap-registry rewrite.",
            "capture_tool_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "java_probe_sha256":hashlib.sha256((ROOT/"brikk-sql-verify-duckdb/testResources/NativeSemanticProbe.java").read_bytes()).hexdigest(),
            "oracles":{e:{k:v for k,v in s.items() if k!="last_error"} for e,s in oracles["engines"].items()},"cases":CASES,"rows":rows,"comparisons":comparisons(rows),"findings":FINDINGS}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({e:{s:sum(r["status"]==s for r in v.values()) for s in ("ok","engine_error","unavailable")} for e,v in rows.items()},indent=2))
    if any(r["status"]=="unavailable" for v in rows.values() for r in v.values()):raise SystemExit("Transport/unavailable results require investigation, not a hazard verdict")


if __name__=="__main__":main()
