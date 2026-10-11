#!/usr/bin/env python3
"""Scoped, restoring release overrides. Publishes support, its test kit, and optionally its core prerequisites."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from release_config import validate_version, release_template


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", type=validate_version)
    parser.add_argument("--core-version", type=validate_version)
    parser.add_argument("--local", action="store_true", help="Publish only to Maven Local, without signing")
    args = parser.parse_args()
    if not args.local and not args.core_version:
        raise SystemExit("Central releases need --core-version: support depends on released core and metadata")
    root = Path(__file__).resolve().parents[1]
    intellij = ["brikk-sql-intellij-support", "brikk-sql-intellij-test-support"]
    descriptors = [root / m / "module.yaml" for m in intellij]
    template = root / "publish.module-template.yaml"
    originals = {p: p.read_text() for p in (*descriptors, template)}
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
    modules = intellij
    if args.core_version: modules = ["brikk-sql-metadata", "brikk-sql"] + modules
    try:
        backup.write_text(originals[template])
        if args.local:
            template.write_text(f'apply:\n  - ./{backup.name}\nsettings:\n  publishing:\n    version: {json.dumps(args.core_version or "0.20.0-SNAPSHOT")}\n')
        else:
            template.write_text(release_template(args.core_version or "0.20.0-SNAPSHOT", f"./{backup.name}")
                               if args.core_version else f'apply:\n  - ./{backup.name}\nsettings:\n  publishing:\n    mavenCentral: {{ enabled: true, publishingMode: auto }}\n    signArtifacts: true\n')
        for descriptor in descriptors:
            released, count = re.subn(r"^    version: \S+-SNAPSHOT$", f"    version: {args.version}", originals[descriptor], flags=re.M)
            if count != 1: raise SystemExit(f"Unexpected development version in {descriptor}")
            descriptor.write_text(released)
        if args.local:
            command = ["./kotlin", "publish", "mavenLocal"] + [v for m in modules for v in ("-m", m)]
            if not args.core_version: command.append("--non-transitive")
        else:
            command = ["./kotlin", "task"] + [f":{m}:publishToMavenCentral" for m in modules]
        subprocess.run(command, cwd=root, check=True)
    finally:
        for path, text in originals.items(): path.write_text(text)
        backup.unlink(missing_ok=True)


if __name__ == "__main__": main()
