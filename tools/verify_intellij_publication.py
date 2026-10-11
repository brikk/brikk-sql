#!/usr/bin/env python3
"""Audit the published SQL IntelliJ helper and test kit from a local Maven repository, without IDE class loading."""
import argparse
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

STDLIB = ('org.jetbrains.kotlin', 'kotlin-stdlib')


def verify(repository: Path, version: str, artifact: str, package: str, dependencies: list, sources: bool):
    folder = repository / 'dev/brikk/house' / artifact / version
    stem = f'{artifact}-{version}'
    jar = folder / f'{stem}.jar'
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert 'META-INF/LICENSE' in names and 'META-INF/NOTICE' in names
        classes = [name for name in names if name.endswith('.class')]
        assert classes
        for name in classes:
            assert name.startswith(package), name
            assert int.from_bytes(archive.read(name)[6:8], 'big') <= 65, name
    if sources:
        with zipfile.ZipFile(folder / f'{stem}-sources.jar') as archive:
            names = archive.namelist()
            assert any(name.endswith('/META-INF/LICENSE') for name in names), names
            assert any(name.endswith('/META-INF/NOTICE') for name in names), names
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(folder / f'{stem}.pom')
    declared = [(d.findtext('m:groupId', namespaces=ns), d.findtext('m:artifactId', namespaces=ns))
                for d in pom.findall('.//m:dependency', ns)]
    assert declared == dependencies, declared
    for dependency in pom.findall('.//m:dependency', ns):
        if dependency.findtext('m:groupId', namespaces=ns) == 'dev.brikk.house':
            assert dependency.findtext('m:version', namespaces=ns) == version, ET.tostring(dependency)
    metadata = json.loads((folder / f'{stem}.module').read_text())
    for variant in metadata['variants']:
        for dependency in variant.get('dependencies', []):
            assert (dependency['group'], dependency['module']) in dependencies, dependency
    print(f'Verified {jar}: {len(classes)} Java-21 classes, notices, dependencies {declared}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    verify(args.repository, args.version, 'brikk-sql-intellij-support', 'dev/brikk/house/sql/intellij/',
           [STDLIB], sources=True)
    verify(args.repository, args.version, 'brikk-sql-intellij-test-support', 'dev/brikk/house/sql/intellij/testing/',
           [('dev.brikk.house', 'brikk-sql-intellij-support'), STDLIB], sources=False)
