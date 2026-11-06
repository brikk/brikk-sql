#!/usr/bin/env python3
"""Immutable DataFusion/Spice upgrade evidence and deterministic review reports.

No command promotes versions, changes bundled catalogs, commits, or publishes artifacts.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from generate_datafusion_metadata import model

ROOT = Path(__file__).resolve().parents[1]
SECTIONS = ('functions', 'keywords', 'settings', 'types', 'table_function_schemas')


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    """Exclusive writes: neither a snapshot nor a report silently replaces earlier evidence."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        stream.write(json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def ordered(values):
    return sorted(values, key=canonical)


def inventory(capture, overrides=()):
    """Normalize names/kinds and evidence without promoting samples to signatures."""
    coverage = {section: 'unknown' for section in SECTIONS}
    result = {section: {} for section in SECTIONS}
    queries = capture.get('queries', {})
    if queries.get('routines', {}).get('status') == 'accepted':
        for definition in model(capture, overrides):
            key = f"{definition['kind']}:{definition['name']}"
            known_aliases = definition['canonical'] is not None
            result['functions'][key] = {
                'name': definition['name'], 'kind': definition['kind'],
                'native_kind': definition['native_kind'],
                'aliases': definition['aliases'] if known_aliases else None,
                'canonical': definition['canonical'], 'signature': definition['signature'],
                'overloads': ordered(definition['overloads']),
                'returns': sorted({row['return_type'] for row in definition['samples'] if row.get('return_type')}),
                'samples': ordered(definition['samples']),
                'descriptions': definition['descriptions'], 'syntax': definition['syntax'],
                'volatility': definition['volatility'],
            }
            if definition['kind'] == 'TABLE_VALUED':
                result['table_function_schemas'][key] = {'knowledge': 'unknown', 'columns': None}
        coverage['functions'] = 'complete'
    if 'keywords' in capture:
        result['keywords'] = {word: True for word in sorted(set(capture['keywords']))}
        coverage['keywords'] = 'complete'
    if queries.get('settings', {}).get('status') == 'accepted':
        result['settings'] = {row['name']: {'value': row.get('value', row.get('setting')),
            'description': row.get('description')} for row in queries['settings']['rows']}
        coverage['settings'] = 'complete'
    if 'type_probes' in capture:
        result['types'] = {name: {k: value[k] for k in ('status', 'rows', 'fields') if k in value}
                           for name, value in capture['type_probes'].items()}
        coverage['types'] = 'observed-candidates'
    # An adapter may supply measured function outputs. Missing/unknown schemas stay unknown.
    schemas = capture.get('table_function_schemas', {})
    if coverage['functions'] == 'complete' and not schemas.keys() <= result['table_function_schemas'].keys():
        raise ValueError('Output schema names a table function absent from the captured inventory')
    result['table_function_schemas'].update(schemas)
    if result['table_function_schemas']:
        coverage['table_function_schemas'] = 'observed-candidates'
    result['coverage'] = coverage
    return result


def observation(value, operation):
    if value is None:
        return {'status': 'not-captured'}
    result = {'status': value['status']}
    if operation == 'parse' and value['status'] == 'accepted':
        # Debug AST formats can change without a grammar change. Keep them raw, compare display/kinds.
        result['statements'] = [{k: v for k, v in row.items() if k != 'ast_debug'} for row in value.get('statements', [])]
    if operation == 'execute' and value['status'] == 'accepted':
        for key in ('rows', 'fields', 'nullability'):
            if key in value:
                result[key] = value[key]
        types = value.get('type_observation')
        if types:
            result['type_observation'] = {k: types[k] for k in ('status', 'rows') if k in types}
    return result


def case_inventory(cases, responses, brikk=None):
    generated = {row['id']: row for row in (brikk or {}).get('cases', [])}
    result = {}
    for case in cases:
        key = case['id']
        result[key] = {'sql_sha256': hashlib.sha256(case['sql'].encode()).hexdigest(),
                       'execute': case['execute'], 'negative': 'negative' in case.get('area', ''),
                       'generated_sql': generated.get(key, {}).get('generated')}
        for variant in ('original', 'generated'):
            result[key][variant] = {op: observation(responses.get(f'{key}/{variant}/{op}'), op)
                                   for op in ('parse', 'execute') if op == 'parse' or case['execute']}
    return result


