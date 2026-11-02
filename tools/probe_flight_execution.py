#!/usr/bin/env python3
"""DFS-010: bounded JDBC execution, binding, session and lowering qualification."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
from probe_flight_catalog import ROOT, DRIVER_URL, DRIVER_SHA256, driver, datafusion_runtime
from probe_datafusion_spice import spice_runtime

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--java-option', action='append', default=[])
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--datafusion-flight', type=Path)
    parser.add_argument('--spiced', type=Path, default=Path('/tmp/opencode/spice-2.4.0-runtime/spiced'))
    parser.add_argument('--output', type=Path, help='New capture path; refuses to replace existing evidence')
    args = parser.parse_args()
    product = 'datafusion' if args.datafusion_flight else 'spice'
    out = args.output or ROOT / f'vendor/data/datafusion-spice/round10/{product}-execution.json'
    if out.exists():
        parser.error(f'Capture already exists: {out}; pass --output with a new path')
    directory = Path('/tmp/opencode/dfs010'); directory.mkdir(exist_ok=True)
    jar = driver(directory)
    paths = shlex.split(args.classpath_file.read_text())[1].split(':')
    cp = ':'.join(p for p in paths if '/_brikk-sql' in p and p.endswith('.jar') or '/org/jetbrains/kotlin' in p)
    compile = subprocess.run(['javac', '--release', '21', '-cp', cp, '-d', str(directory),
        str(ROOT / 'tools/datafusion-reference/FlightCatalogProbe.java'),
        str(ROOT / 'tools/datafusion-reference/FlightExecutionProbe.java')],capture_output=True,text=True)
    if compile.returncode: raise RuntimeError(compile.stderr)
    fixture = datafusion_runtime(args.datafusion_flight,directory) if args.datafusion_flight else spice_runtime(args.spiced,flight=True)
    options = ['--add-opens=java.base/java.nio=ALL-UNNAMED', *args.java_option]
    checkpoint = directory / f'{product}-checkpoint.json'
    checkpoint.unlink(missing_ok=True)
    with fixture as (url,runtime):
        if args.datafusion_flight and args.datafusion_flight.name.endswith('-execution'):
            runtime['embedding'] = 'brikk execution fixture: typed bindings, utility schemas, per-handshake contexts, lazy streams'
            runtime['execution_source_sha256'] = hashlib.sha256((ROOT / 'tools/datafusion-reference/src/flight_execution.rs').read_bytes()).hexdigest()
        try:
            run = subprocess.run([args.java,*options,'-cp',f'{directory}:{jar}:{cp}','FlightExecutionProbe',url,product,str(checkpoint)],capture_output=True,text=True,timeout=60)
            (directory / f'{product}-stderr.log').write_text(run.stderr)
            if run.returncode: raise RuntimeError(run.stderr)
            observations = json.loads(run.stdout)
        except subprocess.TimeoutExpired as error:
            (directory / f'{product}-stderr.log').write_bytes(error.stderr or b'')
            observations = json.loads(checkpoint.read_text()) if checkpoint.exists() else {}
            observations['probe_timeout'] = 'Probe JVM killed at 60 seconds; fixture terminated. Last captured operation: ' + next(reversed(observations), 'none')
    runtime['java'] = subprocess.run([args.java,'-version'],capture_output=True,text=True,check=True).stderr.strip()
    runtime['java_options'] = options
    out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('x') as stream:
        stream.write(json.dumps(dict(runtime=runtime,driver=dict(url=DRIVER_URL,sha256=DRIVER_SHA256),observations=observations),indent=2)+'\n')
    for key,value in observations.items():
        if key=='pipes':
            for row in value: print(row['stage'], 'native:', row['native']['status'], 'lowered:', row['lowered']['status'])
        else: print(key,value)
