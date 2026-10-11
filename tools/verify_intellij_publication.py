#!/usr/bin/env python3
"""Audit a published SQL IntelliJ helper from a local Maven repository, without IDE class loading."""
import argparse
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET


def verify(repository: Path, version: str):
    folder = repository / 'dev/brikk/house/brikk-sql-intellij-support' / version
    stem = f'brikk-sql-intellij-support-{version}'
    jar = folder / f'{stem}.jar'
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert 'META-INF/LICENSE' in names and 'META-INF/NOTICE' in names
        classes = [name for name in names if name.endswith('.class')]
        assert classes
        for name in classes:
            assert name.startswith('dev/brikk/house/sql/intellij/'), name
            assert int.from_bytes(archive.read(name)[6:8], 'big') <= 65, name
    with zipfile.ZipFile(folder / f'{stem}-sources.jar') as archive:
        names = archive.namelist()
        assert any(name.endswith('/META-INF/LICENSE') for name in names), names
        assert any(name.endswith('/META-INF/NOTICE') for name in names), names
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(folder / f'{stem}.pom')
    dependencies = [(d.findtext('m:groupId', namespaces=ns), d.findtext('m:artifactId', namespaces=ns))
                    for d in pom.findall('.//m:dependency', ns)]
    assert dependencies == [('org.jetbrains.kotlin', 'kotlin-stdlib')], dependencies
    metadata = json.loads((folder / f'{stem}.module').read_text())
    for variant in metadata['variants']:
        for dependency in variant.get('dependencies', []):
            assert (dependency['group'], dependency['module']) == ('org.jetbrains.kotlin', 'kotlin-stdlib'), dependency
    print(f'Verified {jar}: {len(classes)} Java-21 helper classes, sources/notices, no SDK/core/native dependencies')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    verify(args.repository, args.version)