def write_snapshot(directory, profile, raw, graph, *, evidence_kind='native-refresh'):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    # Manifest is sealed last. An interrupted/failed directory cannot pass verification.
    for name, value in raw.items():
        if Path(name).name != name or not name.endswith('.json'):
            raise ValueError('Raw evidence names must be simple JSON filenames')
        save(directory / 'raw' / name, value)
    normalized = inventory(raw['metadata.json'], raw.get('overrides.json', {}).get('entries', []))
    normalized['cases'] = case_inventory(raw['cases.json']['cases'], raw['responses.json'], raw.get('brikk.json'))
    save(directory / 'inventory.json', normalized)
    save(directory / 'graph.json', graph)
    save(directory / 'profile.json', profile)
    files = {str(path.relative_to(directory)): file_hash(path) for path in sorted(directory.rglob('*.json'))}
    manifest = {'schema_version': 1, 'evidence_kind': evidence_kind, 'profile': profile,
                'files': files, 'automatic_promotion': False}
    manifest['snapshot_id'] = digest(manifest)
    save(directory / 'manifest.json', manifest)
    return manifest


def read_snapshot(directory):
    directory = Path(directory)
    manifest = load(directory / 'manifest.json')
    if manifest.get('schema_version') != 1 or manifest.get('automatic_promotion') is not False:
        raise ValueError('Unsupported snapshot format')
    content = {k: v for k, v in manifest.items() if k != 'snapshot_id'}
    if digest(content) != manifest['snapshot_id']:
        raise ValueError('Snapshot manifest checksum mismatch')
    required = {'profile.json', 'inventory.json', 'graph.json', 'raw/metadata.json', 'raw/cases.json', 'raw/responses.json'}
    if not required <= manifest['files'].keys():
        raise ValueError('Incomplete snapshot manifest')
    actual = {str(p.relative_to(directory)) for p in directory.rglob('*.json') if p != directory / 'manifest.json'}
    if actual != set(manifest['files']):
        raise ValueError('Snapshot contains missing or unsealed JSON evidence')
    for name, checksum in manifest['files'].items():
        path = directory / name
        if path.resolve().is_relative_to(directory.resolve()) is False or file_hash(path) != checksum:
            raise ValueError(f'Snapshot evidence changed: {name}')
    if load(directory / 'profile.json') != manifest['profile']:
        raise ValueError('Conflicting profile identities')
    # Prevent a recomputed manifest from blessing a stale normalized view of different raw rows.
    raw = {Path(name).name: load(directory / name) for name in manifest['files'] if name.startswith('raw/')}
    if manifest['evidence_kind'] == 'native-refresh':
        from datafusion_upstream_capture import validate_profile
        validate_profile(manifest['profile'], manifest['profile']['product'])
    expected = inventory(raw['metadata.json'], raw.get('overrides.json', {}).get('entries', []))
    expected['cases'] = case_inventory(raw['cases.json']['cases'], raw['responses.json'], raw.get('brikk.json'))
    if load(directory / 'inventory.json') != expected:
        raise ValueError('Normalized inventory does not match raw evidence')
    return manifest, expected, load(directory / 'graph.json')


