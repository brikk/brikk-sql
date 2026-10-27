#!/usr/bin/env python3
"""Audit the published SQL verifier artifacts (API, engines, aggregate) in a local Maven repository."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from release_config import development_version

GROUP = 'dev.brikk.house'
PACKAGE = 'dev/brikk/house/sql/verify/'
JAVA_21 = 65
STDLIB = ('org.jetbrains.kotlin', 'kotlin-stdlib')
API = (GROUP, 'brikk-sql-verify-api')
ENGINES = ['brikk-sql-verify-trino', 'brikk-sql-verify-duckdb', 'brikk-sql-verify-doris',
           'brikk-sql-verify-shardingsphere']
SHARDINGSPHERE = [('org.apache.shardingsphere', f'shardingsphere-{name}') for name in (
    'parser-sql-engine', 'parser-sql-postgresql', 'parser-sql-mysql', 'parser-sql-hive',
    'parser-sql-clickhouse', 'infra-database-hive')]

# Declared dependencies, in POM order. Every brikk dependency is compile scope (re-exported).
ARTIFACTS = {
    'brikk-sql-verify-api': [(GROUP, 'brikk-sql-jvm'), STDLIB],
    'brikk-sql-verify-trino': [API, ('io.trino', 'trino-parser'), STDLIB],
    'brikk-sql-verify-duckdb': [API, ('org.duckdb', 'duckdb_jdbc'), STDLIB,
                             ('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm'),
                             ('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm')],
    'brikk-sql-verify-doris': [API, ('org.antlr', 'antlr4-runtime'), STDLIB],
    'brikk-sql-verify-shardingsphere': [API, *SHARDINGSPHERE, STDLIB],
    # The user's own server and driver: no driver dependency.
    'brikk-sql-verify-clickhouse': [API, STDLIB],
    'brikk-sql-verify': [API, *[(GROUP, engine) for engine in ENGINES], STDLIB],
}
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def verify(repository: Path, version: str, artifact: str, dependencies: list) -> None:
    folder = repository / GROUP.replace('.', '/') / artifact / version
    stem = f'{artifact}-{version}'
    with zipfile.ZipFile(folder / f'{stem}.jar') as archive:
        classes = [name for name in archive.namelist() if name.endswith('.class')]
        assert classes, f'{artifact}: no classes'
        for name in classes:
            assert name.startswith(PACKAGE), f'{artifact}: {name} outside {PACKAGE}'
            assert int.from_bytes(archive.read(name)[6:8], 'big') <= JAVA_21, f'{artifact}: {name} is newer than Java 21'
    with zipfile.ZipFile(folder / f'{stem}-sources.jar') as archive:
        assert any(name.endswith('.kt') for name in archive.namelist()), f'{artifact}: empty sources jar'

    pom = ET.parse(folder / f'{stem}.pom')
    assert pom.findtext('m:description', namespaces=NS), f'{artifact}: POM has no description'
    declared = pom.findall('m:dependencies/m:dependency', NS)
    coordinates = [(d.findtext('m:groupId', namespaces=NS), d.findtext('m:artifactId', namespaces=NS)) for d in declared]
    assert coordinates == dependencies, f'{artifact}: declares {coordinates}'
    for dependency in declared:
        if dependency.findtext('m:groupId', namespaces=NS) == GROUP:
            name = dependency.findtext('m:artifactId', namespaces=NS)
            assert dependency.findtext('m:version', namespaces=NS) == version, f'{artifact}: {name} is not {version}'
            assert dependency.findtext('m:scope', namespaces=NS) == 'compile', f'{artifact}: {name} is not re-exported'

    metadata = json.loads((folder / f'{stem}.module').read_text())
    for variant in metadata['variants']:
        for dependency in variant.get('dependencies', []):
            assert (dependency['group'], dependency['module']) in dependencies, f'{artifact}: metadata adds {dependency}'
    print(f'Verified {artifact}:{version}: {len(classes)} Java 21 classes, dependencies {coordinates}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    template = Path(__file__).resolve().parents[1] / 'publish.module-template.yaml'
    parser.add_argument('version', nargs='?', default=development_version(template.read_text()))
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    for artifact, dependencies in ARTIFACTS.items():
        verify(args.repository, args.version, artifact, dependencies)


if __name__ == '__main__':
    main()
