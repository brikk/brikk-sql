#!/usr/bin/env python3
"""Build an opt-in, test-only C++ window fixture. Tests sign a scratch copy, not this template.

Linux amd64 only. Uses official SHA256-pinned 2.0.0 static libraries and an exact source
checkout for headers. Does not install/load extensions or change any DuckDB trust store.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import urllib.request

REVISION = "d54cbbe3df17e8e5ce68312f483b4155836112aa"
ARCHIVE = "duckdb-static-libs-linux-amd64.tar.gz"
SHA256 = "112d62c980f5447cd303a70b3dacbc09acd41ccb4ef6d154e8629f543a0f8568"
URL = "https://github.com/duckdb/duckdb/releases/download/v2.0.0/" + ARCHIVE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("The pinned fixture is for Linux amd64 only")
    source = args.source.resolve()
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        parser.error("Headers must come from exact DuckDB revision " + REVISION)
    if subprocess.check_output(["git", "-C", str(source), "status", "--porcelain", "--", "src/include", "third_party/fmt/include"], text=True).strip():
        parser.error("DuckDB headers have uncommitted changes")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    archive = out / ARCHIVE
    if not archive.exists():
        urllib.request.urlretrieve(URL, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        parser.error("Pinned static-library archive digest mismatch")
    libraries = out / "static"
    libraries.mkdir(exist_ok=True)
    with tarfile.open(archive) as bundle:
        bundle.extractall(libraries, filter="data")
    cpp = Path(__file__).resolve().parents[1] / "brikk-sql-verify-duckdb/testResources/window-fixture/brikk_window.cpp"
    binary = out / "brikk_window.duckdb_extension"
    command = [os.environ.get("CXX", "g++"), "-std=c++17", "-O2", "-shared", "-fPIC", "-fvisibility=hidden",
               "-ffunction-sections", "-fdata-sections", "-DDUCKDB_EXTENSION_MAIN",
               "-I" + str(source / "src/include"), "-I" + str(source / "third_party/fmt/include"), str(cpp),
               "-Wl,--start-group", *map(str, sorted(libraries.rglob("*.a"))), "-Wl,--end-group",
               "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL", "-ldl", "-pthread", "-o", str(binary)]
    subprocess.run(command, check=True)
    fields = ["4", "linux_amd64", "v2.0.0", "window-fixture-1", "CPP", "", "", ""]
    with binary.open("ab") as target:
        target.write(b"\x00\x93\x04\x10duckdb_signature\x80\x04")
        target.write(b"".join(field.encode().ljust(32, b"\x00") for field in reversed(fields)))
        target.write(bytes(256))  # Signature placeholder; never load this unsigned template.
    manifest = {"revision": revision, "archive_url": URL, "archive_sha256": SHA256,
                "source_sha256": hashlib.sha256(cpp.read_bytes()).hexdigest(), "command": command,
                "extension_sha256": hashlib.sha256(binary.read_bytes()).hexdigest()}
    (out / "build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(binary)
    print("Template SHA256:", manifest["extension_sha256"])


if __name__ == "__main__":
    main()