def compare(old_dir, new_dir):
    old_manifest, old, old_graph = read_snapshot(old_dir)
    new_manifest, new, new_graph = read_snapshot(new_dir)
    before, after = old_manifest['profile'], new_manifest['profile']
    if before['product'] != after['product']:
        raise ValueError('Compare the same product; Spice is not an upstream DataFusion profile')
    if before['id'] == after['id'] and before != after:
        raise ValueError('A changed profile needs a distinct id; do not relabel an existing profile')
    changes, gaps, unchanged_rejections, local_refusals = [], [], [], []
    def brikk_rows(directory, manifest):
        if 'raw/brikk.json' not in manifest['files']:
            return {}
        return {row['id']: row for row in load(Path(directory) / 'raw/brikk.json')['cases']}
    old_brikk, new_brikk = brikk_rows(old_dir, old_manifest), brikk_rows(new_dir, new_manifest)

    def add(kind, subject, impact, previous, current):
        identity = [before['product'], before['id'], after['id'], kind, subject]
        changes.append({'id': 'DFS-UP-' + digest(identity)[:16], 'kind': kind, 'subject': subject,
                        'impact': impact, 'before': previous, 'after': current,
                        'last_observed_at': before['id'], 'first_observed_at': after['id'],
                        'introduced_at': after['id'] if kind.endswith('added') else None,
                        'removed_at': after['id'] if kind.endswith('removed') else None})

    feature_coverage = lambda p, m: p.get('feature_coverage', 'complete' if m['evidence_kind'] == 'controlled-fixture' else 'unknown')
    scope_changed = (any(before.get(k) != after.get(k) for k in ('features', 'parser_profile', 'embedding')) or
                     feature_coverage(before, old_manifest) != 'complete' or feature_coverage(after, new_manifest) != 'complete')
    for key in ('features', 'parser_profile', 'embedding', 'driver', 'source'):
        if before.get(key) != after.get(key):
            add('profile_changed', key, 'transport' if key == 'driver' else 'metadata-only' if key == 'source' else 'IDE', before.get(key), after.get(key))
    for section in SECTIONS:
        a, b = old[section], new[section]
        ca, cb = old['coverage'][section], new['coverage'][section]
        impact = {'functions': 'metadata-only', 'keywords': 'parser', 'settings': 'IDE',
                  'types': 'type/scope', 'table_function_schemas': 'type/scope'}[section]
        if ca == 'unknown' or cb == 'unknown':
            gaps.append({'section': section, 'before': ca, 'after': cb})
            continue
        for key in sorted(a.keys() | b.keys()):
            subject = f'{section}/{key}'
            if key not in a:
                add('item_added', subject, impact, None, b[key])
            elif key not in b:
                # Disappearance under different flags or partial evidence is not a product removal.
                confirmed = ca == cb == 'complete' and not scope_changed
                add('item_removed' if confirmed else 'availability_changed', subject,
                    'breaking behavior' if confirmed and section == 'functions' else impact, a[key], None)
            elif a[key] != b[key]:
                if section != 'functions':
                    add('item_changed', subject, impact, a[key], b[key])
                    continue
                impacts = {'signature': 'breaking behavior', 'overloads': 'type/scope', 'returns': 'type/scope',
                           'aliases': 'parser', 'canonical': 'parser', 'native_kind': 'IDE', 'volatility': 'type/scope'}
                for field in sorted(a[key].keys() | b[key].keys()):
                    av, bv = a[key].get(field), b[key].get(field)
                    if av != bv:
                        kind = 'evidence_changed' if av is None or bv is None else field + '_changed'
                        add(kind, subject + '/' + field, 'metadata-only' if kind == 'evidence_changed' else impacts.get(field, impact), av, bv)
    old_kinds, new_kinds = {}, {}
    for target, functions in ((old_kinds, old['functions']), (new_kinds, new['functions'])):
        for function in functions.values():
            target.setdefault(function['name'], []).append(function['kind'])
    for name in sorted(old_kinds.keys() & new_kinds.keys()):
        if sorted(old_kinds[name]) != sorted(new_kinds[name]):
            add('kinds_changed', name, 'breaking behavior', sorted(old_kinds[name]), sorted(new_kinds[name]))
    for case_id in sorted(old['cases'].keys() | new['cases'].keys()):
        a, b = old['cases'].get(case_id), new['cases'].get(case_id)
        if a is None or b is None or (a['sql_sha256'], a['execute']) != (b['sql_sha256'], b['execute']):
            gaps.append({'case': case_id, 'reason': 'Corpus/input changed; no same-input comparison'})
            continue
        if a['generated_sql'] != b['generated_sql']:
            add('generated_sql_changed', case_id, 'generator', a['generated_sql'], b['generated_sql'])
        for variant in ('original', 'generated'):
            for operation in a[variant]:
                av, bv = a[variant][operation], b[variant][operation]
                subject = f'cases/{case_id}/{variant}/{operation}'
                if av['status'] not in ('accepted', 'rejected') or bv['status'] not in ('accepted', 'rejected'):
                    refused = all(rows.get(case_id, {}).get('status') == 'rejected' or rows.get(case_id, {}).get('generation_error')
                                  for rows in (old_brikk, new_brikk))
                    invalid_input = a['original']['parse']['status'] == b['original']['parse']['status'] == 'rejected'
                    if variant == 'generated' and refused and invalid_input and a['generated_sql'] is None and b['generated_sql'] is None:
                        local_refusals.append(subject)
                    else:
                        gaps.append({'case': subject, 'reason': 'Missing native verdict'})
                elif av['status'] != bv['status']:
                    kind = ('parser_regression' if bv['status'] == 'rejected' else 'parser_more_permissive') if operation == 'parse' else 'execution_acceptance_changed'
                    add(kind, subject, 'parser' if operation == 'parse' else 'generator' if variant == 'generated' else 'type/scope', av, bv)
                elif av['status'] == 'rejected':
                    unchanged_rejections.append(subject)
                else:
                    if operation == 'execute' and any('fields' not in value and value.get('type_observation', {}).get('status') != 'accepted' for value in (av, bv)):
                        gaps.append({'case': subject, 'reason': 'Return-type evidence is incomplete'})
                    if av != bv:
                        add('parser_structure_changed' if operation == 'parse' else 'semantic_change', subject,
                            'parser' if operation == 'parse' else 'generator' if variant == 'generated' else 'type/scope', av, bv)
    for group, impact in (('components', 'parser'), ('packages', 'type/scope')):
        for key in sorted(old_graph.get(group, {}).keys() | new_graph.get(group, {}).keys()):
            a, b = old_graph.get(group, {}).get(key), new_graph.get(group, {}).get(key)
            if a != b:
                add('dependency_changed', f'{group}/{key}', (b or a).get('impact', impact), a, b)
    changes.sort(key=lambda row: (row['subject'], row['kind']))
    for gap in gaps:
        gap['id'] = 'DFS-GAP-' + digest([before['product'], before['id'], after['id'], gap])[:16]
        gap['observed_at'] = after['id']
    return {'schema_version': 1, 'product': before['product'], 'from': old_manifest['snapshot_id'],
            'to': new_manifest['snapshot_id'], 'boundaries': [before['id'], after['id']],
            'evidence_kinds': [old_manifest['evidence_kind'], new_manifest['evidence_kind']],
            'changes': changes, 'gaps': gaps, 'unchanged_rejections_not_equivalence': unchanged_rejections,
            'unchanged_local_refusals_of_native_invalid_sql': local_refusals,
            'coverage': {'before': old['coverage'], 'after': new['coverage']},
            'unknown_function_schemas': sorted(k for k, v in new['table_function_schemas'].items() if v.get('knowledge') != 'full'),
            'promotion_allowed': False,
            'required_gates': ['Review all changes and coverage gaps, including native rejections',
                'Re-run this exact corpus on both pinned profiles with fresh Brikk output',
                'Regenerate candidate metadata twice and compare bytes', 'Run core/native/IDE and ABI gates',
                'Requalify Flight SQL metadata, binding, sessions, errors and cancellation for the selected driver',
                'Update support matrix, compatibility notes and consumer handoff',
                'Explicit approval before changing bundled defaults or publishing']}


