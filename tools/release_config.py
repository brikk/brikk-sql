#!/usr/bin/env python3
"""Validate release SemVer and compose a Toolchain release override (stdlib only)."""

import argparse
import json
import re


NUMBER = r"(?:0|[1-9][0-9]*)"
PRERELEASE_ID = rf"(?:{NUMBER}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER = re.compile(
    rf"{NUMBER}\.{NUMBER}\.{NUMBER}"
    rf"(?:-(?P<prerelease>{PRERELEASE_ID}(?:\.{PRERELEASE_ID})*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)


def validate_version(version: str) -> str:
    match = SEMVER.fullmatch(version)
    if not match:
        raise ValueError(f"Release version must be SemVer 2.0.0: {version!r}")
    prerelease = match.group("prerelease") or ""
    if "SNAPSHOT" in prerelease.upper().split("."):
        raise ValueError(f"Release version must not be a SNAPSHOT: {version!r}")
    return version


def development_version(template_text: str) -> str:
    """The shared publishing version in publish.module-template.yaml."""
    match = re.search(r"^    version: (\S+)$", template_text, re.M)
    if not match:
        raise ValueError("No publishing version in the module template")
    return match.group(1)


def release_template(version: str, base_template: str, local: bool = False) -> str:
    """Use native template inheritance, not text edits or a separate YAML parser. [local] only sets the
    version: a formal candidate for Maven Local, neither signed nor bound for Central."""
    validate_version(version)
    # JSON double-quoted strings are also YAML scalars, including escaped paths.
    central = "" if local else "    mavenCentral: { enabled: true, publishingMode: auto }\n    signArtifacts: true\n"
    return (
        "# Temporary release overrides; publish-release.sh restores the base on exit.\n"
        f"apply:\n  - {json.dumps(base_template)}\n"
        "settings:\n  publishing:\n"
        f"    version: {json.dumps(version)}\n"
        f"{central}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("version")
    template = commands.add_parser("template")
    template.add_argument("version")
    template.add_argument("base_template")
    template.add_argument("--local", action="store_true")
    args = parser.parse_args()
    try:
        validate_version(args.version)
        if args.command == "template":
            print(release_template(args.version, args.base_template, args.local), end="")
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
