"""Pinned source graph and native capture adapters for datafusion_upstream.py."""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request

from datafusion_upstream import ROOT, canonical, digest, file_hash, load, save, write_snapshot
from capture_datafusion_spice_metadata import QUERIES, TYPE_CANDIDATES, query_result, sorted_rows
from probe_datafusion_spice import load_cases, port, run_reference, spice_execute, spice_sql

DF_WATCH = {
    'build': ('breaking behavior', ['Cargo.toml', 'Cargo.lock']),
    'parser': ('parser', ['datafusion/sql/']),
    'optimizer-types': ('type/scope', ['datafusion/optimizer/', 'datafusion/expr/', 'datafusion/common/']),
    'registries': ('metadata-only', ['datafusion/functions/', 'datafusion/functions-aggregate/', 'datafusion/functions-window/', 'datafusion/functions-nested/', 'datafusion/functions-table/']),
    'catalog-session': ('IDE', ['datafusion/catalog/', 'datafusion/core/src/execution/']),
    'slt': ('parser', ['datafusion/sqllogictest/test_files/']),
}
SPICE_WATCH = {
    'build': ('breaking behavior', ['Cargo.toml', 'Cargo.lock']),
    'parser-session': ('parser', ['crates/runtime-datafusion/src/session_config.rs', 'crates/runtime/src/datafusion/planner/']),
    'registry': ('metadata-only', ['crates/runtime/src/datafusion/udf.rs', 'crates/runtime/src/udtfs.rs']),
    'search-ai-json': ('type/scope', ['crates/runtime-search/', 'crates/runtime-decide/', 'crates/runtime/src/embeddings/', 'crates/runtime/src/datafusion/']),
    'release-tests': ('generator', ['crates/runtime/tests/', 'test/']),
}
ARROW_WATCH = {'arrow-types': ('type/scope', ['arrow-schema/', 'arrow-array/']),
               'flight': ('transport', ['arrow-flight/']), 'build': ('transport', ['Cargo.toml', 'Cargo.lock'])}
SHA = re.compile(r'[0-9a-f]{40}')


def default_profile(product):
    version = {'datafusion': '55.2.0', 'spice': '2.4.0'}[product]
    provenance = load(ROOT / f'vendor/data/{product}/{version}/dependency-provenance.json')
    expected = {'id': f'datafusion-{version}', 'datafusion': version, 'revision': provenance['source_commit'],
                'dialect': 'Generic', 'identifier_normalization': True}
    if product == 'spice':
        fork = next(p['source'].split('#')[1] for p in provenance['resolved_packages'] if p['name'] == 'datafusion')
        expected = {'id': f'spice-{version}-parser', 'datafusion': '55.2.0', 'revision': fork,
                    'dialect': 'PostgreSQL', 'identifier_normalization': False}
    package = 'upstream' if product == 'datafusion' else 'spice'
    return {'schema_version': 1, 'id': f'{product}-{version}-reference', 'product': product, 'version': version,
            'source': {'repository': 'apache/datafusion' if product == 'datafusion' else 'spiceai/spiceai',
                       'commit': provenance['source_commit'], 'tag': version if product == 'datafusion' else 'v' + version},
            'parser_profile': provenance['parser_profile'], 'expected_reference': expected,
            'features': provenance.get('engine_profile', {}).get('features', ['models; other compiled features unknown']),
            'feature_coverage': 'complete' if product == 'datafusion' else 'partial',
            'embedding': provenance.get('engine_profile', provenance.get('runtime_fixture')),
            'runtime': provenance.get('release'),
            'reference_lock': {'path': f'tools/datafusion-reference/{package}/Cargo.lock',
                               'sha256': provenance['reference_lockfile']['sha256']},
            'reference_build': {'manifest': f'tools/datafusion-reference/{package}/Cargo.toml',
                                'manifest_sha256': file_hash(ROOT / f'tools/datafusion-reference/{package}/Cargo.toml'),
                                'binary': 'brikk-datafusion-reference' if product == 'datafusion' else 'brikk-spice-parser-reference'},
            'cases': {'path': 'vendor/data/datafusion-spice/cases.json', 'sha256': file_hash(ROOT / 'vendor/data/datafusion-spice/cases.json')},
            'keyword_capture': {'path': 'vendor/data/datafusion/55.2.0/metadata-capture.json',
                                'sha256': file_hash(ROOT / 'vendor/data/datafusion/55.2.0/metadata-capture.json'),
                                'package_pin': next({k: p[k] for k in ('version', 'source', 'checksum') if k in p}
                                    for p in provenance['resolved_packages'] if p['name'] == 'sqlparser')},
            'overrides': {'path': f'vendor/data/{product}/{version}/function-overrides.json',
                          'sha256': file_hash(ROOT / f'vendor/data/{product}/{version}/function-overrides.json')}
                         if (ROOT / f'vendor/data/{product}/{version}/function-overrides.json').exists() else None,
            'driver': {'coordinates': 'org.apache.arrow:flight-sql-jdbc-driver:19.0.0',
                       'sha256': 'd3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f'},
            'transport': 'JSONL reference' if product == 'datafusion' else 'parser JSONL plus disposable HTTP runtime'}


