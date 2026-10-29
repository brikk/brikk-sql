#!/usr/bin/env python3
"""Reject packaged bytecode or dependency/variant declarations incompatible with the Doris Java 17 graph."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

from release_config import development_version

ARTIFACTS = ('brikk-sql-jvm', 'brikk-sql-metadata-jvm', 'brikk-sql-verify-api', 'brikk-sql-verify-doris')
# KMP metadata can refer to the platform-neutral root coordinate.
ALLOWED_BRIKK = set(ARTIFACTS) | {'brikk-sql', 'brikk-sql-metadata'}
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def verify(repository: Path, version: str, artifact: str) -> None:
    folder = repository / 'dev/brikk/house' / artifact / version
    stem = f'{artifact}-{version}'
    with zipfile.ZipFile(folder / f'{stem}.jar') as archive:
        classes = [n for n in archive.namelist() if n.endswith('.class')]
        assert classes, f'{artifact}: empty JAR'
        for name in classes:
            header = archive.read(name)[:8]
            assert len(header) == 8 and header[:4] == b'\xca\xfe\xba\xbe', f'{artifact}: invalid class {name}'
            assert int.from_bytes(header[6:8], 'big') <= 61, f'{artifact}: {name} requires newer than Java 17'
    metadata = json.loads((folder / f'{stem}.module').read_text())
    for variant in metadata['variants']:
        declared = variant.get('attributes', {}).get('org.gradle.jvm.version')
        assert declared is None or int(declared) <= 17, f'{artifact}: variant requires Java {declared}'
        for dependency in variant.get('dependencies', []):
            if dependency['group'] == 'dev.brikk.house':
                assert dependency['module'] in ALLOWED_BRIKK, f'{artifact}: non-Java-17 dependency {dependency}'
                assert dependency.get('version', {}).get('requires') == version, f'{artifact}: stale module dependency {dependency}'
    pom = ET.parse(folder / f'{stem}.pom')
    for dependency in pom.findall('m:dependencies/m:dependency', NS):
        if dependency.findtext('m:groupId', namespaces=NS) == 'dev.brikk.house':
            name = dependency.findtext('m:artifactId', namespaces=NS)
            assert name in ALLOWED_BRIKK, f'{artifact}: non-Java-17 dependency {name}'
            assert dependency.findtext('m:version', namespaces=NS) == version, f'{artifact}: stale dependency {name}'
    print(f'{artifact}:{version}: {len(classes)} packaged classes compatible with Java 17')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    template = Path(__file__).resolve().parents[1] / 'publish.module-template.yaml'
    parser.add_argument('version', nargs='?', default=development_version(template.read_text()))
    parser.add_argument('--repository', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    for artifact in ARTIFACTS:
        verify(args.repository, args.version, artifact)


if __name__ == '__main__':
    main()
