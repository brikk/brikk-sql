#!/usr/bin/env python3
"""Assess finite lambda candidates using isolated, exact-pin disposable engines.

No production converter, certification ledger, host port, mounted data or user
database is used. Both-error is never called equivalent. All created resources
have UUID names and are removed in finally; startup and queries have deadlines.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import time
import uuid

from probe_clickhouse_2026 import DATA, query as clickhouse_query

ROOT = pathlib.Path(__file__).resolve().parents[1]
TRINO = "trinodb/trino@sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f"
FE = "apache/doris@sha256:e67f66e621d2d77be7d210b8cab4b1e62e89ff4a0bdaf894bf155fdb1fe441dc"
BE = "apache/doris@sha256:3f7ff231e3a8b586b0556391f020089b666f786525ad90736bccea31761759bb"


def command(args, timeout=60):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


def sql(engine, container, text):
    client = ["trino", "--output-format=TSV", "--execute"] if engine == "trino" else [
        "mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-N", "-B", "--raw", "-e"]
    return command(["docker", "exec", container, *client, text])


def ready(engine, container, text, accept):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        observed = sql(engine, container, text)
        if observed["returncode"] == 0 and accept(observed["stdout"]):
            return observed
        time.sleep(1)  # Bounded service startup, not asynchronous tool-job polling.
    raise RuntimeError(f"{engine} startup failed: {observed}")


def inspect_image(container):
    info = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
    image = json.loads(subprocess.check_output(["docker", "image", "inspect", info["Image"]]))[0]
    return {"requested_image": info["Config"]["Image"], "image_id": info["Image"], "repo_digests": image["RepoDigests"]}


def observation(raw, engine):
    if raw["returncode"] is None or re.search(r"Error response from daemon|Can't connect|Connection refused|No alive backends|No backend available|No nodes available|Host deadline", raw["stderr"], re.I):
        raise RuntimeError(raw)
    if raw["returncode"] != 0:
        return {"status": "error", "raw": raw}
    text = raw["stdout"].strip()
    # Trino's TSV is quoted like CSV. Unquote its single string cell before JSON decoding.
    if engine == "trino":
        import csv
        text = next(csv.reader([text], delimiter="\t"))[0]
    value = json.loads(text)
    if not isinstance(value, list) or any(x is not None and not isinstance(x, str) for x in value):
        raise ValueError(f"Expected only an ordered string/NULL array: {raw}")
    return {"status": "value", "raw": raw, "value": value}


def comparison(source, target):
    if source["status"] == target["status"] == "error":
        return "both-error-not-equivalence"
    if source["status"] != target["status"]:
        return "different-outcome"
    return "same-finite-value" if source["value"] == target["value"] else "different-value"


def capture(fixture, duckdb, output):
    original_bytes = fixture.read_bytes()
    cases = json.loads(original_bytes)["cases"]
    embedded = json.loads(duckdb.read_text())
    assert embedded["version"] == "v2.0.0" and embedded["fixture_sha256"] == hashlib.sha256(original_bytes).hexdigest()
    names = {part: "brikk-chsem010-" + part + "-" + uuid.uuid4().hex[:12] for part in ("trino", "fe", "be")}
    created = []

    def start(part, image, memory, script, network="none"):
        name = names[part]
        result = command(["docker", "run", "-d", "--rm", "--pull", "never", "--name", name, "--network", network,
                          "--cpus", "4", "--memory", memory, "--pids-limit", "4096", "--user", "root", "--entrypoint", "bash",
                          image, "-lc", "printf '127.0.0.1 %s\\n' \"$(hostname)\" >> /etc/hosts\n" + script])
        if result["returncode"]:
            raise RuntimeError(result)
        created.append(name)

    try:
        start("trino", TRINO, "4g", "sed -i 's/-XX:InitialRAMPercentage=80/-Xms512m/;s/-XX:MaxRAMPercentage=80/-Xmx1536m/' /etc/trino/jvm.config\n"
              "echo '-XX:-UseContainerSupport' >> /etc/trino/jvm.config\n"
              "exec /usr/lib/trino/bin/launcher run --etc-dir /etc/trino")
        trino_version = ready("trino", names["trino"], "SELECT version()", lambda text: text.strip().strip('"') == "484")
        start("fe", FE, "3g", "sed -i 's/-Xmx8192m/-Xmx1536m -XX:-UseContainerSupport/;s/-Xms8192m/-Xms1536m/' /opt/apache-doris/fe/conf/fe.conf\n"
              "echo 'priority_networks = 127.0.0.1/32' >> /opt/apache-doris/fe/conf/fe.conf\n"
              "/opt/apache-doris/fe/bin/start_fe.sh --daemon\nexec tail -f /dev/null")
        ready("doris", names["fe"], "SHOW FRONTENDS", lambda text: "doris-4.1.5-" in text)
        start("be", BE, "6g", "sed -i 's/-Xmx2048m/-Xmx512m -XX:-UseContainerSupport/' /opt/apache-doris/be/conf/be.conf\n"
              "printf '\npriority_networks = 127.0.0.1/32\nenable_file_cache = false\nmem_limit = 70%%\n' >> /opt/apache-doris/be/conf/be.conf\n"
              "export SKIP_CHECK_ULIMIT=true\n/opt/apache-doris/be/bin/start_be.sh --daemon\nexec tail -f /dev/null", "container:" + names["fe"])
        registered = sql("doris", names["fe"], "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050'")
        assert registered["returncode"] == 0, registered
        ready("doris", names["fe"], "SELECT number FROM numbers('number'='1')", lambda text: text.strip() == "0")
        versions = {"trino": trino_version, "frontend": sql("doris", names["fe"], "SHOW FRONTENDS"),
                    "backend": sql("doris", names["fe"], "SHOW BACKENDS")}
        for part in ("frontend", "backend"):
            assert versions[part]["returncode"] == 0 and "doris-4.1.5-" in versions[part]["stdout"], versions
        images = {part: inspect_image(names[part]) for part in names}
        pins = json.loads((DATA / "engines.json").read_text())["engines"]
        native = {}
        for pin in pins:
            version = clickhouse_query(pin, "SELECT version()")
            assert version["returncode"] == 0 and version["stdout"] == pin["version"] + "\n"
            native[pin["version"]] = {"engine": pin, "version_query": version, "cases": []}
        rows = []
        duck_rows = {r["id"]: r for r in embedded["cases"]}
        for case in cases:
            source = None
            for pin in pins:
                pair = {}
                for kind in ("source", "explicit"):
                    query = "SELECT toJSONString(v) FROM (" + case[kind] + ") AS probe"
                    pair[kind] = {"query": query, **observation(clickhouse_query(pin, query), "clickhouse")}
                native[pin["version"]]["cases"].append({"id": case["id"], **pair})
                if pin["version"] == "26.10.2.103":
                    source = pair["source"]
                    assert comparison(source, pair["explicit"]) in ("same-finite-value", "both-error-not-equivalence"), case["id"]
                    if source["status"] == "error":
                        assert re.search(r"Code: (\d+)\.", source["raw"]["stderr"])[1] == re.search(r"Code: (\d+)\.", pair["explicit"]["raw"]["stderr"])[1]
            targets = {"duckdb": duck_rows[case["id"]]}
            for engine, container in (("trino", names["trino"]), ("doris", names["fe"])):
                candidate = case["targets"][engine]
                serialized = "json_format(CAST(v AS JSON))" if engine == "trino" else "TO_JSON(v)"
                query = f"SELECT {serialized} FROM ({candidate}) AS probe"
                targets[engine] = {"candidate": candidate, "query": query, **observation(sql(engine, container, query), engine)}
            rows.append({"id": case["id"], "targets": targets,
                         "comparison": {engine: comparison(source, result) for engine, result in targets.items()}})
        report = {"captured_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "fixture_sha256": hashlib.sha256(original_bytes).hexdigest(),
                  "duckdb_sha256": hashlib.sha256(duckdb.read_bytes()).hexdigest(), "images": images, "versions": versions,
                  "clickhouse": native, "cases": rows, "automatic_lowering_enabled": False}
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        for row in rows:
            print(row["id"], row["comparison"])
    except Exception as failure:
        details = {"error": str(failure), "containers": {}}
        if names["fe"] in created:
            details["backends"] = sql("doris", names["fe"], "SHOW BACKENDS")
        for name in created:
            details["containers"][name] = {"logs": command(["docker", "logs", "--tail", "60", name]),
                "engine_logs": command(["docker", "exec", name, "bash", "-lc", "tail -n 40 /opt/apache-doris/fe/log/fe.out /opt/apache-doris/fe/log/fe.log /opt/apache-doris/be/log/be.out /opt/apache-doris/be/log/be.WARNING.log* 2>/dev/null"])}
        output.parent.mkdir(parents=True, exist_ok=True)
        output.with_name(output.stem + "-infrastructure-failure.json").write_text(json.dumps(details, indent=2) + "\n")
        raise
    finally:
        for name in reversed(created):
            command(["docker", "rm", "-f", name], timeout=30)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=pathlib.Path, default=ROOT / "vendor/data/clickhouse-semantic/lowering-cases.json")
    parser.add_argument("--duckdb", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    capture(args.cases, args.duckdb, args.output)
