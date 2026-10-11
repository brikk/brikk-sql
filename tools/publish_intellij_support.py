#!/usr/bin/env python3
"""Scoped, restoring release overrides. Publishes support and optionally its core prerequisites."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from release_config import validate_version, release_template


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", type=validate_version)
    parser.add_argument("--core-version", type=validate_version)
    parser.add_argument("--local", action="store_true", help="Publish only to Maven Local, without signing")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    support = root / "brikk-sql-intellij-support/module.yaml"
    template = root / "publish.module-template.yaml"
    originals = {p: p.read_text() for p in (support, template)}
    backup = root / ".intellij-publish-base.module-template.yaml"
    if backup.exists():
        raise SystemExit("An interrupted/active release owns the backup; inspect it before proceeding")
    if not args.local:
        for new, old in [("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME", "KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME"),
                         ("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD", "KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD")]:
            value = os.environ.get(new) or os.environ.get(old)
            if not value: raise SystemExit(f"Missing {new}")
            os.environ[new] = os.environ[old] = value
        if not os.environ.get("KOTLIN_TOOLCHAIN_SIGNING_KEY"):
            raise SystemExit("Missing KOTLIN_TOOLCHAIN_SIGNING_KEY")
    modules = ["brikk-sql-intellij-support"]
    if args.core_version: modules = ["brikk-sql-metadata", "brikk-sql"] + modules
    try:
        backup.write_text(originals[template])
        if args.local:
            template.write_text(f'apply:\n  - ./{backup.name}\nsettings:\n  publishing:\n    version: {json.dumps(args.core_version or "0.20.0-SNAPSHOT")}\n')
        else:
            template.write_text(release_template(args.core_version or "0.20.0-SNAPSHOT", f"./{backup.name}")
                               if args.core_version else f'apply:\n  - ./{backup.name}\nsettings:\n  publishing:\n    mavenCentral: {{ enabled: true, publishingMode: auto }}\n    signArtifacts: true\n')
        old_version = "    version: 0.1.0-SNAPSHOT"
        if originals[support].count(old_version) != 1: raise SystemExit("Unexpected support development version")
        support.write_text(originals[support].replace(old_version, f"    version: {args.version}"))
        if args.local:
            command = ["./kotlin", "publish", "mavenLocal"] + [v for m in modules for v in ("-m", m)]
        else:
            command = ["./kotlin", "task"] + [f":{m}:publishToMavenCentral" for m in modules]
        subprocess.run(command, cwd=root, check=True)
    finally:
        for path, text in originals.items(): path.write_text(text)
        backup.unlink(missing_ok=True)


if __name__ == "__main__": main()
