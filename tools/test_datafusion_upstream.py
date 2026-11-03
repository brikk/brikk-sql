import copy
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
import contextlib
import os
from unittest.mock import patch

import datafusion_upstream as tracking
import datafusion_upstream_capture as capture


class UpgradeTrackingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = tracking.demo(self.root / 'demo')
        self.old = self.root / 'demo/before'
        self.new = self.root / 'demo/after'

    def derived(self, name, change, profile_change=None):
        manifest, _, graph = tracking.read_snapshot(self.old)
        raw = {Path(path).name: tracking.load(self.old / path) for path in manifest['files'] if path.startswith('raw/')}
        profile = dict(manifest['profile'], id=name)
        if profile_change:
            profile.update(profile_change)
        change(raw)
        output = self.root / name
        tracking.write_snapshot(output, profile, raw, graph, evidence_kind='controlled-fixture')
        return tracking.compare(self.old, output)

    def test_controlled_upgrade_detects_all_required_regressions(self):
        changes = self.report['changes']
        kinds = {row['kind'] for row in changes}
        self.assertTrue({'item_added', 'item_removed', 'signature_changed', 'parser_regression',
                         'parser_more_permissive', 'semantic_change'} <= kinds)
        self.assertTrue(any(row['subject'].startswith('table_function_schemas/') for row in changes))
        self.assertTrue(any(row['subject'].startswith('settings/') for row in changes))
        self.assertFalse(self.report['promotion_allowed'])
        self.assertEqual([], self.report['gaps'])
        self.assertEqual(len(changes), len({row['id'] for row in changes}))
        for row in changes:
            self.assertEqual('controlled-before', row['last_observed_at'])
            self.assertEqual('controlled-after', row['first_observed_at'])

    def test_reports_and_followup_ids_are_deterministic(self):
        self.assertEqual(tracking.canonical(self.report), tracking.canonical(tracking.compare(self.old, self.new)))
        other = tracking.demo(self.root / 'again')
        self.assertEqual(self.report, other)
        self.assertEqual((self.root / 'demo/report.json').read_bytes(), (self.root / 'again/report.json').read_bytes())

    def test_snapshots_and_reports_cannot_be_overwritten(self):
        original = (self.old / 'manifest.json').read_bytes()
        with self.assertRaises(FileExistsError):
            tracking.demo(self.root / 'demo')
        self.assertEqual(original, (self.old / 'manifest.json').read_bytes())
        with self.assertRaises(FileExistsError):
            tracking.save(self.root / 'demo/report.json', {})

    def test_integrity_checks_reject_changed_raw_unsealed_and_stale_normalized_files(self):
        path = self.old / 'raw/metadata.json'
        path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'changed'):
            tracking.read_snapshot(self.old)
        path = self.new / 'extra.json'
        path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'unsealed'):
            tracking.read_snapshot(self.new)

    def test_incomplete_capture_is_not_a_mass_removal(self):
        report = self.derived('partial', lambda raw: raw['metadata.json']['queries'].update(
            routines={'status': 'unavailable', 'message': 'permission denied'}))
        self.assertTrue(any(row.get('section') == 'functions' for row in report['gaps']))
        self.assertFalse(any(row['kind'] == 'item_removed' and row['subject'].startswith('functions/') for row in report['changes']))

    def test_disabled_features_are_availability_changes(self):
        def remove(raw):
            meta = raw['metadata.json']
            meta['registry'] = meta['registry'][:1]
            meta['queries']['routines']['rows'] = [r for r in meta['queries']['routines']['rows'] if r['routine_name'] != 'removed']
            meta['queries']['show_functions']['rows'] = [r for r in meta['queries']['show_functions']['rows'] if r['function_name'] != 'removed']
        report = self.derived('disabled', remove, {'features': ['different embedding features']})
        self.assertTrue(any(row['kind'] == 'availability_changed' for row in report['changes']))
        self.assertFalse(any(row['kind'] == 'item_removed' for row in report['changes']))
        partial = self.derived('unknown-build', remove, {'feature_coverage': 'partial'})
        self.assertFalse(any(row['kind'] == 'item_removed' for row in partial['changes']))

    def test_source_overrides_cannot_be_borrowed_from_an_older_release(self):
        profile = capture.default_profile('spice')
        self.assertTrue(capture.source_overrides(profile)['entries'])
        profile['source']['commit'] = 'f' * 40
        with self.assertRaisesRegex(ValueError, 'older release'):
            capture.source_overrides(profile)

    def test_unknown_signatures_are_not_zero_argument_or_breaking_signatures(self):
        report = self.derived('unknown', lambda raw: raw['metadata.json'].update(registry=None))
        signatures = [row for row in report['changes'] if row['subject'].endswith('/signature')]
        self.assertTrue(signatures)
        self.assertTrue(all(row['kind'] == 'evidence_changed' and row['impact'] == 'metadata-only' for row in signatures))
        _, inventory, _ = tracking.read_snapshot(self.root / 'unknown')
        self.assertIsNone(inventory['functions']['SCALAR:kept']['signature'])
        self.assertIsNone(inventory['functions']['SCALAR:kept']['aliases'])
        self.assertEqual([], inventory['functions']['SCALAR:kept']['overloads'])

    def test_same_name_scalar_and_table_kinds_stay_distinct(self):
        def add(raw):
            raw['metadata.json']['queries']['routines']['rows'].append({'routine_name': 'kept', 'function_type': 'TABLE'})
        report = self.derived('kinds', add)
        self.assertTrue(any(row['kind'] == 'kinds_changed' and row['subject'] == 'kept' for row in report['changes']))
        self.assertTrue(any(row['kind'] == 'item_added' and row['subject'] == 'functions/TABLE_VALUED:kept' for row in report['changes']))

    def test_alias_identity_changes_are_reported_separately(self):
        def alias(raw):
            definition = copy.deepcopy(raw['metadata.json']['registry'][0])
            definition['name'] = 'kept_alias'
            raw['metadata.json']['registry'].append(definition)
        report = self.derived('alias', alias)
        self.assertTrue(any(row['kind'] == 'aliases_changed' for row in report['changes']))

    def test_output_contract_cannot_invent_a_registered_function(self):
        with self.assertRaisesRegex(ValueError, 'absent'):
            self.derived('orphan-schema', lambda raw: raw['metadata.json']['table_function_schemas'].update(
                {'TABLE_VALUED:missing': {'knowledge': 'full', 'columns': [['invented', 'Int64']]}}))

    def test_changed_profile_cannot_reuse_its_old_identity(self):
        with self.assertRaisesRegex(ValueError, 'distinct id'):
            self.derived('controlled-before', lambda _: None, {'features': ['different']})

    def test_changed_inputs_and_missing_verdicts_never_compare_as_equivalent(self):
        def missing(raw):
            raw['responses.json'].pop('positive/generated/execute')
            raw['cases.json']['cases'][1]['sql'] = 'SELECT 1'
        report = self.derived('missing', missing)
        self.assertEqual(2, len(report['gaps']))
        self.assertTrue(any('Corpus/input' in row['reason'] for row in report['gaps']))

    def test_expected_local_refusals_are_distinct_from_missing_infrastructure(self):
        manifest, _, graph = tracking.read_snapshot(self.old)
        raw = {Path(path).name: tracking.load(self.old / path) for path in manifest['files'] if path.startswith('raw/')}
        raw['brikk.json']['cases'][1].pop('generated')
        raw['brikk.json']['cases'][1]['status'] = 'rejected'
        raw['responses.json'].pop('negative/generated/parse')
        for name in ('refusal-old', 'refusal-new'):
            tracking.write_snapshot(self.root / name, dict(manifest['profile'], id=name), raw, graph, evidence_kind='controlled-fixture')
        report = tracking.compare(self.root / 'refusal-old', self.root / 'refusal-new')
        self.assertFalse(report['gaps'])
        self.assertEqual(['cases/negative/generated/parse'], report['unchanged_local_refusals_of_native_invalid_sql'])

    def test_products_cannot_be_compared_as_versions_of_one_engine(self):
        with self.assertRaisesRegex(ValueError, 'same product'):
            self.derived('other-product', lambda _: None, {'product': 'spice'})

    def test_native_reference_identity_cannot_be_relabelled(self):
        profile = {'expected_reference': {'id': 'new', 'revision': 'a' * 40}}
        with patch.object(capture, 'run_reference', return_value={'x': {'profile': {'id': 'old', 'revision': 'b' * 40}}}):
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                capture.checked_reference(Path('unused'), [], profile)

    def test_release_and_runtime_pins_cannot_disagree(self):
        for product in ('datafusion', 'spice'):
            profile = capture.default_profile(product)
            capture.validate_profile(profile, product)
            profile['version'] = '999.0.0'
            with self.assertRaisesRegex(ValueError, 'release'):
                capture.validate_profile(profile, product)
        profile = capture.default_profile('spice')
        profile['runtime']['reported_version'] = 'v99.0.0+models'
        with self.assertRaisesRegex(ValueError, 'another release'):
            capture.validate_profile(profile, 'spice')

    def test_ci_compare_exit_status_keeps_the_report_for_review(self):
        cli = tracking.ROOT / 'tools/datafusion_upstream.py'
        path = self.root / 'gate-report.json'
        run = subprocess.run([sys.executable, str(cli), 'compare', str(self.old), str(self.new),
                              '--output', str(path), '--fail-on-change'], capture_output=True, text=True)
        self.assertEqual(2, run.returncode, run.stderr)
        self.assertEqual(self.report, tracking.load(path))
        same = subprocess.run([sys.executable, str(cli), 'compare', str(self.old), str(self.old),
                               '--output', str(self.root / 'same.json'), '--fail-on-change'], capture_output=True, text=True)
        self.assertEqual(0, same.returncode, same.stderr)

    def test_source_graph_tracks_lock_dependencies_and_rejects_truncated_trees(self):
        sha = 'a' * 40
        profile = {'product': 'datafusion', 'source': {'repository': 'apache/datafusion', 'commit': sha},
                   'driver': {'coordinates': 'pinned-driver', 'sha256': 'd' * 64}}
        lock = '''version = 4
[[package]]
name = "datafusion"
version = "55.2.0"
dependencies = ["sqlparser"]
[[package]]
name = "sqlparser"
version = "0.62.0"
checksum = "abc"
'''
        tree = {'truncated': False, 'tree': [{'path': path, 'type': 'blob', 'sha': 'b' * 40}
                    for path in ['Cargo.lock', 'Cargo.toml', 'datafusion/sql/src/parser.rs']]}
        def get(url):
            if '/git/commits/' in url:
                return json.dumps({'sha': sha, 'tree': {'sha': 'c' * 40}}).encode()
            if '/git/trees/' in url:
                return json.dumps(tree).encode()
            return lock.encode()
        graph, raw = capture.source_graph(profile, get)
        self.assertIn('apache/datafusion/parser', graph['components'])
        nodes = set(graph['repositories']) | set(graph['components']) | set(graph['packages'])
        self.assertTrue(all(a in nodes and b in nodes for a, b in graph['edges']))
        self.assertTrue(any('sqlparser@0.62.0' in b for a, b in graph['edges'] if 'datafusion@' in a))
        self.assertTrue(raw)
        tree['truncated'] = True
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            capture.source_graph(profile, get)

    def test_candidate_normalization_does_not_touch_supported_catalogs(self):
        path = tracking.ROOT / 'brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDatafusionFunctionCatalog.kt'
        before = path.read_bytes()
        tracking.demo(self.root / 'another')
        self.assertEqual(before, path.read_bytes())

    def test_ci_is_functional_and_manual_with_no_scheduled_or_write_job(self):
        workflow = (tracking.ROOT / '.github/workflows/datafusion-upstream.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('schedule:', workflow)
        self.assertNotIn('contents: write', workflow)
        self.assertNotIn('git push', workflow)
        self.assertIn('cargo', (tracking.ROOT / 'tools/run_datafusion_upgrade_ci.py').read_text())
        release_gate = (tracking.ROOT / '.github/workflows/test.yml').read_text()
        self.assertIn("test_*datafusion*.py", release_gate)

    def test_ci_runner_builds_then_captures_without_publication(self):
        import run_datafusion_upgrade_ci as ci
        profile = self.root / 'profile.json'
        tracking.save(profile, capture.default_profile('datafusion'))
        with contextlib.chdir(self.root), patch.dict(os.environ, {'PRODUCT': 'datafusion', 'CANDIDATE_PROFILE': str(profile)}), patch.object(ci.subprocess, 'run') as run:
            ci.main()
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(3, len(commands))
        self.assertEqual(['cargo', '+1.96.0', 'build', '--locked'], commands[0][:4])
        self.assertEqual(['./kotlin', 'run', '-m', 'dialect-audit'], commands[1][:4])
        self.assertEqual(['python3', 'tools/datafusion_upstream.py', 'refresh', 'datafusion'], commands[2][:4])
        self.assertTrue(all(call.kwargs['check'] for call in run.call_args_list))
        self.assertFalse(any('publish' in command for command in commands))


if __name__ == '__main__':
    unittest.main()
