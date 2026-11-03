import json
import unittest

from probe_datafusion_spice import ROOT, sha256


class SpiceNativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.root = ROOT / 'vendor/data/datafusion-spice/round4'
        self.inputs = json.loads((self.root / 'cases.json').read_text())['cases']
        self.brikk = {c['id']: c for c in json.loads((self.root / 'spice-brikk.json').read_text())['cases']}

    def load(self, filename, profile):
        data = json.loads((self.root / filename).read_text())
        self.assertEqual(data['fixtures_sha256'], sha256(self.root / 'cases.json'))
        self.assertEqual(data['brikk_observations_sha256'], sha256(self.root / 'spice-brikk.json'))
        return data['profiles'][profile]['cases']

    def test_all_native_accepted_statements_have_structured_regeneration(self):
        cases = self.load('parser-native.json', 'spice-2.4.0-parser')
        self.assertEqual([c['id'] for c in cases], [c['id'] for c in self.inputs])
        accepted = [c for c in cases if c['original']['parse']['status'] == 'accepted']
        self.assertEqual(len(accepted), 104)
        for case in accepted:
            self.assertEqual(case['generated']['parse']['status'], 'accepted', case['id'])
            self.assertTrue(all(s['root'] != 'Command' for s in self.brikk[case['id']]['statements']), case['id'])
        # JDBC client bind syntax is intentionally broader than raw PG parser syntax.
        differences = [c['id'] for c in cases if c['original']['parse']['status'] == 'rejected'
                       and self.brikk[c['id']]['status'] == 'accepted']
        self.assertEqual(differences, ['DFS-bind-question'])

    def test_all_native_accepted_executions_keep_results_and_types(self):
        cases = self.load('runtime-native.json', 'spice-2.4.0-runtime')
        self.assertEqual([c['id'] for c in cases], [c['id'] for c in self.inputs if c['execute']])
        accepted = [c for c in cases if c['original']['status'] == 'accepted']
        self.assertEqual(len(accepted), 51)
        for case in accepted:
            self.assertEqual(case['comparison'], 'same-observation', case['id'])

    def test_model_and_write_cases_are_never_executed_by_the_capture(self):
        for case in self.inputs:
            if case['id'].startswith(('SPICE-search', 'SPICE-rerank', 'SPICE-ai', 'SPICE-embed', 'SPICE-create', 'SPICE-prepare', 'SPICE-explain')):
                self.assertFalse(case['execute'], case['id'])


if __name__ == '__main__':
    unittest.main()