def demo(directory):
    """Small, explicitly synthetic upgrade. It uses the real normalization/storage/compare path."""
    directory = Path(directory)
    profile = {'id': 'controlled-before', 'product': 'datafusion', 'version': 'fixture',
               'features': [], 'parser_profile': {'dialect': 'Generic'}, 'embedding': 'controlled fixture'}
    def routine(name, kind='SCALAR'):
        return {'routine_name': name, 'function_type': kind, 'description': name, 'syntax_example': name + '(x)'}
    metadata = {'registry': [{'name': name, 'kind': 'SCALAR', 'canonical_name': name, 'volatility': 'Immutable',
        'signature': {'kind': 'Exact', 'types': ['Int64'], 'native': 'Exact([Int64])'}} for name in ('kept', 'removed')],
        'queries': {'routines': {'status': 'accepted', 'rows': [routine('kept'), routine('removed'), routine('table_fn', 'TABLE')]},
                    'show_functions': {'status': 'accepted', 'rows': [{'function_name': name, 'function_type': 'SCALAR', 'parameter_types': ['Int64'], 'return_type': 'Int64'} for name in ('kept', 'removed')]},
                    'settings': {'status': 'accepted', 'rows': [{'name': 'default', 'value': 'old'}]}},
        'keywords': ['SELECT'], 'type_probes': {},
        'table_function_schemas': {'TABLE_VALUED:table_fn': {'knowledge': 'full', 'columns': [['v', 'Int64']]}}}
    cases = [{'id': 'positive', 'sql': 'SELECT kept(1)', 'execute': True},
             {'id': 'negative', 'sql': 'SELECT (', 'execute': False, 'area': 'negative'}]
    responses = {f'{case["id"]}/{variant}/parse': {'status': 'rejected' if case['id'] == 'negative' else 'accepted', 'statements': []}
                 for case in cases for variant in ('original', 'generated')}
    for variant in ('original', 'generated'):
        responses[f'positive/{variant}/execute'] = {'status': 'accepted', 'fields': [{'name': 'v', 'type': 'Int64'}], 'rows': [['1']]}
    raw = {'metadata.json': metadata, 'cases.json': {'cases': cases}, 'responses.json': responses,
           'brikk.json': {'cases': [dict(case, generated=case['sql']) for case in cases]}}
    write_snapshot(directory / 'before', profile, raw, {'components': {}, 'packages': {}}, evidence_kind='controlled-fixture')
    newer = copy.deepcopy(raw)
    meta = newer['metadata.json']
    meta['registry'] = [meta['registry'][0]]
    meta['registry'][0]['signature'] = {'kind': 'Exact', 'types': ['Utf8'], 'native': 'Exact([Utf8])'}
    meta['queries']['routines']['rows'] = [routine('kept'), routine('added'), routine('table_fn', 'TABLE')]
    meta['queries']['show_functions']['rows'] = [{'function_name': 'kept', 'function_type': 'SCALAR', 'parameter_types': ['Utf8'], 'return_type': 'Utf8'}]
    meta['keywords'].append('NEW_KEYWORD')
    meta['queries']['settings']['rows'][0]['value'] = 'new'
    meta['table_function_schemas']['TABLE_VALUED:table_fn']['columns'] = [['v', 'Utf8']]
    newer['responses.json']['positive/original/parse'] = {'status': 'rejected'}
    newer['responses.json']['negative/original/parse'] = {'status': 'accepted', 'statements': []}
    newer['responses.json']['positive/generated/execute']['rows'] = [['99']]
    write_snapshot(directory / 'after', dict(profile, id='controlled-after'), newer,
                   {'components': {}, 'packages': {}}, evidence_kind='controlled-fixture')
    report = compare(directory / 'before', directory / 'after')
    save(directory / 'report.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    profile = commands.add_parser('profile', help='Export the current pins for an explicit candidate profile edit')
    profile.add_argument('product', choices=['datafusion', 'spice'])
    profile.add_argument('--output', type=Path, required=True)
    refresh = commands.add_parser('refresh', help='Capture new native evidence without changing any supported defaults')
    refresh.add_argument('product', choices=['datafusion', 'spice'])
    refresh.add_argument('--profile', type=Path)
    refresh.add_argument('--reference', type=Path, required=True)
    refresh.add_argument('--spiced', type=Path)
    refresh.add_argument('--work-dir', type=Path, default=ROOT / 'build/datafusion-upstream')
    refresh.add_argument('--brikk', type=Path, required=True)
    refresh.add_argument('--output', type=Path, required=True)
    comparison = commands.add_parser('compare')
    comparison.add_argument('before', type=Path)
    comparison.add_argument('after', type=Path)
    comparison.add_argument('--output', type=Path, required=True)
    comparison.add_argument('--fail-on-change', action='store_true')
    demonstration = commands.add_parser('demo')
    demonstration.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command in ('profile', 'refresh'):
        from datafusion_upstream_capture import default_profile, refresh_snapshot
        if args.command == 'profile':
            save(args.output, default_profile(args.product))
        else:
            refresh_snapshot(args)
    else:
        report = demo(args.output) if args.command == 'demo' else compare(args.before, args.after)
        if args.command == 'compare':
            save(args.output, report)
        print(f"{len(report['changes'])} changes; {len(report['gaps'])} evidence gaps; promotion requires review")
        if args.command == 'compare' and args.fail_on_change and (report['changes'] or report['gaps']):
            raise SystemExit(2)


if __name__ == '__main__':
    main()
