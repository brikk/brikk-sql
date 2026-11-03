#!/usr/bin/env python3
"""Isolate Spice 2.4.0 / Arrow JDBC 19.0.0 failure paths without Brikk or IntelliJ."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.parse

from probe_datafusion_spice import ROOT, spice_runtime
from probe_flight_catalog import DRIVER_SHA256, DRIVER_URL, driver


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spiced', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='A new capture file; existing evidence is never replaced')
    parser.add_argument('--work-directory', type=Path, default=ROOT / 'build/qualification/spice-flight')
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--java-option', action='append', default=[])
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f'Capture already exists: {args.output}')
    directory = args.work_directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    jar = driver(directory)
    sources = [ROOT / 'tools/datafusion-reference' / name for name in ['FlightCatalogProbe.java', 'FlightFailureProbe.java']]
    subprocess.run([args.javac, '--release', '17', '-cp', str(jar), '-d', str(directory), *map(str, sources)], check=True)
    raw = directory / 'observations.json'
    raw.unlink(missing_ok=True)
    options = ['--add-opens=java.base/java.nio=ALL-UNNAMED', *args.java_option]
    with spice_runtime(args.spiced, flight=True) as (url, runtime):
        port = urllib.parse.urlsplit(url.removeprefix('jdbc:')).port
        with (directory / 'probe.log').open('w') as log:
            subprocess.run([args.java, *options, '-cp', f'{directory}:{jar}', 'FlightFailureProbe', url, str(port), str(raw)],
                           stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
    runtime['java'] = subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip()
    runtime['java_options'] = options
    capture = dict(schema_version=1, runtime=runtime, driver=dict(coordinates='org.apache.arrow:flight-sql-jdbc-driver:19.0.0',
                   url=DRIVER_URL, sha256=DRIVER_SHA256),
                   probe_sources={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                   observations=json.loads(raw.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as stream:
        stream.write(json.dumps(capture, indent=2) + '\n')
    print(f'Captured {len(capture["observations"])} investigation groups to {args.output}')


if __name__ == '__main__':
    main()
