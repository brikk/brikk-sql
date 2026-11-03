"""Keep the final consumer claims tied to fresh qualification captures and explicit limits."""
import hashlib
import json
import unittest
from pathlib import Path
import test_datafusion_execution_evidence as execution
import test_datafusion_flight_evidence as flight

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/round12'


class HandoffExecutionEvidenceTest(execution.ExecutionEvidenceTest):
    data = DATA


class HandoffFlightEvidenceTest(flight.FlightEvidenceTest):
    data = DATA


class HandoffEvidenceTest(unittest.TestCase):
    def test_opaque_commands_are_excluded_from_structured_support(self):
        manifest = json.loads((DATA / 'qualification.json').read_text())
        excluded = manifest['structured_syntax_exclusions']
        self.assertEqual('DFS-012-S1', excluded['id'])
        for product in ['datafusion', 'spice']:
            rows = json.loads((DATA / f'brikk-{product}.json').read_text())['cases']
            opaque = [row['id'] for row in rows if any('Command' in statement['nodes'] for statement in row.get('statements', []))]
            self.assertEqual(excluded['cases'] if product == excluded['product'] else [], opaque)

    def test_fresh_captures_are_complete_and_sealed(self):
        manifest = json.loads((DATA / 'qualification.json').read_text())
        self.assertFalse(manifest['publication_authorized'])
        self.assertEqual(['DFS-010-T1', 'DFS-010-T2', 'DFS-010-T3', 'DFS-010-T4'], manifest['execution_exclusions'])
        self.assertIn('DFS-GAP-612e0f186332398a', manifest['resolved_gaps'])
        required = {f'{product}-{kind}.json' for product in ['datafusion', 'spice'] for kind in ['native', 'jdbc', 'execution']}
        required.update({'brikk-datafusion.json', 'brikk-spice.json'})
        self.assertEqual(required, set(manifest['files']))
        for name, digest in manifest['files'].items():
            self.assertEqual(Path(name).name, name)
            self.assertEqual(digest, hashlib.sha256((DATA / name).read_bytes()).hexdigest(), name)
        for product in ['datafusion', 'spice']:
            capture = json.loads((DATA / f'{product}-execution.json').read_text())
            self.assertNotIn('probe_timeout', capture['observations'])
            self.assertEqual(10, len(capture['observations']['pipes']))
            catalog = json.loads((DATA / f'{product}-jdbc.json').read_text())
            self.assertEqual('accepted', catalog['observations']['production_harvest']['status'])

    def test_native_roundtrips_have_no_missing_or_failed_generated_verdicts(self):
        fixtures = ROOT / 'vendor/data/datafusion-spice/cases.json'
        ids = [case['id'] for case in json.loads(fixtures.read_text())['cases']]
        for product, profile in [('datafusion', 'datafusion-55.2.0'), ('spice', 'spice-2.4.0-parser')]:
            capture = json.loads((DATA / f'{product}-native.json').read_text())
            brikk = DATA / f'brikk-{product}.json'
            self.assertEqual(hashlib.sha256(fixtures.read_bytes()).hexdigest(), capture['fixtures_sha256'])
            self.assertEqual(hashlib.sha256(brikk.read_bytes()).hexdigest(), capture['brikk_observations_sha256'])
            rows = capture['profiles'][profile]['cases']
            self.assertEqual(ids, [row['id'] for row in rows])
            for row in rows:
                if row['original']['parse']['status'] == 'accepted':
                    self.assertEqual('accepted', row['generated'].get('parse', {}).get('status'), (product, row['id']))
                self.assertNotIn(row.get('comparison'), ['generated-rejected', 'different-observation', 'incomplete-type-evidence'])
                if row.get('comparison') == 'no-generated-sql':
                    self.assertEqual('rejected', row['original']['execute']['status'], row['id'])
            if product == 'spice':
                runtime = capture['profiles']['spice-2.4.0-runtime']
                self.assertEqual('v2.4.0+models', runtime['runtime']['version'])
                for row in runtime['cases']:
                    self.assertNotIn(row['comparison'], ['generated-rejected', 'different-observation', 'incomplete-type-evidence'])
                    if row['comparison'] == 'no-generated-sql':
                        self.assertEqual('rejected', row['original']['status'], row['id'])


if __name__ == '__main__':
    unittest.main()
