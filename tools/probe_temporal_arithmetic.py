"""SG29-009: compare MySQL TIMESTAMPADD with actual generated DATE_ADD SQL.

Only the private brikk-sg009-mysql container is allowed. This provisions nothing.
"""
import argparse
import base64
import json
import re
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
    container = "brikk-sg009-mysql"
    inspected = run(["docker", "inspect", container])
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    host = info["HostConfig"]
    assert host["NetworkMode"] == "none" and not host.get("Binds") and not host.get("PortBindings")

    def query(sql, metadata=False):
        flags = ["--column-type-info", "-vvv"] if metadata else ["--batch", "--raw", "--skip-column-names"]
        result = run(["docker", "exec", container, "mysql", "-uroot", *flags, "-e", sql])
        assert result.returncode == 0, (sql, result.stderr)
        return dict(stdout=result.stdout, stderr=result.stderr, returncode=result.returncode)

    version = query("SELECT VERSION(),@@sql_mode,@@session.time_zone")
    cases = [f"SELECT TIMESTAMPADD(SQL_TSI_{u}, 1, '2024-01-31 12:00:00') AS v" for u in
             ("SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR")]
    cases += [f"SELECT TIMESTAMPADD(SECOND, {n}, '2024-01-01 12:00:00') AS v" for n in
              ("'1.000001'", "'-1.000001'", "'0.000249'", "1.000001", "-1.000001", "NULL")]
    cases += [
        "SELECT TIMESTAMPADD(SQL_TSI_DAY, n + 1, CAST('2024-01-01 12:00:00' AS DATETIME)) AS v FROM (SELECT 1 n UNION ALL SELECT NULL) t",
        "SELECT TIMESTAMPADD(DAY, 1, CAST('2024-01-31' AS DATE)) AS v",
        "SELECT TIMESTAMPADD(MONTH, 1, CAST('2024-01-31' AS DATE)) AS v",
        "SELECT TIMESTAMPADD(HOUR, 1, CAST('2024-01-31' AS DATE)) AS v",
        "SELECT TIMESTAMPADD(DAY, 1, CAST(NULL AS DATETIME)) AS v",
        "WITH t AS (SELECT '1.000001' n) SELECT TIMESTAMPADD(SECOND, n, '2024-01-01 12:00:00') AS v FROM t",
    ]
    payload = "".join("mysql\tmysql\t" + base64.b64encode(sql.encode()).decode() + "\n" for sql in cases)
    bridge = run([args.java, "-cp", args.classpath, "SqlglotPrecedenceProbe"], input=payload)
    assert bridge.returncode == 0, bridge.stderr
    lines = bridge.stdout.splitlines()
    assert len(lines) == len(cases), bridge.stdout
    observations = []
    for source, line in zip(cases, lines, strict=True):
        fields = line.split("\t")
        assert fields[0] == "OK", line
        ast, preview, status, executable = [base64.b64decode(x).decode() for x in fields[1:]]
        assert status == "accepted" and "TIMESTAMPADD" not in executable, (source, line)
        original, generated = query(source), query(executable)
        assert original["stdout"] == generated["stdout"], (source, executable, original, generated)
        old_meta, new_meta = query(source, True), query(executable, True)
        def metadata(result):
            return re.findall(r"^(Type|Decimals|Flags):\s*(.*)$", result["stdout"], re.M)
        assert metadata(old_meta) and metadata(old_meta) == metadata(new_meta), (source, old_meta, new_meta)
        observations.append(dict(sql=source, ast=ast, preview=preview, executable=executable,
                                 original=original, generated=generated, original_metadata=old_meta,
                                 generated_metadata=new_meta))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(scope="Finite MySQL TIMESTAMPADD/DATE_ADD value/type controls, not cross-engine date-vs-timestamp, arbitrary interval coercion or timezone equivalence.",
                                         image=info["Config"]["Image"], version=version, observations=observations), indent=2) + "\n")
    print(f"Captured {len(observations)} native MySQL temporal value/type pairs")


if __name__ == "__main__":
    main()
