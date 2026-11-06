#!/usr/bin/env python3
"""Qualify the pinned JDBC driver on disposable Spice or DataFusion fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
import contextlib
import socket
import time
import shlex
from probe_datafusion_spice import ROOT, spice_runtime, port

DRIVER_URL = 'https://repo.maven.apache.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/19.0.0/flight-sql-jdbc-driver-19.0.0.jar'
DRIVER_SHA256 = 'd3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f'

def driver(directory):
    jar = directory / 'flight-sql-jdbc-driver-19.0.0.jar'
    if not jar.exists():
        jar.write_bytes(urllib.request.urlopen(DRIVER_URL, timeout=60).read())
    if hashlib.sha256(jar.read_bytes()).hexdigest() != DRIVER_SHA256:
        raise ValueError('Flight SQL JDBC driver checksum mismatch')
    return jar

def probe(url, directory, auth=False, java='java', java_options=(), harvester_classpath=None):
    jar = driver(directory)
    subprocess.run(['javac', '--release', '17', '-d', str(directory), str(ROOT / 'tools/datafusion-reference/FlightCatalogProbe.java')], check=True)
    extra = ''
    if harvester_classpath:
        paths = shlex.split(harvester_classpath.read_text())[1].split(':')
        extra = ':' + ':'.join(p for p in paths if '/_brikk-sql' in p and p.endswith('.jar') or '/org/jetbrains/kotlin' in p)
        compiled = subprocess.run(['javac', '--release', '21', '-cp', extra[1:], '-d', str(directory),
                        str(ROOT / 'tools/datafusion-reference/FlightHarvesterProbe.java')], capture_output=True, text=True)
        if compiled.returncode:
            raise RuntimeError(compiled.stderr)
    arguments = ['fixture-auth' if auth else 'no-auth']
    if extra:
        arguments.append('datafusion' if auth else 'spice')
    run = subprocess.run([java, '--add-opens=java.base/java.nio=ALL-UNNAMED', *java_options, '-cp', f'{directory}:{jar}{extra}',
                          'FlightCatalogProbe', url, *arguments], capture_output=True, text=True, timeout=180)
    (directory / 'jdbc-stderr.log').write_text(run.stderr)
    if run.returncode:
        raise RuntimeError(run.stderr)
    result = json.loads(run.stdout)
    if extra and result.get('production_harvest', {}).get('status') != 'accepted':
        raise RuntimeError(run.stderr)
    return result

@contextlib.contextmanager
def datafusion_runtime(binary, directory):
    flight_port = port()
    with (directory / 'datafusion-flight.log').open('w') as log:
        process = subprocess.Popen([str(binary.resolve()), str(flight_port)], stdout=log, stderr=log)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError('DataFusion Flight fixture exited')
                try:
                    with socket.create_connection(('127.0.0.1', flight_port), timeout=.1):
                        break
                except OSError:
                    time.sleep(.05)
            else:
                raise TimeoutError('DataFusion Flight fixture did not start')
            yield f'jdbc:arrow-flight-sql://127.0.0.1:{flight_port}/?useEncryption=false', dict(
                version='55.2.0', transport='isolated loopback Arrow Flight SQL JDBC',
                embedding='brikk disposable per-handshake context, information_schema enabled',
                lock_sha256=hashlib.sha256((ROOT / 'tools/datafusion-reference/flight/Cargo.lock').read_bytes()).hexdigest(),
                source_sha256=hashlib.sha256((ROOT / 'tools/datafusion-reference/src/flight.rs').read_bytes()).hexdigest())
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--spiced', type=Path, default=ROOT / 'build/spice-2.4.0-runtime/spiced')
    p.add_argument('--work-dir', type=Path, default=ROOT / 'build/flight-catalog')
    p.add_argument('--output', type=Path, default=ROOT / 'vendor/data/datafusion-spice/round9/spice-jdbc.json')
    p.add_argument('--java', default='java')
    p.add_argument('--java-option', action='append', default=[])
    p.add_argument('--datafusion-flight', type=Path)
    p.add_argument('--harvester-classpath-file', type=Path, help='test.log.classpath from the compiled platform suite; requires Java 21+')
    args = p.parse_args()
    directory = args.work_dir.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    fixture = datafusion_runtime(args.datafusion_flight, directory) if args.datafusion_flight else spice_runtime(args.spiced, flight=True, work_dir=directory)
    with fixture as (url, runtime):
        observations = probe(url, directory, auth=bool(args.datafusion_flight), java=args.java, java_options=args.java_option,
                             harvester_classpath=args.harvester_classpath_file)
    runtime['java'] = subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip()
    runtime['java_options'] = ['--add-opens=java.base/java.nio=ALL-UNNAMED', *args.java_option]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(runtime=runtime, driver=dict(coordinates='org.apache.arrow:flight-sql-jdbc-driver:19.0.0',
        url=DRIVER_URL, sha256=DRIVER_SHA256), observations=observations), indent=2) + '\n')
    for key, value in observations.items():
        if key == 'queries':
            for sql, r in value.items(): print(sql, r['status'], len(r.get('value', {}).get('rows', [])), r.get('message', ''))
        else: print(key, value.get('status', value) if isinstance(value, dict) else value)
