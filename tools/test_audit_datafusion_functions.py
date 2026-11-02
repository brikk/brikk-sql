import json
from pathlib import Path
import unittest

from audit_datafusion_functions import ROOT, generate


class DatafusionAuditTest(unittest.TestCase):
    def test_inventory_is_complete_and_reproducible(self):
        expected = json.loads((ROOT / 'vendor/data/datafusion-spice/round3/function-cases.json').read_text())
        self.assertEqual(expected, generate())
        self.assertEqual(len(expected['inventory']), 315)
        self.assertEqual(len({c['id'] for c in expected['cases']}), len(expected['cases']))

    def test_every_accepted_native_execution_matches(self):
        root = ROOT / 'vendor/data/datafusion-spice/round3'
        for filename, minimum in [('function-native.json', 123), ('grammar-native.json', 30)]:
            cases = json.loads((root / filename).read_text())['profiles']['datafusion-55.2.0']['cases']
            accepted = [c for c in cases if c['original'].get('execute', {}).get('status') == 'accepted']
            self.assertGreaterEqual(len(accepted), minimum)
            self.assertTrue(all(c['comparison'] == 'same-observation' for c in accepted))
        self.assertEqual(json.loads((root / 'function-audit-after.json').read_text())['argument_failures'], [])


if __name__ == '__main__':
    unittest.main()
