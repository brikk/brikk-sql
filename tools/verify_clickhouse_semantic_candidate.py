#!/usr/bin/env python3
"""Compile/run the public ClickHouse API smoke on Java 17 using packaged local candidates only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from release_config import validate_version

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", type=validate_version)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--dependency-cache", type=Path, default=Path.home() / ".cache/JetBrains/Kotlin")
    parser.add_argument("--output", type=Path, default=ROOT / "build/clickhouse-release-gate/java17-smoke")
    args = parser.parse_args()
    java, javac = args.java_home / "bin/java", args.java_home / "bin/javac"
    version = subprocess.run([str(java), "-version"], text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, check=True).stdout
    assert 'version "17.' in version, "Use an actual Java 17 runtime, not only --release 17: " + version
    coordinates = [("dev.brikk.house", artifact, args.version) for artifact in
                   ("brikk-sql-jvm", "brikk-sql-metadata-jvm")]
    jars = {}
    for group, artifact, artifact_version in list(coordinates):
        folder = args.repository / group.replace(".", "/") / artifact / artifact_version
        pom = ET.parse(folder / f"{artifact}-{artifact_version}.pom")
        for dep in pom.findall("m:dependencies/m:dependency", NS):
            coord = tuple(dep.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version"))
            assert all(coord), coord
            if coord not in coordinates:
                coordinates.append(coord)
    for group, artifact, artifact_version in coordinates:
        if group == "dev.brikk.house":
            assert artifact_version == args.version and artifact in ("brikk-sql-jvm", "brikk-sql-metadata-jvm"), \
                "Unexpected candidate dependency: " + repr((group, artifact, artifact_version))
        name = f"{artifact}-{artifact_version}.jar"
        jar = args.repository / group.replace(".", "/") / artifact / artifact_version / name
        if not jar.exists():
            assert group != "dev.brikk.house", "Candidate must come from the selected Maven repository: " + str(jar)
            matches = sorted(args.dependency_cache.rglob(name))
            assert matches, "Missing exact runtime dependency: " + name
            assert len({sha256(match) for match in matches}) == 1, "Conflicting cached binaries for " + name
            jar = matches[0]
        jars[(group, artifact, artifact_version)] = jar.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    classes = args.output / "classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, jars.values()))
    source = ROOT / "tools/release/ClickhouseSemanticSmoke.java"
    subprocess.run([str(javac), "--release", "17", "-cp", classpath, "-d", str(classes), str(source)], check=True)
    run = subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath, "ClickhouseSemanticSmoke"],
                         text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    report = {"version": args.version, "java": version, "source_sha256": sha256(source), "stdout": run.stdout,
              "classpath": [{"coordinate": ":".join(coord), "path": str(path), "sha256": sha256(path)}
                            for coord, path in jars.items()]}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(run.stdout, end="")
    print("Classpath/source hashes:", args.output / "report.json")


if __name__ == "__main__":
    main()