def pinned_file(pin):
    path = ROOT / pin['path']
    if file_hash(path) != pin['sha256']:
        raise ValueError(f"Reviewed input changed: {pin['path']}")
    return path


def validate_profile(profile, product):
    if profile.get('schema_version') != 1 or profile.get('product') != product:
        raise ValueError('Wrong product/profile format')
    if not isinstance(profile.get('id'), str) or not profile['id'].strip():
        raise ValueError('A profile needs a nonempty identity')
    version = profile['version']
    expected_id = f'datafusion-{version}' if product == 'datafusion' else f'spice-{version}-parser'
    if profile['expected_reference']['id'] != expected_id:
        raise ValueError('The reference identity must match the declared product release')
    if product == 'datafusion' and profile['expected_reference']['datafusion'] != version:
        raise ValueError('The declared DataFusion engine version differs from the product release')
    if product == 'spice' and profile['runtime']['reported_version'].removeprefix('v').split('+')[0] != version:
        raise ValueError('The Spice runtime pin belongs to another release')
    if profile.get('feature_coverage') not in ('complete', 'partial'):
        raise ValueError('Declare whether compiled features are completely known')


def fetch(url):
    headers = {'User-Agent': 'brikk-upstream-review', 'Accept': 'application/vnd.github+json'}
    if url.startswith('https://api.github.com/') and os.environ.get('GITHUB_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GITHUB_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=45) as response:
        raw = response.read(40_000_001)
    if len(raw) > 40_000_000:
        raise ValueError('Source response exceeded capture budget')
    return raw


def source_graph(profile, get=fetch):
    """Track immutable Git subtrees and Cargo packages, following Spice's actual fork pins."""
    components, packages, edges, raw, repositories = {}, {}, [], {}, {}
    queue = [(profile['source']['repository'], profile['source']['commit'],
              SPICE_WATCH if profile['product'] == 'spice' else DF_WATCH)]
    seen = set()
    while queue:
        repo, commit, watched = queue.pop(0)
        if (repo, commit) in seen:
            continue
        if not SHA.fullmatch(commit) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
            raise ValueError('Sources require a full immutable Git commit and owner/repository')
        seen.add((repo, commit))
        repositories[repo] = {'commit': commit}
        if len(seen) > 12:
            raise ValueError('Dependency source graph exceeded its repository budget')
        base = f'https://api.github.com/repos/{repo}'
        metadata = json.loads(get(f'{base}/git/commits/{commit}'))
        if metadata.get('sha') != commit:
            raise ValueError('Source commit mismatch')
        tree = json.loads(get(f"{base}/git/trees/{metadata['tree']['sha']}?recursive=1"))
        if tree.get('truncated'):
            raise ValueError(f'Incomplete source tree for {repo}')
        name = repo.replace('/', '__')
        raw[name + '-tree.json'] = tree
        raw[name + '-commit.json'] = metadata
        for component, (impact, prefixes) in watched.items():
            files = {row['path']: row['sha'] for row in tree['tree'] if row['type'] == 'blob' and
                     any(row['path'].startswith(prefix) if prefix.endswith('/') else row['path'] == prefix for prefix in prefixes)}
            components[f'{repo}/{component}'] = {'files': files, 'content_sha256': digest(files),
                'impact': impact, 'coverage': 'complete-tree' if files else 'no-matching-paths'}
            edges.append([repo, f'{repo}/{component}'])
        # Cargo.lock is the dependency graph, not proof that every optional package was compiled.
        lock_path = next((row['path'] for row in tree['tree'] if row['path'] == 'Cargo.lock'), None)
        if lock_path is None:
            continue
        lock_text = get(f'https://raw.githubusercontent.com/{repo}/{commit}/Cargo.lock').decode()
        raw[name + '-lock.json'] = {'text': lock_text, 'sha256': hashlib.sha256(lock_text.encode()).hexdigest()}
        lock = tomllib.loads(lock_text)
        selected = [p for p in lock['package'] if p['name'].startswith(('datafusion', 'arrow')) or p['name'] in ('sqlparser', 'prost', 'tonic')]
        def package_key(package):
            return f"{repo}/{package['name']}@{package['version']}#{digest(package.get('source'))[:10]}"
        for package in selected:
            key = package_key(package)
            packages[key] = {k: package[k] for k in ('name', 'version', 'source', 'checksum') if k in package}
            packages[key]['compilation'] = 'not inferred from lockfile'
            edges.append([repo, key])
            for dependency in package.get('dependencies', []):
                parts = dependency.split()
                for target in selected:
                    if target['name'] == parts[0] and (len(parts) == 1 or target['version'] == parts[1]):
                        edges.append([key, package_key(target)])
            source = package.get('source', '')
            if source.startswith('git+https://github.com/'):
                parsed = urllib.parse.urlsplit(source[4:])
                dependency_repo = parsed.path.strip('/').removesuffix('.git')
                if package['name'] in ('datafusion', 'arrow'):
                    queue.append((dependency_repo, parsed.fragment, DF_WATCH if package['name'] == 'datafusion' else ARROW_WATCH))
    tag = profile['source'].get('tag')
    if tag:
        repo = profile['source']['repository']
        try:
            ref = json.loads(get(f'https://api.github.com/repos/{repo}/git/ref/tags/{urllib.parse.quote(tag, safe="")}'))
            raw['release-tag.json'] = ref
            obj = ref['object']
            for _ in range(5):
                if obj['type'] == 'commit':
                    break
                if obj['type'] != 'tag':
                    raise ValueError('Unexpected release tag target')
                annotation = json.loads(get(obj['url']))
                raw['release-tag-' + obj['sha'] + '.json'] = annotation
                obj = annotation['object']
            if obj['sha'] != profile['source']['commit']:
                raise ValueError('Release tag does not match the reviewed source commit')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            raw['release-tag.json'] = {'status': 'no-tag-at-this-name', 'tag': tag}
    packages['flight-sql-jdbc'] = dict(profile['driver'], impact='transport', evidence='declared artifact pin; separate JDBC qualification required')
    edges.append([profile['source']['repository'], 'flight-sql-jdbc'])
    return {'repositories': repositories, 'components': components, 'packages': packages, 'edges': ordered_edges(edges)}, raw


def ordered_edges(edges):
    return sorted({tuple(edge) for edge in edges})


@contextlib.contextmanager
def runtime(binary, profile):
    """Only a checksum-pinned binary and local reviewed views; no user endpoint argument."""
    pin = profile['runtime']
    if file_hash(binary) != pin['binary_sha256']:
        raise ValueError('Spice runtime checksum mismatch')
    version = subprocess.check_output([str(binary.resolve()), '--version'], text=True, timeout=15).strip()
    if version != pin['reported_version']:
        raise ValueError('Spice runtime version mismatch')
    parent = '/tmp/opencode' if Path('/tmp/opencode').is_dir() else None
    with tempfile.TemporaryDirectory(prefix='brikk-upstream-spice-', dir=parent) as tmp:
        pod = Path(tmp) / 'spicepod.yaml'
        fixture = profile['embedding']
        shutil.copyfile(pinned_file(fixture), pod)
        http, flight = port(), port()
        while http == flight:
            flight = port()
        with (Path(tmp) / 'runtime.log').open('w+') as log:
            process = subprocess.Popen([str(binary.resolve()), str(pod), '--http', f'127.0.0.1:{http}',
                '--flight', f'127.0.0.1:{flight}', '--cpu-cores', '2'], cwd=tmp, stdout=log, stderr=subprocess.STDOUT)
            url = f'http://127.0.0.1:{http}'
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Spice exited during startup')
                    try:
                        with urllib.request.urlopen(url + '/v1/ready', timeout=1) as ready:
                            if ready.status == 200:
                                break
                    except (urllib.error.URLError, TimeoutError):
                        pass
                    time.sleep(.2)
                else:
                    raise TimeoutError('Spice readiness timed out')
                yield url, {'version': version, 'binary_sha256': pin['binary_sha256'],
                            'spicepod_sha256': file_hash(pod), 'transport': 'isolated HTTP /v1/sql'}
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def checked_reference(binary, requests, profile):
    expected = profile['expected_reference']
    rows = run_reference(binary, requests, expected_profile=expected['id'])
    for row in rows.values():
        if any(row['profile'].get(key) != value for key, value in expected.items()):
            raise ValueError('Reference profile mismatch; never relabel an older binary')
    return rows


def source_overrides(profile):
    if not profile.get('overrides'):
        return None
    overrides = load(pinned_file(profile['overrides']))
    prefix = f"https://github.com/{profile['source']['repository']}/blob/{profile['source']['commit']}/"
    if not overrides.get('source', '').startswith(prefix):
        raise ValueError('Source overrides need candidate-source provenance, not an older release')
    return overrides


def refresh_snapshot(args):
    if args.output.exists():
        raise FileExistsError('Snapshot destination already exists; choose a new directory')
    profile = load(args.profile) if args.profile else default_profile(args.product)
    validate_profile(profile, args.product)
    if args.product == 'spice' and args.spiced is None:
        raise ValueError('Spice refresh requires both a parser reference and a pinned runtime')
    overrides = source_overrides(profile)
    build = profile['reference_build']
    pinned_file({'path': build['manifest'], 'sha256': build['manifest_sha256']})
    lock = tomllib.loads(pinned_file(profile['reference_lock']).read_text())
    if {p['version'] for p in lock['package'] if p['name'] == 'datafusion-sql'} != {profile['expected_reference']['datafusion']}:
        raise ValueError('Reference lockfile does not contain the declared DataFusion parser version')
    cases_path = pinned_file(profile['cases'])
    cases = load_cases(cases_path)
    brikk = load(args.brikk)
    if brikk.get('dialect') != args.product:
        raise ValueError('Brikk output belongs to a different product')
    generated = {row['id']: row for row in brikk['cases']}
    if len(generated) != len(brikk['cases']) or set(generated) != {row['id'] for row in cases}:
        raise ValueError('Brikk output must cover the exact stable corpus')
    if any(generated[c['id']]['sql'] != c['sql'] for c in cases):
        raise ValueError('Brikk output contains stale SQL')
    requests = []
    for case in cases:
        for variant, sql in (('original', case['sql']), ('generated', generated[case['id']].get('generated'))):
            if sql is None:
                continue
            requests.append({'id': f'{case["id"]}/{variant}/parse', 'operation': 'parse', 'sql': sql})
            if args.product == 'datafusion' and case['execute']:
                requests.append({'id': f'{case["id"]}/{variant}/execute', 'operation': 'execute', 'sql': sql})
    responses = checked_reference(args.reference, requests, profile)
    if args.product == 'datafusion':
        version = checked_reference(args.reference, [{'id': 'version', 'operation': 'execute', 'sql': 'SELECT version()'}], profile)['version']
        reported = version.get('rows', [[]])[0][0] if version.get('rows') else ''
        match = re.search(r'\bDataFusion ([0-9]+\.[0-9]+\.[0-9]+)', reported)
        if not match or match.group(1) != profile['version']:
            raise ValueError('The actual engine version differs from the candidate profile')
    graph, sources = source_graph(profile)
    type_requests = [{'id': name, 'operation': 'execute', 'sql': f'SELECT arrow_typeof(CAST(NULL AS {name})) AS native_type'} for name in TYPE_CANDIDATES]
    if args.product == 'datafusion':
        metadata = checked_reference(args.reference, [{'id': 'catalog', 'operation': 'catalog', 'sql': ''}], profile)['catalog']
        for value in metadata['queries'].values():
            if value['status'] == 'accepted':
                value['rows'] = sorted_rows(value['rows'])
        metadata['type_probes'] = checked_reference(args.reference, type_requests, profile)
        metadata['availability'] = 'Explicit candidate embedding; inspect profile features'
    else:
        if args.spiced is None:
            raise ValueError('Spice refresh requires both a parser reference and a pinned runtime')
        keywords = load(pinned_file(profile['keyword_capture']))['keywords']
        sqlparser_pins = [{k: row[k] for k in ('version', 'source', 'checksum') if k in row}
                         for key, row in graph['packages'].items()
                         if key.startswith(profile['source']['repository'] + '/') and row.get('name') == 'sqlparser']
        if sqlparser_pins != [profile['keyword_capture']['package_pin']]:
            raise ValueError('Keyword capture must match the exact candidate sqlparser package, including patches')
        with runtime(args.spiced, profile) as (url, details):
            metadata = {'runtime': details, 'profile': profile['id'], 'keywords': keywords, 'registry': None,
                'availability': 'Released candidate runtime; no models or external datasets configured',
                'queries': {name: query_result(spice_sql(url, sql)) for name, sql in QUERIES.items()},
                'type_probes': {req['id']: query_result(spice_sql(url, req['sql'])) for req in type_requests}}
            for case in cases:
                if not case['execute']:
                    continue
                for variant, sql in (('original', case['sql']), ('generated', generated[case['id']].get('generated'))):
                    if sql is not None:
                        responses[f'{case["id"]}/{variant}/execute'] = spice_execute(url, sql)
    if metadata['queries']['routines']['status'] != 'accepted':
        raise RuntimeError('Missing routine inventory; no complete snapshot can be sealed')
    if profile.get('function_schemas'):
        schemas = load(pinned_file(profile['function_schemas']))
        if schemas['profile_id'] != profile['id']:
            raise ValueError('Function output schemas belong to another profile')
        metadata['table_function_schemas'] = schemas['functions']
    raw = {'metadata.json': metadata, 'responses.json': responses, 'cases.json': load(cases_path),
           'brikk.json': brikk, 'sources.json': sources,
           'engine-version.json': version if args.product == 'datafusion' else metadata['runtime'],
           'reference.json': {'binary_sha256': file_hash(args.reference), 'lock_sha256': profile['reference_lock']['sha256'],
                              'manifest_sha256': build['manifest_sha256'],
                              'tracking_tools': {name: file_hash(ROOT / 'tools' / name) for name in
                                  ('datafusion_upstream.py', 'datafusion_upstream_capture.py', 'generate_datafusion_metadata.py')}}}
    if overrides is not None:
        raw['overrides.json'] = overrides
    # Generate twice into the candidate, never into the shipped metadata tree.
    with tempfile.TemporaryDirectory(prefix='brikk-candidate-metadata-') as tmp:
        from generate_datafusion_metadata import generate
        meta_path = Path(tmp) / 'metadata-capture.json'
        save(meta_path, metadata)
        if 'overrides.json' in raw:
            save(meta_path.with_name('function-overrides.json'), raw['overrides.json'])
        title, text = generate(meta_path, args.product, profile['version'])
        if generate(meta_path, args.product, profile['version']) != (title, text):
            raise ValueError('Candidate metadata generation is nondeterministic')
        raw['generated-metadata.json'] = {'filename': f'Generated{title}FunctionCatalog.kt', 'text': text,
                                          'sha256': hashlib.sha256(text.encode()).hexdigest()}
    manifest = write_snapshot(args.output, profile, raw, graph)
    print(f"Sealed {manifest['snapshot_id']} at {args.output}; supported defaults unchanged")
