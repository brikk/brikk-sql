#!/usr/bin/env python3
"""Refresh the pinned reference manifests. Does not select newer releases or run engines."""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPICE_COMMIT = "78a47787ef74dec52d705bf9e26a0f9b7a26ca94"
FORK_COMMIT = "cf084aa85dfdc27b4736208f5c132828020774ea"
UPSTREAM_COMMIT = "500744d5c25499055e3b2ca46b022bc425989203"
ASSET_HASH = "4c8179e1a7983a06d907b4efff29b17f0c1d779159efe117c1cafcc3bd3cd918"
WANTED = {"datafusion", "datafusion-sql", "datafusion-spark", "sqlparser", "arrow", "arrow-schema", "datafusion-functions-json"}


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def dependency_rows(raw):
    return [{k: p[k] for k in ["name", "version", "source", "checksum"] if k in p}
            for p in tomllib.loads(raw.decode())["package"] if p["name"] in WANTED]


def source(repo, revision, path):
    url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
    raw = fetch(url)
    return raw, {"url": url, "sha256": digest(raw), "license": "Apache-2.0"}


def capture(root):
    for product, version, repo, revision, package in [
        ("datafusion", "55.2.0", "apache/datafusion", UPSTREAM_COMMIT, "upstream"),
        ("spice", "2.4.0", "spiceai/spiceai", SPICE_COMMIT, "spice"),
    ]:
        raw, lock = source(repo, revision, "Cargo.lock")
        reference_lock = ROOT / f"tools/datafusion-reference/{package}/Cargo.lock"
        reference_raw = reference_lock.read_bytes()
        manifest = {"schema_version": 1, "product": product, "version": version,
                    "source_commit": revision, "captured_on": "2026-10-30", "lockfile": lock,
                    "resolved_packages": dependency_rows(raw),
                    "reference_lockfile": {"path": str(reference_lock.relative_to(ROOT)),
                                           "sha256": digest(reference_raw), "resolved_packages": dependency_rows(reference_raw)},
                    "reference_tool_version": "0.1.0", "protocol_version": 1,
                    "capture_command": "python3 tools/capture_datafusion_spice_provenance.py",
                    "sources": []}
        if product == "spice":
            release = json.loads(fetch("https://api.github.com/repos/spiceai/spiceai/releases/tags/v2.4.0"))
            asset = next(a for a in release["assets"] if a["name"] == "spiced_linux_x86_64.tar.gz")
            if asset.get("digest") != "sha256:" + ASSET_HASH:
                raise ValueError("Release asset digest changed; review the pin before continuing")
            manifest["release"] = {"url": release["html_url"], "published_at": release["published_at"],
                "asset_url": asset["browser_download_url"], "asset_sha256": ASSET_HASH,
                "binary_sha256": "257b9fd8cf1f46c1f4ddf0b6280ba5b6033b79c276ec6840dd5e5d81710b1002",
                "reported_version": "v2.4.0+models", "platform": "linux-x86_64",
                "features": "models is reported by --version; other compiled features are not inferred"}
            for path in ["crates/runtime-datafusion/src/session_config.rs", "crates/runtime/src/datafusion/planner/mod.rs",
                         "crates/runtime/src/datafusion/udf.rs", "crates/runtime/src/udtfs.rs"]:
                manifest["sources"].append(source(repo, revision, path)[1])
            for path in ["datafusion/sql/src/parser.rs", "datafusion/catalog/src/information_schema.rs"]:
                manifest["sources"].append(source("spiceai/datafusion", FORK_COMMIT, path)[1])
            manifest["parser_profile"] = {"dialect": "PostgreSQL", "identifier_normalization": False,
                "extensions": ["adapted Spice CREATE TABLE CLUSTER BY wrapper"],
                "limitation": "Parser reference locks its own transitive Arrow dependencies, not Spice's patched Arrow build; execution uses the checksum-pinned released spiced binary"}
            manifest["runtime_fixture"] = {"path": "tools/datafusion-reference/spicepod.yaml",
                "sha256": digest((ROOT / "tools/datafusion-reference/spicepod.yaml").read_bytes()),
                "models_configured": [], "remote_datasets": [], "cpu_cores": 2}
        else:
            manifest["parser_profile"] = {"dialect": "Generic", "identifier_normalization": True,
                                          "extensions": ["upstream DFParser"]}
            manifest["engine_profile"] = {"execution": "fresh in-memory SessionContext per request", "target_partitions": 1,
                "features": tomllib.loads((ROOT / "tools/datafusion-reference/upstream/Cargo.toml").read_text())["dependencies"]["datafusion"]["features"],
                "optional_packages": [], "limitation": "Explicit embedding profile, not every possible DataFusion embedding"}
            manifest["sources"].append(source(repo, revision, "datafusion/sql/src/parser.rs")[1])
        path = root / product / version / "dependency-provenance.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=ROOT / "vendor/data")
    capture(parser.parse_args().output_root)
