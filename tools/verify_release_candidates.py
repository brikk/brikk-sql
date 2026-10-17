#!/usr/bin/env python3
"""Check formal release candidates in a Maven repository: each artifact of the SQL family and of SQL IntelliJ
support is at its release version, its POM and Gradle module metadata name no SNAPSHOT, and each dependency
on another candidate takes that candidate's release version; other brikk artifacts (brikk-chdb) keep their
own released versions (stdlib only)."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from release_config import validate_version

GROUP = 'dev.brikk.house'
SQL = ['brikk-sql', 'brikk-sql-jvm', 'brikk-sql-metadata', 'brikk-sql-metadata-jvm', 'brikk-sql-verify-api',
       'brikk-sql-verify-trino', 'brikk-sql-verify-duckdb', 'brikk-sql-verify-doris', 'brikk-sql-verify-shardingsphere',
       'brikk-sql-verify-clickhouse', 'brikk-sql-verify', 'brikk-sql-oracle']
SUPPORT = ['brikk-sql-intellij-support', 'brikk-sql-intellij-test-support']
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def problems(repository: Path, artifact: str, version: str, versions: dict) -> list:
    folder = repository / GROUP.replace('.', '/') / artifact / version
    stem = f'{artifact}-{version}'
    pom, module = folder / f'{stem}.pom', folder / f'{stem}.module'
    if not pom.exists():
        return [f'{artifact}: no {pom}']
    found = []
    for path in (pom, module):
        if path.exists() and 'SNAPSHOT' in path.read_text():
            found.append(f'{path.name} names a SNAPSHOT')
    root = ET.parse(pom).getroot()
    if root.findtext('m:version', namespaces=NS) != version:
        found.append(f'{pom.name} is not version {version}')
    for dependency in root.findall('.//m:dependency', NS):
        name = dependency.findtext('m:artifactId', namespaces=NS)
        if name in versions and dependency.findtext('m:version', namespaces=NS) != versions[name]:
            found.append(f'{pom.name} takes {name} {dependency.findtext("m:version", namespaces=NS)}, not {versions.get(name)}')
    if module.exists():
        for variant in json.loads(module.read_text()).get('variants', []):
            for dependency in variant.get('dependencies', []):
                name = dependency['module']
                required = dependency.get('version', {}).get('requires')
                if dependency['group'] == GROUP and name in versions and required != versions[name]:
                    found.append(f'{module.name} takes {name} {required}, not {versions.get(name)}')
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sql_version', type=validate_version)
    parser.add_argument('--support-version', type=validate_version)
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2/repository')
    parser.add_argument('--only', help='Comma-separated artifacts to check; by default every candidate')
    args = parser.parse_args()
    versions = {artifact: args.sql_version for artifact in SQL}
    if args.support_version:
        versions.update({artifact: args.support_version for artifact in SUPPORT})
    checked = args.only.split(',') if args.only else list(versions)
    unknown = [artifact for artifact in checked if artifact not in versions]
    if unknown:
        raise SystemExit(f'Not release candidates: {unknown}')
    found = [p for artifact in checked for p in problems(args.repository, artifact, versions[artifact], versions)]
    if found:
        raise SystemExit('\n'.join(found))
    print(f'Verified {len(checked)} release candidates: {", ".join(f"{a}:{versions[a]}" for a in checked)}')


if __name__ == '__main__':
    main()
