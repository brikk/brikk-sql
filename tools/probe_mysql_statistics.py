"""SG29-008 finite native controls. Only task-private, isolated Docker containers.

Uses the existing Java public-facade bridge. No service provisioning or user data.
"""
import argparse
import base64
import json
import math
import subprocess
from pathlib import Path


def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, timeout=60, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    engines = {}
    for engine in ("mysql", "starrocks"):
        name = "brikk-sg008-" + engine
        inspected = run(["docker", "inspect", name])
        assert inspected.returncode == 0, inspected.stderr
        info = json.loads(inspected.stdout)[0]
        host = info["HostConfig"]
        assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings")
        engines[engine] = {"container": name, "image": info["Config"]["Image"]}

    def query(engine, sql, metadata=False):
        command = ["docker", "exec", engines[engine]["container"], "mysql", "-uroot"]
        if engine == "starrocks":
            command += ["-h127.0.0.1", "-P9030"]
        command += ["--column-type-info", "-vvv"] if metadata else ["--batch", "--raw", "--skip-column-names"]
        r = run(command + ["-e", sql])
        return {"returncode": r.returncode, "stdout": r.stdout, "stderr": r.stderr}

    for engine in engines:
        engines[engine]["version"] = query(engine, "SELECT VERSION(),@@sql_mode" if engine == "mysql" else "SELECT current_version()")
        assert engines[engine]["version"]["returncode"] == 0

    inputs = [
        ("empty", "SELECT CAST(NULL AS DOUBLE) x WHERE FALSE", [None] * 4),
        ("null", "SELECT CAST(NULL AS DOUBLE) x", [None] * 4),
        ("singleton", "SELECT CAST(2 AS DOUBLE) x", [0, None, 0, None]),
        ("pair", "SELECT CAST(0 AS DOUBLE) x UNION ALL SELECT CAST(2 AS DOUBLE)", [1, math.sqrt(2), 1, 2]),
    ]
    requests = []
    for label, input_sql, expected in inputs:
        for source in ("mysql", "starrocks", "postgres", "duckdb"):
            functions = "STDDEV(x),STDDEV_SAMP(x),VARIANCE(x),VAR_SAMP(x)" if source in ("mysql", "starrocks") else "STDDEV_POP(x),STDDEV(x),VAR_POP(x),VARIANCE(x)"
            sql = f"SELECT {functions} FROM ({input_sql}) t"
            for target in engines:
                requests.append((label, source, target, sql, [expected]))
    input_sql = "SELECT 1 g,1 n,CAST(0 AS DOUBLE) x UNION ALL SELECT 1,2,CAST(2 AS DOUBLE) UNION ALL SELECT 2,3,CAST(NULL AS DOUBLE)"
    # StarRocks 4.1.4 rejects named WINDOW references. Qualify the aggregate change
    # using authored inline frames, not a new named-window lowering in this patch.
    window_functions = ",".join(f"{name}(x) OVER (PARTITION BY g ORDER BY n ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)" for name in ("STDDEV", "STDDEV_SAMP", "VARIANCE", "VAR_SAMP"))
    for target in engines:
        requests += [
            ("groups", "mysql", target, f"SELECT STDDEV(x),STDDEV_SAMP(x),VARIANCE(x),VAR_SAMP(x) FROM ({input_sql}) t GROUP BY g ORDER BY g", [[1, math.sqrt(2), 1, 2], [None] * 4]),
            ("window", "mysql", target, f"SELECT {window_functions} FROM ({input_sql}) t ORDER BY n", [[0, None, 0, None], [1, math.sqrt(2), 1, 2], [None] * 4]),
        ]
    payload = "".join(source + "\t" + target + "\t" + base64.b64encode(sql.encode()).decode() + "\n" for _, source, target, sql, _ in requests)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(requests), bridge.stdout
    observations = []
    for request, line in zip(requests, lines, strict=True):
        label, source, target, sql, expected = request
        fields = line.split("\t")
        assert fields[0] == "OK", line
        ast, preview, strict, executable = [base64.b64decode(x).decode() for x in fields[1:]]
        assert strict == "accepted", (sql, strict)
        result = query(target, executable)
        assert result["returncode"] == 0, (executable, result)
        rows = [[None if x == "NULL" else float(x) for x in row.split("\t")] for row in result["stdout"].splitlines()]
        assert len(rows) == len(expected), (request, rows)
        for row, wanted in zip(rows, expected, strict=True):
            assert len(row) == len(wanted)
            for value, reference in zip(row, wanted, strict=True):
                assert value is None if reference is None else value is not None and math.isclose(value, reference, rel_tol=1e-12, abs_tol=1e-12), (request, rows)
        observations.append(dict(case=label, source=source, target=target, sql=sql, ast=ast, preview=preview, executable=executable, result=result, expected=expected))
    controls = []
    for engine in engines:
        for alias in ("STD", "STDDEV", "VARIANCE"):
            result = query(engine, f"SELECT {alias}(x) FROM (SELECT CAST(2 AS DOUBLE) x) t")
            assert result["returncode"] == 0 and float(result["stdout"].strip()) == 0
            controls.append(dict(engine=engine, alias=alias, result=result))
        metadata = query(engine, "SELECT STD(x),STDDEV_SAMP(x),VARIANCE(x),VAR_SAMP(x) FROM (SELECT CAST(0 AS DOUBLE) x UNION ALL SELECT CAST(2 AS DOUBLE)) t", True)
        assert metadata["returncode"] == 0 and metadata["stdout"].count("Type:       DOUBLE") == 4
        assert "NOT_NULL" not in metadata["stdout"]
        engines[engine]["metadata"] = metadata
        rejection = query(engine, "SELECT STDDEV_SAMP(DISTINCT x) FROM (SELECT CAST(2 AS DOUBLE) x) t")
        assert rejection["returncode"] != 0 and "1064" in rejection["stderr"]
        engines[engine]["distinct_syntax_rejection"] = rejection
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Finite DOUBLE value/type controls only; no general coercion, DECIMAL typing or floating-point algorithm equivalence claim.", engines=engines, observations=observations, alias_controls=controls), indent=2) + "\n")
    print(f"Captured {len(observations)} generated native controls and {len(controls)} alias controls")


if __name__ == "__main__":
    main()
